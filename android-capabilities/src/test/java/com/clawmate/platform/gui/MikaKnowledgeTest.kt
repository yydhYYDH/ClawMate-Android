package com.clawmate.platform.gui

import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.knowledge.GuideCatalog
import com.wangzan101.mika.core.uistate.ActionResult
import com.wangzan101.mika.core.uistate.ScreenPort
import com.wangzan101.mika.core.uistate.ScreenSnapshot
import com.wangzan101.mika.core.uistate.UiElement
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 知识与技能索引随内核资源打包；接上之后模型才看得到 App/站点做法，也才会出现 knowledge_* / skill_* 工具。
 */
class MikaKnowledgeTest {
    @Test fun bundledCatalogShipsAppSiteAndSkillKnowledge() {
        val catalog = GuideCatalog.loadBundled()
        val names = catalog.items.map { it.name }
        assertTrue("app knowledge", names.contains("xiaohongshu"))
        assertTrue("app knowledge", names.contains("wechat"))
        assertTrue("site knowledge", names.contains("xiaohongshu.com"))
        assertTrue("skill", names.contains("messaging"))
        assertTrue("chinese alias finds the app", catalog.find("小红书").any { it.name == "xiaohongshu" })
        assertTrue("domain finds the site", catalog.find("xiaohongshu.com").any { it.name == "xiaohongshu.com" })
    }

    @Test fun knowledgeToolsAppearOnlyWhenTheCatalogIsWired() {
        val withCatalog = toolDispatcher(FakeScreen(), skills = GuideCatalog.loadBundled()).definitions().map { it.name }
        assertTrue("knowledge_search", "knowledge_search" in withCatalog)
        assertTrue("skill_read", "skill_read" in withCatalog)

        val withoutCatalog = toolDispatcher(FakeScreen()).definitions().map { it.name }
        assertFalse("knowledge_search", "knowledge_search" in withoutCatalog)
        assertFalse("skill_search", "skill_search" in withoutCatalog)
        // 没有记忆端口时，记忆类工具仍然不出现：接知识与接记忆是两件事。
        assertFalse("memory_search", "memory_search" in withCatalog)
    }

    private class FakeScreen : ScreenPort {
        override suspend fun snapshot() = ScreenSnapshot("p1", "example.fixture",
            listOf(UiElement("@e1", "TextView", "Fixture", 5, 5, listOf(0, 0, 10, 10), null, false, false, false)), 10, 10)
        override suspend fun screenshotPng(): ByteArray? = null
        override suspend fun tapRef(ref: String, snapshotId: String) = ActionResult.Failed("disabled")
        override suspend fun tapXY(x: Int, y: Int) = ActionResult.Failed("disabled")
        override suspend fun typeText(ref: String?, snapshotId: String?, text: String) = ActionResult.Failed("disabled")
        override suspend fun clearText(ref: String?, snapshotId: String?) = ActionResult.Failed("disabled")
        override suspend fun scroll(ref: String?, snapshotId: String?, down: Boolean) = ActionResult.Failed("disabled")
        override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long) = ActionResult.Failed("disabled")
        override suspend fun back() = ActionResult.Failed("disabled")
        override suspend fun pressKey(keyCode: Int) = ActionResult.Failed("disabled")
        override suspend fun openApp(packageName: String, cold: Boolean) = ActionResult.Failed("disabled")
        override suspend fun waitStable(timeoutMs: Long) = true
        override suspend fun listApps() = listOf("example.fixture" to "Fixture")
    }
}
