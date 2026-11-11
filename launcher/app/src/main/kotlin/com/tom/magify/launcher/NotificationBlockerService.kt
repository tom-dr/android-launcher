package com.tom.magify.launcher

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.Calendar

/** Cancels notifications for packages blocked by the currently selected profile. */
class NotificationBlockerService : NotificationListenerService() {

    private val profileConfig by lazy { ProfileConfig.load(this) }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val profile = profileConfig.currentProfile(this, Calendar.getInstance())
        if (sbn.packageName.lowercase() in profile.blockedPackages) {
            cancelNotification(sbn.key)
        }
    }
}
