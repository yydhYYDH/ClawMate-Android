package com.clawmate.shared

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import com.journeyapps.barcodescanner.BarcodeView
import kotlin.math.roundToInt

/** The visible square is the region sent to the QR decoder. */
class CodexPairingPreview(context: Context, attrs: AttributeSet) : BarcodeView(context, attrs) {
    private val density = resources.displayMetrics.density
    private val shade = Paint().apply { color = 0xB3000000.toInt() }
    private val corners = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF8BD8FF.toInt()
        strokeWidth = 3 * density
        strokeCap = Paint.Cap.ROUND
    }
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x55FFFFFF
        style = Paint.Style.STROKE
        strokeWidth = density
    }
    private val bounds = Rect()
    private val idleFrame = Rect()

    override fun calculateFramingRect(container: Rect, surface: Rect): Rect {
        val visible = Rect(container)
        visible.intersect(surface)
        return Rect().apply { squareIn(visible, this) }
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        bounds.set(0, 0, width, height)
        squareIn(bounds, idleFrame)
        val frame = framingRect ?: idleFrame
        if (frame.isEmpty) return
        val left = frame.left.toFloat()
        val top = frame.top.toFloat()
        val right = frame.right.toFloat()
        val bottom = frame.bottom.toFloat()
        canvas.drawRect(0f, 0f, width.toFloat(), top, shade)
        canvas.drawRect(0f, bottom, width.toFloat(), height.toFloat(), shade)
        canvas.drawRect(0f, top, left, bottom, shade)
        canvas.drawRect(right, top, width.toFloat(), bottom, shade)
        canvas.drawRect(left, top, right, bottom, border)
        val length = minOf(22 * density, frame.width() / 5f)
        canvas.drawLine(left, top, left + length, top, corners)
        canvas.drawLine(left, top, left, top + length, corners)
        canvas.drawLine(right - length, top, right, top, corners)
        canvas.drawLine(right, top, right, top + length, corners)
        canvas.drawLine(left, bottom, left + length, bottom, corners)
        canvas.drawLine(left, bottom - length, left, bottom, corners)
        canvas.drawLine(right - length, bottom, right, bottom, corners)
        canvas.drawLine(right, bottom - length, right, bottom, corners)
    }

    private fun squareIn(area: Rect, out: Rect) {
        val size = minOf(minOf(area.width(), area.height()) * 0.84f, 420 * density).roundToInt()
        val left = area.centerX() - size / 2
        val top = area.centerY() - size / 2
        out.set(left, top, left + size, top + size)
    }
}
