package com.clawmate.shared

import android.os.Bundle
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.WindowManager
import android.content.res.Configuration
import android.content.Intent
import android.app.Activity
import android.net.Uri
import android.provider.MediaStore
import org.json.JSONObject
import java.io.File
import java.util.UUID
import ohos.stage.ability.adapter.StageActivity
import android.app.KeyguardManager
import com.clawmate.platform.reminder.CollectionReminderEngine

class SharedActivity : StageActivity() {
    private lateinit var platform: PlatformBridge
    private var captureId = ""
    private var captureStatus = "missing"
    private var captureFile: File? = null
    @Volatile private var resumedForReminder = false
    private var pendingReminder = ""

    internal fun reminderWindowReady(): Boolean = resumedForReminder && !getSystemService(KeyguardManager::class.java).isKeyguardLocked
    internal fun reminderRequest(): String {
        if (pendingReminder.isEmpty()) return ""
        pendingReminder = runCatching {
            val value = JSONObject(pendingReminder)
            CollectionReminderEngine(this).pending(value.getString("planToken"), value.getLong("dueAt"))
        }.getOrDefault("")
        return pendingReminder
    }
    internal fun clearReminderRequest() {
        pendingReminder = ""
        intent?.removeExtra(CollectionReminderEngine.TOKEN); intent?.removeExtra(CollectionReminderEngine.DUE)
    }
    private fun acceptReminder(value: Intent?) {
        if (value?.action != CollectionReminderEngine.ACTION) return
        pendingReminder = runCatching { CollectionReminderEngine(this).pending(value.getStringExtra(CollectionReminderEngine.TOKEN).orEmpty(),
            value.getLongExtra(CollectionReminderEngine.DUE, 0)) }.getOrDefault("")
    }
    override fun onNewIntent(value: Intent) {
        super.onNewIntent(value); setIntent(value); acceptReminder(value)
        if (reminderRequest().isNotEmpty() && reminderWindowReady()) platform.callMethod("collectionReminderArrived")
    }
    override fun onResume() {
        super.onResume(); resumedForReminder = true
        if (reminderRequest().isNotEmpty()) platform.callMethod("collectionReminderArrived")
    }
    override fun onPause() { resumedForReminder = false; platform.guiScreenshotDetach(); super.onPause() }

    internal fun startCapture(): String {
        check(captureStatus != "running") { "相机已打开" }
        val root = File(cacheDir, "chat-camera").also { it.mkdirs() }
        captureId = UUID.randomUUID().toString()
        captureFile = File(root, "$captureId.jpg").also { it.createNewFile() }
        val uri = Uri.parse("content://$packageName.capture/$captureId.jpg")
        captureStatus = "running"
        try {
            startActivityForResult(Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                putExtra(MediaStore.EXTRA_OUTPUT, uri)
                clipData = android.content.ClipData.newRawUri("camera", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }, 7201)
        } catch (failure: Exception) {
            captureFile?.delete(); captureFile = null; captureStatus = "failed"
            throw IllegalStateException("无法启动系统相机，请使用相册选图", failure)
        }
        return captureId
    }
    internal fun pollCapture(id: String): String {
        val status = if (id == captureId) captureStatus else "missing"
        return JSONObject().put("v", 1).put("status", status)
            .put("uri", if (status == "done") Uri.fromFile(captureFile).toString() else "").toString()
    }
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (platform.meetingActivityResult(requestCode, resultCode, data)) return
        if (requestCode == 7201) {
            revokeUriPermission(Uri.parse("content://$packageName.capture/$captureId.jpg"),
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            captureStatus = if (resultCode == Activity.RESULT_OK && (captureFile?.length() ?: 0) > 0) "done" else "cancelled"
            if (captureStatus != "done") { captureFile?.delete(); captureFile = null }
        }
    }

    override fun onCreate(state: Bundle?) {
        platform = PlatformBridge(this)
        setInstanceName("com.clawmate.shared:entry:EntryAbility:")
        super.onCreate(state)
        acceptReminder(intent)
        updateSystemBars()
    }

    internal fun updateSystemBars() {
        val dark = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val color = if (dark) Color.rgb(17, 21, 29) else Color.WHITE
        if ((window.decorView.background as? ColorDrawable)?.color != color) {
            window.decorView.setBackgroundColor(color)
        }
        val translucent = WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS or WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION
        if (window.attributes.flags and translucent != 0) window.clearFlags(translucent)
        if (window.attributes.flags and WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS == 0) {
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        }
        if (window.statusBarColor != color) window.statusBarColor = color
        if (window.navigationBarColor != color) window.navigationBarColor = color
        val mask = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        val flags = (window.decorView.systemUiVisibility and mask.inv()) or if (dark) 0 else mask
        if (window.decorView.systemUiVisibility != flags) window.decorView.systemUiVisibility = flags
    }

    override fun onConfigurationChanged(configuration: Configuration) {
        super.onConfigurationChanged(configuration)
        updateSystemBars()
    }

    override fun onStop() { platform.speechDetach(); platform.ttsStop(); platform.asrCancel(); platform.meetingDetach(); platform.meetingStopPlayback(); super.onStop() }
    override fun onDestroy() { platform.close(); super.onDestroy() }
}
