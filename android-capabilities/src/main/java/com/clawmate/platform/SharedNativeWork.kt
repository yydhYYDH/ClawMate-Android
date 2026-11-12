package com.clawmate.platform

import android.content.Context
import com.clawmate.app.image.AndroidChatImagePreparer
import com.clawmate.app.image.PreparedChatImage
import com.clawmate.app.task.json.WorkflowEditValidator
import com.clawmate.app.task.json.WorkflowJsonParser
import com.clawmate.app.task.model.WorkflowDefinition
import com.clawmate.app.task.runner.RunStatus
import com.clawmate.app.task.runner.WorkflowRunnerImpl
import com.clawmate.app.task.runner.WorkflowRunResult
import com.clawmate.app.task.runner.WorkflowModelTool
import com.clawmate.platform.gui.GuiCollectionWorkflow
import kotlinx.coroutines.runBlocking

/** Blocking entry points for the bounded background bridge, never the UI thread. */
object SharedNativeWork {
    fun prepareImages(context: Context, uris: List<String>): List<PreparedChatImage> = runBlocking {
        require(uris.size in 1..4) { "每次最多发送4张图片" }
        uris.map { AndroidChatImagePreparer(context).prepare(it) }
    }

    fun runWorkflow(raw: String, modelTool: WorkflowModelTool? = null,
        guiWorkflow: (suspend (WorkflowDefinition) -> WorkflowRunResult)? = null,
    ): WorkflowRunResult = runBlocking {
        require(WorkflowEditValidator.error(raw) == null) { WorkflowEditValidator.error(raw) ?: "工作流无效" }
        val parsed = WorkflowJsonParser.parse(raw) as? WorkflowJsonParser.Result.Ok
            ?: error("无法解析工作流")
        if (GuiCollectionWorkflow.containsNode(parsed.definition.steps)) {
            return@runBlocking guiWorkflow?.invoke(parsed.definition)
                ?: WorkflowRunResult(RunStatus.BLOCKED, "无障碍采集需要单次确认，请在任务列表单独运行")
        }
        // The shared ArkTS host owns its workflow files and commits actual summaries.
        WorkflowRunnerImpl(modelTool = modelTool).run(parsed.definition)
    }
}
