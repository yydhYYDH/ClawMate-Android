package com.clawmate.platform.gui

import android.app.Activity
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** The pilot accepts only this fixed public search and previews Mika's response as text. */
class MikaXhsPilotActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private lateinit var status: TextView
    private lateinit var start: Button
    private lateinit var stop: Button
    private lateinit var port: EditText
    private lateinit var token: EditText

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val style = GuiCollectionStyle(this)
        val panel = style.card()
        panel.addView(style.label("Mika 后台屏试验", 22, true))
        panel.addView(style.label(
            "固定公开搜索「${MikaXhsPilot.KEYWORD}」。请先在 Mika 配好模型、完成手机操作设置，并开启经 USB 连接。"
                + "\n任务和 Mika 读取的界面可能发给它配置的模型服务。结果仅在这里预览。",
            15,
        ))
        port = field(style, "Mika USB 本机端口", InputType.TYPE_CLASS_NUMBER, 5).apply { setText("8765") }
        token = field(style, "Mika USB 连接令牌", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD, 128)
        panel.addView(port)
        panel.addView(token)
        start = style.action("确认并开始采集", filled = true) { startCollection() }
        stop = style.action("停止 Mika 任务") {
            status.text = "正在请求 Mika 停止…"
            stop.isEnabled = false
            job?.cancel()
        }.apply { isEnabled = false }
        panel.addView(start)
        panel.addView(stop)
        status = style.label("等待本次确认", 15).apply { accessibilityLiveRegion = TextView.ACCESSIBILITY_LIVE_REGION_POLITE }
        panel.addView(status)
        setContentView(ScrollView(this).apply { addView(panel) })
    }

    private fun field(style: GuiCollectionStyle, label: String, type: Int, limit: Int): EditText = EditText(this).apply {
        hint = label
        setSingleLine()
        inputType = type
        setTextColor(style.label("", 15, true).currentTextColor)
        setHintTextColor(style.label("", 15).currentTextColor)
        filters = arrayOf(InputFilter.LengthFilter(limit))
        imeOptions = imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        isSaveEnabled = false
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
    }

    private fun startCollection() {
        if (job?.isActive == true) return
        val client = try {
            MikaXhsPilot(port.text.toString().toIntOrNull() ?: 0, token.text.toString().trim())
        } catch (failure: IllegalArgumentException) {
            status.text = failure.message
            return
        }
        token.text.clear()
        start.isEnabled = false
        stop.isEnabled = true
        status.text = "正在连接这台手机上的 Mika…"
        job = scope.launch {
            var stopped: Boolean? = null
            try {
                val notes = client.collect(
                    progress = { status.text = it },
                    onStop = { stopped = it },
                )
                status.text = if (notes.isEmpty()) "Mika 没有返回公开笔记。请检查小红书搜索和登录状态。"
                else notes.joinToString("\n\n", prefix = "Mika 返回 ${notes.size} 条 · 请核对原文\n\n") { note ->
                    listOf(note.title, note.excerpt).filter(String::isNotEmpty).joinToString("\n")
                }
            } catch (cancelled: CancellationException) {
                status.text = if (stopped == false) "未能确认 Mika 已停止，请在 Mika 检查本次任务。"
                else "已取消本次试验"
                throw cancelled
            } catch (failure: Exception) {
                val reason = if (failure is IOException) "无法连接 Mika，请检查经 USB 连接、端口和本机服务"
                else failure.message ?: "Mika 试验失败"
                status.text = reason + if (stopped == false) "\n未能确认任务已停止，请在 Mika 检查。" else ""
            } finally {
                start.isEnabled = true
                stop.isEnabled = false
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
