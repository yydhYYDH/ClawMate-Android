package com.clawmate.platform.gui

import android.app.Activity
import android.app.AlarmManager
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.provider.AlarmClock
import android.provider.CalendarContract
import com.wangzan101.mika.core.uistate.ActionResult
import com.wangzan101.mika.core.uistate.ScreenPort
import com.wangzan101.mika.core.uistate.ScreenSnapshot
import com.wangzan101.mika.core.uistate.SystemAction
import com.wangzan101.mika.core.uistate.SystemActionResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

internal val MIKA_SYSTEM_TOOLS = setOf(
    "phone_set_alarm", "phone_set_timer", "phone_show_alarms", "phone_add_calendar_event",
    "ask_user", "end_not_done",
)
internal val MIKA_CALENDAR_BATCH_TOOLS = setOf("phone_calendar_bulk_insert", "ask_user", "end_not_done")
internal const val MIKA_CALENDAR_PERMISSION_GUIDANCE =
    "请在系统设置的 ClawMate 应用权限中允许日历访问，再重新发送批量写入请求"

/** System actions have no screen attached and use only the platform intents listed here. */
internal class MikaSystemScreen(private val activity: Activity, private val goal: String) : ScreenPort {
    private data class Dispatch(val result: SystemActionResult, val receipt: MikaPhoneResult)
    private val completed = linkedMapOf<SystemAction, Dispatch>()
    private var batch: Pair<List<SystemAction.AddCalendarEvent>, Dispatch>? = null

    private fun receipts() = completed.values.map { it.receipt } + listOfNotNull(batch?.second?.receipt)

    val receipt: MikaPhoneResult?
        get() {
            val replies = receipts()
            if (replies.size <= 1) return replies.singleOrNull()
            val status = replies.map { it.status }.distinct().singleOrNull() ?: "partial"
            return MikaPhoneResult(status, replies.joinToString("\n") { it.message })
        }

    override suspend fun systemAction(action: SystemAction): SystemActionResult {
        completed[action]?.let { return it.result }
        if (waitingForUser()) {
            return SystemActionResult(ActionResult.Failed("user_control_required"))
        }
        return dispatch(action).also { completed[action] = it }.result
    }

    override suspend fun bulkCalendarInsert(events: List<SystemAction.AddCalendarEvent>): SystemActionResult {
        batch?.let { (requested, answer) ->
            return if (requested == events) answer.result else SystemActionResult(ActionResult.Failed("calendar_batch_already_run"))
        }
        if (waitingForUser()) return SystemActionResult(ActionResult.Failed("user_control_required"))
        return writeCalendar(events).also { batch = events.toList() to it }.result
    }

    private fun waitingForUser(): Boolean = receipts().any {
        it.status in setOf("cancelled", "opened", "needs_user")
    } || batch?.second?.receipt?.status == "unverified"

    private suspend fun writeCalendar(events: List<SystemAction.AddCalendarEvent>): Dispatch {
        if (events.size !in 1..100) return fail("invalid_calendar_batch", "批量日程需要 1–100 条")
        if (!MikaCalendarWriter.hasAccess(activity)) return calendarPermission()
        val target = try {
            withContext(Dispatchers.IO) { MikaCalendarWriter.defaultTarget(activity) }
        } catch (_: SecurityException) {
            return calendarPermission()
        } catch (_: RuntimeException) {
            currentCoroutineContext().ensureActive()
            return fail("calendar_unavailable", "暂时无法访问日历提供方")
        } ?: return fail("no_writable_calendar", "没有可写入的日历，请在系统日历中创建或启用日历")
        val calendar = target.name.ifBlank { "系统日历" }
        val entries = events.joinToString("\n\n") { event -> describe(event, utcAllDay = true) }
        if (!confirm(calendar, "写入 ${events.size} 条日程", entries)) {
            return fail("user_cancelled", "已取消批量写入日程", "cancelled")
        }
        GuiControlEntry.foreground(activity)
        val result = withContext(Dispatchers.IO) { MikaCalendarWriter.insert(activity, target, events) }
        return when (result.result) {
            is ActionResult.Ok -> Dispatch(result, MikaPhoneResult("success", "日历提供方已确认保存 ${events.size} 条日程到「$calendar」"))
            ActionResult.Failed("calendar_permission_required") -> calendarPermission()
            else -> Dispatch(result, MikaPhoneResult("unverified", "日历未能确认整批写入，请核对现有日程后再试"))
        }
    }

    private fun calendarPermission() = fail(
        "calendar_permission_required", MIKA_CALENDAR_PERMISSION_GUIDANCE,
    )

    private suspend fun dispatch(action: SystemAction): Dispatch {
        val intent = intentFor(action)
        val manager = activity.packageManager
        val handlers = manager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            .filter { it.activityInfo.exported && it.activityInfo.enabled }
        val preferred = manager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo
        val handler = handlers.firstOrNull {
            preferred != null && it.activityInfo.packageName == preferred.packageName && it.activityInfo.name == preferred.name
        } ?: handlers.firstOrNull { it.activityInfo.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0 }
            ?: handlers.singleOrNull()
            ?: return fail(SystemActionResult.NOT_SUPPORTED, "手机上没有可处理此操作的时钟或日历应用")
        val name = handler.loadLabel(manager).toString()
        val details = describe(action)
        if (!confirm(name, details)) {
            return fail("user_cancelled", "已取消「$details」", "cancelled")
        }
        delay(250)
        currentCoroutineContext().ensureActive()
        val alarmClock = activity.getSystemService(AlarmManager::class.java)
        val before = runCatching { alarmClock?.nextAlarmClock?.triggerTime }
        try {
            GuiControlEntry.foreground(activity)
            intent.component = ComponentName(handler.activityInfo.packageName, handler.activityInfo.name)
            activity.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            return fail("launch_failed", "时钟或日历应用未能打开")
        } catch (_: SecurityException) {
            return fail("permission_denied", "Android 拒绝了系统操作")
        } catch (_: IllegalStateException) {
            return fail("foreground_changed", "ClawMate 已离开前台，请返回后重试")
        }

        val (reply, next) = if (action is SystemAction.SetAlarm && before.isSuccess && alarmClock != null) {
            val now = ZonedDateTime.now()
            val expected = nextOccurrence(action, now)
            val matching = withTimeoutOrNull(3_500) {
                while (true) {
                    val at = runCatching { alarmClock.nextAlarmClock?.triggerTime }.getOrNull()
                    if (at == expected) return@withTimeoutOrNull at
                    delay(150)
                }
                @Suppress("UNREACHABLE_CODE")
                null
            }
            val reported = matching ?: runCatching { alarmClock.nextAlarmClock?.triggerTime }.getOrNull()
            val text = when {
                matching == null -> MikaPhoneResult("unverified", "已将 $details 提交给$name，系统尚未核实，请打开时钟查看")
                matching == before.getOrNull() -> MikaPhoneResult("success", "系统已有这次闹钟，时间为 ${localDateTime(matching)}")
                else -> MikaPhoneResult("success", "系统已核实闹钟，时间为 ${localDateTime(matching)}")
            }
            text to reported
        } else {
            val text = when (action) {
                is SystemAction.SetAlarm -> MikaPhoneResult("unverified", "已将 $details 提交给$name，请打开时钟核对")
                is SystemAction.SetTimer -> MikaPhoneResult("unverified", "已将 $details 提交给$name，请打开时钟核对")
                SystemAction.ShowAlarms -> MikaPhoneResult("opened", "已请求打开${name}的闹钟列表")
                is SystemAction.AddCalendarEvent -> MikaPhoneResult("needs_user", "已请求打开${name}的日程表单，请核对并保存")
            }
            text to null
        }
        return Dispatch(SystemActionResult(ActionResult.Ok(), handler.activityInfo.packageName,
            before.getOrNull(), next, before.isSuccess && alarmClock != null), reply)
    }

    private suspend fun confirm(app: String, title: String, entries: String? = null): Boolean {
        GuiControlEntry.foreground(activity)
        val reply = CompletableDeferred<Boolean>()
        val message = if (entries == null) "将交给手机上的 $app。\n\n你的请求\n$goal"
            else "写入日历「$app」\n\n$entries\n\n你的请求\n$goal"
        val dialog = AlertDialog.Builder(activity).setTitle(title)
            .setMessage(message)
            .setPositiveButton("确认") { _, _ -> reply.complete(true) }
            .setNegativeButton("取消", null).create()
        dialog.setOnDismissListener { reply.complete(false) }
        dialog.show()
        return try { withTimeoutOrNull(30_000) { reply.await() } == true } finally { dialog.dismiss() }
    }

    private fun fail(code: String, message: String, status: String = "blocked") =
        Dispatch(SystemActionResult(ActionResult.Failed(code)), MikaPhoneResult(status, message))

    override suspend fun snapshot() = ScreenSnapshot("p0", null, emptyList(), 1, 1, hasWindow = false)
    override suspend fun screenshotPng(): ByteArray? = null
    override suspend fun listApps(): List<Pair<String, String>> = emptyList()
    override suspend fun waitStable(timeoutMs: Long) = true
    override suspend fun tapRef(ref: String, snapshotId: String) = ActionResult.Failed("no_screen")
    override suspend fun tapXY(x: Int, y: Int) = ActionResult.Failed("no_screen")
    override suspend fun typeText(ref: String?, snapshotId: String?, text: String) = ActionResult.Failed("no_screen")
    override suspend fun clearText(ref: String?, snapshotId: String?) = ActionResult.Failed("no_screen")
    override suspend fun scroll(ref: String?, snapshotId: String?, down: Boolean) = ActionResult.Failed("no_screen")
    override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long) = ActionResult.Failed("no_screen")
    override suspend fun back() = ActionResult.Failed("no_screen")
    override suspend fun pressKey(keyCode: Int) = ActionResult.Failed("no_screen")
    override suspend fun openApp(packageName: String, cold: Boolean) = ActionResult.Failed("no_screen")

    companion object {
        // Intent fields ported from Mika cap/phone/shell/SystemIntents.kt at eb10b34.
        fun intentFor(action: SystemAction): Intent = when (action) {
            is SystemAction.SetAlarm -> Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, action.hour)
                putExtra(AlarmClock.EXTRA_MINUTES, action.minute)
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                action.label?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) }
                if (action.days.isNotEmpty()) putIntegerArrayListExtra(AlarmClock.EXTRA_DAYS, ArrayList(action.days))
                action.vibrate?.let { putExtra(AlarmClock.EXTRA_VIBRATE, it) }
            }
            is SystemAction.SetTimer -> Intent(AlarmClock.ACTION_SET_TIMER).apply {
                putExtra(AlarmClock.EXTRA_LENGTH, action.seconds)
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                action.label?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) }
            }
            SystemAction.ShowAlarms -> Intent(AlarmClock.ACTION_SHOW_ALARMS)
            is SystemAction.AddCalendarEvent -> Intent(Intent.ACTION_INSERT).apply {
                setDataAndType(CalendarContract.Events.CONTENT_URI, "vnd.android.cursor.dir/event")
                putExtra(CalendarContract.Events.TITLE, action.title)
                putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, action.beginMs)
                action.endMs?.let { putExtra(CalendarContract.EXTRA_EVENT_END_TIME, it) }
                putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, action.allDay)
                action.location?.let { putExtra(CalendarContract.Events.EVENT_LOCATION, it) }
                action.description?.let { putExtra(CalendarContract.Events.DESCRIPTION, it) }
            }
        }

        internal fun nextOccurrence(alarm: SystemAction.SetAlarm, now: ZonedDateTime): Long {
            return (0L..7L).asSequence().map {
                now.toLocalDate().plusDays(it).atTime(alarm.hour, alarm.minute).atZone(now.zone)
            }.first {
                it.isAfter(now) && (alarm.days.isEmpty() || it.dayOfWeek.value % 7 + 1 in alarm.days)
            }.toInstant().toEpochMilli()
        }

        private fun localDateTime(timestamp: Long): String = DateTimeFormatter.ofPattern("MM-dd HH:mm")
            .format(Instant.ofEpochMilli(timestamp).atZone(java.time.ZoneId.systemDefault()))

        private fun describe(action: SystemAction, utcAllDay: Boolean = false): String = when (action) {
            is SystemAction.SetAlarm -> {
                val time = String.format(Locale.ROOT, "%02d:%02d", action.hour, action.minute)
                val days = listOf("日", "一", "二", "三", "四", "五", "六")
                val repeat = if (action.days.isEmpty()) "响一次" else action.days.joinToString("、") { "周" + days[it - 1] }
                val vibration = action.vibrate?.let { if (it) "，振动" else "，关闭振动" }.orEmpty()
                "设置 $time 闹钟（$repeat$vibration）" + (action.label?.let { " · $it" } ?: "")
            }
            is SystemAction.SetTimer -> "设置 ${action.seconds} 秒计时器" + (action.label?.let { " · $it" } ?: "")
            SystemAction.ShowAlarms -> "打开闹钟列表"
            is SystemAction.AddCalendarEvent -> buildString {
                append(if (utcAllDay) action.title else "打开「${action.title}」日程表单")
                if (utcAllDay && action.allDay) {
                    val first = Instant.ofEpochMilli(action.beginMs).atOffset(ZoneOffset.UTC).toLocalDate()
                    val last = action.endMs?.takeIf { it > action.beginMs }
                        ?.let { Instant.ofEpochMilli(it - 1).atOffset(ZoneOffset.UTC).toLocalDate() }
                    append("\n$first")
                    if (last != null && last != first) append(" 至 $last")
                } else {
                    append("\n${localDateTime(action.beginMs)}")
                    action.endMs?.let { append(" 至 ${localDateTime(it)}") }
                }
                if (action.allDay) append(" · 全天")
                action.location?.let { append("\n地点 $it") }
                action.description?.let { append("\n备注 $it") }
            }
        }
    }
}
