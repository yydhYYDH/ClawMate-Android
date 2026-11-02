package com.clawmate.shared

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Only a synthetic private reference and a reserved invalid endpoint; never picks user photos. */
@RunWith(AndroidJUnit4::class)
class AvatarReferenceDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun click(text: String): Boolean {
        if (text == "使用照片生成" || text == "取消") {
            val bitmap=instrumentation.uiAutomation.takeScreenshot() ?: return false
            val recognizer=TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
            try {
                val result=Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap,0)),15,TimeUnit.SECONDS)
                val bounds=result.textBlocks.flatMap{it.lines}.firstOrNull{it.text.replace(Regex("\\s+"),"")==text}?.boundingBox ?: return false
                instrumentation.sendStatus(2,Bundle().apply { putString("avatarTouch",text+" "+bounds.toShortString()) })
                android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("input tap ${bounds.centerX()} ${bounds.centerY()}")).use { it.readBytes() }
                Thread.sleep(300)
                return true
            } finally { recognizer.close();bitmap.recycle() }
        }
        if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return false
        val nodes = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        while (nodes.isNotEmpty()) {
            val node = nodes.removeFirst()
            if (node.text?.toString() == text && node.isVisibleToUser) {
                val bounds = Rect().also { node.getBoundsInScreen(it) }
                val screen = Rect().also { root.getBoundsInScreen(it) }
                if (bounds.intersect(screen) && !bounds.isEmpty) {
                    android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("input tap ${bounds.centerX()} ${bounds.centerY()}")).use { it.readBytes() }
                    Thread.sleep(300)
                    return true
                }
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let { nodes.add(it) }
        }
        return false
    }
    private fun waitClick(vararg labels: String) {
        repeat(40) {
            for (label in labels) if (click(label)) return
            val root = instrumentation.uiAutomation.rootInActiveWindow
            val nodes = ArrayDeque<AccessibilityNodeInfo>().apply { root?.let { add(it) } }
            while (nodes.isNotEmpty()) {
                val node = nodes.removeFirst()
                if (node.isScrollable && node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) break
                for (i in 0 until node.childCount) node.getChild(i)?.let { nodes.add(it) }
            }
            Thread.sleep(300)
        }
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(instrumentation.targetContext.cacheDir,"migration-avatar-failure.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
            bitmap.recycle()
        }
        error("Visible avatar action missing: ${labels.joinToString()}")
    }
    @Test fun savedReferenceReachesExplicitUploadConfirmationAndCancelPreservesVersions() {
        val context = instrumentation.targetContext
        val avatar = File(context.filesDir,"files/workflows/avatar")
        val state = File(avatar,"state.json")
        val settings = File(context.filesDir,"preference/shared_frontend_settings")
        val privacy = File(context.filesDir,"preference/privacy_consent")
        val originals = listOf(settings,privacy,state).associateWith { if(it.exists())it.readBytes()else null }
        val backup = File(context.cacheDir,"migration-avatar-backup")
        check(!backup.exists());check(backup.mkdirs())
        originals.entries.forEachIndexed { i,e -> e.value?.let { File(backup,"$i.bin").writeBytes(it) } }
        val photo = File(avatar,"assets/migration-avatar-${android.os.SystemClock.elapsedRealtime()}.jpg")
        val versionsBefore = File(avatar,"versions").list()?.toSet() ?: emptySet()
        try {
            photo.parentFile!!.mkdirs()
            val bitmap=Bitmap.createBitmap(64,64,Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(android.graphics.Color.BLUE)
            photo.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG,85,it)) };bitmap.recycle()
            state.parentFile!!.mkdirs()
            state.writeText(JSONObject().put("current_version_id","").put("profile_snapshot_hash","").put("status","empty")
                .put("last_error_code","").put("last_error_message","").put("updated_at","")
                .put("reference_photo_path","assets/${photo.name}").toString())
            val fixtureState=state.readBytes()
            settings.parentFile!!.mkdirs()
            settings.writeText("""<?xml version="1.0" encoding="utf-8"?><preferences>
                <string key="firstLaunchOnboardingVersion">2026-07-core-preview-usage-guide</string><bool key="androidCloudAccessGranted" value="true"/>
                <string key="avatarImageBaseUrl">https://avatar-fixture.invalid/images/generations</string>
                <string key="avatarImageApiKey">synthetic-key</string><string key="avatarTextToImageModel"></string>
                <string key="avatarImageToImageModel">synthetic-reference-model</string></preferences>""")
            privacy.writeText("""<?xml version="1.0" encoding="utf-8"?><preferences><string key="choice">accepted</string><string key="version">2026-10-26-android-v1</string></preferences>""")
            ActivityScenario.launch(SharedActivity::class.java).use {
                waitClick("等待导入数据生成数字分身","可生成数字分身")
                waitClick("使用照片生成")
                waitClick("取消")
                Thread.sleep(700)
                assertArrayEquals(fixtureState,state.readBytes())
                assertEquals(versionsBefore,File(avatar,"versions").list()?.toSet() ?: emptySet<String>())
                instrumentation.sendStatus(2,Bundle().apply { putString("avatarPhotoConsent","Saved synthetic photo reached actual ArkTS upload confirmation; cancel preserved avatar state and versions") })
            }
        } finally {
            originals.forEach { (file,bytes) -> if(bytes==null)file.delete()else file.writeBytes(bytes) }
            photo.delete()
            backup.listFiles().orEmpty().forEach { it.delete() };check(backup.delete())
        }
    }
}
