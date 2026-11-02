package com.clawmate.shared

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.clawmate.platform.avatar.AndroidAvatarForeground
import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AvatarForegroundDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun originalSharedStartupCreatesRealCroppedPersonAndMessagePng() {
        val avatar = File(context.filesDir,"files/workflows/avatar")
        val state = File(avatar,"state.json")
        val settings = File(context.filesDir,"preference/shared_frontend_settings")
        val originals = listOf(state,settings).associateWith { if(it.exists())it.readBytes()else null }
        val backup = File(context.cacheDir,"migration-foreground-backup")
        check(!backup.exists());check(backup.mkdirs())
        originals.entries.forEachIndexed { i,e -> e.value?.let { File(backup,"$i.bin").writeBytes(it) } }
        val id = "migration-foreground-${android.os.SystemClock.elapsedRealtime()}"
        val source = File(avatar,"assets/$id.png")
        val version = File(avatar,"versions/$id.json")
        val foreground = File(avatar,"assets/$id-foreground.png")
        val message = File(avatar,"assets/$id-message-foreground.png")
        try {
            source.parentFile!!.mkdirs();version.parentFile!!.mkdirs();settings.parentFile!!.mkdirs()
            instrumentation.context.assets.open("astronaut.png").use { input -> source.outputStream().use { input.copyTo(it) } }
            val sourceHash = com.clawmate.platform.LocalImageAccess.sha256(source.readBytes())
            version.writeText(JSONObject().put("version_id",id).put("image_path","assets/$id.png")
                .put("created_at","2026-10-28T00:00:00").put("mode","text_to_image")
                .put("provider","public-test-fixture").put("model","NASA-public-portrait")
                .put("generation_params",JSONObject()).put("prompt_snapshot","")
                .put("profile_snapshot",JSONObject()).put("profile_snapshot_hash","")
                .put("consent_snapshot",JSONObject()).toString())
            val originalVersion = version.readBytes()
            state.writeText(JSONObject().put("current_version_id",id).put("status","current").put("profile_snapshot_hash","")
                .put("last_error_code","").put("last_error_message","").put("reference_photo_path","").put("updated_at","").toString())
            settings.writeText("""<?xml version="1.0" encoding="utf-8"?><preferences><string key="firstLaunchOnboardingVersion">2026-07-core-preview-usage-guide</string></preferences>""")
            ActivityScenario.launch(SharedActivity::class.java).use {
                val deadline = System.currentTimeMillis()+40000
                while ((!foreground.exists() || !message.exists()) && System.currentTimeMillis()<deadline) Thread.sleep(150)
                assertTrue("Actual shared ArkTS foreground cache missing",foreground.exists())
                assertTrue("Original message foreground cache missing",message.exists())
                val bitmap=BitmapFactory.decodeFile(foreground.path)
                assertNotNull(bitmap)
                val pixels=IntArray(bitmap.width*bitmap.height)
                bitmap.getPixels(pixels,0,bitmap.width,0,0,bitmap.width,bitmap.height)
                val transparent=pixels.count { it ushr 24 == 0 }
                val opaque=pixels.count { it ushr 24 >= 200 }
                assertTrue("Expected real removed background",transparent > pixels.size/20)
                assertTrue("Expected real person",opaque > pixels.size/10)
                assertTrue(bitmap.width in 1..512 && bitmap.height in 1..512)
                val thumb=BitmapFactory.decodeFile(message.path)
                assertEquals(168,thumb.width);assertEquals(216,thumb.height)
                val evidence=File(context.cacheDir,"migration-foreground-evidence").also{it.mkdirs()}
                foreground.copyTo(File(evidence,"person.png"),overwrite=true)
                message.copyTo(File(evidence,"message.png"),overwrite=true)
                instrumentation.sendStatus(2,Bundle().apply { putString("avatarForeground","Actual ArkTS + ML Kit: ${bitmap.width}x${bitmap.height}, transparent=$transparent, opaque=$opaque, message=168x216") })
                bitmap.recycle();thumb.recycle()
                assertArrayEquals(originalVersion,version.readBytes())
                assertEquals(sourceHash,com.clawmate.platform.LocalImageAccess.sha256(source.readBytes()))
            }
        } finally {
            originals.forEach { (file,bytes) -> if(bytes==null)file.delete()else file.writeBytes(bytes) }
            listOf(source,version,foreground,message).forEach{it.delete()}
            backup.listFiles().orEmpty().forEach{it.delete()};check(backup.delete())
        }
    }

    @Test fun blankImageCannotProduceInventedForeground() {
        val source=File(context.cacheDir,"migration-no-person.png")
        try {
            val bitmap=Bitmap.createBitmap(128,128,Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(android.graphics.Color.BLUE)
            source.outputStream().use{assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))};bitmap.recycle()
            assertThrows(IllegalArgumentException::class.java) { AndroidAvatarForeground(context).prepare(Uri.fromFile(source).toString(),true) }
        } finally { source.delete() }
    }

    @Test fun remoteAndExternalSourcesCannotReachSegmentation() {
        for (uri in listOf("https://example.invalid/photo.png","file:///sdcard/private.png","content://ungranted/image")) {
            assertThrows(IllegalArgumentException::class.java) { AndroidAvatarForeground(context).prepare(uri,true) }
        }
    }
}
