package com.clawmate.platform.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.net.URI

/** Private cached WAV replay. Call on the host's main thread; no network source is accepted. */
class SpeechFilePlayer(context: Context, private val root: File) : Closeable {
    private val context=context.applicationContext
    private val audio=context.getSystemService(AudioManager::class.java)
    private var player: MediaPlayer?=null
    private var focus: AudioFocusRequest?=null
    @Volatile private var value=SpeechPlayback()
    fun play(id: String, uri: String) {
        close()
        val parsed=URI(uri)
        require(parsed.scheme=="file" && parsed.authority.isNullOrEmpty() && parsed.query==null && parsed.fragment==null) { "语音重播只接受本机会话缓存" }
        val file=File(parsed).canonicalFile
        val allowed=root.canonicalFile
        require(allowed.toPath().startsWith(context.filesDir.canonicalFile.toPath()) && file.toPath().startsWith(allowed.toPath()) && file.extension=="wav" && file.length() in 44..(8*1024*1024+44L)) { "语音缓存无效，请重新合成" }
        val attributes=AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
        val request=AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener { change -> if(change<=AudioManager.AUDIOFOCUS_LOSS_TRANSIENT){value=SpeechPlayback(id,"error","语音被系统打断，请重新播放");closePlayer()} }.build()
        check(audio.requestAudioFocus(request)==AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { "系统未允许语音播放" };focus=request
        val engine=MediaPlayer();player=engine;value=SpeechPlayback(id,"synthesizing")
        try {
            engine.setAudioAttributes(attributes)
            engine.setOnPreparedListener{if(player===it){it.start();value=SpeechPlayback(id,"speaking")}}
            engine.setOnCompletionListener{if(player===it){value=SpeechPlayback(id,"idle");it.release();player=null;focus?.let{f->audio.abandonAudioFocusRequest(f)};focus=null}}
            engine.setOnErrorListener{_,_,_->value=SpeechPlayback(id,"error","语音缓存无法播放，请重新合成");closePlayer();true}
            FileInputStream(file).use{engine.setDataSource(it.fd)}
            engine.prepareAsync()
        } catch(failure: Exception){value=SpeechPlayback(id,"error","语音缓存无法播放，请重新合成");closePlayer();throw failure}
    }
    fun pause(paused: Boolean) {
        val engine=player ?: return
        if(value.status !in listOf("speaking","paused"))return
        if(paused){engine.pause();value=value.copy(status="paused")} else {
            check(focus?.let{audio.requestAudioFocus(it)}==AudioManager.AUDIOFOCUS_REQUEST_GRANTED){"系统未允许继续语音播放"}
            engine.start();value=value.copy(status="speaking")
        }
    }
    fun snapshot(): SpeechPlayback=value
    private fun closePlayer(){player?.let{runCatching{it.release()}};player=null;focus?.let{audio.abandonAudioFocusRequest(it)};focus=null}
    override fun close(){closePlayer();value=SpeechPlayback()}
}
