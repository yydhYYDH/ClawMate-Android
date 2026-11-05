package com.clawmate.platform.codex

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Credential policy ported from `CodexCredentialStore.ets`. */
class CodexCredentialStoreTest {

    private class FakeStorage : CodexSecretStorage {
        val data = LinkedHashMap<String, String>()
        var failWrites = false

        override fun readAll(): Map<String, String> = data.toMap()

        override fun write(id: String, value: String) {
            if (failWrites) throw IllegalStateException("无法保存安全连接信息，请解锁设备后重试")
            data[id] = value
        }

        override fun remove(id: String) {
            data.remove(id)
        }
    }

    private val token = "a".repeat(43)
    private fun connection(origin: String, ca: String = "") =
        CodexConnection(origin = origin, token = token, caCertificate = ca)

    @Test
    fun `a saved connection round-trips the token and certificate`() {
        val store = CodexCredentialStore(FakeStorage()) { 1000L }
        val saved = store.save(connection("https://codex.example.com"), name = "开发机")

        assertEquals("开发机", saved.name)
        assertEquals("https://codex.example.com", saved.origin)
        assertFalse(saved.hasCustomCa)

        val loaded = store.load(saved.id)!!
        assertEquals(token, loaded.token)
        assertEquals("https://codex.example.com", loaded.origin)
    }

    @Test
    fun `the token is never present in the exposed metadata`() {
        val storage = FakeStorage()
        val store = CodexCredentialStore(storage) { 1000L }
        store.save(connection("https://codex.example.com"))

        val listed = store.list().single()
        assertFalse(listed.toString().contains(token))
        // ...but the stored record does contain it, since that is what must be restored.
        assertTrue(storage.data.values.single().contains(token))
    }

    @Test
    fun `saving the same origin reuses its entry`() {
        val store = CodexCredentialStore(FakeStorage()) { 1000L }
        val first = store.save(connection("https://codex.example.com"), "一")
        val second = store.save(connection("https://codex.example.com"), "二")

        assertEquals(first.id, second.id)
        assertEquals(1, store.list().size)
        assertEquals("二", store.list().single().name)
    }

    @Test
    fun `a different origin creates a separate entry`() {
        val store = CodexCredentialStore(FakeStorage()) { 1000L }
        store.save(connection("https://a.example.com"))
        store.save(connection("https://b.example.com"))

        assertEquals(2, store.list().size)
        assertEquals(2, setOf("https://a.example.com", "https://b.example.com").size)
    }

    @Test
    fun `list is ordered newest first`() {
        var clock = 1000L
        val store = CodexCredentialStore(FakeStorage()) { clock }
        store.save(connection("https://a.example.com"))
        clock = 2000L
        store.save(connection("https://b.example.com"))

        assertEquals("https://b.example.com", store.list().first().origin)
    }

    @Test
    fun `an empty id resolves the most recent connection`() {
        var clock = 1000L
        val store = CodexCredentialStore(FakeStorage()) { clock }
        store.save(connection("https://a.example.com"))
        clock = 2000L
        store.save(connection("https://b.example.com"))

        assertEquals("https://b.example.com", store.load()!!.origin)
    }

    @Test
    fun `nothing saved means null rather than an error`() {
        assertNull(CodexCredentialStore(FakeStorage()).load())
    }

    @Test
    fun `a tampered token forces a re-pair instead of being used`() {
        val storage = FakeStorage()
        val store = CodexCredentialStore(storage) { 1000L }
        val saved = store.save(connection("https://codex.example.com"))
        val id = saved.id
        storage.data[id] = storage.data[id]!!.replace(token, "tooshort")

        assertThrows(CodexValidationException::class.java) { store.load(id) }
    }

    @Test
    fun `a corrupted record is reported`() {
        val storage = FakeStorage()
        storage.data["aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"] = "{not json"
        val store = CodexCredentialStore(storage)
        assertThrows(CodexValidationException::class.java) {
            store.load("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
        }
    }

    @Test
    fun `an entry with a foreign key is rejected`() {
        val store = CodexCredentialStore(FakeStorage())
        assertThrows(CodexValidationException::class.java) { store.load("not-a-uuid") }
    }

    @Test
    fun `an invalid origin or token cannot be saved`() {
        val store = CodexCredentialStore(FakeStorage())
        assertThrows(CodexValidationException::class.java) {
            store.save(connection("http://codex.example.com"))
        }
        assertThrows(CodexValidationException::class.java) {
            store.save(CodexConnection("https://codex.example.com", "short"))
        }
    }

    @Test
    fun `an invalid certificate cannot be saved`() {
        val store = CodexCredentialStore(FakeStorage())
        assertThrows(CodexValidationException::class.java) {
            store.save(connection("https://codex.example.com", ca = "system"))
        }
    }

    @Test
    fun `forget removes only the requested connection`() {
        val store = CodexCredentialStore(FakeStorage()) { 1000L }
        val a = store.save(connection("https://a.example.com"))
        store.save(connection("https://b.example.com"))

        store.forget(a.id)

        assertEquals(1, store.list().size)
        assertEquals("https://b.example.com", store.list().single().origin)
    }

    @Test
    fun `a storage failure propagates instead of falling back to plain text`() {
        val storage = FakeStorage().apply { failWrites = true }
        val store = CodexCredentialStore(storage)
        assertThrows(IllegalStateException::class.java) {
            store.save(connection("https://codex.example.com"))
        }
        assertTrue(storage.data.isEmpty())
    }
}
