package com.clawmate.app.chat.voice.asr

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.clawmate.app.chat.voice.VoiceTranscriptLogic
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ASR recognition callbacks — port of `AsrRecognitionCallbacks` from
 * `HarmonySpeechRecognizerAsrProvider.ets`.
 */
interface AsrRecognitionCallbacks {
    fun onStart()
    fun onPartialTranscript(transcript: String)
    fun onFinalTranscript(transcript: String, isLast: Boolean)
    fun onComplete()
    fun onError(message: String)
}

/** Typed ASR start options (port of `HarmonyAsrStartOptions` minus platform params). */
data class AsrStartOptions(
    val language: String = "zh-CN",
)

/** Outcome a [start] call reports to the caller, distinct from the callback stream. */
sealed class AsrStartResult {
    object Started : AsrStartResult()
    data class PermissionDenied(val message: String) : AsrStartResult()
    data class Unavailable(val message: String) : AsrStartResult()
    data class Failed(val message: String) : AsrStartResult()
}

/**
 * Reason a recognizer is reported unavailable/denied — typed so the UI can
 * surface the exact requirement (RECORD_AUDIO permission, no recognizer
 * service, or a disabled permission) instead of guessing.
 */
enum class AsrUnavailableReason {
    NO_RECORD_PERMISSION,
    NO_RECOGNITION_SERVICE,
    RECOGNITION_DENIED,
    SPEECH_DISABLED,
}

/**
 * Android ASR adapter around [android.speech.SpeechRecognizer].
 *
 * Mapping from the HarmonyOS CoreSpeechKit contract:
 *   - `createEngine`             → [SpeechRecognizer.createSpeechRecognizer]
 *   - `onStart`                  → [RecognitionListener.onBeginningOfSpeech] / [android.speech] start
 *   - `onResult (partial)`       → [RecognitionListener.onPartialResults]
 *   - `onResult (final/isLast)`  → [RecognitionListener.onResults]
 *   - `onComplete`               → [RecognitionListener.onEndOfSpeech] (device-side end)
 *   - `onError`                  → [RecognitionListener.onError]
 *
 * Truthfulness rules:
 *   - No hidden microphone access: the adapter *never* auto-requests the
 *     RECORD_AUDIO permission. [start] checks [Context] for the permission and
 *     returns [AsrStartResult.PermissionDenied] if it is absent; the UI is
 *     expected to surface this requirement and let the user grant it first.
 *   - There is no fake transcript: every partial/final callback carries bytes
 *     reported by the platform recognizer.
 *   - Cumulative final transcripts are merged via [VoiceTranscriptLogic] exactly
 *     as the source does, and duplicate final callbacks are suppressed.
 */
class AndroidSpeechRecognizerAsrAdapter(
    private val context: Context,
    private val transcriptLogic: VoiceTranscriptLogic = VoiceTranscriptLogic,
) {

    private var recognizer: SpeechRecognizer? = null
    private var callbacks: AsrRecognitionCallbacks? = null
    private var live = AtomicBoolean(false)

    private var latestTranscript: String = ""
    private var finalTranscript: String = ""
    private var lastFinalTranscript: String = ""
    private var lastFinalIsLast: Boolean = false

    /** True while the platform listener is attached (a live recognition session). */
    val isListening: Boolean get() = live.get()

    fun hasRecordPermission(): Boolean =
        context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    fun isRecognizerAvailable(): Boolean =
        SpeechRecognizer.isRecognitionAvailable(context)

    private fun unavailableReason(): AsrUnavailableReason =
        when {
            !hasRecordPermission() -> AsrUnavailableReason.NO_RECORD_PERMISSION
            !isRecognizerAvailable() -> AsrUnavailableReason.NO_RECOGNITION_SERVICE
            else -> AsrUnavailableReason.SPEECH_DISABLED
        }

    private fun shouldAcceptCallbacks(): Boolean = live.get()

    /**
     * Starts an on-device recognition session. Detaches any previous session
     * first. Returns a typed result; on success the platform callbacks flow
     * through [callbacks].
     */
    fun start(cb: AsrRecognitionCallbacks, options: AsrStartOptions = AsrStartOptions()): AsrStartResult {
        stopInternal(clearState = true)
        if (!hasRecordPermission()) {
            return AsrStartResult.PermissionDenied("需要录音权限（RECORD_AUDIO）才能语音输入")
        }
        if (!isRecognizerAvailable()) {
            return AsrStartResult.Unavailable("当前设备没有可用的语音识别服务")
        }
        callbacks = cb
        latestTranscript = ""
        finalTranscript = ""
        lastFinalTranscript = ""
        lastFinalIsLast = false
        val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        if (recognizer == null) {
            return AsrStartResult.Unavailable("无法创建语音识别引擎")
        }
        this.recognizer = recognizer
        live.set(true)
        recognizer.setRecognitionListener(listener)
        val intent = buildRecognizerIntent(options.language)
        try {
            recognizer.startListening(intent)
        } catch (e: Exception) {
            // Start can throw if a listener was already bound on some devices.
            live.set(false)
            this.recognizer = null
            return AsrStartResult.Failed("启动语音识别失败：${e.message ?: "未知错误"}")
        }
        return AsrStartResult.Started
    }

    /** Flags the end-of-speech; the platform will emit a final result / end. */
    fun finish() {
        val active = recognizer ?: return
        if (!live.get()) return
        try {
            active.stopListening()
        } catch (e: Exception) {
            // ignore: finish is best-effort, mirrors source finish().
        }
    }

    /** Cancels the active session and detaches listeners. */
    fun cancel() {
        stopInternal(clearState = false)
    }

    /** Cancels + clears state; the equivalent of the source `shutdown()`. */
    fun shutdown() {
        stopInternal(clearState = true)
    }

    private fun stopInternal(clearState: Boolean) {
        val active = this.recognizer
        this.recognizer = null
        this.callbacks = null
        live.set(false)
        if (active != null) {
            try {
                active.cancel()
            } catch (e: Exception) {
                // best-effort
            }
            try {
                active.destroy()
            } catch (e: Exception) {
                // best-effort
            }
        }
        if (clearState) {
            latestTranscript = ""
            finalTranscript = ""
            lastFinalTranscript = ""
            lastFinalIsLast = false
        }
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            // Source onStart: recognition session underway.
            if (shouldAcceptCallbacks()) callbacks?.onStart()
        }

        override fun onBeginningOfSpeech() {
            // no user-visible start in Harmony contract
        }

        override fun onRmsChanged(rmsdB: Float) = Unit

        override fun onBufferReceived(buffer: ByteArray?) {
            // on-device ASR does not hand PCM bytes to the app.
        }

        override fun onEndOfSpeech() {
            if (shouldAcceptCallbacks()) callbacks?.onComplete()
        }

        override fun onError(error: Int) {
            if (!shouldAcceptCallbacks() || callbacks == null) return
            callbacks?.onError(androidErrorToMessage(error))
        }

        override fun onResults(results: Bundle?) {
            if (!shouldAcceptCallbacks() || callbacks == null) return
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val transcript = matches?.firstOrNull()?.trim().orEmpty()
            if (transcript.isEmpty()) return
            if (shouldSkipDuplicateFinal(transcript, isLast = true)) return
            lastFinalTranscript = transcript
            lastFinalIsLast = true
            finalTranscript = transcriptLogic.mergeAsrFinalTranscript(finalTranscript, transcript, isLast = true)
            latestTranscript = finalTranscript
            callbacks?.onFinalTranscript(finalTranscript, isLast = true)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            if (!shouldAcceptCallbacks() || callbacks == null) return
            val parts = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val partial = parts?.firstOrNull()?.trim().orEmpty()
            if (partial.isEmpty()) return
            latestTranscript = transcriptLogic.mergeAsrPartialTranscript(finalTranscript, partial)
            callbacks?.onPartialTranscript(latestTranscript)
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private fun shouldSkipDuplicateFinal(currentFinalText: String, isLast: Boolean): Boolean =
        VoiceTranscriptLogic.shouldSkipDuplicateAsrFinalCallback(
            lastFinalTranscript, lastFinalIsLast, currentFinalText, isLast,
        )

    private fun buildRecognizerIntent(language: String): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language.ifEmpty { "zh-CN" })
            // Be explicit: we deliver partial results and request the best
            // recognized text only; no hidden audio is captured or sent anywhere.
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
        }

    private fun androidErrorToMessage(error: Int): String =
        when (error) {
            SpeechRecognizer.ERROR_AUDIO -> "语音识别错误：音频输入问题"
            SpeechRecognizer.ERROR_CLIENT -> "语音识别错误：客户端错误（可能需要录音权限）"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "语音识别错误：缺少录音权限"
            SpeechRecognizer.ERROR_NETWORK -> "语音识别错误：网络请求失败"
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "语音识别错误：网络请求超时"
            SpeechRecognizer.ERROR_NO_MATCH -> "没有识别到语音内容"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "语音识别引擎正忙"
            SpeechRecognizer.ERROR_SERVER -> "语音识别服务错误"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "没有检测到语音输入"
            else -> "语音识别失败：错误码 $error"
        }
}
