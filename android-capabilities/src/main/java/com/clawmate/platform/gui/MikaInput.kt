package com.clawmate.platform.gui

/**
 * 手机任务里允许打进输入框的文字。
 *
 * 只认用户这次请求里出现过的内容：页面上的字、模型自己编的数字都不能变成输入。
 * 这比此前「只往搜索框打用户原话」宽松——现在可以填表单，但来源仍必须是用户，
 * 否则一个被污染的页面就能把模型引去输入任意内容。
 */
internal object MikaInput {
    const val MAX_CHARS = 200

    fun allowed(goal: String, text: String): Boolean {
        if (text.isEmpty()) return true
        if (text.length > MAX_CHARS) return false
        if (text.any(Char::isISOControl)) return false
        return goal.contains(text, ignoreCase = true)
    }
}
