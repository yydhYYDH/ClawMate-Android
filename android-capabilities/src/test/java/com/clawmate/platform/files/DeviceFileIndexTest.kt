package com.clawmate.platform.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceFileIndexTest {
    private fun item(id: String, name: String, mime: String? = "text/plain", at: Long = 0) =
        LocalFileItem(id, name, mime, 10, at)

    @Test fun normalizesSeparatorsAndTrailingSlashes() {
        assertEquals("a/b", DeviceFileIndex.normalizePath("/a/b/"))
        assertEquals("a/b", DeviceFileIndex.normalizePath("a\\b"))
        assertEquals("", DeviceFileIndex.normalizePath(null))
    }

    @Test fun matchesNameAndMime() {
        val entry = item("1", "报告.md", "text/markdown")
        assertTrue(DeviceFileIndex.matches(entry, "报告"))
        assertTrue(DeviceFileIndex.matches(entry, "MARKDOWN"))
        assertTrue(DeviceFileIndex.matches(entry, ""))
        assertFalse(DeviceFileIndex.matches(entry, "图片"))
    }

    @Test fun pagesByStableOrderWithCursor() {
        val items = listOf(item("b", "b.txt"), item("a", "a.txt"), item("c", "c.txt"))
        val first = DeviceFileIndex.page(items, null, 2)
        assertEquals(listOf("a.txt", "b.txt"), first.items.map { it.name })
        assertEquals("b", first.nextCursor)
        val second = DeviceFileIndex.page(items, first.nextCursor, 2)
        assertEquals(listOf("c.txt"), second.items.map { it.name })
        assertNull(second.nextCursor)
    }

    @Test fun unknownCursorRestartsFromTheBeginning() {
        val items = listOf(item("a", "a.txt"), item("b", "b.txt"))
        val page = DeviceFileIndex.page(items, "missing", 1)
        assertEquals(listOf("a.txt"), page.items.map { it.name })
    }

    @Test fun searchFiltersBeforePaging() {
        val items = listOf(item("1", "notes.txt"), item("2", "photo.jpg", "image/jpeg"), item("3", "todo.txt"))
        val page = DeviceFileIndex.search(items, "txt", null, 10)
        assertEquals(listOf("notes.txt", "todo.txt"), page.items.map { it.name })
        assertTrue(DeviceFileIndex.search(items, "nothing", null, 10).items.isEmpty())
    }

    @Test fun pageSizeIsBounded() {
        val items = (1..150).map { item("$it", "%03d.txt".format(it)) }
        assertEquals(DeviceFileIndex.MAX_PAGE, DeviceFileIndex.page(items, null, 1000).items.size)
    }
}
