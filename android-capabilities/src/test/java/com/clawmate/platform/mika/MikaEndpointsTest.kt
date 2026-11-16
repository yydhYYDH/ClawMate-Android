package com.clawmate.platform.mika

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MikaEndpointsTest {
    @Test fun normalizesUserSuppliedChatEndpointsToV1Base() {
        assertEquals("https://api.example.com/v1", MikaEndpoints.parse("https://api.example.com")?.second)
        assertEquals("https://api.example.com/v1", MikaEndpoints.parse("https://api.example.com/")?.second)
        assertEquals("https://api.example.com/v1", MikaEndpoints.parse("https://api.example.com/v1")?.second)
        assertEquals("https://api.example.com/v1", MikaEndpoints.parse("https://api.example.com/v1/")?.second)
        assertEquals("https://api.example.com/v1",
            MikaEndpoints.parse("https://api.example.com/v1/chat/completions")?.second)
        assertEquals("https://api.example.com/openai/v1", MikaEndpoints.parse("https://api.example.com/openai")?.second)
        assertEquals("api.example.com", MikaEndpoints.parse("https://api.example.com/v1")?.first?.host)
    }

    @Test fun rejectsNonHttpsCredentialsQueriesAndFragments() {
        assertNull(MikaEndpoints.parse("http://api.example.com"))
        assertNull(MikaEndpoints.parse("https://user:secret@api.example.com"))
        assertNull(MikaEndpoints.parse("https://api.example.com?token=1"))
        assertNull(MikaEndpoints.parse("https://api.example.com#fragment"))
        assertNull(MikaEndpoints.parse("not a url"))
        assertNull(MikaEndpoints.parse(""))
    }
}
