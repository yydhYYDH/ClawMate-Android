package com.clawmate.platform.mika

import com.wangzan101.mika.core.model.Capabilities
import com.wangzan101.mika.core.model.OpenAiCompatModel
import com.wangzan101.mika.core.vision.VisionService
import okhttp3.OkHttpClient

/**
 * 看图模型配置（用户在「云模型」里填的 Decider 视觉模型）。
 *
 * 手机任务有它时，`phone_describe` / `phone_find_visually` 才可用；没有配置就保持隐藏，
 * 由界面提示先配置，而不是让主模型去猜屏上的东西。
 */
data class MikaVisionConfig(
    val baseUrl: String,
    val modelName: String,
    val key: String,
) {
    companion object {
        /** 地址必须能按 Mika 的规则规范化；模型名 1–180 字；Key 可为空但不得超长。 */
        fun parse(url: String, model: String, key: String): MikaVisionConfig? {
            val name = model.trim()
            if (name.isEmpty() || name.length > 180 || key.length > 4096) return null
            val base = MikaEndpoints.parse(url)?.second ?: return null
            return MikaVisionConfig(base, name, key.trim())
        }
    }
}

/** 用看图模型配置造一个 Mika 的 [VisionService]；图片处理走本机的 [AndroidImageOps]。 */
internal fun mikaVisionService(config: MikaVisionConfig, http: OkHttpClient): VisionService {
    val model = OpenAiCompatModel(
        config.baseUrl,
        config.key.ifBlank { null },
        config.modelName,
        Capabilities(toolCalls = false, vision = true, contextTokens = 16_000, reasoning = false),
        http,
    )
    return VisionService(model, AndroidImageOps)
}
