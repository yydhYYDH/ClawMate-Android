package com.clawmate.platform.reminder

import java.time.Instant
import java.time.ZoneId

/** Android calendar port of the pinned CollectionReminderPolicy. */
object ReminderPolicy {
    const val MAX_AGE_MS = 48L * 60 * 60 * 1000
    fun validTime(hour: Int, minute: Int) = hour in 0..23 && minute in 0..59
    fun day(time: Long, zone: ZoneId) = Instant.ofEpochMilli(time).atZone(zone).toLocalDate().toString()
    fun next(hour: Int, minute: Int, now: Long, zone: ZoneId): Long {
        require(validTime(hour, minute)) { "提醒时间无效" }
        val local = Instant.ofEpochMilli(now).atZone(zone)
        var target = local.toLocalDate().atTime(hour, minute).atZone(zone)
        if (target.toInstant().toEpochMilli() <= now) target = local.toLocalDate().plusDays(1).atTime(hour, minute).atZone(zone)
        return target.toInstant().toEpochMilli()
    }
    fun canClaim(enabled: Boolean, token: String, expectedToken: String, due: Long, issuedDue: Long,
                 handledDays: List<String>, now: Long, zone: ZoneId): Boolean =
        enabled && token.isNotEmpty() && token == expectedToken && due == issuedDue && due > 0 && due <= now &&
            now - due < MAX_AGE_MS && day(due, zone) !in handledDays
}
