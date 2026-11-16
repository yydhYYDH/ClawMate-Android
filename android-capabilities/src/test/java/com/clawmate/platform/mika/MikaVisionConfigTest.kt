package com.clawmate.platform.mika

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MikaVisionConfigTest {
    @Test fun parsesConfiguredVisionModelAndTrimsValues() {
        val config = MikaVisionConfig.parse(" https://api.example.com/v1 ", " qwen-vl-max ", " sk-test ")
        assertEquals("https://api.example.com/v1", config?.baseUrl)
        assertEquals("qwen-vl-max", config?.modelName)
        assertEquals("sk-test", config?.key)
    }

    @Test fun acceptsMissingKeyBecauseSomeGatewaysAuthenticateByPath() {
        val config = MikaVisionConfig.parse("https://api.example.com", "vision-model", "")
        assertEquals("https://api.example.com/v1", config?.baseUrl)
        assertEquals("", config?.key)
    }

    @Test fun rejectsBlankModelPlainHttpAndOversizedFields() {
        assertNull("blank model", MikaVisionConfig.parse("https://api.example.com", "  ", "k"))
        assertNull("plain http", MikaVisionConfig.parse("http://api.example.com", "vision", "k"))
        assertNull("model over 180", MikaVisionConfig.parse("https://api.example.com", "m".repeat(181), "k"))
        assertNull("key over 4096", MikaVisionConfig.parse("https://api.example.com", "vision", "k".repeat(4097)))
    }
}
