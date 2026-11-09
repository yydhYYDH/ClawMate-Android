package com.clawmate.platform.gui

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuiCollectionAccumulatorTest {
    private fun page(lines: List<String>, partial: Boolean = false) =
        GuiCollection("com.example.fixture", "Fixture", 1L, lines, partial)

    @Test fun retainsFirstSeenOrderAndDoesNotCountRepeatedPages() {
        val first = page(listOf("first", "overlap"))
        val accumulator = GuiCollectionAccumulator()
        assertTrue(accumulator.append(first))
        assertTrue(accumulator.append(page(listOf("overlap", "second"))))
        assertFalse(accumulator.append(page(listOf("second", "overlap"))))
        assertEquals(
            first.copy(lines = listOf("first", "overlap", "second"), pages = 2, partial = true),
            accumulator.record(first, incomplete = true),
        )
    }

    @Test fun capsCombinedLinesAndMarksPartialResults() {
        val first = page((1..79).map(Int::toString))
        val accumulator = GuiCollectionAccumulator()
        accumulator.append(first)
        assertTrue(accumulator.append(page(listOf("80", "81"))))
        assertTrue(accumulator.truncated)
        assertFalse(accumulator.hasCapacity)
        assertEquals((1..80).map(Int::toString), accumulator.record(first, incomplete = false).lines)
        assertTrue(accumulator.record(first, incomplete = false).partial)
    }

    @Test fun capsCombinedCharactersAndPreservesPageTruncation() {
        val first = page((0..8).map { it.toString().repeat(600) }, partial = true)
        val accumulator = GuiCollectionAccumulator()
        accumulator.append(first)
        assertTrue(accumulator.truncated)
        accumulator.append(page(listOf("a".repeat(599), "bc")))
        val record = accumulator.record(first, incomplete = false)
        assertEquals(5_999, record.lines.sumOf(String::length))
        assertTrue(record.partial)
        assertEquals(2, record.pages)
    }

    @Test fun readsOlderSinglePageRecords() {
        val old = """{"sourcePackage":"com.example.fixture","sourceLabel":"Fixture","capturedAt":1,"lines":["text"],"partial":false}"""
        assertEquals(page(listOf("text")), Json.decodeFromString<GuiCollection>(old))
    }
}
