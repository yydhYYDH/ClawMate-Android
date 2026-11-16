package com.clawmate.platform.gui

/**
 * Recognize an explicit launch of an installed app in the current user message.
 * The caller still confirms the target before any app is opened.
 */
internal object MikaAppRequest {
    private val launch = Regex(
        """^(?:请\s*)?(?:帮我\s*)?(?:打开|启动)\s*|^(?:please\s+)?(?:open|launch)\s+(?:(?:the|my)\s+)?(?:system\s+)?""",
        RegexOption.IGNORE_CASE,
    )

    fun target(goal: String, apps: List<Pair<String, String>>): String? {
        val text = goal.trim().removePrefix("@").trimStart()
        val prefix = launch.find(text) ?: return null
        val rest = text.substring(prefix.range.last + 1)
        val matches = apps.filter { (_, label) ->
            label.isNotBlank() && rest.startsWith(label, ignoreCase = true) &&
                rest.drop(label.length).let { tail ->
                    tail.isEmpty() || tail.first().isWhitespace() || tail.first() in "，。！？、,.;!?:："
                }
        }
        val longest = matches.maxOfOrNull { it.second.length } ?: return null
        return matches.filter { it.second.length == longest }.map { it.first }.distinct().singleOrNull()
    }
}
