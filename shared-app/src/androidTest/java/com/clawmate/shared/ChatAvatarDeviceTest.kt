package com.clawmate.shared

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.Bundle
import android.util.Xml
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import java.io.File
import java.io.StringReader
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.xmlpull.v1.XmlPullParser

/** Synthetic colored private caches test actual rendering; no model/provider quality claim. */
@RunWith(AndroidJUnit4::class)
class ChatAvatarDeviceTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context=instrumentation.targetContext
    private fun tap(bounds: Rect) {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("input tap ${bounds.centerX()} ${bounds.centerY()}")).use{it.readBytes()}
        Thread.sleep(300)
    }
    private fun find(node: AccessibilityNodeInfo?, label: String): AccessibilityNodeInfo? {
        if(node==null)return null
        if(node.isVisibleToUser && (node.contentDescription?.toString()==label || node.text?.toString()==label))return node
        for(i in 0 until node.childCount)find(node.getChild(i),label)?.let{return it}
        return null
    }
    private fun click(text: String): Boolean {
        if(text=="默认形象" || text=="我的分身") {
            if(android.os.Build.VERSION.SDK_INT>=33)instrumentation.uiAutomation.clearCache()
            val label=if(text=="我的分身") "聊天形象：我的分身，点击切回默认助手" else "聊天形象：默认助手，点击使用我的分身"
            find(instrumentation.uiAutomation.rootInActiveWindow,label)?.let {node->tap(Rect().also{node.getBoundsInScreen(it)});return true}
        }
        val bitmap=instrumentation.uiAutomation.takeScreenshot() ?: return false
        val recognizer=TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        try {
            val result=Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap,0)),15,TimeUnit.SECONDS)
            val bounds=result.textBlocks.flatMap{it.lines}.firstOrNull{it.text.replace(Regex("\\s+"),"")==text}?.boundingBox ?: return false
            tap(bounds);return true
        } finally {recognizer.close();bitmap.recycle()}
    }
    private fun waitFor(description: String, predicate: ()->Boolean) {
        repeat(60){if(predicate())return;Thread.sleep(200)}
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(context.cacheDir,"migration-chat-avatar-failure.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
            bitmap.recycle()
        }
        error(description)
    }
    private fun waitClick(text: String) = waitFor("Rendered control missing: $text"){click(text)}
    private fun coloredPixels(green: Boolean): Int {
        val bitmap=instrumentation.uiAutomation.takeScreenshot() ?: return 0
        try {
            val pixels=IntArray(bitmap.width*bitmap.height)
            bitmap.getPixels(pixels,0,bitmap.width,0,0,bitmap.width,bitmap.height)
            val color=if(green)Color.rgb(14,232,83)else Color.rgb(226,15,193)
            return pixels.count { kotlin.math.abs(Color.red(it)-Color.red(color))<=8 &&
                kotlin.math.abs(Color.green(it)-Color.green(color))<=8 && kotlin.math.abs(Color.blue(it)-Color.blue(color))<=8 }
        } finally {bitmap.recycle()}
    }
    private fun mascotPixels(): Int {
        val bitmap=instrumentation.uiAutomation.takeScreenshot() ?: return 0
        try {
            var count=0
            // The original cat's purple ears/body shading in the avatar column;
            // the synthetic green/pink caches and blue/purple text cannot match.
            for(y in bitmap.height/8 until bitmap.height*4/5)for(x in 0 until bitmap.width/3) {
                val pixel=bitmap.getPixel(x,y);val r=Color.red(pixel);val g=Color.green(pixel);val b=Color.blue(pixel)
                if(r>130 && g>90 && b>160 && b>r+20 && r>g+5)count++
            }
            return count
        } finally {bitmap.recycle()}
    }
    private fun tapRenderedTrace(): Boolean {
        val bitmap=instrumentation.uiAutomation.takeScreenshot() ?: return false
        try {
            val pixels=IntArray(bitmap.width*bitmap.height)
            bitmap.getPixels(pixels,0,bitmap.width,0,0,bitmap.width,bitmap.height)
            val groups=mutableListOf<Rect>();var active: Rect?=null
            for(y in 0 until bitmap.height) {
                var left=bitmap.width;var right=0
                for(x in 0 until bitmap.width) {
                    val pixel=pixels[y*bitmap.width+x]
                    if(kotlin.math.abs(Color.red(pixel)-226)<=8 && kotlin.math.abs(Color.green(pixel)-15)<=8 && kotlin.math.abs(Color.blue(pixel)-193)<=8){left=minOf(left,x);right=maxOf(right,x)}
                }
                if(right-left>=3) {
                    if(active==null)active=Rect(left,y,right+1,y+1) else {active.left=minOf(active.left,left);active.right=maxOf(active.right,right+1);active.bottom=y+1}
                } else if(active!=null) {groups.add(active);active=null}
            }
            active?.let{groups.add(it)}
            val visible=groups.filter{it.width()>15 && it.height()>15}
            if(visible.size<2)return false
            tap(visible.last());return true // Lowest of the two visibly rendered synthetic avatars.
        } finally {bitmap.recycle()}
    }
    private fun snapshot(name: String) {
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(context.cacheDir,"migration-chat-avatar-$name.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
            bitmap.recycle()
        }
    }
    private fun image(file: File, color: Int) {
        file.parentFile!!.mkdirs()
        val bitmap=Bitmap.createBitmap(128,160,Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawRoundRect(24f,8f,104f,156f,32f,32f,Paint().apply{this.color=color})
        file.outputStream().use{assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))};bitmap.recycle()
    }
    private fun style(settings: File): String {
        val parser=Xml.newPullParser();parser.setInput(StringReader(settings.readText()))
        while(parser.next()!=XmlPullParser.END_DOCUMENT) {
            if(parser.eventType==XmlPullParser.START_TAG && parser.getAttributeValue(null,"key")=="androidChatAvatarStyle")
                return parser.getAttributeValue(null,"value") ?: parser.nextText()
        }
        return ""
    }
    private fun message(id: String, role: String, kind: String, text: String)=JSONObject()
        .put("id",id).put("role",role).put("kind",kind).put("content",text).put("createdAt",1)
        .put("thinkingContent","").put("thinkingCollapsed",true).put("collapsed",true)
        .put("trace",if(kind=="task_trace")JSONArray().put("合成任务步骤")else JSONArray())

    @Test fun renderedWelcomeMessagesTraceAndRecreationHonorLocalChoiceAndFallback() {
        val id="migration-chat-avatar-${android.os.SystemClock.elapsedRealtime()}"
        val avatar=File(context.filesDir,"files/workflows/avatar")
        val state=File(avatar,"state.json")
        val settings=File(context.filesDir,"preference/shared_frontend_settings")
        val debug=File(context.filesDir,"preference/debug_settings")
        val sessions=File(context.filesDir,"files/chat-sessions")
        val index=File(sessions,"session-index.json")
        val session=File(sessions,"sessions/$id.json")
        val originals=listOf(settings,state,index,debug).associateWith{if(it.exists())it.readBytes()else null}
        val backup=File(context.cacheDir,"migration-chat-avatar-backup")
        check(!backup.exists());check(backup.mkdirs())
        originals.entries.forEachIndexed{i,e->e.value?.let{File(backup,"$i.bin").writeBytes(it)}}
        val source=File(avatar,"assets/$id.png")
        val full=File(avatar,"assets/$id-foreground.png")
        val thumb=File(avatar,"assets/$id-message-foreground.png")
        val version=File(avatar,"versions/$id.json")
        val corruptId="$id-corrupt"
        val corruptFull=File(avatar,"assets/$corruptId-foreground.png")
        val corruptThumb=File(avatar,"assets/$corruptId-message-foreground.png")
        val corruptVersion=File(avatar,"versions/$corruptId.json")
        try {
            settings.parentFile!!.mkdirs();version.parentFile!!.mkdirs();index.parentFile!!.mkdirs();session.parentFile!!.mkdirs()
            settings.writeText("""<?xml version="1.0" encoding="utf-8"?><preferences><string key="firstLaunchOnboardingVersion">2026-07-core-preview-usage-guide</string><string key="androidChatAvatarStyle">mascot</string><bool key="appDarkMode" value="false"/></preferences>""")
            // Original UI intentionally hides task traces outside Debug mode.
            debug.writeText("""<?xml version="1.0" encoding="utf-8"?><preferences><bool key="debugEnabled" value="true"/></preferences>""")
            image(source,Color.rgb(14,232,83));image(full,Color.rgb(14,232,83));image(thumb,Color.rgb(226,15,193))
            version.writeText(JSONObject().put("version_id",id).put("image_path","assets/$id.png").put("created_at","2026-10-28T00:00:00")
                .put("mode","text_to_image").put("provider","synthetic-render-fixture").put("model","no-provider")
                .put("generation_params",JSONObject()).put("prompt_snapshot","").put("profile_snapshot",JSONObject())
                .put("profile_snapshot_hash","").put("consent_snapshot",JSONObject()).toString())
            state.writeText(JSONObject().put("current_version_id",id).put("status","current").put("profile_snapshot_hash","")
                .put("last_error_code","").put("last_error_message","").put("reference_photo_path","").put("updated_at","").toString())
            val originalVersion=version.readBytes()
            index.writeText("""{"sessions":[]}""")
            ActivityScenario.launch(SharedActivity::class.java).use { scenario ->
                waitClick("我的");waitClick("聊天");assertEquals(0,coloredPixels(true));waitClick("默认形象")
                waitFor("Local preference not flushed"){style(settings)=="personal"}
                instrumentation.sendStatus(2,Bundle().apply{putString("chatAvatarSelection","personal persisted; initial green pixels=${coloredPixels(true)}")})
                waitFor("Full-size custom welcome image not rendered"){coloredPixels(true)>500}
                scenario.recreate();waitClick("聊天")
                waitFor("Choice lost after Activity recreation"){coloredPixels(true)>500}
                val now=System.currentTimeMillis()
                session.writeText(JSONObject().put("id",id).put("title","合成分身测试").put("mode","cloud_agent").put("createdAt",now).put("updatedAt",now)
                    .put("messages",JSONArray().put(message("u","user","chat","合成用户消息")).put(message("a","assistant","chat","合成助手消息"))
                        .put(message("trace","assistant","task_trace","合成过程"))).toString())
                index.writeText(JSONObject().put("sessions",JSONArray().put(JSONObject().put("id",id).put("title","合成分身测试").put("mode","cloud_agent")
                    .put("fileName","$id.json").put("createdAt",now).put("updatedAt",now).put("messageCount",3).put("lastMessagePreview","合成过程"))).toString())
                scenario.recreate();waitClick("聊天")
                waitFor("Message thumbnail not rendered"){coloredPixels(false)>100}
                assertEquals("Full-size hero must not be used as message thumbnail",0,coloredPixels(true))
                instrumentation.sendStatus(2,Bundle().apply{putString("chatAvatarMessages","actual thumbnail pixels=${coloredPixels(false)}, full-image pixels=${coloredPixels(true)}")})
                snapshot("custom")
                waitFor("Static trace avatar missing") {
                    if(android.os.Build.VERSION.SDK_INT>=33)instrumentation.uiAutomation.clearCache()
                    val node=find(instrumentation.uiAutomation.rootInActiveWindow,"个人生成形象，点击查看任务过程")
                    if(node==null)tapRenderedTrace() else {tap(Rect().also{node.getBoundsInScreen(it)});true}
                }
                waitFor("Trace avatar tap did not persist one expansion") {
                    val rows=JSONObject(session.readText()).getJSONArray("messages")
                    (0 until rows.length()).map{rows.getJSONObject(it)}.any{it.optString("id")=="trace" && !it.optBoolean("collapsed",true)}
                }
                waitClick("我的分身");waitFor("Choice did not switch back"){style(settings)=="mascot"}
                waitFor("Custom thumbnail remained after default selection"){coloredPixels(false)==0}
                waitFor("Default mascot pixels missing"){mascotPixels()>100}
                // Existing cache files may become unreadable. Keep selection but visibly fall back.
                waitClick("默认形象")
                waitFor("Personal choice not saved for fallback case"){style(settings)=="personal"}
                corruptFull.writeText("invalid-png");corruptThumb.writeText("invalid-png")
                corruptVersion.writeText(JSONObject(version.readText()).put("version_id",corruptId).toString())
                state.writeText(JSONObject(state.readText()).put("current_version_id",corruptId).toString())
                scenario.recreate();waitClick("聊天")
                Thread.sleep(800)
                assertEquals(0,coloredPixels(false));assertEquals(0,coloredPixels(true))
                snapshot("fallback")
                if(android.os.Build.VERSION.SDK_INT>=33)instrumentation.uiAutomation.clearCache()
                val fallbackLabelFound=find(instrumentation.uiAutomation.rootInActiveWindow,"ClawMate 卡通猫助手，点击查看任务过程")!=null
                // ArkUI-X conditional image virtual nodes can remain stale. Record this
                // accessibility gap separately from actual bitmap fallback verification.
                waitFor("Missing-image mascot bitmap fallback not rendered"){mascotPixels()>100}
                instrumentation.sendStatus(2,Bundle().apply{putString("chatAvatarFallback","rendered mascot pixels=${mascotPixels()}, updated accessibility label=$fallbackLabelFound; dynamic TalkBack semantics remain separately tracked")})
                assertArrayEquals(originalVersion,version.readBytes())
                instrumentation.sendStatus(2,Bundle().apply{putString("chatAvatar","Actual ArkTS welcome full image, message thumbnail, task-trace tap, settings flush, Activity recreation, default switch and corrupt-image mascot fallback passed; synthetic caches only")})
            }
        } finally {
            originals.forEach{(file,bytes)->if(bytes==null)file.delete()else file.writeBytes(bytes)}
            listOf(source,full,thumb,version,session,corruptFull,corruptThumb,corruptVersion).forEach{it.delete()}
            backup.listFiles().orEmpty().forEach{it.delete()};check(backup.delete())
        }
    }
}
