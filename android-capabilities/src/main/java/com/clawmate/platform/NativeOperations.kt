package com.clawmate.platform

import java.io.Closeable
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.Future

data class NativeOperation<T>(val state: String, val value: T? = null)

/** Bounded, cancellable native work. Poll consumes terminal results; late work is discarded. */
class NativeOperations<T>(private val limit: Int = 4) : Closeable {
    private class Entry<T>(var result: NativeOperation<T>, var future: Future<*>? = null, val created: Long = System.nanoTime())
    private val jobs = linkedMapOf<String, Entry<T>>()
    private val executor = Executors.newFixedThreadPool(2)
    private var closed = false

    @Synchronized fun start(work: () -> T): String {
        check(!closed) { "能力已关闭" }
        val expired = jobs.filterValues { System.nanoTime() - it.created > 60_000_000_000L }.keys.toList()
        expired.forEach { jobs.remove(it)?.future?.cancel(true) }
        check(jobs.size < limit) { "本地识别繁忙，请稍后重试或降低 OCR 并发数" }
        val id = UUID.randomUUID().toString()
        val entry = Entry<T>(NativeOperation("running"))
        jobs[id] = entry
        entry.future = executor.submit {
            val result = try { NativeOperation("done", work()) } catch (_: InterruptedException) {
                NativeOperation<T>("cancelled")
            } catch (_: Exception) { NativeOperation<T>("failed") }
            synchronized(this) {
                if (jobs[id] === entry && entry.result.state == "running") entry.result = result
            }
        }
        return id
    }

    @Synchronized fun poll(id: String): NativeOperation<T> {
        val entry = jobs[id] ?: return NativeOperation("missing")
        if (entry.result.state != "running") jobs.remove(id)
        return entry.result
    }

    @Synchronized fun cancel(id: String) {
        jobs.remove(id)?.future?.cancel(true)
    }

    @Synchronized override fun close() {
        closed = true
        jobs.values.forEach { it.future?.cancel(true) }
        jobs.clear()
        executor.shutdownNow()
    }
}
