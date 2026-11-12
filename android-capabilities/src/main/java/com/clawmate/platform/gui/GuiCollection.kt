package com.clawmate.platform.gui

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Rect
import android.os.SystemClock
import android.util.AtomicFile
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.RequiresApi
import java.io.File
import java.io.FileNotFoundException
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal const val GUI_COLLECTION_PAGE_LIMIT = 3
internal enum class CollectionDirection {
    DOWN, UP;

    val action get() = if (this == DOWN) ACTION_SCROLL_DOWN else ACTION_SCROLL_UP
}
private const val MAX_COLLECTION_LINES = 80
private const val MAX_COLLECTION_CHARS = 6000

@Serializable
internal data class GuiCollection(
    val sourcePackage: String,
    val sourceLabel: String,
    val capturedAt: Long,
    val lines: List<String>,
    val partial: Boolean,
    val pages: Int = 1,
    val weibo: WeiboSnapshot? = null,
) {
    fun title(): String = "$sourceLabel · ${DateFormat.getDateTimeInstance().format(Date(capturedAt))}"

    fun preview(context: Context): FrameLayout = FrameLayout(context).apply {
        val metrics = resources.displayMetrics
        val maxHeight = minOf((240 * metrics.density).toInt(), metrics.heightPixels / 3)
        addView(ScrollView(context).apply {
            addView(TextView(context).apply {
                setPadding(32, 16, 32, 24)
                text = lines.joinToString("\n")
            })
        }, FrameLayout.LayoutParams(MATCH_PARENT, maxHeight))
    }

    fun description(): String = weibo?.let { snapshot ->
        "${snapshot.date} · $pages 页 · ${snapshot.entries.size} 条\n" +
            if (snapshot.complete) "已核对主榜第 1 至 50 名" else "部分采集 · ${snapshot.endReason}"
    } ?: "$sourcePackage\n$pages 页 · ${lines.size} 条可见文字" +
        if (partial) "（仅包含已读部分）" else ""

    companion object {
        @RequiresApi(34)
        fun read(
            scope: CoroutineScope,
            root: AccessibilityNodeInfo,
            bounds: Rect,
            sourcePackage: String,
            sourceLabel: String,
            scroll: CollectionDirection?,
        ): GuiCollectionPage {
            val deadline = SystemClock.elapsedRealtime() + 2000
            val lines = linkedSetOf<String>()
            val nodeBounds = Rect()
            var textSize = 0
            var remaining = 250
            var partial = false
            var scrollNode: AccessibilityNodeInfo? = null
            var ambiguousScroll = false
            fun visit(node: AccessibilityNodeInfo, depth: Int) {
                scope.ensureActive()
                if (!node.isVisibleToUser || node.packageName?.toString() != sourcePackage ||
                    node.isPassword || node.isEditable || node.isAccessibilityDataSensitive
                ) return
                node.getBoundsInScreen(nodeBounds)
                if (!Rect.intersects(bounds, nodeBounds)) return
                if (scroll != null && !ambiguousScroll && node.canScrollCollection(sourcePackage, bounds, scroll)) {
                    if (scrollNode == null) {
                        scrollNode = node
                    } else if (scrollNode != node) {
                        ambiguousScroll = true
                    }
                }

                val raw = node.text?.takeUnless { it.isBlank() } ?: node.contentDescription
                if (raw != null) {
                    val line = raw.take(600).toString().trim()
                    partial = partial || raw.length > 600
                    if (line.isNotEmpty() && line !in lines) {
                        if (lines.size >= MAX_COLLECTION_LINES || textSize + line.length > MAX_COLLECTION_CHARS) {
                            remaining = 0
                            partial = true
                            return
                        }
                        lines.add(line)
                        textSize += line.length
                    }
                }
                for (index in 0 until node.childCount) {
                    scope.ensureActive()
                    if (remaining <= 0 || depth >= 40 || SystemClock.elapsedRealtime() >= deadline) {
                        partial = true
                        break
                    }
                    remaining--
                    node.getChild(index)?.let { visit(it, depth + 1) }
                }
            }
            visit(root, 0)
            scope.ensureActive()
            return GuiCollectionPage(
                GuiCollection(sourcePackage, sourceLabel, System.currentTimeMillis(), lines.toList(), partial),
                scrollNode.takeUnless { ambiguousScroll || partial },
            )
        }
    }
}

internal data class GuiCollectionPage(val record: GuiCollection, val scrollNode: AccessibilityNodeInfo?)

@RequiresApi(34)
internal fun AccessibilityNodeInfo.canScrollCollection(sourcePackage: String, area: Rect, scroll: CollectionDirection): Boolean =
    packageName?.toString() == sourcePackage && isVisibleToUser && isEnabled && isScrollable &&
        !isPassword && !isEditable && !isAccessibilityDataSensitive && scroll.action in actionList &&
        Rect.intersects(area, Rect().also(::getBoundsInScreen))

/** Keeps first-seen order and applies the existing record limits across all pages. */
internal class GuiCollectionAccumulator {
    private val lines = linkedSetOf<String>()
    private var chars = 0
    var pages = 0
        private set
    var truncated = false
        private set
    val hasCapacity: Boolean
        get() = lines.size < MAX_COLLECTION_LINES && chars < MAX_COLLECTION_CHARS

    fun append(page: GuiCollection): Boolean {
        val before = lines.size
        for (line in page.lines) {
            if (line in lines) continue
            if (lines.size >= MAX_COLLECTION_LINES || chars + line.length > MAX_COLLECTION_CHARS) {
                truncated = true
                break
            }
            lines.add(line)
            chars += line.length
        }
        truncated = truncated || page.partial
        val added = lines.size > before
        if (added) pages++
        return added
    }

    fun record(first: GuiCollection, incomplete: Boolean): GuiCollection =
        first.copy(lines = lines.toList(), partial = incomplete || truncated, pages = pages)
}

/** Serializes UI writes and model-side reads of the app-private collection file. */
internal object GuiCollectionHistory {
    private fun file(context: Context) = AtomicFile(File(context.noBackupFilesDir, "gui-collections.json"))

    @Synchronized fun read(context: Context): List<GuiCollection> {
        val bytes = try {
            file(context).readFully()
        } catch (_: FileNotFoundException) {
            return emptyList()
        }
        return Json.decodeFromString(bytes.toString(Charsets.UTF_8))
    }

    @Synchronized fun save(context: Context, value: GuiCollection) {
        val records = read(context)
        val isBoard = value.weibo != null
        check(records.count { (it.weibo != null) == isBoard } < 200) {
            if (isBoard) "微博榜单已保存 200 批，请先删除旧批次" else "本机采集已保存 200 条，请先删除旧记录"
        }
        write(context, listOf(value) + records)
    }

    @Synchronized private fun delete(context: Context, record: GuiCollection) =
        write(context, read(context).toMutableList().apply { remove(record) })

    private fun write(context: Context, records: List<GuiCollection>) {
        val bytes = Json.encodeToString(records).toByteArray(Charsets.UTF_8)
        val file = file(context)
        val output = file.startWrite()
        try {
            output.write(bytes)
            file.finishWrite(output)
        } catch (failure: Exception) {
            file.failWrite(output)
            throw failure
        }
        check(file.readFully().contentEquals(bytes)) { "采集记录写入校验失败" }
    }

    fun show(activity: Activity) {
        val records = read(activity)
        val list = AlertDialog.Builder(activity).setTitle("本机采集记录").setNegativeButton("关闭", null)
        if (records.isEmpty()) {
            list.setMessage("暂无记录。采集后选择“保存到本机”。")
        } else {
            list.setItems(records.map { it.title() }.toTypedArray()) { _, index ->
                val record = records[index]
                if (record.weibo != null) {
                    val style = GuiCollectionStyle(activity)
                    style.sheet("微博热搜 · ${record.weibo.date}", record.description(), "关闭", "删除记录",
                        content = style.recordContent(record), secure = true, onNegative = {
                            style.sheet("删除这批微博热搜？", "从本机历史移除后，模型查询也不会返回这批记录。", "删除", secure = true) {
                                runCatching { delete(activity, record) }.onFailure {
                                    android.widget.Toast.makeText(activity, "删除失败，请重试", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            }
                        }) {}
                    return@setItems
                }
                AlertDialog.Builder(activity).setTitle(record.title()).setMessage(record.description())
                    .setView(record.preview(activity)).setNegativeButton("关闭", null)
                    .setNeutralButton("删除记录") { _, _ ->
                        AlertDialog.Builder(activity).setMessage("删除这条本机采集记录？")
                            .setNegativeButton("取消", null).setPositiveButton("删除") { _, _ ->
                                runCatching { delete(activity, record) }.onFailure {
                                    AlertDialog.Builder(activity).setMessage("删除失败，请重新打开采集记录后重试。")
                                        .setPositiveButton("知道了", null).show()
                                }
                            }.show()
                    }.create().also {
                        it.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                        it.show()
                    }
            }
        }
        list.create().also {
            it.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            it.show()
        }
    }
}
