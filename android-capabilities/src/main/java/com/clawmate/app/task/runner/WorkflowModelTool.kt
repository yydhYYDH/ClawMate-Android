package com.clawmate.app.task.runner

import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.JsonObject
import java.io.Closeable
import java.util.UUID

/** Host owns transport/consent; the reusable runner owns ordering and budgets. */
data class WorkflowModelRequest(val id: String, val mode: String, val question: String, val image: String, val schema: JsonObject?)
fun interface WorkflowModelTool { suspend fun evaluate(request: WorkflowModelRequest): String }

/** One outstanding request per run. Replies cannot cross runs or be replayed. */
class WorkflowModelMailbox : WorkflowModelTool, Closeable {
    private data class Pending(val request: WorkflowModelRequest, val reply: CompletableDeferred<String>)
    private var pending: Pending? = null
    private var closed = false
    @Synchronized fun peek(): WorkflowModelRequest? = pending?.takeUnless { it.reply.isCompleted }?.request
    @Synchronized fun reply(id: String, response: String, error: String): Boolean {
        val current = pending ?: return false
        if (closed || current.request.id != id || current.reply.isCompleted) return false
        require(response.length <= 16000 && error.length <= 500) { "模型结果超出限制" }
        return if (error.isNotEmpty()) current.reply.completeExceptionally(IllegalStateException(error))
            else current.reply.complete(response)
    }
    override suspend fun evaluate(request: WorkflowModelRequest): String {
        val current = synchronized(this) {
            check(!closed && pending == null) { "模型请求已取消或仍在执行" }
            Pending(request.copy(id = UUID.randomUUID().toString()), CompletableDeferred()).also { pending = it }
        }
        try { return current.reply.await() }
        finally { synchronized(this) { if (pending === current) pending = null } }
    }
    @Synchronized override fun close() {
        closed = true
        pending?.reply?.cancel()
        pending = null
    }
}
