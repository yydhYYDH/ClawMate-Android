package com.clawmate.platform

import android.content.Context
import com.clawmate.app.image.AndroidChatImagePreparer
import com.clawmate.app.image.PreparedChatImage
import com.clawmate.app.task.json.WorkflowEditValidator
import com.clawmate.app.task.json.WorkflowJsonParser
import com.clawmate.app.task.runner.WorkflowRunnerImpl
import com.clawmate.app.task.runner.WorkflowRunResult
import com.clawmate.app.task.runner.WorkflowModelTool
import kotlinx.coroutines.runBlocking

/** Blocking entry points for the bounded background bridge, never the UI thread. */
object SharedNativeWork {
    fun prepareImages(context: Context, uris: List<String>): List<PreparedChatImage> = runBlocking {
        require(uris.size in 1..4) { "每次最多发送4张图片" }
        uris.map { AndroidChatImagePreparer(context).prepare(it) }
    }

    fun runWorkflow(raw: String, modelTool: WorkflowModelTool? = null): WorkflowRunResult = runBlocking {
        require(WorkflowEditValidator.error(raw) == null) { WorkflowEditValidator.error(raw) ?: "工作流无效" }
        val parsed = WorkflowJsonParser.parse(raw) as? WorkflowJsonParser.Result.Ok
            ?: error("无法解析工作流")
        // The shared ArkTS host owns its files and commits actual summaries.
        // No second native data writer, GUI backend, or implicit cloud call.
        WorkflowRunnerImpl(modelTool = modelTool).run(parsed.definition)
    }
}
