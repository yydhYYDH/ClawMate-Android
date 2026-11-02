package com.clawmate.shared

import android.graphics.BitmapFactory
import android.graphics.Rect
import android.os.Bundle
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class GuiScreenshotDeviceTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context=instrumentation.targetContext
    private fun fixture(test:(ActivityScenario<SharedActivity>,PlatformBridge)->Unit) {
        val settings=File(context.filesDir,"preference/shared_frontend_settings")
        val original=if(settings.exists())settings.readBytes()else null
        val backup=File(context.cacheDir,"migration-gui-settings.backup")
        check(!backup.exists());original?.let{backup.writeBytes(it)}
        try {
            settings.parentFile!!.mkdirs()
            settings.writeText("""<?xml version="1.0" encoding="utf-8"?><preferences><string key="firstLaunchOnboardingVersion">2026-07-core-preview-usage-guide</string><bool key="androidCloudAccessGranted" value="false"/></preferences>""")
            ActivityScenario.launch(SharedActivity::class.java).use { scenario ->
                lateinit var port:PlatformBridge
                scenario.onActivity{port=SharedActivity::class.java.getDeclaredField("platform").apply{isAccessible=true}.get(it) as PlatformBridge}
                Thread.sleep(1200);test(scenario,port)
            }
        } finally { if(original==null)settings.delete()else settings.writeBytes(original);backup.delete() }
    }
    private fun await(message:String, condition:()->Boolean) {
        val deadline=System.currentTimeMillis()+15000
        while(System.currentTimeMillis()<deadline){if(condition())return;Thread.sleep(80)}
        error(message)
    }
    private fun shot(port:PlatformBridge):JSONObject {
        val id=port.guiScreenshotStart();var result=JSONObject()
        await("Screenshot never settled"){result=JSONObject(port.guiScreenshotPoll(id));result.getString("status")!="running"}
        assertEquals("done",result.getString("status"));return result
    }
    @Test fun ownArkUiSurfaceProducesRealBoundedJpeg()=fixture{_,port->
        val value=shot(port);val bytes=java.util.Base64.getDecoder().decode(value.getString("dataUrl").substringAfter(','))
        assertTrue(bytes.size in 1..1000000)
        val bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.size) ?: error("No JPEG pixels")
        try{
            assertEquals(value.getInt("width"),bitmap.width);assertEquals(value.getInt("height"),bitmap.height)
            assertTrue(maxOf(bitmap.width,bitmap.height)<=1280)
            val colors=mutableSetOf<Int>()
            for(y in 0 until bitmap.height step 4)for(x in 0 until bitmap.width step 4)colors+=bitmap.getPixel(x,y)
            assertTrue("ArkUI Surface was blank",colors.size>100)
            val ocr=TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
            try{val text=Tasks.await(ocr.process(InputImage.fromBitmap(bitmap,0)),15,TimeUnit.SECONDS).text
                assertTrue("Own UI labels absent",text.contains("首页") || text.contains("ClawMate") || text.contains("概览"))
            }finally{ocr.close()}
        }finally{bitmap.recycle()}
        instrumentation.sendStatus(2,Bundle().apply{putString("guiScreenshot","Real own ArkUI Surface JPEG, <=1280 pixels, bounded bytes, nonblank pixels and on-device OCR; no image export/upload")})
    }
    @Test fun protectedBackgroundCancelledAndStaleCapturesNeverReturnPixels()=fixture{scenario,port->
        scenario.onActivity{it.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)}
        val protectedId=port.guiScreenshotStart();var result=JSONObject()
        await("Protected capture did not settle"){result=JSONObject(port.guiScreenshotPoll(protectedId));result.getString("status")!="running"}
        assertEquals("failed",result.getString("status"));assertEquals("",result.getString("dataUrl"))
        scenario.onActivity{it.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)}
        val cancelled=port.guiScreenshotStart();port.guiScreenshotCancel(cancelled)
        assertEquals("missing",JSONObject(port.guiScreenshotPoll(cancelled)).getString("status"))
        val stale=port.guiScreenshotStart();Thread.sleep(500);port.guiScreenshotDetach()
        val value=JSONObject(port.guiScreenshotPoll(stale));assertEquals("",value.getString("dataUrl"));assertNotEquals("done",value.getString("status"))
        android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_HOME")).use{it.readBytes()}
        await("Host never left foreground"){!port.guiPlanReady()}
        val blocked=runCatching{port.guiScreenshotStart()}.exceptionOrNull() ?: error("Background screenshot start was accepted")
        val cause=if(blocked is java.util.concurrent.ExecutionException)blocked.cause else blocked
        assertTrue(cause is IllegalStateException)
        assertEquals("请返回前台并解锁后截图",cause?.message)
    }
    private fun node(root:AccessibilityNodeInfo?,label:String):Rect? {
        if(root==null)return null
        if(root.isVisibleToUser && (root.text?.toString()==label || root.contentDescription?.toString()==label))return Rect().also{root.getBoundsInScreen(it)}
        for(i in 0 until root.childCount)node(root.getChild(i),label)?.let{return it};return null
    }
    private fun click(label:String):Boolean {
        if(android.os.Build.VERSION.SDK_INT>=33)instrumentation.uiAutomation.clearCache()
        var bounds=node(instrumentation.uiAutomation.rootInActiveWindow,label)
        if(bounds==null){
            val bitmap=instrumentation.uiAutomation.takeScreenshot() ?: return false
            val ocr=TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
            try{bounds=Tasks.await(ocr.process(InputImage.fromBitmap(bitmap,0)),15,TimeUnit.SECONDS).textBlocks.flatMap{it.lines}.firstOrNull{it.text.replace(Regex("\\s+"),"")==label}?.boundingBox}
            finally{ocr.close();bitmap.recycle()}
        }
        val b=bounds ?: return false
        android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("input tap ${b.centerX()} ${b.centerY()}")).use{it.readBytes()};Thread.sleep(180);return true
    }
    private fun previews()=(context.filesDir.walkTopDown()+context.cacheDir.walkTopDown()).filter{it.isFile && it.name.startsWith("gui-preview-")}.toList()
    @Test fun actualTaskPageCapturesPreviewAndClearDeletesItWithoutCloudAuthorization()=fixture{_,_->
        val before=previews().toSet()
        try{
            await("Task navigation missing"){click("任务")}
            await("GUI planning entry missing"){click("本应用截图与单步规划")}
            await("Own screenshot button missing"){click("截取本应用界面")}
            await("No preview file from actual page"){(previews().toSet()-before).isNotEmpty()}
            val image=(previews().toSet()-before).single();assertTrue(image.length()>1000)
            await("Clear preview control missing"){click("清除截图")}
            await("Preview cache survived clear"){!image.exists()}
            instrumentation.sendStatus(2,Bundle().apply{putString("guiPreviewPage","Actual task entry and ArkTS capture/preview/private file/clear; cloud authorization false, no provider request or execution")})
        }finally{(previews().toSet()-before).forEach{it.delete()}}
    }
}
