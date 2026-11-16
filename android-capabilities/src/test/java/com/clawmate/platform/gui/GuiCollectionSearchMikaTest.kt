package com.clawmate.platform.gui

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 任务页用 Mika 保存的采集记录走的是同一个本机存储，聊天里的 `guiCollectionQuery` 必须能查到。
 */
class GuiCollectionSearchMikaTest {
    private val mika = GuiCollection(
        sourcePackage = "tv.danmaku.bili",
        sourceLabel = "哔哩哔哩 · Mika",
        capturedAt = 1_794_800_000_000,
        lines = listOf("视频 A", "视频 B"),
        partial = true,
        pages = 5,
    )
    private val weibo = GuiCollection(
        sourcePackage = "com.sina.weibo",
        sourceLabel = "微博热搜",
        capturedAt = 1_794_900_000_000,
        lines = listOf("热搜一"),
        partial = false,
    )

    @Test fun genericQueryReturnsMikaRecordsWithTheirTitles() {
        val result: JsonObject = GuiCollectionSearch.query(listOf(mika, weibo), """{"source":"gui","limit":10}""")
        assertEquals("true", result["ok"].toString())
        val records = result["records"] as JsonArray
        // 查询按“每条内容”返回：Mika 采集的每一行各是一条记录。
        assertEquals(2, records.size)
        val first = records[0] as JsonObject
        assertEquals("哔哩哔哩 · Mika", first["source"]!!.jsonPrimitive.content)
        assertEquals("视频 A", first["title"]!!.jsonPrimitive.content)
    }

    @Test fun weiboQueryDoesNotLeakMikaRecords() {
        val result = GuiCollectionSearch.query(listOf(mika, weibo), """{"source":"weibo","limit":10}""")
        val records = result["records"] as JsonArray
        assertTrue(records.all { (it as JsonObject)["source"]!!.jsonPrimitive.content != "哔哩哔哩 · Mika" })
    }

    @Test fun keywordMatchesAMikaLine() {
        val result = GuiCollectionSearch.query(listOf(mika), """{"source":"gui","keyword":"视频 B","limit":10}""")
        val records = result["records"] as JsonArray
        assertEquals(1, records.size)
        assertEquals("视频 B", (records[0] as JsonObject)["title"]!!.jsonPrimitive.content)
    }
}
