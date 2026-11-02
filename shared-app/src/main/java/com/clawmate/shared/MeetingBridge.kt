package com.clawmate.shared

import android.app.Activity
import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import local.meetingnotes.sdk.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/** Android ports only. ArkTS owns imported records, receipts and collection indexes. */
class MeetingBridge(private val activity: SharedActivity) : Closeable {
    private data class Job(@Volatile var payload: String = "{}", @Volatile var error: String = "",
                           @Volatile var httpStatus: Int = 0, @Volatile var status: String = "running",
                           var cancel: (() -> Unit)? = null, val created: Long = System.currentTimeMillis())
    private val jobs = ConcurrentHashMap<String, Job>()
    private val files = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var client: MeetingClient? = null
    private var subscription: Closeable? = null
    private val eventLock = Any()
    private val events = ArrayDeque<Pair<Long, String>>()
    private val sequence = AtomicLong()
    private var picker: Picker? = null
    private data class Picker(val job: String, val kind: String, val mid: String = "", val format: String = "", val packet: String = "", val profile: String = "local-sensevoice")
    private var player: MediaPlayer? = null
    private var playbackGeneration = 0
    @Volatile private var closed = false
    @Volatile private var microphonePendingUntil = 0L

    fun initialize(): Boolean {
        check(!closed)
        MeetingSdk.initialize(activity, MeetingConfiguration(apiPort = 18090, summaryPort = 18091, threads = 2))
        if (client == null) client = MeetingClient()
        attach()
        return true
    }
    fun attach(): Boolean {
        if (subscription == null) subscription = MeetingSdk.subscribe { event ->
            if (JSONObject(event).optString("type") in setOf("capture_phase", "capture_error", "capture_closed")) microphonePendingUntil = 0
            synchronized(eventLock) {
                events.addLast(sequence.incrementAndGet() to event)
                while (events.size > 256) events.removeFirst()
            }
        }
        return true
    }
    fun detach(): Boolean { subscription?.close(); subscription = null; return true }
    fun capturing(): Boolean = android.os.SystemClock.elapsedRealtime() < microphonePendingUntil ||
        JSONObject(MeetingSdk.getRecordingState()).optBoolean("streaming")
    fun record(mid: String): Boolean {
        check(!capturing()) { "请先结束当前会议录音" }
        stopPlayback(); microphonePendingUntil = android.os.SystemClock.elapsedRealtime() + 30000
        try { MeetingSdk.startRecording(activity, mid) }
        catch (error: Exception) { microphonePendingUntil = 0; throw error }
        return true
    }
    fun snapshot(since: String): String {
        val cursor = since.toLongOrNull() ?: 0
        val engine = MeetingSdk.getEngineState()
        return synchronized(eventLock) {
            JSONObject().put("v", 1).put("engine", JSONObject().put("phase", engine.phase.name.lowercase()).put("error", engine.error))
                .put("capture", JSONObject(MeetingSdk.getRecordingState())).put("sequence", sequence.get())
                .put("events", JSONArray(events.filter { it.first > cursor }.map { it.second }))
                .put("overflowed", events.firstOrNull()?.let { cursor > 0 && cursor < it.first - 1 } ?: false).toString()
        }
    }
    private fun api(): MeetingClient = client ?: error("请先打开本机会议引擎")
    private fun newJob(): String {
        check(!closed)
        jobs.entries.removeAll { System.currentTimeMillis() - it.value.created > 300000 && it.value.status != "running" }
        check(jobs.size < 32) { "会议请求过多，请稍后重试" }
        return UUID.randomUUID().toString().also { jobs[it] = Job() }
    }
    private fun complete(id: String, payload: String) { jobs[id]?.let { if (it.status == "running") { it.payload = payload; it.status = "done" } } }
    private fun fail(id: String, error: Throwable) {
        val cause = error.cause ?: error
        jobs[id]?.let { if (it.status == "running") {
            it.error = cause.message ?: "会议操作失败"; it.httpStatus = (cause as? MeetingApiException)?.status ?: 0; it.status = "failed"
        } }
    }
    fun request(operation: String, args: String): String {
        require(args.length <= 1024 * 1024)
        val id = newJob()
        try {
            val request = api().request(operation, JSONObject(args))
            jobs[id]?.cancel = { request.cancel() }
            request.result.whenComplete { value, error ->
                if (error != null) fail(id, error)
                else try {
                    val payload = if (operation.equals("handoff", true)) MeetingHandoff.parse(value.payload).toJson() else value.payload
                    complete(id, payload)
                } catch (e: Exception) { fail(id, e) }
            }
        } catch (error: Exception) { fail(id, error) }
        return id
    }
    fun poll(id: String): String {
        val job = jobs[id] ?: return JSONObject().put("v", 1).put("status", "failed").put("error", "请求已结束或过期").toString()
        val result = JSONObject().put("v", 1).put("status", job.status).put("payload", job.payload)
            .put("error", job.error).put("http_status", job.httpStatus).toString()
        if (job.status != "running") jobs.remove(id, job)
        return result
    }
    fun cancel(id: String): Boolean {
        jobs.remove(id)?.let { it.status = "cancelled"; it.cancel?.invoke() }
        return true
    }
    fun chooseAudio(mid: String, profile: String): String {
        require(Regex("[0-9a-f]{32}").matches(mid))
        require(profile in setOf("local-sensevoice", "local-firered", "local-qwen"))
        return pick(Picker(newJob(), "audio", mid, profile = profile), Intent(Intent.ACTION_OPEN_DOCUMENT).setType("audio/*"))
    }
    fun chooseHandoff(): String = pick(Picker(newJob(), "handoff"), Intent(Intent.ACTION_OPEN_DOCUMENT).setType("application/json"))
    fun export(mid: String, format: String, revision: String): String {
        require(Regex("[0-9a-f]{32}").matches(mid)); require(format in setOf("export", "export.md", "handoff"))
        val id = newJob()
        if (format == "handoff") {
            val request = api().request(MeetingOperation.HANDOFF, JSONObject().put("id", mid).put("body", JSONObject(revision)))
            jobs[id]?.cancel = { request.cancel() }
            request.result.whenComplete { value, error ->
                if (error != null) fail(id, error)
                else main.post {
                    if (closed || jobs[id]?.status != "running") return@post
                    try { savePicker(Picker(id, "export", mid, format, MeetingHandoff.parse(value.payload).toJson())) }
                    catch (e: Exception) { fail(id, e) }
                }
            }
        } else savePicker(Picker(id, "export", mid, format))
        return id
    }
    private fun savePicker(value: Picker) {
        val type = if (value.format == "export.md") "text/markdown" else "application/json"
        pick(value, Intent(Intent.ACTION_CREATE_DOCUMENT).setType(type).putExtra(Intent.EXTRA_TITLE,
            if (value.format == "export.md") "meeting-notes.md" else if (value.format == "handoff") "meeting-handoff.json" else "meeting-notes.json"))
    }
    private fun pick(value: Picker, intent: Intent): String {
        if (picker != null) { fail(value.job, IllegalStateException("请先完成当前文件选择")); return value.job }
        picker = value
        try { activity.startActivityForResult(intent.addCategory(Intent.CATEGORY_OPENABLE), 7301) }
        catch (e: Exception) { picker = null; fail(value.job, e) }
        return value.job
    }
    fun activityResult(code: Int, result: Int, data: Intent?): Boolean {
        if (code != 7301) return false
        val pending = picker ?: return true
        picker = null
        val uri = data?.data
        if (result != Activity.RESULT_OK || uri == null) { fail(pending.job, IllegalStateException("已取消文件选择")); return true }
        if (jobs[pending.job]?.status != "running") return true
        when (pending.kind) {
            "audio" -> {
                val upload = api().uploadAudio(pending.mid, "selected-audio", {
                    activity.contentResolver.openInputStream(uri) ?: error("无法读取所选音频")
                }, refinementProfileId = pending.profile)
                jobs[pending.job]?.cancel = { upload.cancel() }
                upload.result.whenComplete { value, error -> if (error != null) fail(pending.job, error) else complete(pending.job, value.payload) }
            }
            "handoff" -> files.execute {
                try {
                    val raw = activity.contentResolver.openInputStream(uri)!!.use { input ->
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            val count = input.read(buffer); if (count < 0) break
                            require(output.size() + count <= 2 * 1024 * 1024) { "交接文件超过 2 MiB" }
                            output.write(buffer, 0, count)
                        }
                        output.toString("UTF-8")
                    }
                    complete(pending.job, MeetingHandoff.parse(raw).toJson())
                } catch (e: Exception) { fail(pending.job, e) }
            }
            "export" -> files.execute {
                try {
                    val output = activity.contentResolver.openOutputStream(uri) ?: error("无法写入所选位置")
                    output.use {
                        if (pending.format == "handoff") output.write(pending.packet.toByteArray(Charsets.UTF_8))
                        else {
                            val request = api().export(pending.mid, pending.format, output)
                            jobs[pending.job]?.cancel = { request.cancel() }; request.result.get()
                        }
                    }
                    complete(pending.job, "{\"saved\":true}")
                } catch (e: Exception) { fail(pending.job, e) }
            }
        }
        return true
    }
    fun validateHandoff(raw: String): String = MeetingHandoff.parse(raw).toJson()
    fun play(mid: String, aid: String, start: String, end: String): Boolean {
        require(Regex("[0-9a-f]{32}").matches(mid) && Regex("[0-9a-f]{32}").matches(aid))
        val from = start.toDouble(); val to = end.toDouble()
        require(from.isFinite() && to.isFinite() && from >= 0 && to >= 0 && from <= 3600 && to <= 3600)
        check(!JSONObject(MeetingSdk.getRecordingState()).optBoolean("streaming")) { "录音时不能回听" }
        stopPlayback(); val generation = playbackGeneration
        val media = MediaPlayer(); player = media
        media.setDataSource(activity, Uri.parse("${MeetingSdk.baseUrl}/api/v1/meetings/$mid/audio?audio_id=$aid"))
        media.setOnPreparedListener {
            if (player === media) {
                media.seekTo((from * 1000).toInt()); media.start()
                if (to > from) main.postDelayed({ if (generation == playbackGeneration) stopPlayback() }, ((to - from) * 1000).toLong())
            }
        }
        media.setOnCompletionListener { if (player === media) stopPlayback() }
        media.setOnErrorListener { _, _, _ -> if (player === media) stopPlayback(); true }
        media.prepareAsync(); return true
    }
    fun stopPlayback(): Boolean { playbackGeneration++; player?.release(); player = null; return true }
    override fun close() {
        closed = true; detach(); stopPlayback(); jobs.keys.toList().forEach { cancel(it) }
        client?.close(); files.shutdownNow()
    }
}
