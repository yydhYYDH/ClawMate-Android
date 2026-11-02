package com.clawmate.shared

import android.graphics.*
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
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
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class WorkflowModelDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun evidence(key: String, text: String) = instrumentation.sendStatus(2, Bundle().apply { putString(key,text) })
    private fun withBridge(test: (PlatformBridge) -> Unit) = ActivityScenario.launch(SharedActivity::class.java).use { scenario ->
        lateinit var bridge: PlatformBridge
        scenario.onActivity { activity -> bridge = SharedActivity::class.java.getDeclaredField("platform").apply { isAccessible=true }.get(activity) as PlatformBridge }
        test(bridge)
    }
    private fun workflow(image: String, mode: String = "summary") = JSONObject().put("metadata",JSONObject().put("name","视觉移植测试"))
        .put("steps",JSONArray().put(JSONObject().put("id",1).put("type","tool").put("tool_name","vlm_qa")
            .put("inputs",JSONObject().put("mode",mode).put("question","只读取合成图片上的测试文字").put("image",image)))).toString()
    private fun poll(bridge: PlatformBridge, id: String, ready: (JSONObject)->Boolean): JSONObject {
        val deadline=SystemClock.elapsedRealtime()+15000
        while(SystemClock.elapsedRealtime()<deadline) {
            val value=JSONObject(bridge.workflowPoll(id)); if(ready(value)) return value; Thread.sleep(50)
        }
        error("Native workflow contract did not settle")
    }
    @Test fun realBridgePreservesModelOutputAndRejectsReplay() = withBridge { bridge ->
        val runs=File(context.filesDir,"files/workflows/runs"); val before=runs.list()?.toSet() ?: emptySet()
        val id=bridge.workflowStart(workflow("file:///fixture.jpg"))
        val request=poll(bridge,id){it.has("tool")}.getJSONObject("tool")
        assertEquals("summary",request.getString("mode")); assertTrue(bridge.workflowToolPending(id,request.getString("id")))
        assertFalse(bridge.workflowToolReply(id,"foreign","{}"))
        assertTrue(bridge.workflowToolReply(id,request.getString("id"),JSONObject().put("response","受控协议夹具的真实返回文本").toString()))
        val result=JSONObject(poll(bridge,id){it.getString("status")=="done"}.getString("result"))
        assertEquals("success",result.getString("status"));assertEquals("受控协议夹具的真实返回文本",result.getString("resultSummary"))
        assertEquals("vlm_qa",result.getJSONArray("steps").getJSONObject(0).getJSONObject("output").getString("tool_name"))
        assertFalse(bridge.workflowToolReply(id,request.getString("id"),"{}"));assertEquals(before,runs.list()?.toSet() ?: emptySet<String>())
        val escapedId=bridge.workflowStart(workflow("file:///fixture.jpg","answer"))
        val escapedRequest=poll(bridge,escapedId){it.has("tool")}.getJSONObject("tool").getString("id")
        val escaped="\"".repeat(16000)
        assertTrue(bridge.workflowToolReply(escapedId,escapedRequest,JSONObject().put("response",escaped).toString()))
        val escapedResult=JSONObject(poll(bridge,escapedId){it.getString("status")=="done"}.getString("result"))
        assertEquals(escaped,escapedResult.getJSONArray("steps").getJSONObject(0).getJSONObject("output").getString("response"))
        evidence("modelMailbox","Actual Android bridge/runner response, output provenance, replay rejection and no native file writer; controlled reply, no inference claim")
    }
    @Test fun cancellingPendingModelRejectsLateReply() = withBridge { bridge ->
        val id=bridge.workflowStart(workflow("file:///fixture.jpg")); val request=poll(bridge,id){it.has("tool")}.getJSONObject("tool").getString("id")
        bridge.workflowCancel(id); assertFalse(bridge.workflowToolPending(id,request))
        assertFalse(bridge.workflowToolReply(id,request,"{}")); assertEquals("missing",JSONObject(bridge.workflowPoll(id)).getString("status"))
        assertThrows(IllegalArgumentException::class.java){bridge.imagePrepareStart("[\"file:///etc/hosts\"]")}
        evidence("modelCancel","Actual pending wait cancellation rejects late replies; image preparation refuses paths outside private roots")
    }
    private fun click(text: String): Boolean {
        if (DeviceTestTimeouts.multiplier > 1 && text == "任务") return DeviceTestTimeouts.clickNavigation(text)
        val bitmap=instrumentation.uiAutomation.takeScreenshot() ?: return false
        val recognizer=TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        try {
            val result=Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap,0)),DeviceTestTimeouts.seconds(15),TimeUnit.SECONDS)
            val lines=result.textBlocks.flatMap { it.lines }
            val normalize: (String)->String = { it.replace(Regex("\\s+"),"") }
            // Recorded PKB110 OCR confusion for the blue 送 glyph. This is a
            // fixture-driver alias only; actual failed-run receipts stay required.
            val labels=if(text=="发送图片")setOf(text,"发迭图片")else setOf(text)
            var bounds=lines.firstOrNull { normalize(it.text) in labels }?.boundingBox
            // ML Kit can merge the two dialog buttons into one line. Use exact
            // consecutive elements, and prefer the bottom button over its title.
            if(bounds==null && text in listOf("取消","发送图片")) {
                val candidates=mutableListOf<Rect>()
                for(line in lines) for(start in line.elements.indices) {
                    var label=""; val box=Rect()
                    for(end in start until line.elements.size) {
                        val element=line.elements[end];val part=element.boundingBox ?: break
                        label+=normalize(element.text);box.union(part)
                        if(label in labels){candidates.add(Rect(box));break}
                        if(label.length>=text.length)break
                    }
                }
                bounds=candidates.maxByOrNull { it.centerY() }
            }
            bounds ?: return false
            evidence("workflowRenderedClick",text+" "+bounds.toShortString())
            val time=SystemClock.uptimeMillis()
            val down=MotionEvent.obtain(time,time,MotionEvent.ACTION_DOWN,bounds.centerX().toFloat(),bounds.centerY().toFloat(),0)
            val up=MotionEvent.obtain(time,time+80,MotionEvent.ACTION_UP,bounds.centerX().toFloat(),bounds.centerY().toFloat(),0)
            down.source=InputDevice.SOURCE_TOUCHSCREEN;up.source=InputDevice.SOURCE_TOUCHSCREEN
            try {instrumentation.uiAutomation.injectInputEvent(down,true);instrumentation.uiAutomation.injectInputEvent(up,true)}finally{down.recycle();up.recycle()}
            Thread.sleep(300);return true
        } finally {recognizer.close();bitmap.recycle()}
    }
    private fun waitClick(text: String) {
        val deadline=SystemClock.elapsedRealtime()+DeviceTestTimeouts.seconds(30)*1000
        var attempts=0
        while(SystemClock.elapsedRealtime()<deadline){
            if(click(text))return
            attempts++
            if(text=="运行" && attempts in listOf(3,6)) {
                android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("input swipe 540 1800 540 800 400")).use{it.readBytes()}
            }
            Thread.sleep(300)
        }
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(context.cacheDir,"migration-workflow-model-failure.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
            val recognizer=TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
            try {
                val result=Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap,0)),DeviceTestTimeouts.seconds(15),TimeUnit.SECONDS)
                // This screenshot contains this test's synthetic fixture only.
                evidence("workflowMissingControlOCR",result.textBlocks.flatMap{it.lines}.filter{(it.boundingBox?.centerY() ?: 0)>bitmap.height/2}.takeLast(12).joinToString(" | "){it.text.take(80)+" "+it.boundingBox?.toShortString()})
            } finally { recognizer.close();bitmap.recycle() }
        }
        error("Rendered workflow control missing: $text")
    }
    private fun awaitFailure(index: File, id: String, previous: String = ""): JSONObject {
        val deadline=SystemClock.elapsedRealtime()+45000
        while(SystemClock.elapsedRealtime()<deadline) {
            val tasks=runCatching{JSONObject(index.readText()).getJSONArray("tasks")}.getOrNull()
            val task=(0 until (tasks?.length() ?: 0)).map{tasks!!.getJSONObject(it)}.find{it.optString("id")==id}
            if(task?.optString("lastStatus")=="failed" && task.optString("lastMessage")!=previous)return task
            Thread.sleep(100)
        }
        error("Actual ArkTS workflow did not persist the expected failure")
    }
    @Test fun actualTaskConfirmationCancellationAndUnavailableModelNeverWriteSuccessfulSummary() {
        val root=File(context.filesDir,"files/workflows");val index=File(root,"task-index.json")
        val settings=File(context.filesDir,"preference/shared_frontend_settings");val privacy=File(context.filesDir,"preference/privacy_consent")
        val originals=listOf(settings,privacy,index).associateWith{if(it.exists())it.readBytes()else null}
        val backup=File(context.cacheDir,"migration-workflow-model-backup");check(!backup.exists());check(backup.mkdirs())
        originals.entries.forEachIndexed{position,entry->entry.value?.let{File(backup,"$position.bin").writeBytes(it)}}
        val id="migration-vlm-${SystemClock.elapsedRealtime()}";val config=File(root,"configs/$id.json");val image=File(context.cacheDir,"$id.png")
        val beforeRuns=File(root,"runs").list()?.toSet() ?: emptySet()
        val beforeLogs=File(root,"daily-log").walkTopDown().filter{it.isFile}.associate{it.absolutePath to it.readBytes().contentHashCode()}
        try {
            val bitmap=Bitmap.createBitmap(240,120,Bitmap.Config.ARGB_8888);val canvas=Canvas(bitmap);canvas.drawColor(Color.WHITE)
            canvas.drawText("SYNTHETIC 2026",8f,70f,Paint().apply{color=Color.BLACK;textSize=24f})
            image.outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
            val raw=workflow(image.toURI().toString());config.parentFile!!.mkdirs();config.writeText(raw)
            val fixture=JSONObject().put("id",id).put("sceneId",0).put("icon","🧪").put("iconColor","#276EF1").put("title","视觉移植测试")
                .put("source","合成测试").put("enabled",true).put("configFileName",config.name).put("workflowJson",raw)
                .put("lastStatus","").put("lastRun","").put("lastMessage","").put("isUserModified",true)
            index.writeText(JSONObject().put("tasks",JSONArray().put(fixture)).toString())
            settings.parentFile!!.mkdirs()
            settings.writeText("""<?xml version="1.0" encoding="utf-8"?><preferences>
              <string key="firstLaunchOnboardingVersion">2026-07-core-preview-usage-guide</string><bool key="androidCloudAccessGranted" value="true"/>
              <string key="cloudDeciderBaseUrl">https://workflow-vision-fixture.invalid</string><string key="cloudDeciderModel">synthetic-unavailable-model</string>
              <string key="cloudDeciderApiKey"></string><string key="cloudApiKey"></string></preferences>""")
            privacy.writeText("""<?xml version="1.0" encoding="utf-8"?><preferences><string key="choice">accepted</string><string key="version">2026-10-26-android-v1</string></preferences>""")
            ActivityScenario.launch(SharedActivity::class.java).use {
                val confirmationDelay=InstrumentationRegistry.getArguments().getString("workflowConfirmationDelayMs","0").toLong()
                require(confirmationDelay in 0..75000) { "workflowConfirmationDelayMs must be between 0 and 75000" }
                waitClick("任务");waitClick("配置任务");waitClick("运行")
                if(confirmationDelay>0)Thread.sleep(confirmationDelay)
                waitClick("取消")
                val cancelled=awaitFailure(index,id)
                evidence("workflowCancelReceipt",cancelled.getString("lastMessage").take(1000))
                assertTrue(cancelled.getString("lastMessage"),cancelled.getString("lastMessage").contains("取消"))
                if(confirmationDelay>0) {
                    assertTrue(cancelled.getString("lastMessage"),cancelled.getString("lastMessage").contains("所属任务已结束"))
                    assertEquals(beforeRuns,File(root,"runs").list()?.toSet() ?: emptySet<String>())
                    assertEquals(beforeLogs,File(root,"daily-log").walkTopDown().filter{it.isFile}.associate{it.absolutePath to it.readBytes().contentHashCode()})
                    evidence("workflowExpiredConfirmation","Late image refusal and expired-task context preserved; no invented run result, summary or collection log")
                    return@use
                }
                waitClick("运行");waitClick("发送图片")
                val unavailable=awaitFailure(index,id,cancelled.getString("lastMessage"))
                evidence("workflowUnavailableReceipt",unavailable.getString("lastMessage").take(1000))
                assertFalse(unavailable.getString("lastMessage"),unavailable.getString("lastMessage").contains("取消"))
                val records=File(root,"runs").listFiles().orEmpty().filter{it.name !in beforeRuns}.mapNotNull{dir->
                    runCatching{JSONObject(File(dir,"run_summary.json").readText())}.getOrNull()
                }.filter{it.optString("workflow_file")==config.name}
                assertEquals(2,records.size);assertTrue(records.all{it.getString("status")=="failed" && it.getString("result_summary").isEmpty()})
                assertEquals(beforeLogs,File(root,"daily-log").walkTopDown().filter{it.isFile}.associate{it.absolutePath to it.readBytes().contentHashCode()})
                evidence("modelActualPage","Actual ArkTS task -> explicit model/image confirmation -> cancel or real .invalid transport failure -> persisted failed run, no successful summary")
            }
        } finally {
            Thread.sleep(500)
            File(root,"runs").listFiles().orEmpty().filter{it.name !in beforeRuns}.forEach{dir->
                val own=runCatching{JSONObject(File(dir,"run_summary.json").readText()).optString("workflow_file")==config.name}.getOrDefault(false)
                if(own && dir.canonicalFile.parentFile==File(root,"runs").canonicalFile)dir.deleteRecursively()
            }
            config.delete();image.delete()
            originals.forEach{(file,bytes)->if(bytes==null)file.delete()else file.writeBytes(bytes)}
            backup.listFiles().orEmpty().forEach{it.delete()};backup.delete()
        }
    }
}
