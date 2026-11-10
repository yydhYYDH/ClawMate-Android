package com.clawmate.platform.gui

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CollectedMemoryTest {
    @Test fun completeRequiresEveryRankAndNoConflicts() {
        val board = WeiboBoardAccumulator()
        board.append((1..30).map { WeiboTopic("词条 $it", it) })
        board.append((25..50).map { WeiboTopic("词条 $it", it) })
        assertTrue(board.complete)
        assertEquals(50, board.entries().size)
        board.append(listOf(WeiboTopic("榜单期间发生变化", 20)))
        assertFalse(board.complete)
        assertEquals(null, board.entries().first { it.title == "词条 20" }.rank)
    }

    @Test fun searchesSavedSnapshotsByDateRankAndOffset() {
        val older = snapshot("2026-11-09", 1794210000000, "旧")
        val newest = snapshot("2026-11-10", 1794300000000, "新")
        val sameDayEarlier = snapshot("2026-11-10", 1794290000000, "较早")
        val records = listOf(older, newest, sameDayEarlier)
        val second = GuiCollectionSearch.query(records, """{"source":"weibo","rank":2}""")
        assertTrue(second.toString().contains("新 2"))
        assertFalse(second.toString().contains("较早"))
        val page = GuiCollectionSearch.query(records, """{"source":"weibo","date":"2026-11-09","offset":1,"limit":2}""")
        assertEquals(JsonPrimitive(3), page["next_offset"])
        assertEquals(2, (page["records"] as JsonArray).size)
        assertTrue(page.toString().contains("旧 2"))
        assertFalse(page.toString().contains("新"))
    }

    @Test fun excludesUnverifiedRankAndRejectsUnsupportedArguments() {
        val record = GuiCollection("com.example.fixture", "自建夹具", 1794300000000, listOf("合成账单"), true)
        assertEquals(0, (GuiCollectionSearch.query(listOf(record), """{"rank":1}""")["records"] as JsonArray).size)
        assertTrue(GuiCollectionSearch.query(listOf(record), """{"source":"gui","keyword":"账单"}""").toString().contains("合成账单"))
        assertThrows(IllegalArgumentException::class.java) { GuiCollectionSearch.query(listOf(record), """{"path":"/private"}""") }
    }

    private fun snapshot(date: String, captured: Long, prefix: String): GuiCollection {
        val topics = (1..50).map { WeiboTopic("$prefix $it", it, "100") }
        return GuiCollection("com.sina.weibo", "微博热搜", captured, emptyList(), false, 6,
            WeiboSnapshot(date, "Asia/Shanghai", topics, true, "完整"))
    }
}
