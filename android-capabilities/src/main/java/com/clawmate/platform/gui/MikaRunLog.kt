package com.clawmate.platform.gui

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/** 一次 Mika 运行的简短记录；正文与截图都不进这里。 */
@Serializable
data class MikaRunRecord(
    val at: Long,
    val kind: String,
    val app: String = "",
    val label: String = "",
    val goal: String = "",
    val status: String,
    val steps: Int = 0,
    val pages: Int = 0,
    val items: Int = 0,
    val note: String = "",
)

/**
 * Mika 运行记录：只记本机、只记摘要。
 *
 * 每条是 JSON 一行，追加写在本应用私有目录（不进备份），最多保留 [MAX_RECORDS] 行；
 * 不存界面截图、不存模型原文，目标与结果各截断到 200 字，供「最近运行」查看与后续分析。
 */
object MikaRunLog {
    const val MAX_RECORDS = 200
    const val MAX_FIELD = 200
    private const val FILE_NAME = "mika-runs.jsonl"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun file(context: Context): File = File(context.noBackupFilesDir, FILE_NAME)

    @Synchronized
    fun append(context: Context, record: MikaRunRecord) {
        val target = file(context)
        val existing = runCatching { target.readLines() }.getOrDefault(emptyList()).filter { it.isNotBlank() }
        val kept = (existing + encode(record)).takeLast(MAX_RECORDS)
        target.writeText(kept.joinToString("\n") + "\n")
    }

    fun encode(record: MikaRunRecord): String = json.encodeToString(record)

    fun decode(line: String): MikaRunRecord? =
        runCatching { json.decodeFromString<MikaRunRecord>(line) }.getOrNull()

    /** 最新的在前，最多 [limit] 条；坏行跳过，不让一条坏记录挡住历史。 */
    fun recent(context: Context, limit: Int): List<MikaRunRecord> =
        parse(runCatching { file(context).readLines() }.getOrDefault(emptyList()), limit)

    /** 纯函数版本，便于单测；输入按写入顺序（旧的在前）。 */
    fun parse(lines: List<String>, limit: Int): List<MikaRunRecord> =
        lines.mapNotNull(::decode).takeLast(limit.coerceIn(1, MAX_RECORDS)).reversed()

    fun record(kind: String, app: String, label: String, goal: String, status: String,
        steps: Int = 0, pages: Int = 0, items: Int = 0, note: String = "",
    ): MikaRunRecord = MikaRunRecord(
        at = System.currentTimeMillis(),
        kind = kind,
        app = app.take(MAX_FIELD),
        label = label.take(MAX_FIELD),
        goal = goal.replace('\n', ' ').take(MAX_FIELD),
        status = status,
        steps = steps,
        pages = pages,
        items = items,
        note = note.replace('\n', ' ').take(MAX_FIELD),
    )
}
