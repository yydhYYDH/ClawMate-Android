package com.clawmate.platform.gui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MikaCollectTest {
    @Test fun stripsTheUntrustedWrapperAndKeepsOriginalLines() {
        val text = "<untrusted>\n标题一\n作者：张三\n\n点赞 12\n</untrusted>"
        assertEquals(listOf("标题一", "作者：张三", "点赞 12"), MikaCollect.lines(text))
    }

    @Test fun deduplicatesWhileKeepingFirstSeenOrder() {
        val text = "<untrusted>\na\nb\na\nc\nb\n</untrusted>"
        assertEquals(listOf("a", "b", "c"), MikaCollect.lines(text))
    }

    @Test fun dropsBlankLinesAndCapsVeryLongLines() {
        val long = "x".repeat(1000)
        val lines = MikaCollect.lines("<untrusted>\n\n$long\n</untrusted>")
        assertEquals(1, lines.size)
        assertEquals(600, lines.single().length)
    }

    @Test fun capsTheNumberOfEntriesAndTotalCharacters() {
        val many = (1..400).joinToString("\n") { "条目$it" }
        val lines = MikaCollect.lines("<untrusted>\n$many\n</untrusted>")
        assertEquals(MikaCollect.MAX_LINES, lines.size)

        val wide = (1..80).joinToString("\n") { "y".repeat(599) }
        val capped = MikaCollect.lines("<untrusted>\n$wide\n</untrusted>")
        assertTrue(capped.sumOf { it.length } <= MikaCollect.MAX_CHARS)
        assertTrue(capped.size < 80)
    }

    @Test fun emptyOrWrapperOnlyTextYieldsNothing() {
        assertEquals(emptyList<String>(), MikaCollect.lines(""))
        assertEquals(emptyList<String>(), MikaCollect.lines("<untrusted>\n\n</untrusted>"))
    }
}
