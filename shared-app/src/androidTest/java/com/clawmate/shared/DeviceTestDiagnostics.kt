package com.clawmate.shared

import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

/** Local failure evidence. Never dumps preferences, message bodies or model credentials. */
internal object DeviceTestDiagnostics {
    fun capture(label: String, failure: Throwable) {
        require(label.matches(Regex("[a-z-]+")))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        runCatching {
            val output = File(instrumentation.targetContext.cacheDir, "migration-diagnostics-$label.txt")
            output.writeText(buildString {
                appendLine("elapsedRealtimeMs=${android.os.SystemClock.elapsedRealtime()}")
                appendLine("failureType=${failure.javaClass.name}")
                failure.stackTrace.forEach { appendLine("  $it") }
                failure.suppressed.forEach { suppressed ->
                    appendLine("suppressedType=${suppressed.javaClass.name}")
                    suppressed.stackTrace.forEach { appendLine("  $it") }
                }
                for ((thread, frames) in Thread.getAllStackTraces().entries.sortedBy { it.key.name }) {
                    appendLine("thread=${thread.name} state=${thread.state}")
                    frames.forEach { appendLine("  $it") }
                }
            })
            instrumentation.sendStatus(2, Bundle().apply { putString("failureThreadDump", output.name) })
        }.onFailure {
            val errorType = it.javaClass.simpleName
            runCatching {
                instrumentation.sendStatus(2, Bundle().apply { putString("failureThreadDumpError", errorType) })
            }
        }
    }
}
