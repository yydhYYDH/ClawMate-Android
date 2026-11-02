package com.clawmate.shared

import android.graphics.*
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import org.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.TimeUnit

/** Two separately invoked phases with an external force-stop between them. */
@RunWith(AndroidJUnit4::class)
class GalleryScanDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val root get() = File(context.filesDir,"files/gallery-log")
    private val backup get() = File(context.cacheDir,"migration-gallery-scan-backup")
    private val checkpoint get() = File(root,"scan-session.json")
    private val day = "1901-01-02"
    private fun originals() = listOf(File(context.filesDir,"preference/shared_frontend_settings"),File(context.filesDir,"preference/privacy_consent"),checkpoint,File(checkpoint.absolutePath+".part"))
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it.toInt() and 255)}
    private fun evidence(key: String,text: String) = instrumentation.sendStatus(2,Bundle().apply{putString(key,text)})
    private fun jpeg(number: Int): ByteArray {
        val bitmap=Bitmap.createBitmap(1400,900,Bitmap.Config.ARGB_8888);val canvas=Canvas(bitmap);canvas.drawColor(Color.WHITE)
        val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.BLACK;textSize=80f}
        canvas.drawText("SYNTHETIC GALLERY $number",60f,200f,paint);canvas.drawText("ClawMate 2026",60f,350f,paint)
        return java.io.ByteArrayOutputStream().use{assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG,85,it));bitmap.recycle();it.toByteArray()}
    }
    private fun tap(bounds: Rect) {
        val time=SystemClock.uptimeMillis();val down=MotionEvent.obtain(time,time,MotionEvent.ACTION_DOWN,bounds.centerX().toFloat(),bounds.centerY().toFloat(),0)
        val up=MotionEvent.obtain(time,time+80,MotionEvent.ACTION_UP,bounds.centerX().toFloat(),bounds.centerY().toFloat(),0)
        down.source=InputDevice.SOURCE_TOUCHSCREEN;up.source=InputDevice.SOURCE_TOUCHSCREEN
        try{instrumentation.uiAutomation.injectInputEvent(down,true);instrumentation.uiAutomation.injectInputEvent(up,true)}finally{down.recycle();up.recycle()}
        Thread.sleep(180)
    }
    private fun click(text: String): Boolean {
        val bitmap=instrumentation.uiAutomation.takeScreenshot() ?: return false
        val recognizer=TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        try {
            val result=Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap,0)),15,TimeUnit.SECONDS)
            val line=result.textBlocks.flatMap{it.lines}.firstOrNull{val value=it.text.replace(Regex("\\s+"),"");value==text || text=="图库分析" && value.contains(text)}
            line?.boundingBox?.let{bounds->evidence("galleryTap","$text at ${bounds.toShortString()}, screen ${bitmap.width}x${bitmap.height}");tap(Rect(bounds));return true};return false
        }finally{recognizer.close();bitmap.recycle()}
    }
    private fun waitClick(text: String) {
        val deadline=SystemClock.elapsedRealtime()+30000
        while(SystemClock.elapsedRealtime()<deadline){if(click(text))return;Thread.sleep(200)}
        instrumentation.uiAutomation.takeScreenshot()?.let{bitmap->File(context.cacheDir,"migration-gallery-scan-failure.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()}
        error("Gallery control missing: $text")
    }
    private fun awaitState(test: (JSONObject)->Boolean): JSONObject {
        val deadline=SystemClock.elapsedRealtime()+45000
        while(SystemClock.elapsedRealtime()<deadline){val value=runCatching{JSONObject(checkpoint.readText())}.getOrNull();if(value!=null&&test(value))return value;Thread.sleep(100)}
        error("Gallery checkpoint did not reach expected state")
    }
    private fun setup() {
        check(!backup.exists()){"Recover previous gallery scan backup before rerun"}
        check(!File(root,"items/$day").exists()&&!File(root,"daily-log/$day").exists()&&!File(root,"embeddings/$day").exists()){"Preserve existing gallery date"}
        check(backup.mkdirs());val present=JSONArray()
        originals().forEachIndexed{index,file->present.put(file.exists());if(file.exists())File(backup,"$index.bin").writeBytes(file.readBytes())}
        val manifest=JSONObject().put("originals",present).put("assets",JSONArray())
        File(backup,"manifest.json").writeText(manifest.toString())
        val settings=originals()[0];settings.parentFile!!.mkdirs()
        settings.writeText("""<?xml version="1.0" encoding="utf-8"?><preferences>
          <string key="firstLaunchOnboardingVersion">2026-07-core-preview-usage-guide</string><bool key="androidCloudAccessGranted" value="true"/>
          <string key="galleryApiBaseUrl">https://gallery-scan-fixture.invalid</string><string key="galleryApiKey"></string><string key="galleryModel">synthetic-unavailable-model</string>
          <string key="gallerySkipOcr">false</string><string key="galleryUseOcrKeywordFilter">false</string><string key="galleryOcrConcurrency">1</string>
          <string key="galleryModelConcurrency">1</string><string key="galleryEmbeddingConcurrency">1</string><string key="galleryParallelFieldRequests">false</string>
          <string key="galleryEmbeddingMode">huawei_local</string><string key="galleryAmapWebKey"></string></preferences>""")
        originals()[1].writeText("""<?xml version="1.0" encoding="utf-8"?><preferences><string key="choice">accepted</string><string key="version">2026-10-26-android-v1</string></preferences>""")
        val items=JSONArray();val assets=manifest.getJSONArray("assets")
        repeat(9){number->
            val bytes=jpeg(number);val hash=sha(bytes);val asset=File(root,"android-images/$hash.jpg");check(!asset.exists())
            assets.put(hash);File(backup,"manifest.json").writeText(manifest.toString());asset.parentFile!!.mkdirs();asset.writeBytes(bytes)
            val id="gallery_android_$hash";val uri="file://"+asset.absolutePath
            items.put(JSONObject().put("id",id).put("uri",uri).put("date",day).put("status",if(number==0)"done" else "queued").put("error","").put("skipReason",""))
            if(number==0){
                val result=JSONObject().put("id",id).put("uri",uri).put("date",day).put("title","合成的既有完成项").put("category","其他")
                    .put("summary","受控已完成回执，仅用于恢复测试").put("details",JSONArray()).put("tags",JSONArray()).put("keywords",JSONArray()).put("ocrText","SYNTHETIC")
                    .put("rawModelText","").put("embeddingRef","").put("embeddingStatus","disabled").put("embeddingDimension",0).put("error","").put("createdAt",1)
                val file=File(root,"items/$day/$id.json");file.parentFile!!.mkdirs();file.writeText(result.toString());manifest.put("completedHash",sha(file.readBytes())).put("completedId",id)
            }
        }
        File(backup,"manifest.json").writeText(manifest.toString())
        checkpoint.writeText(JSONObject().put("id","gallery_scan_device_fixture").put("source","android-picker").put("status","running")
            .put("createdAt",1).put("updatedAt",1).put("total",9).put("processed",1).put("failed",0).put("skipped",0).put("items",items).toString())
    }
    private fun assertCompletedUnchanged() {
        val manifest=JSONObject(File(backup,"manifest.json").readText())
        assertEquals(manifest.getString("completedHash"),sha(File(root,"items/$day/${manifest.getString("completedId")}.json").readBytes()))
        assertEquals("done",checkpoint.let{JSONObject(it.readText()).getJSONArray("items").getJSONObject(0).getString("status")})
    }
    private fun cleanup() {
        if(!backup.exists())return
        val manifest=JSONObject(File(backup,"manifest.json").readText())
        val assets=manifest.getJSONArray("assets")
        for(i in 0 until assets.length()){val hash=assets.getString(i);check(Regex("[0-9a-f]{64}").matches(hash));File(root,"android-images/$hash.jpg").delete()}
        for(category in listOf("items","daily-log","embeddings")) {
            val directory=File(root,"$category/$day").canonicalFile
            check(directory.parentFile==File(root,category).canonicalFile && directory.name==day);if(directory.exists())directory.deleteRecursively()
        }
        originals().forEachIndexed{index,file->if(manifest.getJSONArray("originals").getBoolean(index))file.writeBytes(File(backup,"$index.bin").readBytes())else file.delete()}
        check(backup.canonicalFile.parentFile==context.cacheDir.canonicalFile)
        backup.listFiles().orEmpty().forEach{it.delete()};check(backup.delete())
    }
    @Test fun phaseOnePauseActualQueueAndPreserveForProcessRestart() {
        var preserved=false
        try {
            setup()
            ActivityScenario.launch(SharedActivity::class.java).use{scenario->
                scenario.onActivity{it.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)}
                waitClick("汇总");waitClick("图库分析")
                awaitState{it.optString("status")=="paused"};assertCompletedUnchanged()
                waitClick("继续分析");waitClick("开始分析")
                // Keep the target visible while not resumed: fully stopping the
                // instrumented process lets vendor freezer suspend the test thread too.
                scenario.moveToState(Lifecycle.State.STARTED)
                val paused=awaitState{it.optString("status")=="paused"}
                assertTrue((0 until paused.getJSONArray("items").length()).any{paused.getJSONArray("items").getJSONObject(it).optString("status")=="queued"})
                assertEquals(1,paused.getInt("processed"));assertCompletedUnchanged()
                scenario.moveToState(Lifecycle.State.RESUMED)
            }
            val manifest=JSONObject(File(backup,"manifest.json").readText()).put("phaseOnePid",android.os.Process.myPid())
            File(backup,"manifest.json").writeText(manifest.toString());preserved=true
            evidence("galleryPause","Actual ArkTS resume confirmation, real native OCR/model pipeline background pause; completed receipt preserved and queued work retained for external process restart")
        }finally{if(!preserved)cleanup()}
    }
    @Test fun phaseTwoResumeAfterExternalProcessRestartAndCleanup() {
        check(backup.exists()){"Run phase one then force-stop before phase two"}
        try {
            assertNotEquals(JSONObject(File(backup,"manifest.json").readText()).getInt("phaseOnePid"),android.os.Process.myPid())
            ActivityScenario.launch(SharedActivity::class.java).use{scenario->
                scenario.onActivity{it.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)}
                waitClick("汇总");waitClick("图库分析");assertCompletedUnchanged()
                waitClick("继续分析");waitClick("开始分析")
                val failed=awaitState{it.optString("status")=="paused"&&it.optInt("failed")==8}
                assertEquals(1,failed.getInt("processed"));assertEquals(9,failed.getInt("total"));assertCompletedUnchanged()
                assertFalse(failed.toString().contains("\"status\":\"completed\""))
                evidence("galleryProcessRestart","New app process resumed only unfinished items; actual .invalid provider failures remain retriable, prior completion unchanged, no fake completion")
            }
        }finally{Thread.sleep(500);cleanup()}
    }
    @Test fun nativeSnapshotFingerprintAndPrivateCopyDigestAgree() {
        val source=File.createTempFile("gallery-digest-",".jpg",context.cacheDir).apply{writeBytes(jpeg(99))}
        val copy=File.createTempFile("gallery-copy-",".jpg",context.cacheDir)
        try {
            ActivityScenario.launch(SharedActivity::class.java).use{scenario->
                lateinit var bridge:PlatformBridge
                scenario.onActivity{activity->bridge=SharedActivity::class.java.getDeclaredField("platform").apply{isAccessible=true}.get(activity) as PlatformBridge}
                val id=bridge.imagePrepareStart(JSONArray().put(source.toURI().toString()).toString())
                var value=JSONObject();val deadline=SystemClock.elapsedRealtime()+30000
                do{value=JSONObject(bridge.imagePreparePoll(id));if(value.getString("status")!="running")break;Thread.sleep(50)}while(SystemClock.elapsedRealtime()<deadline)
                assertEquals("done",value.getString("status"));val bytes=Base64.getDecoder().decode(value.getJSONArray("images").getString(0).substringAfter(','))
                val hash=value.getJSONArray("fingerprints").getString(0);assertEquals(sha(bytes),hash)
                copy.writeBytes(bytes);assertEquals(hash,bridge.imageDigest(copy.toURI().toString()));copy.appendText("corrupt");assertNotEquals(hash,bridge.imageDigest(copy.toURI().toString()))
                evidence("galleryDigest","Actual prepared JPEG hash, persistent private-copy verification and tampering detection passed")
            }
        }finally{source.delete();copy.delete()}
    }
}
