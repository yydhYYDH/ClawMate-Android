package com.clawmate.platform.gui

import android.Manifest
import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CalendarContract
import com.wangzan101.mika.core.uistate.ActionResult
import com.wangzan101.mika.core.uistate.SystemAction
import com.wangzan101.mika.core.uistate.SystemActionResult
import java.util.TimeZone

/** Adapted from Mika's CalendarBulkWriter at eb10b34. Existing events are never read. */
internal object MikaCalendarWriter {
    data class Target(val id: Long, val name: String)

    fun hasAccess(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED &&
            context.checkSelfPermission(Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED

    fun defaultTarget(context: Context): Target? {
        val projection = arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.CALENDAR_DISPLAY_NAME)
        val where = "${CalendarContract.Calendars.VISIBLE} = 1 AND " +
            "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ${CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR}"
        val order = "${CalendarContract.Calendars.IS_PRIMARY} DESC, ${CalendarContract.Calendars._ID} ASC"
        return context.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, projection, where, null, order)
            ?.use { cursor -> if (cursor.moveToFirst()) Target(cursor.getLong(0), cursor.getString(1).orEmpty()) else null }
    }

    fun insert(
        context: Context,
        calendar: Target,
        events: List<SystemAction.AddCalendarEvent>,
    ): SystemActionResult {
        if (!hasAccess(context)) return failed("calendar_permission_required")
        val zone = TimeZone.getDefault().id
        val operations = events.mapTo(ArrayList()) { event ->
            ContentProviderOperation.newInsert(CalendarContract.Events.CONTENT_URI)
                .withValue(CalendarContract.Events.CALENDAR_ID, calendar.id)
                .withValue(CalendarContract.Events.TITLE, event.title)
                .withValue(CalendarContract.Events.DTSTART, event.beginMs)
                .withValue(CalendarContract.Events.DTEND, event.endMs ?: event.beginMs + if (event.allDay) DAY else HOUR)
                .withValue(CalendarContract.Events.ALL_DAY, if (event.allDay) 1 else 0)
                .withValue(CalendarContract.Events.EVENT_TIMEZONE, if (event.allDay) "UTC" else zone)
                .withValue(CalendarContract.Events.EVENT_LOCATION, event.location)
                .withValue(CalendarContract.Events.DESCRIPTION, event.description)
                .build()
        }
        val uris = try {
            context.contentResolver.applyBatch(CalendarContract.AUTHORITY, operations).map { it.uri }
        } catch (_: Exception) {
            return failed("calendar_write_unconfirmed")
        }
        if (uris.size != events.size || uris.any { it == null || !isEventUri(it) }) {
            return failed("calendar_write_unconfirmed")
        }
        return SystemActionResult(ActionResult.Ok(), eventUris = uris.filterNotNull().map(Uri::toString))
    }

    private fun isEventUri(uri: Uri): Boolean = uri.scheme == "content" && uri.authority == CalendarContract.AUTHORITY &&
        uri.pathSegments.size == 2 && uri.pathSegments[0] == "events" &&
        runCatching { ContentUris.parseId(uri) > 0 }.getOrDefault(false)

    private fun failed(reason: String) = SystemActionResult(ActionResult.Failed(reason))

    private const val HOUR = 3_600_000L
    private const val DAY = 24 * HOUR
}
