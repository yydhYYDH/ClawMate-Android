package com.clawmate.platform.codex

import kotlinx.serialization.json.Json

/**
 * Storage backend for [CodexCredentialStore].
 *
 * Kept behind an interface so the policy can be unit-tested on a plain JVM while the
 * Android implementation is backed by hardware-backed encryption.
 */
interface CodexSecretStorage {
    /** All values currently stored, keyed by [CodexCredentialStore] entry id. */
    fun readAll(): Map<String, String>

    /** Atomically replaces the value for [id]. */
    fun write(id: String, value: String)

    fun remove(id: String)
}

/**
 * Multi-connection credential store for the Codex bridge.
 *
 * Replaces the HarmonyOS `@kit.AssetStoreKit` usage in `CodexCredentialStore.ets`.
 * Policy carried over unchanged:
 *
 *  - the value stored per connection is `{origin, token, caCertificate}` as JSON;
 *  - an entry id is a UUID-shaped string, so a corrupted key cannot address a foreign slot;
 *  - a stored origin or token that no longer validates is reported instead of being used,
 *    so a tampered record forces a re-pair instead of sending a wrong request;
 *  - nothing here falls back to plain text: if [CodexSecretStorage] cannot write because the
 *    keystore is unavailable, the error propagates to the caller.
 *
 * The token never leaves this object except into [CodexConnection] for [CodexTransport].
 */
class CodexCredentialStore(
    private val storage: CodexSecretStorage,
    private val now: () -> Long = { System.currentTimeMillis() },
) {

    private val json = Json { ignoreUnknownKeys = true }

    /** Lists saved connections, newest first, without exposing tokens. */
    fun list(): List<CodexSavedConnection> = storage.readAll().mapNotNull { (id, value) ->
        if (!ID.matches(id)) return@mapNotNull null
        val record = decode(value) ?: return@mapNotNull null
        CodexSavedConnection(
            id = id,
            name = record.name,
            origin = record.connection.origin,
            updatedAt = record.updatedAt,
            hasCustomCa = record.connection.caCertificate.isNotEmpty(),
        )
    }.sortedByDescending { it.updatedAt }

    /**
     * Resolves a connection for use.
     *
     * @param id the saved entry, or empty for the most recent one.
     * @return the connection, or `null` when nothing is saved.
     * @throws CodexValidationException when the stored record is present but unusable.
     */
    fun load(id: String = ""): CodexConnection? {
        val resolved = if (id.isEmpty()) list().firstOrNull()?.id ?: return null else validateId(id)
        val value = storage.readAll()[resolved] ?: return null
        val record = decode(value) ?: throw CodexValidationException("已保存的 Codex 连接信息无效，请删除后重新配对")
        val origin = runCatching { CodexTypes.normalizeOrigin(record.connection.origin) }.getOrNull()
        if (origin != record.connection.origin || !CodexTypes.isValidToken(record.connection.token)) {
            throw CodexValidationException("已保存的 Codex 连接信息无效，请删除后重新配对")
        }
        val ca = record.connection.caCertificate
        if (ca.isNotEmpty() && !CodexTypes.isValidCa(ca)) {
            throw CodexValidationException("已保存的配对证书无效，请删除后重新配对")
        }
        return record.connection
    }

    /**
     * Saves [connection], reusing the existing entry for the same origin.
     *
     * @return the saved metadata shown in the connection list.
     * @throws CodexValidationException when the origin, token or CA is malformed.
     */
    fun save(connection: CodexConnection, name: String = ""): CodexSavedConnection {
        val origin = CodexTypes.normalizeOrigin(connection.origin)
        if (!CodexTypes.isValidToken(connection.token)) throw CodexValidationException("桥接令牌格式无效")
        if (connection.caCertificate.isNotEmpty() && !CodexTypes.isValidCa(connection.caCertificate)) {
            throw CodexValidationException("配对证书格式无效")
        }
        val existing = list().firstOrNull { it.origin == origin }
        val id = existing?.id ?: java.util.UUID.randomUUID().toString()
        val displayName = name.trim().ifEmpty { existing?.name ?: CodexTypes.connectionLabel(origin) }.take(80)
        val record = Record(
            connection = connection.copy(origin = origin),
            name = displayName,
            updatedAt = now(),
        )
        storage.write(validateId(id), json.encodeToString(Record.serializer(), record))
        return CodexSavedConnection(id, displayName, origin, record.updatedAt, connection.caCertificate.isNotEmpty())
    }

    /** Removes a saved connection, or the most recent one when [id] is empty. */
    fun forget(id: String = "") {
        val resolved = if (id.isEmpty()) list().firstOrNull()?.id ?: return else id
        storage.remove(validateId(resolved))
    }

    private fun validateId(id: String): String {
        if (!ID.matches(id)) throw CodexValidationException("连接编号无效")
        return id
    }

    private fun decode(value: String): Record? =
        try {
            json.decodeFromString(Record.serializer(), value)
        } catch (e: Exception) {
            null
        }

    @kotlinx.serialization.Serializable
    private data class Record(
        val connection: CodexConnection,
        val name: String = "",
        val updatedAt: Long = 0,
    )

    private companion object {
        val ID = Regex("^[a-fA-F0-9-]{32,36}$")
    }
}
