package com.clawmate.platform.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Delivers only an owned notification; never runs collection or opens UI. */
class CollectionReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val engine = CollectionReminderEngine(context)
        // Permission revocation or storage failure must not crash a background process.
        runCatching {
            if (intent.action == CollectionReminderEngine.ACTION) {
                engine.onAlarm(intent.getStringExtra(CollectionReminderEngine.TOKEN).orEmpty(), intent.getLongExtra(CollectionReminderEngine.DUE, 0))
            } else if (intent.action in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED,
                    Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED)) {
                engine.refresh(clockChanged = true)
            }
        }
    }
}
