package com.clawmate.platform.gui

import com.wangzan101.mika.core.assembly.collectPages
import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.uistate.ActionResult
import com.wangzan101.mika.core.uistate.ScreenPort
import com.wangzan101.mika.core.uistate.ScreenSnapshot
import com.wangzan101.mika.core.uistate.UiElement
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 任务页“Mika 采集”走的真实路径：`toolDispatcher` → `phone_collect` → 翻页/去重 → 文本。
 * 端口用两页假界面，验证跨页合并、重复行只留一次、以及结果能被 [MikaCollect] 整理成本机记录的行。
 */
class MikaCollectionFlowTest {
    @Test fun mikaCollectReadsPagesDeduplicatesAndReturnsSaveableLines() = runTest {
        val screen = PagedScreen()
        val collected = toolDispatcher(screen).collectPages(direction = "down", pages = 3, maxItems = 50)

        assertEquals("page_limit", collected.stoppedBecause)
        assertEquals(3, collected.pages)
        assertEquals(2, screen.scrolls)
        val lines = MikaCollect.lines(collected.text)
        assertEquals(listOf("第一页 A", "第一页 B", "第二页 C", "第二页 D"), lines)
        assertTrue("same line repeated across pages is kept once", lines.count { it == "第一页 A" } == 1)
    }

    @Test fun mikaCollectReportsStoppedReasonWhenThePortCannotScroll() = runTest {
        val screen = PagedScreen(scrollable = false)
        val collected = toolDispatcher(screen).collectPages(direction = "down", pages = 3, maxItems = 50)

        assertEquals("第一页 A", MikaCollect.lines(collected.text).first())
        assertTrue("stops after the first screen", collected.pages in 1..2)
        assertTrue(collected.stoppedBecause.isNotEmpty())
    }

    private class PagedScreen(private val scrollable: Boolean = true) : ScreenPort {
        private var page = 0
        private var sequence = 0
        var scrolls = 0
            private set

        private fun row(index: Int, text: String) =
            UiElement("@e$index", "TextView", text, 50, index * 100, listOf(0, index * 100, 99, index * 100 + 50),
                null, false, false, false)

        private fun scrollNode() = UiElement("@e9", "RecyclerView", "", 50, 500, listOf(0, 400, 99, 600),
            null, false, false, scrollable)

        override suspend fun snapshot(): ScreenSnapshot {
            val elements = if (page == 0) {
                listOf(row(0, "第一页 A"), row(1, "第一页 B"), row(2, "第一页 A"), scrollNode())
            } else {
                listOf(row(0, "第二页 C"), row(1, "第二页 D"), scrollNode())
            }
            return ScreenSnapshot("p${++sequence}", "example.list", elements, 100, 600)
        }

        override suspend fun screenshotPng(): ByteArray? = null
        override suspend fun tapRef(ref: String, snapshotId: String) = ActionResult.Failed("disabled")
        override suspend fun tapXY(x: Int, y: Int) = ActionResult.Failed("disabled")
        override suspend fun typeText(ref: String?, snapshotId: String?, text: String) = ActionResult.Failed("disabled")
        override suspend fun clearText(ref: String?, snapshotId: String?) = ActionResult.Failed("disabled")
        override suspend fun scroll(ref: String?, snapshotId: String?, down: Boolean): ActionResult {
            if (!scrollable) return ActionResult.Failed("no_scrollable")
            scrolls++
            page = 1
            return ActionResult.Ok()
        }

        override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long) = ActionResult.Failed("disabled")
        override suspend fun back() = ActionResult.Failed("disabled")
        override suspend fun pressKey(keyCode: Int) = ActionResult.Failed("disabled")
        override suspend fun openApp(packageName: String, cold: Boolean) = ActionResult.Failed("disabled")
        override suspend fun waitStable(timeoutMs: Long) = true
        override suspend fun listApps() = listOf("example.list" to "List")
    }
}
