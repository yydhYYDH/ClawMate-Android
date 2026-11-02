package com.clawmate.app.datamanager

import java.io.File

/**
 * Explicit app-private write API for daily-log documents — the one place that
 * documents how an external workflow importer writes daily-log files so the
 * [DailyLogEventSource] tree can later be consumed by the Home pipeline.
 *
 * Phase-29 rule #4: daily-log files are **never auto-created** by the
 * recommendation pipeline. Nothing in [DailyLogEventSource] or
 * [DailyLogPlannerEventMapper] creates a directory or file; importing is the
 * workflow/import layer's job. [DailyLogWorkspace] is that import contract:
 *
 *   val ws = DailyLogWorkspace(appPrivFileDir)
 *   ws.writeDocument(
 *       day = "2026-08-01",
 *       fileName = "wechat.logs.md",
 *       entries = listOf("1. ...", "2. ..."),
 *       metadata = mapOf("workflow" to "wechat"),
 *   )
 *
 * The write is bounded and follows the exact layout the reader expects:
 *   <base>/workflows/daily-log/YYYY-MM-DD/<fileName>.md
 * Each document begins with the optional Harmony metadata header followed by
 * blank-line separated numbered entries.
 *
 * This is a pure `java.io.File` API (no Android deps), so unit tests can use a
 * temp dir to model both writing and the subsequent read path.
 */
object DailyLogWorkspace {

    /**
     * Append/replace one daily-log document. Never used by the recommendation
     * pipeline itself — only by external workflow/import code (and tests).
     *
     * @return the written [File], or null when the request is invalid (bad day,
     *   bad file name, over the entry cap, or the resulting text would exceed
     *   the document bound).
     */
    fun writeDocument(
        baseDir: File,
        day: String,
        fileName: String,
        entries: List<String>,
        metadata: Map<String, String> = emptyMap(),
    ): File? {
        val dayDir = File(File(baseDir, DAILY_LOG_ROOT_RELATIVE), day)
        if (!DailyLogPaths.isValidDayDirectory(day)) return null
        if (!fileName.endsWith(".md") || fileName.length > DailyLogBounds.MAX_FILE_NAME_LENGTH) return null
        if (entries.isEmpty() || entries.size > DailyLogBounds.MAX_ENTRIES_PER_DAY) return null

        val body = StringBuilder()
        if (metadata.isNotEmpty()) {
            body.append("<!-- DAILY_LOG_METADATA\n")
            body.append("{")
            val items = metadata.entries
                .filter { it.value.isNotBlank() }
                .take(DailyLogBounds.MAX_FILE_NAME_LENGTH)
                .joinToString(",") { (k, v) ->
                    "\"${escape(k)}\":\"${escape(v)}\""
                }
            body.append(items)
            body.append("}\n-->\n")
        }
        entries.forEachIndexed { idx, text ->
            if (text.isNotBlank()) {
                body.append("${idx + 1}. ").append(text.trim()).append("\n\n")
            }
        }
        val content = body.toString()
        if (content.isBlank()) return null
        if (content.length > DailyLogBounds.MAX_DOCUMENT_CHARACTERS) return null

        return runCatching {
            dayDir.mkdirs()
            val file = File(dayDir, fileName)
            file.writeText(content)
            file
        }.getOrNull()
    }

    /**
     * Append one real numbered entry to an existing (or new) daily-log document,
     * mirroring the Harmony `WorkflowRunner.appendDailySummaryLog` semantics that
     * [`DailyLogParser`] already reads: a bounded Harmony `workflow_metadata` +
     * `latest_entry_index` header followed by blank-line separated `N. text`
     * numbered entries (`N` keeps climbing so existing stable source refs never
     * shift).
     *
     * This is the single-writer entry point used by the Android workflow runner
     * so the Home「generation」pipeline gets real input. It never fabricates text:
     * the caller supplies the real [entryText]; blanks are rejected and every
     * dimension is bounded (entry chars / file name / document chars / entries
     * per day).
     *
     * Repeat-runs into the same [day]/[fileName] **append** (the previous content
     * and its stable numbered indices are preserved); only the new entry and the
     * [latest_entry_index] in the header grow. The write is atomic (temp-file +
     * rename) and bounded, so a second run can never renumber or lose the first.
     *
     * @return the written file, or null when the request is invalid / would exceed
     *   a bound (over an explicit return-and-stop, never a throw).
     */
    fun appendEntry(
        baseDir: File,
        day: String,
        fileName: String,
        entryText: String,
        workflowMetadata: Map<String, String> = emptyMap(),
    ): File? {
        val safeDay = day.trim()
        if (!DailyLogPaths.isValidDayDirectory(safeDay)) return null
        if (!fileName.endsWith(".md") || fileName.length > DailyLogBounds.MAX_FILE_NAME_LENGTH) return null
        val text = entryText.trim().take(DailyLogBounds.MAX_ENTRY_CHARACTERS)
        if (text.isEmpty()) return null

        val dayDir = File(File(baseDir, DAILY_LOG_ROOT_RELATIVE), safeDay)
        val target = File(dayDir, fileName)
        val existingBody = if (target.isFile) stripMetadataHeader(readExisting(target)) else ""

        val next = lastEntryNumber(existingBody) + 1
        if (next > DailyLogBounds.MAX_ENTRIES_PER_DAY) return null

        val newBody = if (existingBody.isBlank()) {
            "$next. $text\n"
        } else {
            existingBody.trimEnd('\n') + "\n\n$next. $text\n"
        }
        val metadata = buildMetadataHeader(workflowMetadata, next)
        val content = "$metadata\n$newBody"
        if (content.length > DailyLogBounds.MAX_DOCUMENT_CHARACTERS) return null

        return runCatching {
            dayDir.mkdirs()
            val tmp = File(dayDir, "$fileName.tmp${System.nanoTime()}")
            tmp.writeText(content)
            if (!tmp.renameTo(target)) {
                tmp.delete()
                target.parentFile?.mkdirs()
                target.writeText(content)
            }
            target
        }.getOrNull()
    }

    /**
     * The Harmony `<!-- DAILY_LOG_METADATA ... -->` block carrying the flat
     * [workflow_metadata] primitives (name/description/scene) plus
     * [latestEntryIndex]. JSON is rendered with 2-space indentation, exactly what
     * [DailyLogMetadata] (and Harmony `DataManagerUtils`) parse.
     */
    private fun buildMetadataHeader(
        workflowMetadata: Map<String, String>,
        latestEntryIndex: Int,
    ): String {
        val meta = workflowMetadata.entries
            .filter { it.key.isNotBlank() && it.value.isNotBlank() }
            .take(DailyLogBounds.MAX_FILE_NAME_LENGTH)
            .joinToString(",\n") { (k, v) ->
                "    \"${escape(k)}\": \"${escape(v.take(DailyLogBounds.MAX_ENTRY_CHARACTERS))}\""
            }
        return "<!-- DAILY_LOG_METADATA\n{\n" +
            "  \"workflow_metadata\": {\n$meta\n  },\n" +
            "  \"latest_entry_index\": $latestEntryIndex\n}\n-->"
    }

    /** The entry index marker as the Harmony `lastEntryNumber` would scan it. */
    internal fun lastEntryNumber(body: String): Int {
        var last = 0
        body.split('\n').forEach { line ->
            val dot = line.indexOf(". ")
            if (dot > 0) {
                val value = line.substring(0, dot).trim().toIntOrNull()
                if (value != null && value > last) last = value
            }
        }
        return last
    }

    /** Strip the DAILY_LOG_METADATA header, returning the raw numbered body. */
    private fun stripMetadataHeader(content: String): String {
        val idx = content.indexOf("-->")
        return if (idx >= 0) content.substring(idx + 3).trimStart() else content
    }

    private fun readExisting(file: File): String = try {
        if (file.length() > DailyLogBounds.MAX_DOCUMENT_CHARACTERS.toLong() * 4L) "" else file.readText()
    } catch (t: Throwable) {
        ""
    }

    private fun escape(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"")

    /**
     * The relative path of the daily-log root, for documentation/tests.
     * Mirrors `workflows/daily-log` under the app-private base dir.
     */
    const val DAILY_LOG_ROOT_RELATIVE: String = "workflows/daily-log"
}
