package com.clawmate.platform.voice

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class SpeechHttpTest {
    private fun withServer(test: (MockWebServer, SpeechHttp)->Unit) {
        val certificate=HeldCertificate.Builder().addSubjectAlternativeName("127.0.0.1").build()
        val serverTls=HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientTls=HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        MockWebServer().use { server ->
            server.useHttps(serverTls.sslSocketFactory(),false);server.start()
            SpeechHttp(OkHttpClient.Builder().proxy(java.net.Proxy.NO_PROXY).sslSocketFactory(clientTls.sslSocketFactory(),clientTls.trustManager)
                .eventListener(object : okhttp3.EventListener(){override fun callFailed(call: okhttp3.Call, ioe: java.io.IOException){System.err.println("Synthetic localhost TLS failure: $ioe")}}).build()).use{test(server,it)}
        }
    }
    private fun url(server: MockWebServer, path: String) = server.url(path).newBuilder().host("127.0.0.1").build()
    private fun terminal(http: SpeechHttp,id: String): Pair<SpeechHttpResult,List<String>> {
        val events=mutableListOf<String>()
        repeat(500){val value=http.poll(id);events+=value.events;if(value.status!="running")return value to events;Thread.sleep(10)}
        error("Transport did not finish")
    }
    @Test fun rejectsUnsafeTargetsAndHeaderInjectionBeforeNetwork() {
        SpeechHttp().use { http ->
            for(url in listOf("http://example.invalid","https://user:key@example.invalid","https://example.invalid?key=x","https://example.invalid/#fragment"))
                assertThrows(IllegalArgumentException::class.java){http.start(url,"fixture","{}",true)}
            assertThrows(IllegalArgumentException::class.java){http.start("https://example.invalid","bad\nheader","{}",true)}
        }
    }
    @Test fun httpsSsePreservesUnicodeMultilineEventsAndRealRequest() = withServer { server,http ->
        server.enqueue(MockResponse().setHeader("Content-Type","text/event-stream").setBody(":keepalive\r\ndata: 合成\r\ndata: 第二行\r\n\r\ndata: [DONE]\n\n").throttleBody(3,1,TimeUnit.MILLISECONDS))
        val id=http.start(url(server,"/v1/chat/completions").toString(),"synthetic-key","{\"stream\":true}",true)
        val (result,events)=terminal(http,id)
        assertEquals("done",result.status);assertEquals(listOf("data: 合成\ndata: 第二行","data: [DONE]"),events)
        val request=server.takeRequest(5,TimeUnit.SECONDS) ?: error("Synthetic request not received");assertEquals("synthetic-key",request.getHeader("api-key"));assertEquals("Bearer synthetic-key",request.getHeader("Authorization"))
        assertEquals("{\"stream\":true}",request.body.readUtf8());http.cancel(id);assertEquals("missing",http.poll(id).status)
    }
    @Test fun httpFailureNeverReleasesAudioLookingEvents() = withServer { server,http ->
        server.enqueue(MockResponse().setResponseCode(401).setHeader("Content-Type","text/event-stream").setBody("data: fake audio\n\n"))
        val (result,events)=terminal(http,http.start(url(server,"/").toString(),"fixture","{}",true))
        assertEquals("failed",result.status);assertTrue(events.isEmpty());assertEquals("语音服务HTTP 401",result.error)
    }
    @Test fun redirectsAndWrongContentTypesAreControlledFailures() = withServer { server,http ->
        for(response in listOf(MockResponse().setResponseCode(302).setHeader("Location",url(server,"/private")),MockResponse().setHeader("Content-Type","application/json").setBody("{}"))) {
            server.enqueue(response);val id=http.start(url(server,"/").toString(),"fixture","{}",true)
            assertEquals("failed",terminal(http,id).first.status);http.cancel(id)
        }
        assertEquals(2,server.requestCount)
    }
    @Test fun nonStreamingJsonIsBoundedAndReturnedWithoutProviderParsing() = withServer { server,http ->
        server.enqueue(MockResponse().setBody("{\"choices\":[]}"))
        val (result,events)=terminal(http,http.start(url(server,"/").toString(),"fixture","{}",false))
        assertEquals("done",result.status);assertEquals("{\"choices\":[]}",result.body);assertTrue(events.isEmpty())
    }
    @Test fun oversizedEventIsRejected() = withServer { server,http ->
        server.enqueue(MockResponse().setHeader("Content-Type","text/event-stream").setBody("data: "+"A".repeat(262145)+"\n\n"))
        val (result,events)=terminal(http,http.start(url(server,"/").toString(),"fixture","{}",true))
        assertEquals("failed",result.status);assertTrue(events.isEmpty())
    }
    @Test fun cancellationDropsBufferedEventsAndNextJobIsIndependent() = withServer { server,http ->
        server.enqueue(MockResponse().setHeader("Content-Type","text/event-stream").setBody((1..50).joinToString(""){"data: $it\n\n"}))
        val id=http.start(url(server,"/").toString(),"fixture","{}",true);assertNotNull(server.takeRequest(5,TimeUnit.SECONDS));Thread.sleep(100)
        assertThrows(IllegalStateException::class.java){http.start(url(server,"/").toString(),"fixture","{}",true)}
        http.cancel(id);assertEquals("missing",http.poll(id).status)
        server.enqueue(MockResponse().setHeader("Content-Type","text/event-stream").setBody("data: new job\n\n"))
        val (_,events)=terminal(http,http.start(url(server,"/").toString(),"fixture","{}",true))
        assertEquals(listOf("data: new job"),events)
    }
}
