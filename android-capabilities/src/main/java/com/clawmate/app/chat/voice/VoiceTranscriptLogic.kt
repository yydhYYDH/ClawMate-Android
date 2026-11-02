package com.clawmate.app.chat.voice

/**
 * Pure ASR transcript normalization used by the Android speech recognizer
 * adapter. Port of the pure helpers in HarmonyOS
 * `entry/src/main/ets/utils/voice/HarmonySpeechRecognizerAsrProvider.ets`.
 *
 * These are deterministic, device-free functions so cumulative/committed
 * transcript merging and duplicate-final suppression can be unit-tested.
 */
object VoiceTranscriptLogic {

    fun normalizeAsrText(text: String): String = text.trim()

    private fun isBoundaryChar(char: Char): Boolean =
        char == '，' || char == '。' || char == '、' || char == '！' || char == '？' ||
            char == ',' || char == '.' || char == '!' || char == '?' || char == ';' || char == '；' ||
            char == ':' || char == '：' || char == ' ' || char == '\n' || char == '\t'

    /** Strips punctuation/whitespace for cumulative-length comparison. */
    fun compactAsrText(text: String): String {
        val sb = StringBuilder(text.length)
        for (ch in text) {
            if (!isBoundaryChar(ch)) sb.append(ch)
        }
        return sb.toString()
    }

    internal fun isLongerCumulativeTranscript(committed: String, next: String): Boolean {
        if (next.length <= committed.length) return false
        if (next.startsWith(committed)) return true
        val committedCompact = compactAsrText(committed)
        val nextCompact = compactAsrText(next)
        return committedCompact.isNotEmpty() && nextCompact.length > committedCompact.length &&
            nextCompact.startsWith(committedCompact)
    }

    /**
     * Joins a committed transcript with a new segment. An ASR final is usually
     * the current clause; a few implementations return the *cumulative full
     * text*, in which case the full text replaces the committed one to avoid
     * repeating earlier content.
     */
    fun joinAsrText(committedText: String, nextText: String): String {
        val committed = normalizeAsrText(committedText)
        val next = normalizeAsrText(nextText)
        if (committed.isEmpty()) return next
        if (next.isEmpty()) return committed
        return if (isLongerCumulativeTranscript(committed, next)) next else committed + next
    }

    @Suppress("UNUSED_PARAMETER")
    fun mergeAsrFinalTranscript(committedText: String, finalText: String, isLast: Boolean): String =
        joinAsrText(committedText, finalText)

    fun mergeAsrPartialTranscript(committedText: String, partialText: String): String =
        joinAsrText(committedText, partialText)

    /** True when this final callback is an exact duplicate of the last one. */
    fun shouldSkipDuplicateAsrFinalCallback(
        lastFinalText: String,
        lastFinalIsLast: Boolean,
        currentFinalText: String,
        currentFinalIsLast: Boolean,
    ): Boolean =
        normalizeAsrText(lastFinalText).isNotEmpty() &&
            normalizeAsrText(lastFinalText) == normalizeAsrText(currentFinalText) &&
            lastFinalIsLast == currentFinalIsLast
}
