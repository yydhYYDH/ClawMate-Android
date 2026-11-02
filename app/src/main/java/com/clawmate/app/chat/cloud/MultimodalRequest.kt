package com.clawmate.app.chat.cloud

import java.util.Base64
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

/** Plain text/tool requests keep their original serialization exactly. Images are explicit local data only. */
internal fun encodeChatCompletionsRequest(request: ChatCompletionsRequest, json: Json = WireJson.json): String {
    if (request.messages.none { it.imageUrls.isNotEmpty() }) return json.encodeToString(ChatCompletionsRequest.serializer(), request)
    require(request.messages.sumOf { it.imageUrls.size } <= ChatImageBounds.MAX_IMAGES) { "单次请求最多发送4张图片" }
    for (message in request.messages.filter { it.imageUrls.isNotEmpty() }) {
        require(message.role == "user") { "仅用户消息可以附加图片" }
        message.imageUrls.forEach(::validateChatImageDataUrl)
    }
    val original = json.encodeToJsonElement(ChatCompletionsRequest.serializer(), request).jsonObject
    val messages = original["messages"]!!.jsonArray.mapIndexed { i, value ->
        val message = request.messages[i]
        if (message.imageUrls.isEmpty()) value else JsonObject(value.jsonObject.toMutableMap().apply {
            put("content", buildJsonArray {
                message.content?.takeIf { it.isNotBlank() }?.let { text -> add(buildJsonObject { put("type", "text"); put("text", text) }) }
                message.imageUrls.forEach { url -> add(buildJsonObject {
                    put("type", "image_url"); putJsonObject("image_url") { put("url", url); put("detail", "auto") }
                }) }
            })
        })
    }
    return JsonObject(original.toMutableMap().apply { put("messages", JsonArray(messages)) }).toString()
}
