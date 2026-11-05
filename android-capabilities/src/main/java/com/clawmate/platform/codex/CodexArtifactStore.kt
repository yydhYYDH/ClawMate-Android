package com.clawmate.platform.codex

import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.delay

/**
 * Downloads a delivered file into the app sandbox and verifies it.
 *
 * Counterpart of `CodexArtifactStore.ets`. Each connection gets its own directory keyed by
 * a digest of its origin, so two computers cannot overwrite each other's files.
 *
 * Integrity is checked twice: every chunk must report the expected id, offset and total,
 * and the finished file must hash to the SHA-256 the PC advertised. A mismatched file is
 * deleted rather than kept — a corrupt delivery must not look like a successful one.
 */
class CodexArtifactStore(
    private val root: File,
) {

    /**
     * Receives [artifact] onto local storage.
     *
     * @param onProgress invoked with (receivedBytes, totalBytes).
     * @return the absolute path of the verified file.
     * @throws CodexValidationException when the artifact metadata or a chunk is invalid.
     * @throws IOException when the file cannot be written.
     */
    suspend fun receive(
        taskId: String,
        artifact: CodexArtifact,
        transport: CodexTransport,
        onProgress: (received: Long, total: Long) -> Unit = { _, _ -> },
    ): String {
        CodexTypes.validateArtifact(artifact)
        if (!CodexTypes.isValidTaskId(taskId)) throw CodexValidationException("会话编号无效")
        val directory = File(root, sanitize(artifact.id)).apply {
            if (!isDirectory && !mkdirs()) throw IOException("无法创建交付文件目录")
        }
        val destination = File(directory, safeName(artifact.name))

        // A previously completed download that still matches is reused, not refetched.
        if (destination.isFile && destination.length() == artifact.size &&
            sha256(destination) == artifact.sha256
        ) {
            onProgress(artifact.size, artifact.size)
            return destination.absolutePath
        }

        val partial = File(directory, "download-${System.nanoTime()}.part")
        val digest = MessageDigest.getInstance("SHA-256")
        var offset = 0L
        try {
            partial.outputStream().use { out ->
                while (offset < artifact.size) {
                    val chunk = fetchChunk(taskId, artifact, offset, transport)
                    val bytes = decodeChunk(chunk, artifact, offset)
                    out.write(bytes)
                    digest.update(bytes)
                    offset += bytes.size
                    onProgress(offset, artifact.size)
                    // Yield so cancellation and UI updates stay responsive during long transfers.
                    delay(0)
                }
            }
            if (offset != artifact.size) throw CodexValidationException("交付文件大小与电脑声明不一致")
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (actual != artifact.sha256) {
                throw CodexValidationException("交付文件校验失败（SHA256 不匹配），已丢弃")
            }
            if (destination.exists() && !destination.delete()) throw IOException("无法替换旧的交付文件")
            if (!partial.renameTo(destination)) throw IOException("无法保存交付文件")
            return destination.absolutePath
        } finally {
            if (partial.exists()) partial.delete()
        }
    }

    private suspend fun fetchChunk(
        taskId: String,
        artifact: CodexArtifact,
        offset: Long,
        transport: CodexTransport,
    ): CodexArtifactChunk {
        val chunkBytes = transport.artifactChunkBytes()
        val length = if (chunkBytes < DEFAULT_CHUNK) "&length=$chunkBytes" else ""
        // One chunk is up to 1 MiB of base64, and the whole exchange must still fit inside a
        // single platform bridge call, so this is bounded rather than left at the default.
        val envelope = transport.get(
            "/v1/tasks/$taskId/artifacts/${artifact.id}?offset=$offset$length",
            timeoutMs = CHUNK_TIMEOUT_MS,
        )
        return envelope.artifactChunk
            ?: throw CodexValidationException("交付文件分块缺失，请同步后重试")
    }

    private fun decodeChunk(chunk: CodexArtifactChunk, artifact: CodexArtifact, offset: Long): ByteArray {
        val valid = chunk.id == artifact.id &&
            chunk.offset == offset &&
            chunk.total == artifact.size &&
            chunk.sha256 == artifact.sha256 &&
            chunk.base64.length <= MAX_CHUNK_BASE64 &&
            chunk.base64.length % 4 == 0 &&
            BASE64.matches(chunk.base64)
        if (!valid) throw CodexValidationException("交付文件分块校验失败")
        val bytes = try {
            java.util.Base64.getDecoder().decode(chunk.base64)
        } catch (e: IllegalArgumentException) {
            throw CodexValidationException("交付文件分块解码失败")
        }
        if (bytes.isEmpty() || offset + bytes.size > artifact.size) {
            throw CodexValidationException("交付文件分块校验失败")
        }
        return bytes
    }

    private fun safeName(name: String): String =
        if (name.toByteArray(Charsets.UTF_8).size <= MAX_NAME_BYTES) name
        else name.substring(0, 60) + name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }

    private fun sanitize(value: String): String = value.replace(Regex("[^A-Za-z0-9_-]"), "_")

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val DEFAULT_CHUNK: Long = 1024 * 1024
    /** Deadline for one chunk exchange, kept under the platform bridge call ceiling. */
    private const val CHUNK_TIMEOUT_MS: Long = 45_000
        private const val MAX_CHUNK_BASE64: Int = 1_400_000
        private const val MAX_NAME_BYTES: Int = 240
        private val BASE64 = Regex("^[A-Za-z0-9+/]*={0,2}$")

        /** Creates the per-connection root: `<filesDir>/codex/sessions/<originDigest>/`. */
        fun forOrigin(filesDir: File, origin: String): CodexArtifactStore {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(origin.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            return CodexArtifactStore(File(filesDir, "codex/sessions/$digest"))
        }
    }
}
