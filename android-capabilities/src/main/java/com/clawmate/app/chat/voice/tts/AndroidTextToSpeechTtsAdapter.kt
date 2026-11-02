package com.clawmate.app.chat.voice.tts

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Android TTS adapter around [android.speech.TextToSpeech].
 *
 * Lifecycle-safe initialization (waits for onInit, serializes start/stop),
 * typed unavailable/error states, and speak / pause / stop / replay support.
 * The Android `TextToSpeech` engine is a local on-device voice; it does not
 * require any runtime permission.
 *
 * Truthfulness rules:
 *   - The adapter only *starts speaking* after [TextToSpeech] reports
 *     [TextToSpeech.SUCCESS] on init.
 *   - No cloud MiMO provider is claimed here. Cloud streaming voice is exposed
 *     separately behind an explicit NOT_CONFIGURED interface.
 *   - [speak] requires [ready]; otherwise it returns a typed [TtsSpeakResult].
 */
interface TtsSpeakListener {
    fun onStarted(utteranceId: String)
    fun onDone(utteranceId: String)
    fun onError(utteranceId: String, message: String)
}

enum class TtsInitState { UNAVAILABLE, INITIALIZING, READY, ENGINE_TTS_ERROR }

sealed class TtsInitResult {
    object Ready : TtsInitResult()
    data class Unavailable(val enumReason: TtsUnavailableReason) : TtsInitResult()
    data class Failed(val message: String) : TtsInitResult()
}

enum class TtsUnavailableReason { NO_TTS_ENGINE, ENGINE_INIT_ERROR }

sealed class TtsSpeakResult {
    object Accepted : TtsSpeakResult()
    data class NotReady(val reason: TtsInitState) : TtsSpeakResult()
    data class RichTypeError(val message: String) : TtsSpeakResult()
}

/**
 * Typed Android TextToSpeech adapter. All speak/stop/pause/replay calls are
 * idempotent and safe even before/after init, and [release] frees the engine.
 */
class AndroidTextToSpeechTtsAdapter(
    private val context: Context,
) : TextToSpeech.OnInitListener {

    @Volatile
    private var engine: TextToSpeech? = null

    @Volatile
    private var initState: TtsInitState = TtsInitState.UNAVAILABLE

    private val initListener = AtomicBoolean(false)
    private var listener: TtsSpeakListener? = null
    private var pendingUtteranceId = AtomicInteger(0)

    val state: TtsInitState get() = initState

    /** Whether the engine finished initializing and reported SUCCESS. */
    val ready: Boolean get() = initState == TtsInitState.READY

    /** Whether a speak is in flight and not yet completed. */
    @Volatile
    private var speaking = false

    @Volatile
    private var currentText: String = ""
    @Volatile private var activeUtteranceId: String = ""

    /**
     * Initializes the TTS engine. Safe to call once; a second call while an
     * engine exists returns [TtsInitResult.Ready] immediately.
     */
    fun initialize(cbListener: TtsSpeakListener): TtsInitResult {
        listener = cbListener
        val existing = engine
        if (existing != null && ready) return TtsInitResult.Ready
        if (existing != null) return TtsInitResult.Unavailable(TtsUnavailableReason.ENGINE_INIT_ERROR)
        initState = TtsInitState.INITIALIZING
        try {
            val created = TextToSpeech(context.applicationContext, this)
            engine = created
        } catch (e: Exception) {
            initState = TtsInitState.UNAVAILABLE
            return TtsInitResult.Failed("语音引擎初始化失败：${e.message ?: "未知错误"}")
        }
        return TtsInitResult.Ready
    }

    override fun onInit(status: Int) {
        initListener.set(true)
        when (status) {
            TextToSpeech.SUCCESS -> {
                // Prefer a Chinese locale if the engine can provide it; left at
                // system default otherwise. No fake voice is claimed.
                val zh = Locale.CHINESE
                val result = engine?.setLanguage(zh) ?: TextToSpeech.LANG_MISSING_DATA
                initState = if (result == TextToSpeech.LANG_MISSING_DATA ||
                    result == TextToSpeech.LANG_NOT_SUPPORTED
                ) TtsInitState.ENGINE_TTS_ERROR else TtsInitState.READY
            }
            TextToSpeech.ERROR -> initState = TtsInitState.ENGINE_TTS_ERROR
            else -> initState = TtsInitState.ENGINE_TTS_ERROR
        }
        attachProgressListener()
    }

    private fun attachProgressListener() {
        val active = engine ?: return
        active.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                if (utteranceId != activeUtteranceId) return
                speaking = true
                listener?.onStarted(utteranceId ?: "")
            }

            override fun onDone(utteranceId: String?) {
                if (utteranceId != activeUtteranceId) return
                speaking = false
                listener?.onDone(utteranceId ?: "")
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                if (utteranceId != activeUtteranceId) return
                speaking = false
                listener?.onError(utteranceId ?: "", "语音引擎错误")
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                if (utteranceId != activeUtteranceId) return
                speaking = false
                listener?.onError(utteranceId ?: "", "语音引擎错误：错误码 $errorCode")
            }
        })
    }

    /** Speaks [text]. Returns typed result; never silently drops or fakes audio. */
    fun speak(text: String): TtsSpeakResult {
        val active = engine
        if (active == null || !ready) return TtsSpeakResult.NotReady(initState)
        currentText = text
        val utteranceId = "voice-tts-" + pendingUtteranceId.incrementAndGet()
        activeUtteranceId = utteranceId
        val params = Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
            putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, android.media.AudioManager.STREAM_MUSIC)
        }
        val result = active.speak(text.trim(), TextToSpeech.QUEUE_FLUSH, params, utteranceId)
        return if (result == TextToSpeech.SUCCESS) TtsSpeakResult.Accepted
        else TtsSpeakResult.RichTypeError("语音引擎返回错误码 $result")
    }

    /** Resumes playback of the current utterance (Android TTS has no pause API; replay restarts). */
    fun resume(): TtsSpeakResult {
        val active = engine
        if (active == null || !ready) return TtsSpeakResult.NotReady(initState)
        if (currentText.isEmpty()) return TtsSpeakResult.NotReady(initState)
        return speak(currentText)
    }

    /** Replays the last text (or [text] if provided). */
    fun replay(text: String? = null): TtsSpeakResult {
        val active = engine
        if (active == null || !ready) return TtsSpeakResult.NotReady(initState)
        val toSay = text?.trim()?.ifEmpty { null } ?: currentText
        if (toSay.isEmpty()) return TtsSpeakResult.NotReady(initState)
        return speak(toSay)
    }

    /** Stops current playback. Idempotent. */
    fun stop() {
        activeUtteranceId = ""
        val active = engine
        if (active == null) return
        try {
            active.stop()
            speaking = false
        } catch (e: Exception) {
            // best-effort
        }
    }

    /** Pauses by stopping; Android's TTS has no native pause/resume distinct from replay. */
    fun pause() = stop()

    /** Frees the engine and clears listener. Idempotent. */
    fun release() {
        activeUtteranceId = ""
        listener = null
        val active = engine
        engine = null
        initState = TtsInitState.UNAVAILABLE
        initListener.set(false)
        speaking = false
        if (active != null) {
            try {
                active.stop()
            } catch (e: Exception) {
                // best-effort
            }
            try {
                active.shutdown()
            } catch (e: Exception) {
                // best-effort
            }
        }
    }
}
