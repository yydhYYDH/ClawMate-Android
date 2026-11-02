package com.clawmate.platform.avatar

import org.junit.Assert.*
import org.junit.Test

class ForegroundPixelsTest {
    @Test fun maskKeepsStraightColorAndMultipliesSourceAlpha() {
        val result = ForegroundPixels.rgba(intArrayOf(0xff123456.toInt(), 0x80123456.toInt(), 0xffabcdef.toInt()), floatArrayOf(1f, 0.5f, 0.01f))
        assertArrayEquals(byteArrayOf(0x12,0x34,0x56,0xff.toByte(),0x12,0x34,0x56,64,0xab.toByte(),0xcd.toByte(),0xef.toByte(),0), result)
    }
    @Test fun cachedForegroundDecodePreservesAlphaWithoutSegmentation() {
        assertArrayEquals(byteArrayOf(0x12,0x34,0x56,0x80.toByte()), ForegroundPixels.rgba(intArrayOf(0x80123456.toInt())))
    }
    @Test fun absentPersonCannotBecomeOpaqueRectangle() {
        assertThrows(IllegalArgumentException::class.java) { ForegroundPixels.rgba(IntArray(100) { -1 }, FloatArray(100) { 0.05f }) }
    }
    @Test fun malformedMaskIsRejected() {
        for (value in listOf(Float.NaN, Float.POSITIVE_INFINITY, -0.1f, 1.1f)) {
            assertThrows(IllegalArgumentException::class.java) { ForegroundPixels.rgba(intArrayOf(-1), floatArrayOf(value)) }
        }
        assertThrows(IllegalArgumentException::class.java) { ForegroundPixels.rgba(intArrayOf(-1), FloatArray(0)) }
    }
}
