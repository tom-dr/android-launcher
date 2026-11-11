package com.tom.magify.launcher

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process
import java.util.concurrent.TimeUnit

private val USAGE_WINDOW_MILLIS = TimeUnit.DAYS.toMillis(30)

/**
 * Reads how much foreground time each app has accumulated over the last 30
 * days, so folders can be sorted by actual usage instead of alphabetically.
 * Requires the special "Usage access" permission, which the user has to
 * grant manually in system Settings — there is no runtime dialog for it.
 */
object UsageScores {

    fun hasAccess(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /** packageName -> total foreground time (ms) over the trailing window. 0 if no access or never used. */
    fun get(context: Context): Map<String, Long> {
        if (!hasAccess(context)) return emptyMap()
        return aggregatedStats(context).mapValues { it.value.totalTimeInForeground }
    }

    /** packageName -> last time brought to foreground (epoch ms) over the trailing window. Absent if never used in it. */
    fun getLastUsed(context: Context): Map<String, Long> {
        if (!hasAccess(context)) return emptyMap()
        return aggregatedStats(context).mapValues { it.value.lastTimeUsed }
    }

    private fun aggregatedStats(context: Context) = run {
        val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val end = System.currentTimeMillis()
        val start = end - USAGE_WINDOW_MILLIS
        usageStatsManager.queryAndAggregateUsageStats(start, end)
    }
}
