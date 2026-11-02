package com.clawmate.platform.reminder

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class ReminderPolicyTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private fun time(value: String) = Instant.parse(value).toEpochMilli()
    @Test fun nextOccurrenceUsesLocalCalendarAndStrictlyFutureTime() {
        val now = time("2026-10-26T13:00:00Z")
        assertEquals(time("2026-10-27T13:00:00Z"), ReminderPolicy.next(21, 0, now, zone))
        assertEquals(now + 60000, ReminderPolicy.next(21, 1, now, zone))
        assertEquals("2026-10-26", ReminderPolicy.day(now, zone))
    }
    @Test fun dailyAlarmHandlesDstWithoutUsingFixed24HourIntervals() {
        val newYork = ZoneId.of("America/New_York")
        val now = time("2026-03-07T14:00:00Z")
        assertEquals(time("2026-03-08T13:00:00Z"), ReminderPolicy.next(9, 0, now, newYork))
        assertEquals(23 * 3600000L, ReminderPolicy.next(9, 0, now, newYork) - now)
    }
    @Test fun timeBoundsRejectInvalidValues() {
        for ((hour, minute) in listOf(-1 to 0, 24 to 0, 0 to -1, 0 to 60)) {
            assertFalse(ReminderPolicy.validTime(hour, minute))
            assertThrows(IllegalArgumentException::class.java) { ReminderPolicy.next(hour, minute, 1, zone) }
        }
    }
    @Test fun claimRequiresTheActualIssuedDueTokenAndUnconsumedDay() {
        val due = time("2026-10-26T13:00:00Z")
        fun claim(enabled: Boolean = true, token: String = "plan", expected: String = "plan", requestDue: Long = due,
                  issued: Long = due, days: List<String> = emptyList(), now: Long = due) =
            ReminderPolicy.canClaim(enabled, token, expected, requestDue, issued, days, now, zone)
        assertTrue(claim())
        assertFalse(claim(enabled = false)); assertFalse(claim(token = "")); assertFalse(claim(token = "forged"))
        assertFalse(claim(issued = 0)); assertFalse(claim(requestDue = due + 1)); assertFalse(claim(now = due - 1))
        assertFalse(claim(now = due + ReminderPolicy.MAX_AGE_MS)); assertTrue(claim(now = due + ReminderPolicy.MAX_AGE_MS - 1))
        assertFalse(claim(days = listOf("2026-10-26")))
    }
}
