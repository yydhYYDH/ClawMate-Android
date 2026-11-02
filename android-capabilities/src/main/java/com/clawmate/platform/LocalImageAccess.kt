package com.clawmate.platform

import java.io.File
import java.net.URI
import java.security.MessageDigest

/** Image inputs cross a UI/runtime boundary; remote URLs and private non-image stores are refused. */
object LocalImageAccess {
    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    /** Bounded immutable snapshot check; never follows a content grant or remote URI. */
    fun digest(value: String, privateRoots: List<File>, maxBytes: Int = 1_048_576): String {
        require(maxBytes in 1..4_194_304) { "图片校验大小限制无效" }
        val uri = URI(validate(value, privateRoots))
        require(uri.scheme == "file") { "图片校验只接受应用私有副本" }
        val file = File(uri)
        require(file.length() in 1..maxBytes.toLong()) { "图片副本为空或过大，请重新选择" }
        val hash = MessageDigest.getInstance("SHA-256")
        var total = 0
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                if (Thread.currentThread().isInterrupted) throw InterruptedException("图片校验已取消")
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                require(total <= maxBytes) { "图片副本超出校验上限" }
                hash.update(buffer, 0, read)
            }
        }
        return hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    fun validate(value: String, privateRoots: List<File>): String {
        require(value.length in 1..8192) { "图片 URI 无效" }
        val uri = runCatching { URI(value) }.getOrElse { throw IllegalArgumentException("图片 URI 无效") }
        if (uri.scheme == "content") {
            require(!uri.authority.isNullOrBlank() && uri.userInfo == null) { "内容 URI 无效" }
            return value // ContentResolver still enforces the system's actual read grant.
        }
        require(uri.scheme == "file" && uri.authority.isNullOrEmpty() && uri.query == null && uri.fragment == null) {
            "本地识别只接受系统选图 URI 或应用私有图片"
        }
        val file = runCatching { File(uri).canonicalFile }.getOrElse { throw IllegalArgumentException("图片路径无效") }
        require(privateRoots.any { file.toPath().startsWith(it.canonicalFile.toPath()) && file != it.canonicalFile }) {
            "图片不在应用可用目录中，请重新选择"
        }
        return file.toURI().toString()
    }
}
