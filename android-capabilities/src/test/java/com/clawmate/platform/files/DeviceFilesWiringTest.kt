package com.clawmate.platform.files

import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.cap.devicefiles.DeviceFileEntry
import com.wangzan101.mika.core.cap.devicefiles.DeviceFileImport
import com.wangzan101.mika.core.cap.devicefiles.DeviceFilePage
import com.wangzan101.mika.core.cap.devicefiles.DeviceFileSearchQuery
import com.wangzan101.mika.core.cap.devicefiles.DeviceFileSearchResult
import com.wangzan101.mika.core.cap.devicefiles.DeviceFilesPort
import com.wangzan101.mika.core.uistate.ActionResult
import com.wangzan101.mika.core.uistate.ScreenPort
import com.wangzan101.mika.core.uistate.ScreenSnapshot
import com.wangzan101.mika.core.uistate.UiElement
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 设备文件工具只在授权了端口时出现；未授权时不应暴露给模型。 */
class DeviceFilesWiringTest {
    @Test fun deviceFileToolsAppearOnlyWithAGrantedPort() {
        val withPort = toolDispatcher(IdleScreen(), deviceFiles = FakePort()).definitions().map { it.name }
        assertTrue("expected a device_files tool, got $withPort", withPort.any { it.contains("device_files") })

        val without = toolDispatcher(IdleScreen()).definitions().map { it.name }
        assertFalse(without.any { it.contains("device_files") })
    }

    private class FakePort : DeviceFilesPort {
        override suspend fun list(path: String?, cursor: String?, limit: Int) =
            DeviceFilePage(listOf(DeviceFileEntry("notes.txt", "doc-1", false, 10, 1_000)))
        override suspend fun importFile(path: String, workspace: String) = DeviceFileImport(path, 10)
        override suspend fun search(query: DeviceFileSearchQuery) = DeviceFileSearchResult(emptyList(), 0, false)
    }

    private class IdleScreen : ScreenPort {
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
