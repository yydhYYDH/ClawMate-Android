package com.wangzan101.mika.core.assembly

import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.tool.ActionPass
import com.wangzan101.mika.core.tool.SubActions
import com.wangzan101.mika.core.tool.ToolDispatcher
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** ClawMate：一次不经过模型的手机采集。 */
data class MikaCollectedPageText(
    val text: String,
    val items: Int,
    val pages: Int,
    val stoppedBecause: String,
    val truncated: Boolean,
) {
    val ok: Boolean get() = items > 0 && stoppedBecause != "no_app_open" && stoppedBecause != "read_failed"
}

/**
 * ClawMate：用 Mika 自己的 `phone_collect` 读取当前页面及后续页。
 *
 * 与模型调用走同一套工具和同一个执行入口（[ToolDispatcher.execute]），只是这一步由本机直接发起，
 * 适合用户在任务页已经站在目标页面上、明确要求采集的场景。文本仍是不可信的外部内容，调用方负责展示与保存。
 * 子动作（读屏、滚动）经 [SubActions] 回到同一入口执行。
 */
suspend fun ToolDispatcher.collectPages(
    direction: String = "down",
    pages: Int = 3,
    maxItems: Int = 300,
): MikaCollectedPageText {
    val args = buildJsonObject {
        put("direction", direction)
        put("pages", pages)
        put("maxItems", maxItems)
    }
    val sub = SubActions { call -> execute(call, ActionPass()) }
    val result = execute(Part.ToolCall("clawmate-collect", "phone_collect", args.toString()), ActionPass(), sub = sub)
    val content = result.content
    val ok = content["ok"] == JsonPrimitive(true)
    val stopped = (content["stoppedBecause"] ?: content["error"]) as? JsonPrimitive
    return MikaCollectedPageText(
        text = (content["text"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
        items = (content["items"] as? JsonPrimitive)?.intOrNull ?: 0,
        pages = (content["pagesInspected"] as? JsonPrimitive)?.intOrNull ?: 0,
        stoppedBecause = stopped?.contentOrNull ?: if (ok) "page_limit" else "failed",
        truncated = (content["truncated"] as? JsonPrimitive)?.contentOrNull == "true",
    )
}
