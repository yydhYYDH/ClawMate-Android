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

    fun description(): String = "$sourcePackage\n$pages 页 · ${lines.size} 条可见文字" +
        if (partial) "（仅包含已读部分）" else ""

    companion object {
        @RequiresApi(34)
        fun read(
            scope: CoroutineScope,
            root: AccessibilityNodeInfo,
            bounds: Rect,
            sourcePackage: String,
            sourceLabel: String,
            findScroll: Boolean,
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
                if (findScroll && !ambiguousScroll && node.canScrollCollectionDown(sourcePackage, bounds)) {
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
internal fun AccessibilityNodeInfo.canScrollCollectionDown(sourcePackage: String, area: Rect): Boolean =
    packageName?.toString() == sourcePackage && isVisibleToUser && isEnabled && isScrollable &&
        !isPassword && !isEditable && !isAccessibilityDataSensitive && ACTION_SCROLL_DOWN in actionList &&
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

/** Accessed on the UI thread. Only explicitly saved previews reach this app-private file. */
internal object GuiCollectionHistory {
    private fun file(context: Context) = AtomicFile(File(context.noBackupFilesDir, "gui-collections.json"))

    private fun read(context: Context): List<GuiCollection> {
        val bytes = try {
            file(context).readFully()
        } catch (_: FileNotFoundException) {
            return emptyList()
        }
        return Json.decodeFromString(bytes.toString(Charsets.UTF_8))
    }

    fun save(context: Context, value: GuiCollection) {
        write(context, (listOf(value) + read(context)).take(10))
    }

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
                AlertDialog.Builder(activity).setTitle(record.title()).setMessage(record.description())
                    .setView(record.preview(activity)).setNegativeButton("关闭", null)
                    .setNeutralButton("删除记录") { _, _ ->
                        AlertDialog.Builder(activity).setMessage("删除这条本机采集记录？")
                            .setNegativeButton("取消", null).setPositiveButton("删除") { _, _ ->
                                runCatching { write(activity, read(activity).toMutableList().apply {
                                    // Use the current file so a record saved since opening the list survives.
                                    remove(record)
                                }) }.onFailure {
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
