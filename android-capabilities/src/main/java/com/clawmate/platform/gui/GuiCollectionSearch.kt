package com.clawmate.platform.gui

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** A read-only view over the same store shown in collection history. */
object GuiCollectionSearch {
    fun query(context: Context, raw: String): String = try {
        require(raw.length <= 1500) { "查询过长" }
        query(GuiCollectionHistory.read(context), raw).toString()
    } catch (failure: Exception) {
        buildJsonObject { put("ok", false); put("error", failure.message?.take(160) ?: "读取采集记录失败") }.toString()
    }

    internal fun query(records: List<GuiCollection>, raw: String): JsonObject {
        val args = Json.parseToJsonElement(raw.ifBlank { "{}" }) as? JsonObject ?: error("查询参数必须是对象")
        val allowed = setOf("source", "keyword", "date", "start_date", "end_date", "rank", "offset", "limit")
        require(args.keys.all { it in allowed }) { "含有未知的采集查询参数" }
        fun text(key: String): String {
            val value = args[key] ?: return ""
            require(value is JsonPrimitive && value.isString && value.content.length <= 120) { "${key}格式无效" }
            return value.content.trim()
        }
        fun number(key: String, default: Int, range: IntRange): Int {
            val value = args[key] ?: return default
            require(value is JsonPrimitive && !value.isString && (value.intOrNull ?: -1) in range) { "${key}超出范围" }
            return checkNotNull(value.intOrNull)
        }
        fun date(key: String): String = text(key).also { if (it.isNotEmpty()) require(LocalDate.parse(it).toString() == it) { "日期格式无效" } }
        val source = text("source")
        require(source in setOf("", "weibo", "gui")) { "采集来源无效" }
        val exactDate = date("date")
        val start = date("start_date")
        val end = date("end_date")
        require((exactDate.isEmpty() || start.isEmpty() && end.isEmpty()) && (start.isEmpty() || end.isEmpty() || start <= end)) { "日期范围冲突" }
        val rank = number("rank", 0, 1..WEIBO_BOARD_SIZE)
        val offset = number("offset", 0, 0..5000)
        val limit = number("limit", 20, 1..50)
        val keyword = text("keyword")
        var newestWeiboSeen = false
        val matches = mutableListOf<JsonObject>()
        for (record in records.sortedByDescending(GuiCollection::capturedAt)) {
            val isWeibo = record.sourcePackage == WeiboGuiWorkflow.PACKAGE
            if (source == "weibo" && !isWeibo || source == "gui" && isWeibo) continue
            val captured = Instant.ofEpochMilli(record.capturedAt)
            val day = record.weibo?.date ?: captured.atZone(ZoneId.systemDefault()).toLocalDate().toString()
            if (exactDate.isNotEmpty() && day != exactDate || start.isNotEmpty() && day < start || end.isNotEmpty() && day > end) continue
            // A single-day or unqualified Weibo request returns one ranked snapshot, not a merged ranking.
            if (isWeibo && source == "weibo" && start.isEmpty() && end.isEmpty() && keyword.isEmpty()) {
                if (newestWeiboSeen) continue
                newestWeiboSeen = true
            }
            val topics = record.weibo?.entries ?: record.lines.map { WeiboTopic(it) }
            for (topic in topics) {
                if (rank > 0 && topic.rank != rank || keyword.isNotEmpty() &&
                    !topic.title.contains(keyword, ignoreCase = true) && !record.sourceLabel.contains(keyword, ignoreCase = true)) continue
                matches += buildJsonObject {
                    put("source", record.sourceLabel.take(60)); put("date", day); put("captured_at", captured.toString())
                    put("title", topic.title.take(240)); topic.rank?.let { put("rank", it) }
                    if (topic.heat.isNotEmpty()) put("heat", topic.heat)
                    put("partial", record.partial)
                }
                if (matches.size > offset + limit) break
            }
            if (matches.size > offset + limit) break
        }
        val items = mutableListOf<JsonObject>()
        var chars = 150
        for (item in matches.drop(offset).take(limit)) {
            if (chars + item.toString().length > 3650) break
            items += item
            chars += item.toString().length + 1
        }
        val next = offset + items.size
        return buildJsonObject {
            put("ok", true); put("records", JsonArray(items)); put("has_more", next < matches.size)
            if (next < matches.size) put("next_offset", next)
        }
    }
}
