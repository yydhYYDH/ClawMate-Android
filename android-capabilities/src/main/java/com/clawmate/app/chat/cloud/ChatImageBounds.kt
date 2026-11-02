package com.clawmate.app.chat.cloud

import java.util.Base64

object ChatImageBounds {
    const val MAX_IMAGES = 4
    const val MAX_IMAGE_BYTES = 1024 * 1024
    const val MAX_DIMENSION = 1280
}

fun validateChatImageDataUrl(value: String) {
    require(value.length <= (ChatImageBounds.MAX_IMAGE_BYTES + 2) / 3 * 4 + 64) { "图片编码超出大小限制" }
    val prefix = listOf("data:image/jpeg;base64,", "data:image/png;base64,").firstOrNull(value::startsWith)
        ?: throw IllegalArgumentException("图片必须是本机准备的 JPEG/PNG 数据，不能发送远程或本地路径")
    val encoded = value.substring(prefix.length)
    require(encoded.isNotEmpty() && encoded.length % 4 == 0 && Regex("[A-Za-z0-9+/]+={0,2}").matches(encoded)) { "图片编码无效" }
    val bytes = try { Base64.getDecoder().decode(encoded) } catch (_: IllegalArgumentException) { throw IllegalArgumentException("图片编码无效") }
    require(bytes.size in 1..ChatImageBounds.MAX_IMAGE_BYTES) { "图片数据超出大小限制" }
}
