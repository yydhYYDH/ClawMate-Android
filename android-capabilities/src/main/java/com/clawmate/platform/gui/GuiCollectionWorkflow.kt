package com.clawmate.platform.gui

import com.clawmate.app.task.model.WorkflowDefinition
import com.clawmate.app.task.model.WorkflowStep
import com.clawmate.app.task.runner.RunStatus
import com.clawmate.app.task.runner.StepResult
import com.clawmate.app.task.runner.WorkflowRunResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

internal data class GuiCollectionWorkflow(
    val open: WorkflowStep,
    val read: WorkflowStep,
    val preview: WorkflowStep,
    val target: String,
    val instructions: String,
    val pages: Int,
    val direction: CollectionDirection,
) {
    companion object {
        const val TYPE = "android_accessibility"
        const val OPEN = "collection_open_app"
        const val READ = "collection_read_pages"
        const val PREVIEW = "collection_preview"
        private val packagePattern = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")

        fun containsNode(steps: List<WorkflowStep>): Boolean = steps.any {
            it.type == TYPE || containsNode(it.steps) || containsNode(it.thenSteps) || containsNode(it.elseSteps)
        }

        fun validate(definition: WorkflowDefinition): GuiCollectionWorkflow {
            val steps = definition.steps
            require(steps.map { it.action } == listOf(OPEN, READ, PREVIEW) && steps.all { it.type == TYPE }) {
                "请依次连接：打开采集应用 → 读取页面 → 预览并保存采集"
            }
            val (open, read, preview) = steps
            val common = setOf("id", "type", "action", "name", "on_error")
            val extra = listOf(setOf("package_name", "task_description"), setOf("page_limit", "direction"), emptySet())
            steps.zip(extra).forEach { (step, fields) ->
                require(step.raw.keys.all { it in common || it in fields } &&
                    (step.onError == null || step.onError == "stop")) {
                    "第 ${step.id} 步含有不支持的参数或错误策略"
                }
            }
            val target = (open.raw["package_name"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
            require(target.length <= 180 && target.matches(packagePattern)) { "请在打开采集应用节点选择已安装的应用" }
            val instructions = open.taskDescription.orEmpty().trim()
            require(instructions.length <= 180 && instructions.none(Char::isISOControl)) { "页面说明须为 180 字以内的单行文字" }
            val pageField = read.raw["page_limit"]
            val pages = if (pageField == null) 1 else
                (pageField as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull ?: 0
            require(pages in 1..GUI_COLLECTION_PAGE_LIMIT) { "采集页数须为 1 至 $GUI_COLLECTION_PAGE_LIMIT 页" }
            val direction = when (read.raw["direction"]) {
                null, JsonPrimitive("down") -> CollectionDirection.DOWN
                JsonPrimitive("up") -> CollectionDirection.UP
                else -> throw IllegalArgumentException("请选择向下或向上翻页")
            }
            val contact = (definition.context["contact_name"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim().orEmpty()
            val scope = if (target == "com.tencent.mm" && contact.isNotEmpty()) {
                require(contact.length <= 60 && contact.none(Char::isISOControl)) { "请检查微信任务的联系人名称" }
                "$instructions\n请核对联系人：$contact".trimStart()
            } else instructions
            return GuiCollectionWorkflow(open, read, preview, target, scope, pages, direction)
        }
    }
}

internal class PageGuiRun(val plan: GuiCollectionWorkflow) {
    val result = CompletableDeferred<WorkflowRunResult>()
    private val finished = mutableListOf<StepResult>()
    private var current = plan.open
    val awaitingPage get() = current === plan.read

    fun advance(step: WorkflowStep, note: String) {
        finished += StepResult(current.id, current.type, RunStatus.SUCCESS, note)
        current = step
    }

    fun finish(status: RunStatus, message: String) {
        result.complete(WorkflowRunResult(status, message,
            finished + StepResult(current.id, current.type, status, message)))
    }
}
