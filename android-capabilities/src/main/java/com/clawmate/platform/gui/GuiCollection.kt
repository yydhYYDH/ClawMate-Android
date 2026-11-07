package com.clawmate.platform.gui

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Rect
import android.os.SystemClock
import android.util.AtomicFile
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
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

@Serializable
internal data class GuiCollection(
    val sourcePackage: String,
    val sourceLabel: String,
    val capturedAt: Long,
    val lines: List<String>,
    val partial: Boolean,
) {
    fun title(): String = "$sourceLabel · ${DateFormat.getDateTimeInstance().format(Date(capturedAt))}"

    fun preview(context: Context): ScrollView = ScrollView(context).apply {
        addView(TextView(context).apply {
            setPadding(32, 16, 32, 24)
            text = lines.joinToString("\n")
        })
    }

    fun description(): String = "$sourcePackage\n${lines.size} 条可见文字" +
        if (partial) "（达到读取上限，仅保存已读部分）" else ""

    companion object {
        @RequiresApi(34)
        fun read(
            scope: CoroutineScope,
            root: AccessibilityNodeInfo,
            bounds: Rect,
            sourcePackage: String,
            sourceLabel: String,
        ): GuiCollection {
            val deadline = SystemClock.elapsedRealtime() + 2000
            val lines = linkedSetOf<String>()
            val nodeBounds = Rect()
            var textSize = 0
            var remaining = 250
            var partial = false
            fun visit(node: AccessibilityNodeInfo, depth: Int) {
                scope.ensureActive()
                if (!node.isVisibleToUser || node.packageName?.toString() != sourcePackage ||
                    node.isPassword || node.isEditable || node.isAccessibilityDataSensitive
                ) return
                node.getBoundsInScreen(nodeBounds)
                if (!Rect.intersects(bounds, nodeBounds)) return

                val raw = node.text?.takeUnless { it.isBlank() } ?: node.contentDescription
                if (raw != null) {
                    val line = raw.take(600).toString().trim()
                    partial = partial || raw.length > 600
                    if (line.isNotEmpty() && line !in lines) {
                        if (lines.size >= 80 || textSize + line.length > 6000) {
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
            return GuiCollection(sourcePackage, sourceLabel, System.currentTimeMillis(), lines.toList(), partial)
        }
    }
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
