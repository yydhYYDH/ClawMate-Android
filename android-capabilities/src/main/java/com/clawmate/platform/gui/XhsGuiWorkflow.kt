package com.clawmate.platform.gui

import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.RequiresApi
import com.clawmate.app.task.model.WorkflowStep
import com.clawmate.app.task.runner.RunStatus
import com.clawmate.app.task.runner.StepResult
import com.clawmate.app.task.runner.WorkflowModelRequest
import com.clawmate.app.task.runner.WorkflowModelTool
import com.clawmate.app.task.runner.WorkflowRunResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

internal object XhsGuiWorkflow {
    const val PACKAGE = "com.xingin.xhs"
    const val LABEL = "小红书公开笔记"
    const val ACTION = "xhs_public_notes"
    const val MODE = "xhs_public_choice"
    const val MAX_PAGES = 3
    const val MAX_ENTRIES = 15
    val actions = mapOf(
        "read_visible" to "采集当前页公开笔记的可读摘录",
        "scroll_forward" to "向下翻一页以查看更多公开笔记",
        "finish" to "结束采集并打开本机预览",
    )

    fun validate(steps: List<WorkflowStep>): Pair<String, String> {
        val step = requireNotNull(steps.singleOrNull()) { "小红书实例需单独使用一个 AI 采集节点" }
        require(step.type == WeiboGuiWorkflow.TYPE && step.action == ACTION &&
            step.raw.keys.all { it in setOf("id", "type", "action", "name", "on_error", "task_description", "text") } &&
            (step.onError == null || step.onError == "stop")) { "小红书 AI 采集节点含不支持的配置" }
        fun field(key: String, max: Int): String {
            val raw = requireNotNull((step.raw[key] as? JsonPrimitive)?.takeIf { it.isString }) {
                "请填写小红书搜索关键词和采集提示词"
            }
            return raw.content.trim().also {
                require(it.length in 1..max && it.none(Char::isISOControl)) { "搜索关键词或采集提示词为空、过长或格式不支持" }
            }
        }
        return field("text", 40) to field("task_description", 300)
    }

    fun launch(keyword: String): Intent = Intent(Intent.ACTION_VIEW, Uri.Builder().scheme("xhsdiscover")
        .authority("search").appendPath("result").appendQueryParameter("keyword", keyword).build()).setPackage(PACKAGE)

    fun request(prompt: String, allowed: Set<String>, pages: Int, collected: Int): WorkflowModelRequest {
        val body = buildJsonObject {
            put("task", prompt)
            put("page", "小红书公开搜索结果")
            put("pages_read", pages)
            put("entries_collected", collected)
            putJsonArray("choices") {
                allowed.forEach { id -> add(buildJsonObject { put("id", id); put("label", actions.getValue(id)) }) }
            }
        }
        return WorkflowModelRequest("", MODE, body.toString(), "", null)
    }

    fun choice(raw: String, allowed: Set<String>): String {
        val value = Json.parseToJsonElement(raw) as? JsonObject
        val action = value?.get("action") as? JsonPrimitive
        require(value?.keys == setOf("action") && action?.isString == true && action.content in allowed) {
            "模型未选择当前页面允许的动作，采集已停止"
        }
        return requireNotNull(action).content
    }

    @RequiresApi(34)
    fun page(root: AccessibilityNodeInfo, area: Rect, keyword: String): XhsPublicPage? {
        fun readable(node: AccessibilityNodeInfo): Boolean = node.packageName?.toString() == PACKAGE &&
            node.isVisibleToUser && !node.isEditable && !node.isPassword && !node.isAccessibilityDataSensitive &&
            Rect.intersects(area, node.weiboBounds())
        val toolbarEnd = area.top + area.height() / 8
        fun inToolbar(node: AccessibilityNodeInfo): Boolean = readable(node) && node.weiboBounds().bottom <= toolbarEnd
        val query = root.findAccessibilityNodeInfosByText(keyword)
            .singleOrNull { it.text?.toString() == keyword && inToolbar(it) } ?: return null
        val search = root.findAccessibilityNodeInfosByText("搜索")
            .singleOrNull { it.text?.toString() == "搜索" && it.isClickable && inToolbar(it) } ?: return null
        if (search.weiboBounds().top != query.weiboBounds().top) return null

        // Search cards occupy one column of the two-column results grid.
        // The compact tree exposes the note's visible title and excerpt as one TextView.
        // Read that first text node and skip the author, date and engagement footer.
        fun descendants(node: AccessibilityNodeInfo, depth: Int): Sequence<AccessibilityNodeInfo> = sequence {
            if (depth == 0 || !readable(node)) return@sequence
            yield(node)
            for (index in 0 until node.childCount.coerceAtMost(30)) {
                node.getChild(index)?.let { yieldAll(descendants(it, depth - 1)) }
            }
        }
        val list = descendants(root, 18).take(260)
            .firstOrNull { it.className?.toString() == "androidx.recyclerview.widget.RecyclerView" &&
                it.weiboBounds().width() >= area.width() * 9 / 10 } ?: return null
        val cards = (0 until list.childCount.coerceAtMost(24)).mapNotNull(list::getChild).filter { card ->
            val bounds = card.weiboBounds()
            readable(card) && bounds.top >= search.weiboBounds().bottom &&
                bounds.width() in (area.width() / 3)..(area.width() * 3 / 5)
        }.sortedWith(compareBy({ it.weiboBounds().top }, { it.weiboBounds().left }))
        val excerpts = cards.mapNotNull { card ->
            val nodes = descendants(card, 10).take(70).toList()
            if (nodes.none { it.isClickable }) return@mapNotNull null
            nodes.firstOrNull { it.className?.toString() == "android.widget.TextView" && !it.text.isNullOrBlank() }
                ?.text?.toString()?.trim()?.takeIf { it.length in 2..140 }
        }.distinct()
        return XhsPublicPage(list, excerpts)
    }
}

internal data class XhsPublicPage(val list: AccessibilityNodeInfo, val excerpts: List<String>)

internal class XhsGuiRun(
    val step: WorkflowStep, val keyword: String, val prompt: String, val model: WorkflowModelTool,
) {
    val result = CompletableDeferred<WorkflowRunResult>()

    fun finish(status: RunStatus, message: String) {
        result.complete(WorkflowRunResult(status, message, listOf(StepResult(step.id, step.type, status, message))))
    }
}
