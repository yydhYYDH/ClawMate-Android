package com.clawmate.platform.codex

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Origin, token, CA, pairing and label rules ported from `CodexTypes.ets`. */
class CodexTypesTest {

    private val now = 1_700_000_000_000L

    @Test
    fun `origin must be a bare https authority`() {
        assertEquals("https://codex.example.com", CodexTypes.normalizeOrigin("  https://codex.example.com/  "))
        assertEquals("https://codex.example.com:8787", CodexTypes.normalizeOrigin("https://Codex.Example.com:8787"))
        assertEquals("https://[2001:db8::1]:8787", CodexTypes.normalizeOrigin("https://[2001:DB8::1]:8787"))
    }

    @Test
    fun `origin rejects paths schemes and credentials`() {
        for (bad in listOf(
            "http://codex.example.com",
            "https://codex.example.com/v1",
            "https://user:pass@codex.example.com",
            "https://codex.example.com:0",
            "https://codex.example.com:99999",
            "codex.example.com",
            "",
        )) {
            assertThrows(CodexValidationException::class.java) { CodexTypes.normalizeOrigin(bad) }
        }
    }

    @Test
    fun `token length and alphabet are enforced`() {
        assertTrue(CodexTypes.isValidToken("a".repeat(43)))
        assertTrue(CodexTypes.isValidToken("A-_9".repeat(11)))
        assertFalse(CodexTypes.isValidToken("a".repeat(42)))
        assertFalse(CodexTypes.isValidToken("a".repeat(129)))
        assertFalse(CodexTypes.isValidToken("a".repeat(43) + "+"))
    }

    @Test
    fun `certificate must be one bounded PEM block`() {
        val pem = "-----BEGIN CERTIFICATE-----\nMIIB\n-----END CERTIFICATE-----"
        assertTrue(CodexTypes.isValidCa(pem))
        assertFalse(CodexTypes.isValidCa("system"))
        assertFalse(CodexTypes.isValidCa(pem + "\ntrailing"))
        assertFalse(CodexTypes.isValidCa("-----BEGIN CERTIFICATE-----\n" + "A".repeat(2100) + "\n-----END CERTIFICATE-----"))
    }

    @Test
    fun `pairing bundle must be fresh and self-consistent`() {
        val good = bundle(expiresAt = now + 60_000)
        assertNotNull(CodexTypes.validatePairing(good, now))
        // Already expired.
        assertThrows(CodexValidationException::class.java) {
            CodexTypes.validatePairing(bundle(expiresAt = now - 1), now)
        }
        // Valid for longer than the ten-minute window: the file or the clock is wrong.
        assertThrows(CodexValidationException::class.java) {
            CodexTypes.validatePairing(bundle(expiresAt = now + 11 * 60_000), now)
        }
        // A non-normalized origin in the bundle is rejected rather than silently fixed.
        assertThrows(CodexValidationException::class.java) {
            CodexTypes.validatePairing(bundle(origin = "https://codex.example.com/"), now)
        }
        assertThrows(CodexValidationException::class.java) {
            CodexTypes.validatePairing(null, now)
        }
    }

    @Test
    fun `a full task snapshot replaces the current task`() {
        val current = task(revision = 3)
        val incoming = task(revision = 9, items = listOf(CodexItem("a", "assistant", "hi")))
        val merged = CodexTypes.mergeTask(current, CodexEnvelope(task = incoming))
        assertEquals(9L, merged?.revision)
        assertEquals("hi", merged?.items?.single()?.text)
    }

    @Test
    fun `a delta must continue its exact base revision`() {
        val current = task(revision = 3, items = listOf(CodexItem("a", "assistant", "he")))
        val next = task(revision = 4)
        val delta = CodexTaskDelta(
            baseRevision = 5, // stale
            task = next,
            items = listOf(CodexItemPatch("a", "assistant", "llo", append = true)),
            itemOrder = listOf("a"),
        )
        assertThrows(CodexStaleDeltaException::class.java) {
            CodexTypes.mergeTask(current, CodexEnvelope(taskDelta = delta))
        }
    }

    @Test
    fun `an append patch extends the existing record`() {
        val current = task(revision = 3, items = listOf(CodexItem("a", "assistant", "he")))
        val delta = CodexTaskDelta(
            baseRevision = 3,
            task = task(revision = 4),
            items = listOf(CodexItemPatch("a", "assistant", "llo", append = true)),
            itemOrder = listOf("a"),
        )
        val merged = CodexTypes.mergeTask(current, CodexEnvelope(taskDelta = delta))
        assertEquals("hello", merged?.items?.single()?.text)
    }

    @Test
    fun `an append patch needs the original record`() {
        val current = task(revision = 3, items = emptyList())
        val delta = CodexTaskDelta(
            baseRevision = 3,
            task = task(revision = 4),
            items = listOf(CodexItemPatch("missing", "assistant", "x", append = true)),
            itemOrder = listOf("missing"),
        )
        assertThrows(CodexStaleDeltaException::class.java) {
            CodexTypes.mergeTask(current, CodexEnvelope(taskDelta = delta))
        }
    }

    @Test
    fun `a delta that would reorder collapsed records is rejected`() {
        val current = task(revision = 3, items = listOf(CodexItem("a", "assistant", "1")))
        val delta = CodexTaskDelta(
            baseRevision = 3,
            task = task(revision = 4),
            items = emptyList(),
            itemOrder = listOf("a", "a"),
        )
        assertThrows(CodexStaleDeltaException::class.java) {
            CodexTypes.mergeTask(current, CodexEnvelope(taskDelta = delta))
        }
    }

    @Test
    fun `an envelope without a task yields no change`() {
        assertNull(CodexTypes.mergeTask(task(), CodexEnvelope(unchanged = true)))
    }

    @Test
    fun `artifact bounds are enforced`() {
        val ok = CodexArtifact(id = "f1", name = "report.pdf", size = 10, sha256 = "a".repeat(64))
        CodexTypes.validateArtifact(ok)
        assertThrows(CodexValidationException::class.java) { CodexTypes.validateArtifact(ok.copy(name = "../x")) }
        assertThrows(CodexValidationException::class.java) { CodexTypes.validateArtifact(ok.copy(sha256 = "xyz")) }
        assertThrows(CodexValidationException::class.java) { CodexTypes.validateArtifact(ok.copy(size = 0)) }
        val many = (1..25).map { ok.copy(id = "f$it", name = "f$it.txt") }
        assertThrows(CodexValidationException::class.java) { CodexTypes.validateTurnArtifacts(many) }
    }

    @Test
    fun `busy statuses match the bridge`() {
        for (s in listOf("starting", "running", "waitingApproval", "stopping", "releasing")) {
            assertTrue(s, CodexTypes.taskIsBusy(s))
        }
        for (s in listOf("idle", "completed", "failed", "interrupted")) {
            assertFalse(s, CodexTypes.taskIsBusy(s))
        }
    }

    @Test
    fun `link label reflects the most recent outcome`() {
        assertEquals("已连接", CodexTypes.linkLabel(lastSuccess = 1000, lastFailure = 0, now = 2000))
        assertEquals("连接已中断", CodexTypes.linkLabel(lastSuccess = 1000, lastFailure = 2000, now = 2500))
        assertEquals("正在重连", CodexTypes.linkLabel(lastSuccess = 1000, lastFailure = 2000, now = 2500, checking = true))
        assertEquals("正在确认连接", CodexTypes.linkLabel(lastSuccess = 0, lastFailure = 0, now = 99_000))
        // A success older than 25s is no longer proof of a live link.
        assertEquals("正在确认连接", CodexTypes.linkLabel(lastSuccess = 1000, lastFailure = 0, now = 30_000))
    }

    private fun bundle(
        expiresAt: Long = now + 60_000,
        origin: String = "https://codex.example.com",
    ) = CodexPairingBundle(        type = "clawmate-codex-pairing",
        version = 1,
        name = "我的开发机",
        origin = origin,
        serverId = "server-1",
        caCertificate = "",
        code = "a".repeat(43),
        expiresAt = expiresAt,
    )

    private fun task(
        revision: Long = 0,
        items: List<CodexItem> = emptyList(),
    ) = CodexTask(
        id = "task-1",
        workspaceId = "app",
        revision = revision,
        items = items,
    )
}
