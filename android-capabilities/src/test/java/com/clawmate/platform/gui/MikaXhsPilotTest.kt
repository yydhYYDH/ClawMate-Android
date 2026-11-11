package com.clawmate.platform.gui

import java.net.InetAddress
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MikaXhsPilotTest {
    @Test fun returnsOnlyBoundedNotesFromMatchingTask() = runBlocking {
        val endpoint = Endpoint()
        endpoint.server.use {
            val notes = endpoint.client.collect({}, { error("Completed task should not be cancelled") })
            assertEquals(listOf(MikaXhsNote("城市漫步", "公开摘录")), notes)
            assertEquals(1, endpoint.submissions)
        }
    }

    @Test fun cancellationTargetsTheSubmittedTask() = runBlocking {
        val endpoint = Endpoint(running = true)
        endpoint.server.use {
            val submitted = CompletableDeferred<Unit>()
            val stopped = CompletableDeferred<Boolean>()
            val job = launch { endpoint.client.collect({ submitted.complete(Unit) }, { stopped.complete(it) }) }
            submitted.await()
            job.cancelAndJoin()
            assertTrue(stopped.await())
            assertEquals(1, endpoint.submissions)
            assertEquals(endpoint.taskId, endpoint.cancelledId)
        }
    }

    @Test fun missingHelperPreventsSubmission() {
        val endpoint = Endpoint(helper = false)
        endpoint.server.use {
            val error = assertThrows(IllegalStateException::class.java) {
                runBlocking { endpoint.client.collect({}, {}) }
            }
            assertTrue(error.message.orEmpty().contains("手机助手"))
            assertEquals(0, endpoint.submissions)
        }
    }

    @Test fun parsesFencedJsonButRejectsUnexpectedContent() {
        assertEquals(listOf(MikaXhsNote("标题", "")),
            MikaXhsPilot.parseNotes("```json\n{\"notes\":[{\"title\":\"标题\",\"excerpt\":\"\"}]}\n```"))
        assertThrows(IllegalStateException::class.java) { MikaXhsPilot.parseNotes("I found some notes") }
        assertThrows(IllegalStateException::class.java) {
            MikaXhsPilot.parseNotes("""{"notes":[{"title":" ","excerpt":"text"}]}""")
        }
    }

    private class Endpoint(running: Boolean = false, helper: Boolean = true) {
        @Volatile var taskId: String? = null
        @Volatile var cancelledId: String? = null
        @Volatile var submissions = 0
        private val token = "a".repeat(43)
        val server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.getHeader("Authorization") != "Bearer $token") {
                        return MockResponse().setResponseCode(401)
                    }
                    val body = when (request.path) {
                        "/api/v1" -> """{"api_version":1,"capabilities":["tasks","task_recovery"]}"""
                        "/api/v1/device" -> """{"device":{"ready":true,"helper_available":$helper}}"""
                        "/api/v1/tasks" -> {
                            val submission = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
                            if (request.method != "POST" || MikaXhsPilot.KEYWORD !in submission.getValue("goal").jsonPrimitive.content) {
                                return MockResponse().setResponseCode(400)
                            }
                            taskId = submission.getValue("request_id").jsonPrimitive.content
                            submissions++
                            """{"task_id":"$taskId","status":"running"}"""
                        }
                        "/api/v1/tasks/$taskId" -> if (running) """{"task_id":"$taskId","status":"running"}"""
                            else """{"task_id":"$taskId","status":"done","answer":"{\"notes\":[{\"title\":\"城市漫步\",\"excerpt\":\"公开摘录\"}]}"}"""
                        "/api/v1/tasks/$taskId/cancel" -> {
                            cancelledId = taskId
                            """{"task_id":"$taskId","accepted":true}"""
                        }
                        else -> return MockResponse().setResponseCode(404)
                    }
                    return MockResponse().setBody(body)
                }
            }
            start(InetAddress.getByName("127.0.0.1"), 0)
        }
        val client = MikaXhsPilot(server.port, token)
    }
}
