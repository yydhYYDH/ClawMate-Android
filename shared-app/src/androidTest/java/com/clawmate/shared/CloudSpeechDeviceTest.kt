package com.clawmate.shared

import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.clawmate.platform.voice.SpeechHttp
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class CloudSpeechDeviceTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context=instrumentation.targetContext
    private var failureDetails: (() -> String)? = null
    private fun bridge(scenario: ActivityScenario<SharedActivity>): PlatformBridge {
        lateinit var value: PlatformBridge
        scenario.onActivity{value=SharedActivity::class.java.getDeclaredField("platform").apply{isAccessible=true}.get(it) as PlatformBridge};return value
    }
    private fun await(description: String, ready: ()->Boolean) {
        val deadline=System.currentTimeMillis()+20000
        while(System.currentTimeMillis()<deadline){if(ready())return;Thread.sleep(50)}
        failureDetails?.let{details->
            instrumentation.sendStatus(2,Bundle().apply{putString("speechFailure",details())})
            instrumentation.uiAutomation.takeScreenshot()?.let{bitmap->File(context.cacheDir,"migration-speech-failure.png").outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()}
        }
        error(description)
    }
    private fun tone(samples: Int)=ByteArray(samples*2).also{data->repeat(samples){i->val v=(kotlin.math.sin(i*2*Math.PI*440/24000)*700).toInt();data[i*2]=v.toByte();data[i*2+1]=(v shr 8).toByte()}}
    private fun find(node: AccessibilityNodeInfo?, text: String): AccessibilityNodeInfo? {
        if(node==null)return null
        if(node.isVisibleToUser && (node.text?.toString()==text || node.contentDescription?.toString()==text))return node
        for(i in 0 until node.childCount)find(node.getChild(i),text)?.let{return it};return null
    }
    private fun click(text: String): Boolean {
        if(android.os.Build.VERSION.SDK_INT>=33)instrumentation.uiAutomation.clearCache()
        var bounds=find(instrumentation.uiAutomation.rootInActiveWindow,text)?.let{node->Rect().also{node.getBoundsInScreen(it)}}
        if(bounds==null && text=="聊天") {
            val bitmap=instrumentation.uiAutomation.takeScreenshot() ?: return false
            val ocr=TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
            try{bounds=Tasks.await(ocr.process(InputImage.fromBitmap(bitmap,0)),15,TimeUnit.SECONDS).textBlocks.flatMap{it.lines}.firstOrNull{it.text.replace(Regex("\\s+"),"")==text}?.boundingBox}
            finally{ocr.close();bitmap.recycle()}
        }
        val b=bounds ?: return false
        android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("input tap ${b.centerX()} ${b.centerY()}")).use{it.readBytes()}
        Thread.sleep(250);return true
    }
    @Test fun actualAudioTrackDrainsPcmPausesResumesAndDropsCancelledTurn() {
        ActivityScenario.launch(SharedActivity::class.java).use{scenario->
            val port=bridge(scenario);val id="pcm-device-fixture";val pcm=tone(48000)
            port.speechPcmStart(id)
            for(offset in pcm.indices step 24000){val chunk=java.util.Base64.getEncoder().encodeToString(pcm.copyOfRange(offset,minOf(offset+24000,pcm.size)));await("PCM sink blocked"){port.speechPcmAppend(id,chunk)}}
            port.speechPcmFinish(id)
            await("AudioTrack did not start"){JSONObject(port.speechPlaybackPoll()).getString("status")=="speaking"}
            port.speechPlaybackPause(true);assertEquals("paused",JSONObject(port.speechPlaybackPoll()).getString("status"));Thread.sleep(100);port.speechPlaybackPause(false)
            await("AudioTrack did not drain"){JSONObject(port.speechPlaybackPoll()).getString("status")=="idle"}
            assertEquals(48000L,JSONObject(port.speechPlaybackPoll()).getLong("frames"))
            port.speechPcmStart("cancelled-turn");port.speechPcmAppend("cancelled-turn",java.util.Base64.getEncoder().encodeToString(tone(24000)));port.speechStop("cancelled-turn")
            assertEquals("",JSONObject(port.speechPlaybackPoll()).getString("id"))
            assertThrows(IllegalStateException::class.java){port.speechPcmAppend("cancelled-turn","AAAA")}
            instrumentation.sendStatus(2,Bundle().apply{putString("pcmPlayback","Real AudioTrack playback head drained 48000 PCM16 mono/24kHz frames; pause/resume and cancelled-turn discard passed")})
            android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_HOME")).use{it.readBytes()}
            await("Background did not detach playback"){JSONObject(port.speechPlaybackPoll()).getString("id")==""}
            assertTrue("Background voice request accepted",runCatching{port.speechRequestStart("https://example.invalid","synthetic","{}",true)}.isFailure)
        }
    }
    @Test fun cachedPlaybackRefusesRemoteAndExternalSources() {
        com.clawmate.platform.voice.SpeechFilePlayer(context,File(context.filesDir,"files/chat-sessions/assets")).use { player ->
            for(uri in listOf("https://example.invalid/audio.wav","file:///system/build.prop","content://ungranted/audio"))
                assertThrows(IllegalArgumentException::class.java){player.play("fixture",uri)}
        }
    }
    @Test fun actualSharedPageStreamsBeforeDoneCachesOriginalWavAndReplaysWithoutAnotherRequest() {
        val certificate=HeldCertificate.Builder().addSubjectAlternativeName("127.0.0.1").build()
        val serverTls=HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientTls=HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val id="migration-speech-${android.os.SystemClock.elapsedRealtime()}";val messageId="speech-fixture"
        val root=File(context.filesDir,"files/chat-sessions");val index=File(root,"session-index.json");val session=File(root,"sessions/$id.json")
        val settings=File(context.filesDir,"preference/shared_frontend_settings");val privacy=File(context.filesDir,"preference/privacy_consent")
        val originals=listOf(index,settings,privacy).associateWith{if(it.exists())it.readBytes()else null}
        val backup=File(context.cacheDir,"migration-speech-backup");check(!backup.exists());check(backup.mkdirs())
        originals.entries.forEachIndexed{i,e->e.value?.let{File(backup,"$i.bin").writeBytes(it)}}
        val pcm=tone(24000);val cache=File(root,"assets/$id/$messageId-tts.wav")
        try {
            MockWebServer().use{server->
                server.useHttps(serverTls.sslSocketFactory(),false);server.start()
                val endpoint=server.url("/v1").newBuilder().host("127.0.0.1").build().toString()
                val event="data: "+JSONObject().put("choices",JSONArray().put(JSONObject().put("delta",JSONObject().put("audio",JSONObject().put("data",java.util.Base64.getEncoder().encodeToString(pcm)))))).toString()+"\n\n"
                // Fill Okio's output segment so the complete first SSE event is
                // physically flushed before throttling the final DONE marker.
                val padding=((event.toByteArray().size+3+8191)/8192)*8192-event.toByteArray().size
                val first=event+":"+" ".repeat(padding-3)+"\n\n"
                server.enqueue(MockResponse().setHeader("Content-Type","text/event-stream").setBody(first+"data: [DONE]\n\n").throttleBody(first.toByteArray().size.toLong(),4,TimeUnit.SECONDS))
                settings.parentFile!!.mkdirs();index.parentFile!!.mkdirs();session.parentFile!!.mkdirs()
                settings.writeText("""<?xml version="1.0" encoding="utf-8"?><preferences><string key="firstLaunchOnboardingVersion">2026-07-core-preview-usage-guide</string><bool key="androidCloudAccessGranted" value="true"/><string key="voiceTtsBaseUrl">$endpoint</string><string key="voiceTtsApiKey">synthetic-test-key</string><string key="voiceTtsModel">fixture-mimo-tts</string><string key="voiceTtsVoice">mimo_default</string><bool key="voiceTtsStreamingEnabled" value="true"/></preferences>""")
                privacy.writeText("""<?xml version="1.0" encoding="utf-8"?><preferences><string key="choice">accepted</string><string key="version">2026-10-26-android-v1</string></preferences>""")
                val now=System.currentTimeMillis()
                session.writeText(JSONObject().put("id",id).put("title","合成语音测试").put("mode","cloud_agent").put("createdAt",now).put("updatedAt",now)
                    .put("messages",JSONArray().put(JSONObject().put("id",messageId).put("role","assistant").put("kind","chat").put("content","合成语音流测试").put("createdAt",now)
                        .put("thinkingContent","").put("thinkingCollapsed",true).put("collapsed",false).put("trace",JSONArray()))).toString())
                index.writeText(JSONObject().put("sessions",JSONArray().put(JSONObject().put("id",id).put("title","合成语音测试").put("mode","cloud_agent").put("fileName","$id.json")
                    .put("createdAt",now).put("updatedAt",now).put("messageCount",1).put("lastMessagePreview","合成语音"))).toString())
                ActivityScenario.launch(SharedActivity::class.java).use{scenario->
                    val port=bridge(scenario)
                    val field=PlatformBridge::class.java.getDeclaredField("speechHttp").apply{isAccessible=true}
                    (field.get(port) as SpeechHttp).close()
                    field.set(port,SpeechHttp(OkHttpClient.Builder().proxy(java.net.Proxy.NO_PROXY).sslSocketFactory(clientTls.sslSocketFactory(),clientTls.trustManager).build()))
                    await("Chat navigation missing"){click("聊天")};await("Actual voice button missing"){click("朗读消息")}
                    val received=server.takeRequest(5,TimeUnit.SECONDS) ?: error("No actual HTTPS voice request")
                    val request=JSONObject(received.body.readUtf8());assertTrue(request.getBoolean("stream"));assertEquals("pcm16",request.getJSONObject("audio").getString("format"))
                    assertEquals("assistant",request.getJSONArray("messages").getJSONObject(0).getString("role"));assertEquals("synthetic-test-key",received.getHeader("api-key"))
                    fun voice()=runCatching{JSONObject(session.readText()).getJSONArray("messages").getJSONObject(0).optJSONObject("voice")}.getOrNull()
                    failureDetails={voice()?.toString() ?: "No synthetic message voice metadata"}
                    await("No real streamed playing state before cache"){voice()?.optString("status")=="playing" && voice()?.optString("audioUri")==""}
                    assertFalse("Cache committed before DONE",cache.exists())
                    await("Original WAV cache/ready state missing"){cache.exists() && voice()?.optString("status")=="ready"}
                    val bytes=cache.readBytes();assertEquals("RIFF",String(bytes.copyOfRange(0,4),Charsets.US_ASCII));assertArrayEquals(pcm,bytes.copyOfRange(44,bytes.size));assertEquals("mimo",voice()!!.getString("provider"))
                    await("Replay control missing"){click("重播缓存语音")}
                    await("Local replay never entered playing"){voice()?.optString("status")=="playing"}
                    await("Local replay did not complete"){voice()?.optString("status")=="ready"}
                    assertEquals("Replay requested cloud again",1,server.requestCount)
                    instrumentation.sendStatus(2,Bundle().apply{putString("cloudSpeechPage","Actual ArkTS -> original MiMO request/parser -> loopback HTTPS SSE -> AudioTrack before DONE -> original WAV bytes/cache -> MediaPlayer local replay; provider is synthetic tone, not real MiMO quality")})
                }
            }
        } finally {
            originals.forEach{(file,bytes)->if(bytes==null)file.delete()else file.writeBytes(bytes)}
            session.delete();cache.delete();File(cache.path+".tmp").delete();cache.parentFile?.delete()
            backup.listFiles().orEmpty().forEach{it.delete()};check(backup.delete())
        }
    }
}
