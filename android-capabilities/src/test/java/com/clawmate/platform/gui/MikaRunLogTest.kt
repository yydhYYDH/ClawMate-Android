package com.clawmate.platform.gui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MikaRunLogTest {
    private fun line(at: Long, status: String = "success") = MikaRunLog.encode(
        MikaRunRecord(at = at, kind = "collect", app = "com.example.app", label = "示例", goal = "采集当前页",
            status = status, pages = 2, items = 5),
    )

    @Test fun newestFirstAndBounded() {
        val lines = (1L..5L).map { line(it * 1_000) }
        val recent = MikaRunLog.parse(lines, 3)
        assertEquals(3, recent.size)
        assertEquals(5_000L, recent[0].at)
        assertEquals(3_000L, recent[2].at)
    }

    @Test fun malformedLinesAreSkipped() {
        val parsed = MikaRunLog.parse(listOf(line(1_000), "{not json", line(2_000)), 10)
        assertEquals(listOf(2_000L, 1_000L), parsed.map { it.at })
    }

    @Test fun fieldsAreTruncatedOnWrite() {
        val record = MikaRunLog.record("collect", "a".repeat(500), "b".repeat(500), "c\n".repeat(300),
            "success", note = "d".repeat(500))
        assertEquals(MikaRunLog.MAX_FIELD, record.app.length)
        assertEquals(MikaRunLog.MAX_FIELD, record.goal.length)
        assertTrue("newlines in goal are flattened", '\n' !in record.goal)
        assertEquals(MikaRunLog.MAX_FIELD, record.note.length)
    }

    @Test fun roundTripsThroughJsonl() {
        val record = MikaRunLog.record("phone", "com.sina.weibo", "微博", "看热搜", "failed", steps = 4, note = "403")
        val decoded = MikaRunLog.decode(MikaRunLog.encode(record))
        assertEquals(record.copy(at = decoded!!.at), decoded)
        assertNull(MikaRunLog.decode("garbage"))
    }
}
