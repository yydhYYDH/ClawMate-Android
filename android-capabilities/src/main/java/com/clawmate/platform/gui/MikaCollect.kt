package com.clawmate.platform.gui

/**
 * 把 Mika `phone_collect` 的文本结果整理成可保存的条目。
 *
 * 内核返回的正文带 `<untrusted>` 标记（外部内容不可信）；保存到本机时去掉标记，保留原文，
 * 按首次出现顺序去重，并套用本机采集记录的上限（[MAX_LINES]、[MAX_CHARS]）。
 * 只做格式整理，不改写、不摘要。
 */
internal object MikaCollect {
    const val MAX_LINES = 300
    const val MAX_CHARS = 20_000
    private const val MAX_LINE_CHARS = 600

    fun lines(text: String): List<String> {
        val body = text.removePrefix("<untrusted>").removeSuffix("</untrusted>").trim('\n')
        if (body.isBlank()) return emptyList()
        val seen = LinkedHashSet<String>()
        var chars = 0
        for (raw in body.lineSequence()) {
            val line = raw.trim().take(MAX_LINE_CHARS)
            if (line.isEmpty()) continue
            if (seen.size >= MAX_LINES || chars + line.length > MAX_CHARS) break
            if (seen.add(line)) chars += line.length
        }
        return seen.toList()
    }
}
