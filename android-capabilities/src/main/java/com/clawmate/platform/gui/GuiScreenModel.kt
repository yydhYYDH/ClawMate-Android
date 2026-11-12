package com.clawmate.platform.gui

import android.graphics.Bitmap
import android.graphics.Rect
import com.clawmate.app.task.runner.WorkflowModelRequest
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.ByteArrayOutputStream
import java.util.Base64
import kotlin.math.roundToInt

internal object GuiScreenModel {
    const val MODE = "screen_collection"

    @Serializable
    data class Decision(val action: String, val lines: List<String>)

    fun request(prompt: String, image: String, page: Int, collected: Int, canContinue: Boolean): WorkflowModelRequest {
        val question = buildJsonObject {
            put("task", prompt)
            put("page", page)
            put("entries_collected", collected)
            putJsonArray("choices") {
                if (canContinue) add("continue")
                add("finish")
            }
        }
        return WorkflowModelRequest("", MODE, question.toString(), image, null)
    }

    fun decision(raw: String, canContinue: Boolean): Decision {
        require(raw.length <= 8000) { "模型回复超出采集限制" }
        val answer = try {
            Json.decodeFromString<Decision>(raw)
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("模型未返回有效的采集结果")
        }
        require(answer.action == "finish" || (canContinue && answer.action == "continue")) { "模型选择了不允许的采集动作" }
        require(answer.lines.size <= 15 && answer.lines.all { line ->
            line.length <= 200 && line.none(Char::isISOControl)
        }) { "模型返回的采集条目超出限制" }
        return answer.copy(lines = answer.lines.map(String::trim).filter(String::isNotEmpty).distinct())
    }

    fun encode(frame: Bitmap, area: Rect): String {
        val crop = Bitmap.createBitmap(frame, area.left, area.top, area.width(), area.height())
        try {
            val scale = minOf(1.0, 1280.0 / maxOf(crop.width, crop.height))
            val image = Bitmap.createScaledBitmap(crop, (crop.width * scale).roundToInt().coerceAtLeast(1),
                (crop.height * scale).roundToInt().coerceAtLeast(1), true)
            try {
                val output = ByteArrayOutputStream()
                check(image.compress(Bitmap.CompressFormat.JPEG, 80, output) && output.size() in 1..1_000_000) {
                    "目标窗口截图无法编码或超过大小限制"
                }
                return "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(output.toByteArray())
            } finally {
                if (image !== crop) image.recycle()
            }
        } finally {
            if (crop !== frame) crop.recycle()
        }
    }
}
