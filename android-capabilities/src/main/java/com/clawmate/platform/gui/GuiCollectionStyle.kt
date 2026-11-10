package com.clawmate.platform.gui

import android.app.Dialog
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Native collection surfaces use the same light/dark color tokens as ArkUI AppTheme. */
internal class GuiCollectionStyle(private val context: Context) {
    private val dark = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    private val surface = color("#FFFFFF", "#1C222D")
    private val soft = color("#F3F5FA", "#232B38")
    private val primary = color("#172033", "#ECF1FA")
    private val secondary = color("#536078", "#C0C9D9")
    private val border = color("#DCE3EF", "#465166")
    private val accent = color("#4B63EC", "#4658CB")
    private val accentText = color("#4258CE", "#B5C1FF")

    private fun color(light: String, night: String) = Color.parseColor(if (dark) night else light)
    fun dp(value: Int) = (value * context.resources.displayMetrics.density + .5f).toInt()
    private fun rounded(fill: Int, radius: Int, outline: Boolean = false) = GradientDrawable().apply {
        setColor(fill); cornerRadius = dp(radius).toFloat()
        if (outline) setStroke(dp(1), border)
    }

    fun card(compact: Boolean = false) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val padding = dp(if (compact) 12 else 20)
        setPadding(padding, padding, padding, padding)
        background = rounded(surface, if (compact) 16 else 24, outline = true)
        elevation = dp(8).toFloat()
    }

    fun label(text: String, size: Int, strong: Boolean = false) = TextView(context).apply {
        this.text = text; textSize = size.toFloat()
        setTextColor(if (strong) primary else secondary)
        if (strong) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setLineSpacing(dp(2).toFloat(), 1f)
    }

    fun badge(text: String) = label(text, 11, true).apply {
        setTextColor(accentText)
        setPadding(dp(9), dp(5), dp(9), dp(5))
        background = rounded(soft, 9)
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(10) }
    }

    fun action(text: String, filled: Boolean = false, click: () -> Unit) = Button(context).apply {
        this.text = text; isAllCaps = false; textSize = 14f; minHeight = dp(44); minimumHeight = dp(44)
        setPadding(dp(12), dp(6), dp(12), dp(6)); setTextColor(if (filled) Color.WHITE else accentText)
        background = rounded(if (filled) accent else soft, 12)
        setOnClickListener { click() }
    }

    fun recordContent(record: GuiCollection): ScrollView = ScrollView(context).apply {
        val rows = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            for (line in record.lines) {
                addView(label(line, 14, true).apply {
                    setPadding(dp(12), dp(9), dp(12), dp(9)); background = rounded(soft, 12)
                }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
            }
        }
        addView(rows)
        val height = (record.lines.size * dp(48)).coerceIn(dp(58), minOf(dp(310), context.resources.displayMetrics.heightPixels / 3))
        layoutParams = LinearLayout.LayoutParams(-1, height).apply { topMargin = dp(12) }
    }

    fun sheet(title: String, message: String, positive: String, negative: String = "取消",
        content: View? = null, overlay: Boolean = false, secure: Boolean = false,
        onNegative: (() -> Unit)? = null, confirm: () -> Unit,
    ): Dialog {
        val dialog = Dialog(context).apply { requestWindowFeature(Window.FEATURE_NO_TITLE) }
        val body = card().apply {
            addView(badge("ClawMate · 本机采集"))
            addView(label(title, 22, true))
            if (message.isNotEmpty()) addView(label(message, 14), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            content?.let { addView(it) }
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(action(negative) { dialog.cancel(); onNegative?.invoke() }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) })
                addView(action(positive, true) { confirm(); dialog.dismiss() }, LinearLayout.LayoutParams(0, -2, 1f))
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
        }
        dialog.setContentView(body)
        val window = checkNotNull(dialog.window)
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        if (overlay) window.setType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
        if (secure) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.attributes = window.attributes.apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; y = dp(20); dimAmount = .32f }
        dialog.show()
        window.setLayout(minOf(dp(480), context.resources.displayMetrics.widthPixels - dp(32)), -2)
        return dialog
    }
}
