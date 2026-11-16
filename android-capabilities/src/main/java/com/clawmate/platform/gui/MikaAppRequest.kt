package com.clawmate.platform.gui

/**
 * Recognize an explicit launch of an installed app in the current user message.
 * The caller still confirms the target before any app is opened.
 *
 * 除了「打开/启动 + 应用名」，也认「去/到/在/用 + 应用名」的说法，
 * 例如「去微博帮我找一下…」；应用名后面只接受空白、标点或常见动词/方位词，
 * 避免把「打开小红书助手」这类更长的名字误认成小红书。
 */
internal object MikaAppRequest {
    private val launch = Regex(
        """^(?:请\s*)?(?:帮我\s*)?(?:去|到|在|用|打开|启动|进入)\s*|^(?:please\s+)?(?:open|launch|go\s+to)\s+(?:(?:the|my)\s+)?(?:system\s+)?""",
        RegexOption.IGNORE_CASE,
    )

    /** 应用名后面可能紧跟的字：动词、方位、语气词与标点；别的字说明这是更长的名字。 */
    private val continuation = setOf(
        '帮', '搜', '找', '看', '查', '点', '进', '读', '写', '发', '问', '说', '试', '弄', '玩',
        '里', '上', '中', '内', '下', '的', '吧', '呢', '啊', '呀', '嘛', '了', '和', '与', '或',
        ' ', '，', '。', '！', '？', '、', '：', ':', ',', '.', ';', '!', '?',
    )

    /** 常用应用的中英文别名：只在标签完全匹配时套用，避免把“小红书助手”之类带偏。 */
    private val aliases = mapOf(
        "设置" to listOf("settings"),
        "微信" to listOf("wechat", "weixin"),
        "微博" to listOf("weibo"),
        "哔哩哔哩" to listOf("bilibili"),
        "小红书" to listOf("xiaohongshu", "rednote"),
        "支付宝" to listOf("alipay"),
        "淘宝" to listOf("taobao"),
        "京东" to listOf("jd"),
        "美团" to listOf("meituan"),
        "大众点评" to listOf("dianping"),
        "抖音" to listOf("douyin", "tiktok"),
        "飞书" to listOf("feishu", "lark"),
        "Gmail" to listOf("gmail"),
    )

    fun target(goal: String, apps: List<Pair<String, String>>): String? {
        val text = goal.trim().removePrefix("@").trimStart()
        val prefix = launch.find(text)
        val expanded = apps.flatMap { app -> (listOf(app.second) + aliases[app.second].orEmpty()).map { app.first to it } }
        val matches = if (prefix != null) {
            val rest = text.substring(prefix.range.last + 1)
            expanded.filter { (_, name) -> name.isNotBlank() && startsWithLabel(rest, name) }
        } else {
            expanded.filter { (_, name) -> name.length >= 2 && mentions(text, name) }
        }
        val longest = matches.maxOfOrNull { it.second.length } ?: return null
        return matches.filter { it.second.length == longest }.map { it.first }.distinct().singleOrNull()
    }

    private fun startsWithLabel(rest: String, label: String): Boolean {
        if (!rest.startsWith(label, ignoreCase = true)) return false
        val tail = rest.drop(label.length)
        return tail.isEmpty() || tail.first().let { it.isWhitespace() || it in continuation }
    }

    private fun mentions(text: String, label: String): Boolean {
        var index = text.indexOf(label, ignoreCase = true)
        while (index >= 0) {
            val before = text.getOrNull(index - 1)
            val after = text.getOrNull(index + label.length)
            val starts = before == null || before.isWhitespace() || before in "去在用到帮我给和与或，。！？、：:,.!?"
            val ends = after == null || after.isWhitespace() || after in continuation
            if (starts && ends) return true
            index = text.indexOf(label, index + 1, ignoreCase = true)
        }
        return false
    }
}
