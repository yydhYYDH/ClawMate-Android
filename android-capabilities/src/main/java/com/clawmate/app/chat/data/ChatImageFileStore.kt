package com.clawmate.app.chat.data

import com.clawmate.app.chat.cloud.ChatImageBounds
import com.clawmate.app.chat.cloud.validateChatImageDataUrl
import com.clawmate.app.image.PreparedChatImage
import java.io.File
import java.io.FileOutputStream
import java.util.Base64
import java.util.UUID

/** Only re-encoded, metadata-free images are retained; names never contain a source URI. */
class ChatImageFileStore(filesDir: File) {
    private val root = File(filesDir, "chat-images").canonicalFile

    fun file(name: String): File {
        require(Regex("[0-9a-f-]{36}\\.(jpg|png)").matches(name)) { "聊天图片引用无效" }
        val file = File(root, name).canonicalFile
        require(file.parentFile == root) { "聊天图片引用越界" }
        return file
    }

    /** Blocking disk operation; callers use the IO dispatcher. Failure removes this batch only. */
    fun save(images: List<PreparedChatImage>): List<String> {
        require(images.size in 1..ChatImageBounds.MAX_IMAGES) { "单次最多发送4张图片" }
        images.forEach { validateChatImageDataUrl(it.dataUrl) }
        check(root.isDirectory || root.mkdirs()) { "无法创建聊天图片目录" }
        val owned = mutableListOf<String>()
        try {
            for (image in images) {
                val extension = if (image.dataUrl.startsWith("data:image/png;")) "png" else "jpg"
                val name = "${UUID.randomUUID()}.$extension"
                owned += name
                val target = file(name)
                FileOutputStream(target).use { stream ->
                    stream.write(Base64.getDecoder().decode(image.dataUrl.substringAfter(',')))
                    stream.fd.sync()
                }
            }
            return owned
        } catch (e: Exception) {
            delete(owned)
            throw e
        }
    }

    fun delete(names: List<String>) {
        names.forEach { name -> runCatching { file(name).delete() } }
    }
}
