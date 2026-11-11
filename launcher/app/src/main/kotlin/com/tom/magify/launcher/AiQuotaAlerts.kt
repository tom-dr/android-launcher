package com.tom.magify.launcher

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

/**
 * Notifications for the quota cards, only when a window changes stage:
 * - a session window goes from under [WARN_PERCENT] to at or above it,
 *   seen by a background check ([AiQuotaJobService]), never by a refresh
 *   made from the launcher, whose card already shows it;
 * - a window that was at or above [WARN_PERCENT] gets reset: an alarm set
 *   for its reset time fires it, locked phone or not, network or not.
 */
object AiQuotaAlerts {

    const val WARN_PERCENT = 90

    private const val CHANNEL_ID = "ai_quotas"
    private const val ACTION_RESET = "com.tom.magify.launcher.AI_QUOTA_RESET"
    private const val EXTRA_PROVIDER = "provider"
    private const val EXTRA_LABEL = "label"

    /** Called after every successful quota fetch. */
    fun onFreshQuotas(
        context: Context,
        provider: AiProvider,
        previous: List<QuotaWindow>,
        fresh: List<QuotaWindow>,
        notifyCrossings: Boolean
    ) {
        val now = System.currentTimeMillis()
        fresh.forEach { window ->
            if (notifyCrossings && isSession(window) && crossedWarning(previous, window, now)) {
                val reset = window.resetsAtMillis?.let { formatRemaining(it - now) }
                notify(
                    context,
                    id = "warn_${provider.name}_${window.label}".hashCode(),
                    title = "${provider.label} : ${window.label.lowercase()} à ${window.usedPercent} %",
                    text = reset?.let { "Réinitialisation $it" } ?: "Quota presque atteint"
                )
            }
            scheduleReset(context, provider, window, now)
        }
    }

    private fun isSession(window: QuotaWindow) = window.label.startsWith("Session")

    private fun crossedWarning(previous: List<QuotaWindow>, window: QuotaWindow, now: Long): Boolean {
        if (window.usedPercent < WARN_PERCENT) return false
        // Nothing known before (first fetch after sign-in): no crossing seen.
        val before = previous.firstOrNull { it.label == window.label } ?: return false
        // If that earlier cycle has ended since, this one started from zero.
        val beforePercent = if (before.resetsAtMillis != null && before.resetsAtMillis <= now) 0 else before.usedPercent
        return beforePercent < WARN_PERCENT
    }

    private fun scheduleReset(context: Context, provider: AiProvider, window: QuotaWindow, now: Long) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        val pending = resetIntent(context, provider, window.label)
        val resetAt = window.resetsAtMillis
        if (window.usedPercent < WARN_PERCENT || resetAt == null || resetAt <= now) {
            // Not in the alert stage (or already past): a reset changes nothing worth telling.
            alarms.cancel(pending)
            return
        }
        // Same PendingIntent each time, so this replaces the previous alarm.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, resetAt, pending)
        } else {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, resetAt, pending)
        }
    }

    private fun resetIntent(context: Context, provider: AiProvider, label: String): PendingIntent {
        val intent = Intent(context, AiQuotaResetReceiver::class.java)
            .setAction(ACTION_RESET)
            .putExtra(EXTRA_PROVIDER, provider.name)
            .putExtra(EXTRA_LABEL, label)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, "reset_${provider.name}_$label".hashCode(), intent, flags)
    }

    /** Receives the reset alarms. */
    class AiQuotaResetReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_RESET) return
            val provider = intent.getStringExtra(EXTRA_PROVIDER)
                ?.let { name -> AiProvider.entries.firstOrNull { it.name == name } } ?: return
            val label = intent.getStringExtra(EXTRA_LABEL) ?: return
            notify(
                context,
                id = "reset_${provider.name}_$label".hashCode(),
                title = "${provider.label} : ${label.lowercase()} réinitialisée",
                text = "Tes tokens sont de nouveau disponibles"
            )
        }
    }

    private fun notify(context: Context, id: Int, title: String, text: String) {
        // Android 13+ needs the runtime permission; without it, skip silently.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Quotas IA", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .build()
        manager.notify(id, notification)
    }

    /** "dans 2 h 05", "dans 3 j 4 h"; null once passed. */
    private fun formatRemaining(millis: Long): String? {
        val minutes = millis / 60_000
        return when {
            minutes <= 0 -> null
            minutes < 60 -> "dans $minutes min"
            minutes < 24 * 60 -> "dans ${minutes / 60} h %02d".format(minutes % 60)
            else -> "dans ${minutes / 1440} j ${(minutes / 60) % 24} h"
        }
    }
}
