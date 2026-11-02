package com.clawmate.platform.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import java.io.Closeable
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class SpeechPlayback(val id: String = "", val status: String = "idle", val error: String = "", val frames: Long = 0)

/** Fixed MiMO PCM16LE/24kHz/mono sink with bounded backpressure and real playback-head drain. */
class PcmSpeechPlayer(context: Context) : Closeable {
    private val audio=context.applicationContext.getSystemService(AudioManager::class.java)
    private val executor=Executors.newSingleThreadExecutor()
    private class Playback(val id: String) {
        val queue=ArrayBlockingQueue<ByteArray>(8)
        @Volatile var ended=false
        @Volatile var stopped=false
        @Volatile var paused=false
        @Volatile var track: AudioTrack?=null
        @Volatile var snapshot=SpeechPlayback(id,"synthesizing")
        var bytes=0
    }
    @Volatile private var current: Playback?=null
    @Synchronized fun start(id: String) {
        require(id.isNotBlank() && id.length<=160);stop()
        val play=Playback(id);current=play
        executor.submit {
            val attributes=AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
            val focus=AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(attributes)
                .setOnAudioFocusChangeListener { change -> if(change<=AudioManager.AUDIOFOCUS_LOSS_TRANSIENT){play.stopped=true;play.snapshot=play.snapshot.copy(status="error",error="语音被系统打断，请重新播放")} }.build()
            var frames=0L
            try {
                check(audio.requestAudioFocus(focus)==AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { "系统未允许语音播放，请返回前台重试" }
                val minimum=AudioTrack.getMinBufferSize(24000,AudioFormat.CHANNEL_OUT_MONO,AudioFormat.ENCODING_PCM_16BIT)
                check(minimum>0) { "系统不支持24kHz语音输出" }
                val track=AudioTrack.Builder().setAudioAttributes(attributes).setAudioFormat(AudioFormat.Builder()
                    .setSampleRate(24000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                    .setBufferSizeInBytes(maxOf(minimum,16384)).setTransferMode(AudioTrack.MODE_STREAM).build()
                play.track=track;check(track.state==AudioTrack.STATE_INITIALIZED) { "语音输出初始化失败" }
                track.play()
                while(!play.stopped) {
                    if(play.paused){track.pause();Thread.sleep(20);continue}
                    if(track.playState!=AudioTrack.PLAYSTATE_PLAYING)track.play()
                    val chunk=play.queue.poll(50,TimeUnit.MILLISECONDS)
                    if(chunk!=null) {
                        var offset=0;var stalled=System.nanoTime()
                        while(offset<chunk.size && !play.stopped) {
                            if(play.paused){track.pause();stalled=System.nanoTime();Thread.sleep(20);continue}
                            if(track.playState!=AudioTrack.PLAYSTATE_PLAYING)track.play()
                            val n=track.write(chunk,offset,chunk.size-offset,AudioTrack.WRITE_NON_BLOCKING)
                            check(n>=0 && n%2==0) { "系统语音输出失败" }
                            if(n>0){offset+=n;frames+=n/2;stalled=System.nanoTime();play.snapshot=SpeechPlayback(id,"speaking",frames=track.playbackHeadPosition.toLong() and 0xffffffffL)}
                            else {check(System.nanoTime()-stalled<30_000_000_000L){"语音输出等待超时"};Thread.sleep(10)}
                        }
                    } else if(play.ended)break
                }
                var deadline=System.nanoTime()+30_000_000_000L
                while(!play.stopped && (track.playbackHeadPosition.toLong() and 0xffffffffL)<frames) {
                    if(play.paused){track.pause();deadline=System.nanoTime()+30_000_000_000L;Thread.sleep(20);continue}
                    if(track.playState!=AudioTrack.PLAYSTATE_PLAYING)track.play()
                    check(System.nanoTime()<deadline) { "语音播放完成等待超时" };Thread.sleep(20)
                }
                if(!play.stopped)play.snapshot=SpeechPlayback(id,"idle",frames=frames)
            } catch (failure: Exception) {
                if(!play.stopped)play.snapshot=SpeechPlayback(id,"error",failure.message ?: "语音输出失败",frames)
            } finally {
                play.queue.clear();play.track?.let{runCatching{it.pause();it.flush();it.release()}};play.track=null
                audio.abandonAudioFocusRequest(focus)
            }
        }
    }
    @Synchronized fun append(id: String, bytes: ByteArray): Boolean {
        val play=current?.takeIf{it.id==id && !it.stopped} ?: error("语音输出已结束")
        check(play.snapshot.status!="error" && !play.ended) { "语音输出不可用" }
        require(bytes.isNotEmpty() && bytes.size<=65536 && bytes.size%2==0) { "PCM音频分片无效" }
        require(play.bytes.toLong()+bytes.size<=8*1024*1024) { "语音过长，请缩短文本" }
        if(!play.queue.offer(bytes))return false
        play.bytes+=bytes.size;return true
    }
    fun finish(id: String) { current?.takeIf{it.id==id}?.ended=true }
    fun snapshot(): SpeechPlayback = current?.let { p ->
        if(p.paused && p.snapshot.status!="error")p.snapshot.copy(status="paused") else p.snapshot
    } ?: SpeechPlayback()
    fun pause(paused: Boolean) { current?.let{it.paused=paused} }
    @Synchronized fun stop() { current?.let{it.stopped=true;it.queue.clear();it.snapshot=it.snapshot.copy(status="paused")};current=null }
    override fun close(){stop();executor.shutdownNow()}
}
