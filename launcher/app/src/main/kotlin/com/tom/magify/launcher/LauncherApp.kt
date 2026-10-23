package com.tom.magify.launcher

import android.content.pm.ResolveInfo

data class LauncherApp(
    val label: String,
    val packageName: String,
    val activityName: String,
    val resolveInfo: ResolveInfo
) {
    val iconCacheKey: String get() = "$packageName/$activityName"
}

data class LauncherFolder(
    val name: String,
    val apps: List<LauncherApp>
)
