package com.clawmate.platform.avatar

import kotlin.math.roundToInt

/** Convert the model's person confidence to straight RGBA; never invent a foreground. */
object ForegroundPixels {
    fun rgba(argb: IntArray, confidence: FloatArray? = null): ByteArray {
        require(confidence == null || confidence.size == argb.size) { "分割蒙版尺寸无效" }
        var confident = 0
        val output = ByteArray(argb.size * 4)
        argb.forEachIndexed { i, color ->
            val score = confidence?.get(i) ?: 1f
            require(score.isFinite() && score in 0f..1f) { "分割蒙版数值无效" }
            if (score >= 0.8f && (color ushr 24) > 0) confident++
            val alpha = if (score < 0.1f) 0 else ((color ushr 24) * score).roundToInt()
            output[i * 4] = (color ushr 16).toByte()
            output[i * 4 + 1] = (color ushr 8).toByte()
            output[i * 4 + 2] = color.toByte()
            output[i * 4 + 3] = alpha.toByte()
        }
        require(confidence == null || confident >= maxOf(1, argb.size / 200)) { "未检测到清晰人物，请换用人物清晰的分身图片" }
        return output
    }
}
