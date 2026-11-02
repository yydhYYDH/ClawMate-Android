package com.clawmate.platform

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class NativeOperationsTest {
    @Test fun resultIsDeliveredOnce() {
        NativeOperations<String>().use { jobs ->
            val completed = CountDownLatch(1)
            val id = jobs.start { completed.countDown(); "real-result" }
            assertTrue(completed.await(2, TimeUnit.SECONDS))
            var result = jobs.poll(id)
            val deadline = System.nanoTime() + 2_000_000_000
            while (result.state == "running" && System.nanoTime() < deadline) { Thread.yield(); result = jobs.poll(id) }
            assertEquals("done", result.state)
            assertEquals("real-result", result.value)
            assertEquals("missing", jobs.poll(id).state)
        }
    }

    @Test fun cancelledWorkCannotPublishLateResults() {
        NativeOperations<String>().use { jobs ->
            val started = CountDownLatch(1)
            val finish = CountDownLatch(1)
            val exited = CountDownLatch(1)
            val id = jobs.start {
                started.countDown()
                try { finish.await(2, TimeUnit.SECONDS) } catch (_: InterruptedException) { }
                exited.countDown()
                "late-result"
            }
            assertTrue(started.await(2, TimeUnit.SECONDS))
            jobs.cancel(id); finish.countDown()
            assertTrue(exited.await(2, TimeUnit.SECONDS))
            assertEquals("missing", jobs.poll(id).state)
        }
    }

    @Test fun concurrencyAndLifecycleAreBounded() {
        val jobs = NativeOperations<String>(1)
        val finish = CountDownLatch(1)
        val id = jobs.start { finish.await(2, TimeUnit.SECONDS); "done" }
        assertThrows(IllegalStateException::class.java) { jobs.start { "overflow" } }
        jobs.cancel(id); finish.countDown(); jobs.close()
        assertThrows(IllegalStateException::class.java) { jobs.start { "closed" } }
    }

    @Test fun failureNeverBecomesSuccessfulText() {
        NativeOperations<String>().use { jobs ->
            val id = jobs.start { throw IllegalArgumentException("private details") }
            var result = jobs.poll(id)
            val deadline = System.nanoTime() + 2_000_000_000
            while (result.state == "running" && System.nanoTime() < deadline) { Thread.yield(); result = jobs.poll(id) }
            assertEquals("failed", result.state)
            assertNull(result.value)
        }
    }
}
