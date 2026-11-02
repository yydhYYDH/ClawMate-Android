package com.clawmate.platform.voice

import java.io.Closeable
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.HttpUrl.Companion.toHttpUrl

data class SpeechHttpResult(val status: String, val events: List<String> = emptyList(), val body: String = "", val error: String = "")

/** Bounded HTTPS/SSE transport only. The shared frontend builds/parses the MiMO contract. */
class SpeechHttp(client: OkHttpClient) : Closeable {
    constructor() : this(OkHttpClient())
    private val client = client.newBuilder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(10,TimeUnit.SECONDS).readTimeout(30,TimeUnit.SECONDS).callTimeout(120,TimeUnit.SECONDS).build()
    private class Job(val id: String, val call: Call) {
        val events = ArrayBlockingQueue<String>(4)
        @Volatile var state = "running"
        @Volatile var body = ""
        @Volatile var error = ""
    }
    private val executor = Executors.newSingleThreadExecutor()
    private var job: Job? = null
    private var closed = false

    @Synchronized fun start(url: String, key: String, body: String, stream: Boolean): String {
        check(!closed) { "语音网络已关闭" }
        check(job == null) { "语音请求正在进行" }
        val target = url.toHttpUrl()
        require(target.isHttps && target.username.isEmpty() && target.password.isEmpty() && target.encodedQuery == null && target.fragment == null) { "语音服务必须是无凭据参数的HTTPS地址" }
        require(key.isNotBlank() && key.length <= 8192 && !key.contains('\r') && !key.contains('\n')) { "语音服务API Key无效" }
        require(body.toByteArray().size in 1..100000) { "语音请求过长" }
        val request = Request.Builder().url(target).header("api-key",key).header("Authorization","Bearer $key")
            .header("Accept",if(stream)"text/event-stream" else "application/json")
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        val current = Job(UUID.randomUUID().toString(),client.newCall(request));job = current
        executor.submit {
            try {
                current.call.execute().use { response ->
                    check(response.isSuccessful) { "语音服务HTTP ${response.code}" }
                    val input = response.body ?: error("语音服务返回空响应")
                    if(stream) {
                        check(input.contentType()?.subtype?.equals("event-stream",ignoreCase=true)==true) { "语音服务未返回SSE音频流" }
                        val source=input.source();var total=0L;val lines=mutableListOf<String>();var eventSize=0
                        fun emit() {
                            if(lines.isEmpty())return
                            val value=lines.joinToString("\n");lines.clear();eventSize=0
                            while(current.state=="running") {
                                if(current.events.offer(value,100,TimeUnit.MILLISECONDS))return
                            }
                            throw IOException("语音请求已取消")
                        }
                        while(!source.exhausted()) {
                            check(current.state=="running") { "语音请求已取消" }
                            val end=source.indexOf('\n'.code.toByte(),0,262145)
                            val line=if(end>=0)source.readUtf8(end+1).trimEnd('\n','\r') else {
                                check(source.buffer.size<=262144) { "语音事件行超出限制" };source.readUtf8()
                            }
                            total+=line.toByteArray().size
                            check(total<=24*1024*1024) { "语音响应超出限制，请缩短文本" }
                            if(line.isEmpty())emit() else if(line.startsWith("data:")) {
                                eventSize+=line.length;check(eventSize<=524288) { "语音事件超出限制" };lines+=line
                            }
                        }
                        emit()
                    } else {
                        val source=input.source()
                        check(!source.request(12*1024*1024+1L)) { "语音响应超出限制，请缩短文本" }
                        current.body=source.readUtf8()
                    }
                    if(current.state=="running")current.state="done"
                }
            } catch (failure: Exception) {
                if(current.state=="running") {
                    current.error=if(failure is IllegalStateException)failure.message ?: "语音服务响应无效" else "语音网络失败或超时，请检查服务配置后重试"
                    current.events.clear();current.body="";current.state="failed"
                }
            }
        }
        return current.id
    }
    @Synchronized fun poll(id: String): SpeechHttpResult {
        val current=job?.takeIf{it.id==id} ?: return SpeechHttpResult("missing")
        val events=mutableListOf<String>();current.events.drainTo(events,4)
        val state=current.state
        val delivered=if(state=="done" && current.events.isNotEmpty()) "running" else state
        return SpeechHttpResult(delivered,events,if(delivered=="done")current.body else "",current.error)
    }
    @Synchronized fun cancel(id: String) {
        val current=job?.takeIf{it.id==id} ?: return
        current.state="cancelled";current.call.cancel();current.events.clear();current.body="";job=null
    }
    @Synchronized override fun close() { closed=true;job?.let{cancel(it.id)};executor.shutdownNow() }
}
