package com.clawmate.platform.gui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.RequiresApi
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.serialization.Serializable
import java.util.concurrent.TimeUnit
import kotlin.math.abs

internal const val WEIBO_BOARD_SIZE = 50

@Serializable
internal data class WeiboTopic(val title: String, val rank: Int? = null, val heat: String = "") {
    fun display(): String = (rank?.let { "$it. " } ?: "未标名次 · ") + title +
        if (heat.isEmpty()) "" else " · $heat"
}

@Serializable
internal data class WeiboSnapshot(
    val date: String,
    val timezone: String,
    val entries: List<WeiboTopic>,
    val complete: Boolean,
    val endReason: String,
)

internal class WeiboBoardAccumulator {
    private val topics = linkedMapOf<String, WeiboTopic>()
    private val titlesByRank = mutableMapOf<Int, String>()
    private val disputed = mutableSetOf<Int>()
    val size: Int get() = topics.size
    val reachedLastRank: Boolean get() = WEIBO_BOARD_SIZE in titlesByRank
    val complete: Boolean get() = disputed.isEmpty() && (1..WEIBO_BOARD_SIZE).all(titlesByRank::containsKey)

    fun append(rows: List<WeiboTopic>): Boolean {
        val before = topics.values.toList()
        for (row in rows) {
            if (topics.size >= 100 && row.title !in topics) break
            val rank = row.rank
            if (rank != null) {
                val previous = titlesByRank.putIfAbsent(rank, row.title)
                if (previous != null && previous != row.title) disputed += rank
                val oldRank = topics[row.title]?.rank
                if (oldRank != null && oldRank != rank) { disputed += oldRank; disputed += rank }
            }
            val existing = topics[row.title]
            topics[row.title] = if (existing == null) row else existing.copy(
                rank = existing.rank ?: rank,
                heat = existing.heat.ifEmpty { row.heat },
            )
            if (rank == WEIBO_BOARD_SIZE) break
        }
        return before != topics.values.toList()
    }

    fun entries(): List<WeiboTopic> = topics.values.map { if (it.rank in disputed) it.copy(rank = null) else it }
}

internal data class WeiboVisibleRow(val title: String, val heat: String, val rankIcon: Rect?)
internal data class WeiboVisiblePage(val rows: List<WeiboVisibleRow>, val list: Rect?, val sectionEnded: Boolean)

/** Only the rank glyphs are sent to the bundled, on-device text recognizer. */
internal class WeiboRankReader : AutoCloseable {
    private val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())

    fun ranks(image: Bitmap, window: Rect, rows: List<WeiboVisibleRow>): Map<Int, Int> {
        val icons = rows.mapIndexedNotNull { index, row ->
            val icon = row.rankIcon ?: return@mapIndexedNotNull null
            val crop = Rect(icon).apply { offset(-window.left, -window.top) }
            if (crop.isEmpty || crop.height() != crop.width() ||
                !Rect(0, 0, image.width, image.height).contains(crop)) null else index to crop
        }
        if (icons.isEmpty()) return emptyMap()
        val primary = recognize(image, icons, columns = 4, contrast = true)
        val missing = icons.filter { (index, _) -> index !in primary && rows[index].heat.isNotEmpty() }
        if (missing.isEmpty()) return primary
        // Use the original color and wider separation when the first layout missed a numbered row.
        val fallback = runCatching { recognize(image, missing, columns = 2, contrast = false) }.getOrDefault(emptyMap())
        return primary + fallback
    }

    private fun recognize(image: Bitmap, icons: List<Pair<Int, Rect>>, columns: Int, contrast: Boolean): Map<Int, Int> {
        val slot = if (contrast) 128 else 176
        val inset = if (contrast) 14 else 22
        val strip = Bitmap.createBitmap(slot * columns, slot * ((icons.size + columns - 1) / columns), Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(strip)
            canvas.drawColor(Color.WHITE)
            val paint = Paint(Paint.FILTER_BITMAP_FLAG)
            icons.forEachIndexed { cell, (_, crop) ->
                val left = (cell % columns) * slot
                val top = (cell / columns) * slot
                val target = Rect(left + inset, top + inset, left + slot - inset, top + slot - inset)
                if (contrast) {
                    val glyph = contrastGlyph(image, crop)
                    try { canvas.drawBitmap(glyph, null, target, paint) } finally { glyph.recycle() }
                } else {
                    canvas.drawBitmap(image, crop, target, paint)
                }
            }
            val text = Tasks.await(recognizer.process(InputImage.fromBitmap(strip, 0)), 5, TimeUnit.SECONDS)
            val matches = mutableMapOf<Int, MutableSet<Int>>()
            for (element in text.textBlocks.flatMap { it.lines }.flatMap { it.elements }) {
                val box = element.boundingBox ?: continue
                val number = element.text.trim().takeIf { it.matches(Regex("[1-9][0-9]?")) }?.toIntOrNull() ?: continue
                if (number !in 1..WEIBO_BOARD_SIZE || box.isEmpty) continue
                val row = box.centerY() / slot
                val column = box.centerX() / slot
                if (column !in 0 until columns || box.left / slot != column || (box.right - 1) / slot != column ||
                    box.top / slot != row || (box.bottom - 1) / slot != row) continue
                val index = icons.getOrNull(row * columns + column)?.first ?: continue
                matches.getOrPut(index) { mutableSetOf() }.add(number)
            }
            return matches.mapNotNull { (index, candidates) -> candidates.singleOrNull()?.let { index to it } }.toMap()
        } finally { strip.recycle() }
    }

    private fun contrastGlyph(image: Bitmap, crop: Rect): Bitmap {
        val width = crop.width()
        val height = crop.height()
        val pixels = IntArray(width * height)
        image.getPixels(pixels, 0, width, crop.left, crop.top, width, height)
        val background = pixels[0]
        for (index in pixels.indices) {
            val pixel = pixels[index]
            val difference = maxOf(abs(Color.red(pixel) - Color.red(background)),
                abs(Color.green(pixel) - Color.green(background)), abs(Color.blue(pixel) - Color.blue(background)))
            pixels[index] = if (difference >= 55) Color.BLACK else Color.WHITE
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    override fun close() = recognizer.close()
}

@RequiresApi(34)
internal fun AccessibilityNodeInfo.weiboBounds(): Rect = Rect().also(::getBoundsInScreen)
