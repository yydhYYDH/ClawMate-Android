package com.clawmate.platform.reminder

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID

/** Owns only reminder state, the system alarm and its own notification. No app/task dependencies. */
class CollectionReminderEngine(context: Context, private val clock: () -> Long = System::currentTimeMillis) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val alarms = app.getSystemService(AlarmManager::class.java)
    private val notifications = app.getSystemService(NotificationManager::class.java)
    private val zone get() = ZoneId.systemDefault()

    companion object {
        const val PREFERENCES = "collection-reminder-android-v1"
        const val CHANNEL = "clawmate.collection.daily"
        const val NOTIFICATION_ID = 21900
        const val ACTION = "com.clawmate.collection.REMIND"
        const val TOKEN = "collectionReminderToken"
        const val DUE = "collectionReminderDueAt"
        private const val ALARM_ID = 21901
        private val lock = Any()
    }
    private fun state(): JSONObject = try { JSONObject(prefs.getString("state", "{}")!!) }
        catch (error: Exception) { throw IllegalStateException("采集提醒状态损坏，请关闭提醒后重新设置", error) }
    private fun save(value: JSONObject) {
        value.put("version", prefs.getLong("version", 0) + 1)
        check(prefs.edit().putString("state", value.toString()).putLong("version", value.getLong("version")).commit()) { "提醒保存失败，请重试" }
    }
    fun version(): Long = prefs.getLong("version", 0)
    fun initialize(activityClass: String, hour: Int, minute: Int): String = synchronized(lock) {
        require(activityClass.startsWith(app.packageName + ".")) { "提醒入口必须属于当前应用" }
        val value = state()
        value.put("activity", activityClass)
        if (!value.optBoolean("initialized")) {
            require(ReminderPolicy.validTime(hour, minute)) { "提醒时间无效" }
            value.put("hour", hour).put("minute", minute).put("initialized", true)
        }
        save(value); refresh()
    }
    private fun channel() {
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, "每日采集提醒", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "按设定时间附近提醒；点击后由你确认执行任务"
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        })
    }
    private fun allowed(): Boolean =
        (Build.VERSION.SDK_INT < 33 || app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            notifications.areNotificationsEnabled() && notifications.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
    fun snapshot(): String = synchronized(lock) {
        val value = state()
        val enabled = value.optBoolean("enabled")
        val registered = enabled && allowed() && value.optLong("scheduledDue") > 0 &&
            alarm("", 0, PendingIntent.FLAG_NO_CREATE) != null
        JSONObject().put("v", 1).put("version", version()).put("enabled", enabled)
            .put("hour", value.optInt("hour", 21)).put("minute", value.optInt("minute", 0))
            .put("planToken", value.optString("token")).put("lastCollectedAt", value.optLong("lastCollectedAt"))
            .put("handledDays", value.optJSONArray("handledDays") ?: JSONArray())
            .put("reminderIds", if (registered) JSONArray().put(ALARM_ID) else JSONArray())
            .put("registeredStamp", if (registered) value.optString("token") + ":" + value.optLong("scheduledDue") else "")
            .put("errorText", if (enabled && !allowed()) "通知未授权或提醒渠道已关闭，请在系统设置允许后重试" else value.optString("error"))
            .put("scheduledDue", value.optLong("scheduledDue")).toString()
    }
    private fun alarm(token: String, due: Long, flags: Int = PendingIntent.FLAG_UPDATE_CURRENT): PendingIntent? {
        val intent = Intent(app, CollectionReminderReceiver::class.java).setAction(ACTION)
            .putExtra(TOKEN, token).putExtra(DUE, due)
        return PendingIntent.getBroadcast(app, ALARM_ID, intent, flags or PendingIntent.FLAG_IMMUTABLE)
    }
    private fun cancelOwn() {
        alarm("", 0, PendingIntent.FLAG_NO_CREATE)?.let { alarms.cancel(it); it.cancel() }
        notifications.cancel(NOTIFICATION_ID)
    }
    private fun fail(value: JSONObject, error: Exception): Nothing {
        cancelOwn()
        value.put("enabled", false).put("scheduledDue", 0).put("issuedDue", 0).put("error", error.message ?: "提醒注册失败")
        save(value)
        throw error
    }
    private fun schedule(value: JSONObject) {
        check(allowed()) { "通知未授权或渠道已关闭，请在系统设置中允许通知后重试" }
        check(value.optString("activity").startsWith(app.packageName + ".")) { "请先打开应用初始化提醒入口" }
        val due = ReminderPolicy.next(value.optInt("hour", 21), value.optInt("minute", 0), clock(), zone)
        val pending = alarm(value.getString("token"), due)!!
        // Inexact: no exact-alarm permission, no wakeful service or GUI work.
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, due, pending)
        try { save(value.put("scheduledDue", due).put("error", "")) }
        catch (error: Exception) { alarms.cancel(pending); throw error }
    }
    fun refresh(clockChanged: Boolean = false): String = synchronized(lock) {
        val value = state()
        if (value.optBoolean("enabled")) {
            channel()
            if (clockChanged) {
                cancelOwn(); value.put("token", UUID.randomUUID().toString()).put("issuedDue", 0)
            }
            try {
                check(allowed()) { "通知未授权或渠道已关闭，请在系统设置中允许通知后重试" }
                if (clockChanged || value.optLong("scheduledDue") <= 0 || alarm("", 0, PendingIntent.FLAG_NO_CREATE) == null) schedule(value)
            } catch (error: Exception) { fail(value, error) }
        }
        snapshot()
    }
    fun setEnabled(enabled: Boolean): String = synchronized(lock) {
        val value = if (enabled) state() else runCatching { state() }.getOrElse { JSONObject() }
        cancelOwn()
        value.put("enabled", enabled).put("token", UUID.randomUUID().toString()).put("issuedDue", 0)
            .put("scheduledDue", 0).put("error", "").put("initialized", true)
        save(value)
        if (enabled) {
            channel()
            try { schedule(value) } catch (error: Exception) { fail(value, error) }
        }
        snapshot()
    }
    fun setTime(hour: Int, minute: Int): String = synchronized(lock) {
        require(ReminderPolicy.validTime(hour, minute)) { "提醒时间无效" }
        val value = state()
        if (hour == value.optInt("hour", 21) && minute == value.optInt("minute", 0)) return@synchronized snapshot()
        cancelOwn()
        value.put("hour", hour).put("minute", minute).put("token", UUID.randomUUID().toString())
            .put("issuedDue", 0).put("scheduledDue", 0).put("error", "").put("initialized", true)
        save(value)
        if (value.optBoolean("enabled")) try { schedule(value) } catch (error: Exception) { fail(value, error) }
        snapshot()
    }
    fun recordCollection(): String = synchronized(lock) {
        val value = state().put("lastCollectedAt", clock())
        save(value); snapshot()
    }
    fun pending(token: String, due: Long): String = synchronized(lock) {
        val value = state()
        val days = value.optJSONArray("handledDays") ?: JSONArray()
        if (!ReminderPolicy.canClaim(value.optBoolean("enabled"), token, value.optString("token"), due,
                value.optLong("issuedDue"), (0 until days.length()).map { days.getString(it) }, clock(), zone)) return@synchronized ""
        JSONObject().put("planToken", token).put("dueAt", due).toString()
    }
    fun claim(request: String): Boolean = synchronized(lock) {
        val parsed = runCatching { JSONObject(request) }.getOrNull() ?: return@synchronized false
        if (pending(parsed.optString("planToken"), parsed.optLong("dueAt")).isEmpty()) return@synchronized false
        val value = state()
        val previous = value.optJSONArray("handledDays") ?: JSONArray()
        val days = JSONArray()
        for (index in (previous.length() - 89).coerceAtLeast(0) until previous.length()) days.put(previous.getString(index))
        days.put(ReminderPolicy.day(parsed.getLong("dueAt"), zone))
        save(value.put("handledDays", days)) // Commit replay protection before any task starts.
        notifications.cancel(NOTIFICATION_ID)
        true
    }
    fun onAlarm(token: String, due: Long) = synchronized(lock) {
        val value = state()
        if (!value.optBoolean("enabled") || token != value.optString("token") || due != value.optLong("scheduledDue")) return@synchronized
        val now = clock()
        if (due <= 0 || due > now) return@synchronized
        try { schedule(value) } catch (error: Exception) { fail(value, error) }
        if (now - due >= ReminderPolicy.MAX_AGE_MS || value.optString("deliveredDay") == ReminderPolicy.day(due, zone)) return@synchronized
        save(value.put("issuedDue", due).put("deliveredDay", ReminderPolicy.day(due, zone)))
        val open = Intent().setComponent(ComponentName(app.packageName, value.getString("activity")))
            .setAction(ACTION).setData(Uri.parse("clawmate-reminder://collection/$token/$due"))
            .putExtra(TOKEN, token).putExtra(DUE, due).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val content = PendingIntent.getActivity(app, NOTIFICATION_ID, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val last = value.optLong("lastCollectedAt")
        val text = if (last <= 0) "还没有成功保存采集摘要，点击打开任务页。" else {
            val days = ChronoUnit.DAYS.between(Instant.ofEpochMilli(last).atZone(zone).toLocalDate(), Instant.ofEpochMilli(now).atZone(zone).toLocalDate()).coerceAtLeast(0)
            if (days == 0L) "今天已保存采集摘要，也可以再次更新。" else "已连续${days}天未保存采集摘要，点击打开任务页。"
        }
        val public = Notification.Builder(app, CHANNEL).setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("ClawMate 采集提醒").setContentText("解锁后查看任务").build()
        val notification = Notification.Builder(app, CHANNEL).setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("该采集今天的足迹啦").setContentText(text).setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(content).setAutoCancel(true).setOnlyAlertOnce(true).setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(public).build()
        try { notifications.notify(NOTIFICATION_ID, notification) } catch (error: Exception) { fail(value, error) }
    }
}
