package com.clawmate.app.datamanager

/**
 * Pure, device-independent parser for Harmony daily-log documents stored under
 * the app-private `.../workflows/daily-log/YYYY-MM-DD/<any-markdown-file>` directory tree.
 *
 * This is a faithful subset port of Harmony `DataManagerUtils.parseDailyLogDocument`
 * / `parseNumberedEntries` / `sourceRef`, made strict and bounded per Phase 29
 * safety requirements. It has **zero Android dependencies** so it is fully
 * unit-testable on the JVM and never touches storage, network or the UI thread.
 *
 * Document format (mirrors `DAILY_LOG_METADATA_PATTERN` and the numbered-entry
 * grammar in the Harmony source):
 *
 *   <!-- DAILY_LOG_METADATA
 *   {"workflow_metadata":{"app":"wechat","workflow":"a"},"latest_entry_index":3}
 *   -->
 *   1. first entry text
 *
 *   2. second entry text
 *
 * The metadata header is optional; when present its JSON block is bounded (see
 * [DailyLogBounds]). Numbered entries are `N. <text>` blocks separated by blank
 * lines.
 *
 * Every file that cannot be parsed (malformed date, non-`.md` name, oversized,
 * no usable numbered entries) is **skipped** via an explicit [DailyLogParseError]
 * in the [DailyLogResult] — the parser never throws, so one bad file can never
 * fail the whole refresh and report a false success. Errors are countable and
 * deliberately not content-bearing (no raw file text leaks into the error).
 */
object DailyLogParser {

    /** Strip of document metadata header + numbered entry markers. */
    private val DAILY_LOG_METADATA_PATTERN =
        Regex("^<!-- DAILY_LOG_METADATA\\n([\\s\\S]*?)\\n-->\\n*")

    /**
     * Numbered-entry scanner. An entry starts at the document start or after a
     * blank line: `(\d+)\. <text>`, body captured up to the next blank-line
     * followed by a numbered marker (or end of text). Mirrors the Harmony regex
     * so a real `a. text` line never matches a numbered entry.
     */
    private val NUMBERED_ENTRY_PATTERN =
        Regex("""(?:^|\n\s*\n)(\d+)\.[ \t]+([\s\S]*?)(?=\n\s*\n\d+\.[ \t]+|\z)""")
    /**
     * Parse one already-read daily-log document into typed, bounded entries.
     * The parser is storage-free: callers resolve the on-disk day/file.
     *
     * @param baseDirDay the `YYYY-MM-DD` day directory the file belongs to.
     * @param fileName the full file name (must end with `.md`).
     * @param content the raw file decoded as UTF-8.
     */
    fun parse(
        baseDirDay: String,
        fileName: String,
        content: String,
    ): DailyLogResult {
        val errors = mutableListOf<DailyLogParseError>()
        val day = baseDirDay.trim()

        // -- strict structural limits (never throw; skip via error) --
        if (!DailyLogPaths.isValidDayDirectory(day)) {
            errors.add(DailyLogParseError(DailyLogErrorKind.INVALID_DAY_DIRECTORY, day, fileName))
            return DailyLogResult(entries = emptyList(), metadata = emptyMap(), errors = errors, latestEntryIndex = 0)
        }
        if (!fileName.endsWith(".md") || fileName.length > DailyLogBounds.MAX_FILE_NAME_LENGTH) {
            errors.add(DailyLogParseError(DailyLogErrorKind.INVALID_FILE_NAME, day, fileName))
            return DailyLogResult(entries = emptyList(), metadata = emptyMap(), errors = errors, latestEntryIndex = 0)
        }
        if (content.length > DailyLogBounds.MAX_DOCUMENT_CHARACTERS) {
            errors.add(DailyLogParseError(DailyLogErrorKind.OVERSIZED_DOCUMENT, day, fileName))
            return DailyLogResult(entries = emptyList(), metadata = emptyMap(), errors = errors, latestEntryIndex = 0)
        }

        // -- optional Harmony metadata header (bounded) --
        var latestEntryIndex = 0
        val headerMatch = DAILY_LOG_METADATA_PATTERN.find(content)
        val metadata = if (headerMatch != null) {
            val headerText = headerMatch.groupValues[1]
            if (headerText.length > DailyLogBounds.MAX_METADATA_CHARACTERS) {
                errors.add(DailyLogParseError(DailyLogErrorKind.OVERSIZED_METADATA, day, fileName))
            }
            DailyLogMetadata.parse(headerText.take(DailyLogBounds.MAX_METADATA_CHARACTERS))
        } else {
            emptyMap()
        }
        val body = if (headerMatch != null) {
            content.substring(headerMatch.range.last + 1)
        } else {
            content
        }

        // -- numbered entries (bounded, individual oversize skipped, never throw) --
        val entries = mutableListOf<DailyLogEntry>()
        var entryCount = 0
        NUMBERED_ENTRY_PATTERN.findAll(body).forEach { m ->
            entryCount++
            if (entryCount > DailyLogBounds.MAX_ENTRIES_PER_DAY) {
                if (errors.none { it.kind == DailyLogErrorKind.TOO_MANY_ENTRIES }) {
                    errors.add(DailyLogParseError(DailyLogErrorKind.TOO_MANY_ENTRIES, day, fileName))
                }
                return@forEach
            }
            val index = m.groupValues[1].toIntOrNull() ?: 0
            val text = m.groupValues[2].trim()
            if (text.isEmpty()) {
                return@forEach
            }
            if (text.length > DailyLogBounds.MAX_ENTRY_CHARACTERS) {
                errors.add(DailyLogParseError(DailyLogErrorKind.OVERSIZED_ENTRY, day, fileName))
                return@forEach
            }
            if (index > latestEntryIndex) latestEntryIndex = index
            entries.add(
                DailyLogEntry(
                    sourceDate = day,
                    fileName = fileName,
                    entryIndex = index,
                    text = text,
                    workflowMetadata = metadata,
                    latestEntryIndex = latestEntryIndex,
                    sourceRef = sourceRef(day, fileName, index),
                ),
            )
        }

        return DailyLogResult(entries = entries, metadata = metadata, errors = errors, latestEntryIndex = latestEntryIndex)
    }

    /**
     * The stable source-ref string, ported verbatim from Harmony
     * `DataManagerUtils.sourceRef`: `sourceDate/fileName#entryIndex`.
     * Non-printable or oversized components are bounded defensively.
     */
    fun sourceRef(sourceDate: String, fileName: String, entryIndex: Int): String {
        val day = sourceDate.trim().substringBefore('/').take(DailyLogBounds.MAX_FILE_NAME_LENGTH)
        val name = fileName.trim().take(DailyLogBounds.MAX_FILE_NAME_LENGTH)
        return "$day/$name#$entryIndex"
    }
}

/**
 * Bounding limits for untrusted daily-log input (mirrors the Harmony-adjacent
 * safety posture; kept conservative so a hostile/unusual file is skipped).
 */
object DailyLogBounds {
    /** A day directory must be `YYYY-MM-DD`. */
    val DAY_PATTERN: Regex get() = Regex("""^\d{4}-\d{2}-\d{2}$""")

    /** Cap on a single file name (chars) — rejects absurd names. */
    const val MAX_FILE_NAME_LENGTH: Int = 120

    /** Cap on the whole decoded document (chars). */
    const val MAX_DOCUMENT_CHARACTERS: Int = 200_000

    /** Cap on the metadata JSON header content (chars). */
    const val MAX_METADATA_CHARACTERS: Int = 8_000

    /** Cap on the number of numbered entries admitted per file. */
    const val MAX_ENTRIES_PER_DAY: Int = 60

    /** Cap on a single entry's trimmed text (chars). */
    const val MAX_ENTRY_CHARACTERS: Int = 2_000
}

/**
 * Tolerant, bounded parser for the optional Harmony daily-log metadata header
 * (`<!-- DAILY_LOG_METADATA ... -->`). Returns a flat key→string map of the
 * `workflow_metadata` primitive values plus `latest_entry_index`. Nested or
 * untyped artefacts are dropped. Never throws — it can only return an empty map.
 */
object DailyLogMetadata {
    private val json = kotlinx.serialization.json.Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    /**
     * Parse a bounded metadata JSON block into a flat string map (only primitive
     * `workflow_metadata` fields + `latest_entry_index`). Malformed input yields
     * an empty map (honest no-metadata), never an exception.
     */
    fun parse(header: String): Map<String, String> = runCatching {
        val root = json.parseToJsonElement(header.trim()) as? kotlinx.serialization.json.JsonObject
            ?: return emptyMap()
        val out = HashMap<String, String>()
        root["latest_entry_index"]?.let { v ->
            val text = (v as? kotlinx.serialization.json.JsonPrimitive)?.content
                ?.trim()?.filter { it.isDigit() }
            if (!text.isNullOrEmpty() && text.length <= DailyLogBounds.MAX_FILE_NAME_LENGTH) {
                out["latest_entry_index"] = text
            }
        }
        val workflow = root["workflow_metadata"] as? kotlinx.serialization.json.JsonObject
        if (workflow != null) {
            workflow.entries.forEach { (key, value) ->
                val text = (value as? kotlinx.serialization.json.JsonPrimitive)?.content
                    ?.take(DailyLogBounds.MAX_ENTRY_CHARACTERS)
                    ?: ""
                if (text.isNotEmpty()) out[key] = text
            }
        }
        out
    }.getOrElse { emptyMap() }
}

/** One strictly-valid typed daily-log entry (mirrors Harmony `SourceEntry`). */
data class DailyLogEntry(
    /** `YYYY-MM-DD`. */
    val sourceDate: String,
    /** File name, e.g. `wechat.logs.md`. */
    val fileName: String,
    /** The `N` from the `N. text` line. */
    val entryIndex: Int,
    /** Bounded, real entry text. */
    val text: String,
    /** Flat bounded workflow metadata from the file header. */
    val workflowMetadata: Map<String, String>,
    /** The `latest_entry_index` from the file (header) or from the scanned entries. */
    val latestEntryIndex: Int,
    /** Stable source ref: `sourceDate/fileName#index`. */
    val sourceRef: String,
)

/** Result of parsing one daily-log file(s) attempt. */
data class DailyLogResult(
    val entries: List<DailyLogEntry>,
    val metadata: Map<String, String>,
    val errors: List<DailyLogParseError>,
    val latestEntryIndex: Int,
) {
    val errorCount: Int get() = errors.size
}

/** Honest, countable error describing one skipped daily-log file. */
data class DailyLogParseError(
    val kind: DailyLogErrorKind,
    val day: String,
    val fileName: String,
)

/** Machine-readable category for an honest daily-log parse error. */
enum class DailyLogErrorKind {
    INVALID_DAY_DIRECTORY,
    INVALID_FILE_NAME,
    OVERSIZED_DOCUMENT,
    OVERSIZED_METADATA,
    OVERSIZED_ENTRY,
    TOO_MANY_ENTRIES,
}

/** Path / naming helpers for the app-private daily-log tree (pure). */
object DailyLogPaths {
    /** Directory name under the workflows root, matching Harmony `daily-log`. */
    const val DAILY_LOG_DIR_NAME: String = "daily-log"

    /** Whether a directory name is a strict `YYYY-MM-DD`. */
    fun isValidDayDirectory(name: String): Boolean = DailyLogBounds.DAY_PATTERN.matches(name.trim())
}
