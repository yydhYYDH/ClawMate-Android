package com.clawmate.shared

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.graphics.Rect
import android.os.Build
import android.os.Looper
import android.view.inputmethod.InputMethodManager
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import ohos.ace.adapter.capability.bridge.BridgePlugin
import ohos.stage.ability.adapter.StageActivity
import android.content.res.Configuration
import com.clawmate.app.chat.voice.tts.*
import com.clawmate.app.chat.voice.asr.*
import com.clawmate.app.image.PreparedChatImage
import com.clawmate.app.task.runner.WorkflowRunResult
import com.clawmate.app.gallery.analysis.OcrResult
import com.clawmate.app.gallery.ocr.LocalOcrRecognizer
import com.clawmate.platform.LocalImageAccess
import com.clawmate.platform.NativeOperations
import com.clawmate.platform.SharedNativeWork
import com.clawmate.platform.avatar.AndroidAvatarForeground
import com.clawmate.platform.avatar.AvatarForegroundRgba
import com.clawmate.platform.voice.SpeechHttp
import com.clawmate.platform.voice.PcmSpeechPlayer
import com.clawmate.platform.voice.SpeechFilePlayer
import com.clawmate.platform.reminder.CollectionReminderEngine
import com.clawmate.app.task.runner.WorkflowModelMailbox
import org.json.JSONObject
import org.json.JSONArray
import java.io.Closeable

/** Android APIs only; no account, service endpoint or vendor-specific dependency. */
class PlatformBridge(private val activity: StageActivity) :
    BridgePlugin(activity, "ClawMatePlatform", activity.bridgeManager), Closeable {
    private val ocrJobs = NativeOperations<OcrResult>()
    private val imageJobs = NativeOperations<List<PreparedChatImage>>(1)
    private val avatarJobs = NativeOperations<AvatarForegroundRgba>(1)
    private val screenshotJobs = NativeOperations<Pair<Int,com.clawmate.platform.gui.WindowScreenshotResult>>(1)
    @Volatile private var screenshotEpoch = 0
    private var speechHttp = SpeechHttp()
    private var speechHttpDetached = false
    private val pcmSpeech = PcmSpeechPlayer(activity.applicationContext)
    private val fileSpeech = SpeechFilePlayer(activity.applicationContext, java.io.File(activity.filesDir,"files/chat-sessions/assets"))
    private var speechMode = ""
    private val workflowJobs = NativeOperations<WorkflowRunResult>(1)
    private val workflowModels = java.util.concurrent.ConcurrentHashMap<String, WorkflowModelMailbox>()
    private var asr: AndroidSpeechRecognizerAsrAdapter? = null
    private val meeting = MeetingBridge(activity as SharedActivity)
    private val reminders = CollectionReminderEngine(activity)

    fun reminderInitialize(hour: String, minute: String): String = onUi {
        reminders.initialize(SharedActivity::class.java.name, hour.toInt(), minute.toInt())
    }
    fun reminderRefresh(): String = onUi { reminders.refresh() }
    fun reminderState(): String = onUi { reminders.snapshot() }
    fun reminderSetEnabled(enabled: Boolean): String = onUi {
        if (enabled && Build.VERSION.SDK_INT >= 33 && activity.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            runCatching { reminders.setEnabled(true) } // Persist a controlled disabled/error state before the prompt.
            activity.requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 7303)
            return@onUi reminders.snapshot()
        }
        val result = reminders.setEnabled(enabled)
        (activity as SharedActivity).clearReminderRequest()
        result
    }
    fun reminderSetTime(hour: String, minute: String): String = onUi {
        val result = reminders.setTime(hour.toInt(), minute.toInt())
        (activity as SharedActivity).clearReminderRequest()
        result
    }
    fun reminderRecordCollection(): String = onUi { reminders.recordCollection() }
    fun reminderClaim(request: String): Boolean = onUi {
        val host = activity as SharedActivity
        if (!host.reminderWindowReady() || host.reminderRequest() != request || request.isEmpty()) return@onUi false
        val claimed = reminders.claim(request)
        if (claimed) host.clearReminderRequest()
        claimed
    }
    fun reminderClear(request: String): Boolean = onUi {
        val host = activity as SharedActivity
        if (host.reminderRequest() == request) host.clearReminderRequest()
        true
    }
    fun reminderRequest(): String = onUi { (activity as SharedActivity).reminderRequest() }

    fun meetingInitialize(): Boolean = onUi { meeting.initialize() }
    fun meetingAttach(): Boolean = onUi { meeting.attach() }
    fun meetingDetach(): Boolean = onUi { meeting.detach() }
    fun meetingPoll(since: String): String = meeting.snapshot(since)
    fun meetingRequest(operation: String, args: String): String = meeting.request(operation, args)
    fun meetingJob(id: String): String = meeting.poll(id)
    fun meetingCancel(id: String): Boolean = meeting.cancel(id)
    fun meetingRecord(id: String): Boolean = onUi {
        if (activity.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            activity.requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 7302)
            error("请允许麦克风权限后再次点击开始录音")
        }
        asrCancel(); ttsStop(); meeting.stopPlayback()
        meeting.record(id)
    }
    fun meetingCapture(action: String): Boolean = onUi {
        when(action) {
            "stop" -> local.meetingnotes.sdk.MeetingSdk.stopRecording(activity)
            "pause" -> local.meetingnotes.sdk.MeetingSdk.pauseRecording(activity)
            "resume" -> local.meetingnotes.sdk.MeetingSdk.resumeRecording(activity)
            else -> error("不支持的录音操作")
        }; true
    }
    fun meetingChooseAudio(id: String, profile: String): String = onUi { meeting.chooseAudio(id, profile) }
    fun meetingChooseHandoff(): String = onUi { meeting.chooseHandoff() }
    fun meetingExport(id: String, format: String, revision: String): String = onUi { meeting.export(id, format, revision) }
    fun meetingPlay(id: String, audioId: String, start: String, end: String): Boolean = onUi { meeting.play(id, audioId, start, end) }
    fun meetingStopPlayback(): Boolean = onUi { meeting.stopPlayback() }
    fun meetingValidateHandoff(raw: String): String = meeting.validateHandoff(raw)
    fun meetingActivityResult(code: Int, result: Int, data: Intent?): Boolean = meeting.activityResult(code, result, data)
    private var asrState = "idle"
    private var asrText = ""
    private var asrError = ""
    private var asrVersion = 0
    private var tts: AndroidTextToSpeechTtsAdapter? = null
    private data class Voice(val status: String = "idle", val messageId: String = "", val error: String = "")
    @Volatile private var voice = Voice()

    fun imagePrepareStart(raw: String): String {
        require(raw.length <= 16000)
        val input = JSONArray(raw)
        require(input.length() in 1..4) { "每次最多发送4张图片" }
        val uris = (0 until input.length()).map {
            LocalImageAccess.validate(input.getString(it), listOf(activity.filesDir, activity.cacheDir)).toString()
        }
        return imageJobs.start { SharedNativeWork.prepareImages(activity.applicationContext, uris) }
    }
    fun imagePreparePoll(id: String): String {
        val result = imageJobs.poll(id)
        return JSONObject().put("v", 1).put("status", result.state)
            .put("images", JSONArray(result.value?.map { it.dataUrl } ?: emptyList<String>()))
            .put("fingerprints", JSONArray(result.value?.map { image ->
                LocalImageAccess.sha256(java.util.Base64.getDecoder().decode(image.dataUrl.substringAfter(',')))
            } ?: emptyList<String>()))
            .put("error", if (result.state == "failed") "图片无法读取或超出限制，请重新选择较小图片" else "").toString()
    }
    fun imagePrepareCancel(id: String): Boolean { imageJobs.cancel(id); return true }
    fun imageDigest(uri: String): String = LocalImageAccess.digest(uri, listOf(activity.filesDir, activity.cacheDir))
    fun avatarForegroundStart(uri: String, segment: Boolean): String {
        val checked = LocalImageAccess.validate(uri, listOf(activity.filesDir, activity.cacheDir))
        return avatarJobs.start { AndroidAvatarForeground(activity.applicationContext).prepare(checked, segment) }
    }
    fun avatarForegroundPoll(id: String): String {
        val result = avatarJobs.poll(id)
        return JSONObject().put("v", 1).put("status", result.state)
            .put("width", result.value?.width ?: 0).put("height", result.value?.height ?: 0)
            .put("rgba", result.value?.let { java.util.Base64.getEncoder().encodeToString(it.pixels) } ?: "")
            .put("error", if (result.state == "failed") "人物分割未完成，请换用人物清晰的分身图片；原图已保留" else "").toString()
    }
    fun avatarForegroundCancel(id: String): Boolean { avatarJobs.cancel(id); return true }
    fun workflowStart(raw: String): String {
        require(raw.length <= 256000) { "工作流配置过长" }
        val mailbox = WorkflowModelMailbox()
        return try {
            val id = workflowJobs.start { try { SharedNativeWork.runWorkflow(raw, mailbox) } finally { mailbox.close() } }
            workflowModels.keys.filter { it != id }.forEach { workflowModels.remove(it)?.close() }
            workflowModels[id] = mailbox
            id
        } catch (error: Exception) { mailbox.close(); throw error }
    }
    fun workflowPoll(id: String): String {
        val operation = workflowJobs.poll(id)
        val result = operation.value
        val detail = if (result == null) "" else JSONObject()
            .put("status", result.status.name.lowercase())
            .put("message", result.message).put("resultSummary", result.resultSummary)
            .put("steps", JSONArray(result.steps.map { step ->
                JSONObject().put("id", step.stepId).put("status", step.status.name.lowercase())
                    .put("message", step.note).put("detail", step.detail)
                    .put("output", step.output?.let { JSONObject(it.toString()) })
            })).toString()
        val tool = workflowModels[id]?.peek()
        if (operation.state != "running") workflowModels.remove(id)?.close()
        return JSONObject().put("v", 1).put("status", operation.state).put("result", detail)
            .put("tool", tool?.let { JSONObject().put("id", it.id).put("mode", it.mode).put("question", it.question)
                .put("image", it.image).put("schema", it.schema?.let { schema -> JSONObject(schema.toString()) }) }).toString()
    }
    fun workflowToolReply(id: String, request: String, raw: String): Boolean {
        require(raw.length <= 100000) { "模型结果超出限制" } // JSON escaping can expand the bounded 16K response.
        val mailbox = workflowModels[id] ?: return false
        if (mailbox.peek()?.id != request) return false
        val reply = JSONObject(raw)
        require(reply.keys().asSequence().all { it in setOf("response", "error") } &&
            (!reply.has("response") || reply.get("response") is String) && (!reply.has("error") || reply.get("error") is String)) { "模型回复格式无效" }
        return mailbox.reply(request, reply.optString("response"), reply.optString("error"))
    }
    fun workflowToolPending(id: String, request: String): Boolean = onUi {
        (activity as SharedActivity).reminderWindowReady() && workflowModels[id]?.peek()?.id == request
    }
    fun workflowCancel(id: String): Boolean { workflowModels.remove(id)?.close(); workflowJobs.cancel(id); return true }
    fun captureStart(): String = onUi { (activity as SharedActivity).startCapture() }
    fun guiScreenshotStart(): String = onUi {
        check((activity as SharedActivity).reminderWindowReady() && activity.window.decorView.hasWindowFocus()) { "请返回前台并解锁后截图" }
        val epoch = screenshotEpoch
        screenshotJobs.start { epoch to com.clawmate.platform.gui.WindowScreenshot(activity) {
            screenshotEpoch == epoch && (activity as SharedActivity).reminderWindowReady()
        }.capture() }
    }
    fun guiScreenshotPoll(id: String): String {
        val result = screenshotJobs.poll(id)
        val value = result.value?.takeIf { it.first == screenshotEpoch }?.second
        return JSONObject().put("v",1).put("status",if(result.state=="done" && value==null) "cancelled" else result.state).put("dataUrl",value?.dataUrl ?: "")
            .put("width",value?.width ?: 0).put("height",value?.height ?: 0).toString()
    }
    fun guiPlanReady(): Boolean = onUi { (activity as SharedActivity).reminderWindowReady() }
    fun guiControlSettings(): Boolean = onUi { com.clawmate.platform.gui.GuiControlEntry.settings(activity);true }
    fun guiControlOpen(): Boolean = onUi { com.clawmate.platform.gui.GuiControlEntry.open(activity);true }
    fun guiControlStop(): Boolean = onUi { com.clawmate.platform.gui.GuiControlService.connected?.stop();true }
    fun guiControlStatus(): String = JSONObject().put("v",1)
        .put("supported",android.os.Build.VERSION.SDK_INT>=34)
        .put("connected",com.clawmate.platform.gui.GuiControlService.connected!=null)
        .put("message",com.clawmate.platform.gui.GuiControlService.lastStatus).toString()
    fun guiScreenshotCancel(id: String): Boolean { screenshotJobs.cancel(id); return true }
    fun guiScreenshotDetach() { screenshotEpoch++ }
    fun capturePoll(id: String): String = onUi { (activity as SharedActivity).pollCapture(id) }
    fun cameraDiscard(value: String): Boolean {
        val root = java.io.File(activity.cacheDir, "chat-camera").canonicalFile
        val uri = Uri.parse(value)
        if (uri.scheme != "file" || !uri.authority.isNullOrBlank()) return false
        val file = java.io.File(uri.path ?: return false).canonicalFile
        if (file.parentFile != root || !Regex("[0-9a-f-]{36}\\.jpg").matches(file.name)) return false
        return !file.exists() || file.delete()
    }

    fun asrStart(): Boolean = onUi {
        check(!meeting.capturing()) { "会议正在录音，请先结束会议录音" }
        meeting.stopPlayback()
        if (activity.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            activity.requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 7202)
            asrState = "error"; asrError = "请允许麦克风权限后重新按下语音按钮"; asrVersion++
            return@onUi false
        }
        ttsStop(); asr?.shutdown()
        asr = AndroidSpeechRecognizerAsrAdapter(activity.applicationContext)
        asrText = ""; asrError = ""; asrState = "listening"; asrVersion++
        when (val result = asr!!.start(object : AsrRecognitionCallbacks {
            override fun onStart() { asrState = "listening"; asrVersion++ }
            override fun onPartialTranscript(transcript: String) { asrText = transcript; asrVersion++ }
            override fun onFinalTranscript(transcript: String, isLast: Boolean) { asrText = transcript; asrState = "done"; asrVersion++ }
            override fun onComplete() { asrState = "recognizing"; asrVersion++ }
            override fun onError(message: String) { asrState = "error"; asrError = message; asrVersion++ }
        })) {
            AsrStartResult.Started -> true
            else -> {
                asrState = "error"
                asrError = when (result) {
                    is AsrStartResult.PermissionDenied -> result.message
                    is AsrStartResult.Unavailable -> result.message
                    is AsrStartResult.Failed -> result.message
                    else -> "无法启动系统识别"
                }
                asrVersion++; false
            }
        }
    }
    fun asrFinish(): Boolean = onUi { asr?.finish(); true }
    fun asrCancel(): Boolean = onUi { asr?.shutdown(); asr = null; asrState = "idle"; asrVersion++; true }

    fun ocrStart(value: String): String {
        val uri = LocalImageAccess.validate(value, listOf(activity.filesDir, activity.cacheDir))
        return ocrJobs.start { LocalOcrRecognizer(activity.applicationContext).recognizeOnWorkerThread(uri) }
    }

    fun ocrPoll(id: String): String {
        val job = ocrJobs.poll(id)
        val result = JSONObject().put("v", 1).put("status", job.state).put("text", "").put("error", "")
        when (val outcome = job.value) {
            is OcrResult.Text -> result.put("text", outcome.value)
            OcrResult.Empty -> Unit
            OcrResult.Cancelled -> result.put("status", "cancelled").put("error", "本地识别已取消")
            is OcrResult.ModelDownload -> result.put("status", "failed").put("error", outcome.message)
            is OcrResult.Failed -> result.put("status", "failed").put("error", outcome.reason)
            is OcrResult.Unavailable -> result.put("status", "failed").put("error", outcome.reason)
            null -> if (job.state != "running") result.put("error", "本地识别已取消、过期或失败，请重试")
        }
        return result.toString()
    }

    fun ocrCancel(id: String): Boolean { ocrJobs.cancel(id); return true }

    fun ttsInitialize(): Boolean = onUi {
        if (tts == null || tts?.state == TtsInitState.ENGINE_TTS_ERROR || tts?.state == TtsInitState.UNAVAILABLE) {
            tts?.release()
            tts = AndroidTextToSpeechTtsAdapter(activity.applicationContext)
            val result = tts!!.initialize(object : TtsSpeakListener {
                override fun onStarted(utteranceId: String) { voice = voice.copy(status = "speaking", error = "") }
                override fun onDone(utteranceId: String) { voice = voice.copy(status = "idle") }
                override fun onError(utteranceId: String, message: String) { voice = voice.copy(status = "error", error = message) }
            })
            if (result is TtsInitResult.Failed) voice = Voice("error", error = result.message)
        }
        true // Initialization requested. Read the actual state from runtimeSnapshot.
    }

    fun ttsSpeak(messageId: String, text: String): Boolean = onUi {
        require(messageId.isNotBlank() && text.isNotBlank() && text.length <= 20000) { "朗读文本为空或过长" }
        val engine = tts ?: error("请先初始化本机语音引擎")
        check(engine.ready) { "本机语音引擎尚不可用：${engine.state}" }
        voice = Voice("synthesizing", messageId)
        try {
            when (val result = engine.speak(text)) {
                TtsSpeakResult.Accepted -> true
                is TtsSpeakResult.NotReady -> error("本机语音引擎尚未就绪：${result.reason}")
                is TtsSpeakResult.RichTypeError -> error(result.message)
            }
        } catch (failure: Exception) {
            voice = voice.copy(status = "error", error = failure.message ?: "系统朗读失败")
            throw failure
        }
    }

    fun ttsStop(): Boolean = onUi {
        tts?.stop()
        if (voice.status == "speaking" || voice.status == "synthesizing") voice = voice.copy(status = "paused")
        true
    }
    fun speechRequestStart(url: String, key: String, body: String, stream: Boolean): String = onUi {
        check((activity as SharedActivity).reminderWindowReady()){"请返回前台并解锁后使用语音服务"}
        if(speechHttpDetached){speechHttp=SpeechHttp();speechHttpDetached=false}
        speechHttp.start(url,key,body,stream)
    }
    fun speechRequestPoll(id: String): String {
        val value=speechHttp.poll(id)
        return JSONObject().put("v",1).put("status",value.status).put("events",JSONArray(value.events)).put("body",value.body).put("error",value.error).toString()
    }
    fun speechRequestCancel(id: String): Boolean {speechHttp.cancel(id);return true}
    fun speechPcmStart(id: String): Boolean = onUi {
        check((activity as SharedActivity).reminderWindowReady()){"请返回前台并解锁后播放语音"}
        tts?.stop();fileSpeech.close();pcmSpeech.start(id);speechMode="pcm";true
    }
    fun speechPcmAppend(id: String, base64: String): Boolean {
        require(base64.length<=87384) {"语音分片过大"}
        return pcmSpeech.append(id,java.util.Base64.getDecoder().decode(base64))
    }
    fun speechPcmFinish(id: String): Boolean {pcmSpeech.finish(id);return true}
    fun speechFilePlay(id: String, uri: String): Boolean = onUi {
        check((activity as SharedActivity).reminderWindowReady()){"请返回前台并解锁后播放语音"}
        tts?.stop();pcmSpeech.stop();fileSpeech.play(id,uri);speechMode="file";true
    }
    fun speechPlaybackPause(paused: Boolean): Boolean = onUi {
        if(!paused)check((activity as SharedActivity).reminderWindowReady()){"请返回前台并解锁后继续语音"}
        if(speechMode=="pcm")pcmSpeech.pause(paused)else fileSpeech.pause(paused);true
    }
    fun speechPlaybackPoll(): String = onUi {
        val value=if(speechMode=="pcm")pcmSpeech.snapshot()else fileSpeech.snapshot()
        JSONObject().put("v",1).put("id",value.id).put("status",value.status).put("error",value.error).put("frames",value.frames).toString()
    }
    fun speechStop(id: String): Boolean = onUi {
        val current=if(speechMode=="pcm")pcmSpeech.snapshot()else fileSpeech.snapshot()
        if(id.isEmpty() || current.id==id){pcmSpeech.stop();fileSpeech.close();speechMode=""};true
    }
    fun speechDetach() {speechHttp.close();speechHttpDetached=true;speechStop("")}

    fun runtimeSnapshot(): String = onUi {
        (activity as? SharedActivity)?.updateSystemBars()
        val engineState = tts?.state?.name ?: "UNINITIALIZED"
        val failure = engineState == "ENGINE_TTS_ERROR" || (tts != null && engineState == "UNAVAILABLE")
        JSONObject().put("v", 1)
            .put("dark", activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES)
            .put("keyboardHeight", keyboardHeight())
            .put("ttsEngineState", engineState)
            .put("voiceStatus", if (failure) "error" else voice.status)
            .put("voiceMessageId", voice.messageId)
            .put("voiceError", if (failure) "本机未提供可用的中文朗读引擎，请在系统设置中安装或启用" else voice.error)
            .put("asrState", asrState).put("asrText", asrText).put("asrError", asrError).put("asrVersion", asrVersion)
            .put("reminderVersion", reminders.version()).put("reminderRequest", (activity as SharedActivity).reminderRequest())
            .put("reminderReady", (activity as SharedActivity).reminderWindowReady())
            .put("windowReady", activity.reminderWindowReady())
            .toString()
    }

    override fun close() {
        guiScreenshotDetach();screenshotJobs.close()
        speechHttp.close();pcmSpeech.close();onUi{fileSpeech.close()}
        meeting.close()
        ocrJobs.close()
        avatarJobs.close()
        imageJobs.close(); workflowModels.values.forEach { it.close() }; workflowModels.clear(); workflowJobs.close(); asrCancel()
        onUi { tts?.release(); tts = null; voice = Voice() }
    }
    fun platformInfo(): String = "Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}"

    fun clearFocus(): Boolean = onUi {
        activity.currentFocus?.let { it.clearFocus(); true } ?: false
    }

    fun hideKeyboard(): Boolean = onUi {
        val focused = activity.currentFocus ?: return@onUi false
        val manager = activity.getSystemService(InputMethodManager::class.java)
        manager.hideSoftInputFromWindow(focused.windowToken, 0)
    }

    fun keyboardHeight(): Int = onUi {
        val root = activity.window.decorView
        val frame = Rect()
        root.getWindowVisibleDisplayFrame(frame)
        (root.rootView.height - frame.bottom).takeIf { it > root.rootView.height / 5 } ?: 0
    }

    fun setClipboard(text: String): Boolean = onUi {
        val clipboard = activity.getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("ClawMate", text))
        true
    }

    fun openUrl(value: String): Boolean = onUi {
        val uri = Uri.parse(value)
        require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null) { "Invalid HTTPS URL" }
        activity.startActivity(Intent(Intent.ACTION_VIEW, uri))
        true
    }

    private fun <T> onUi(action: () -> T): T {
        val work = FutureTask(action)
        if (Looper.myLooper() == Looper.getMainLooper()) work.run() else activity.runOnUiThread(work)
        return try { work.get(2, TimeUnit.SECONDS) } catch (timeout: TimeoutException) {
            // A timed-out, still-queued action must not start speaking later.
            work.cancel(false)
            throw IllegalStateException("安卓界面响应超时，请重试", timeout)
        }
    }
}
