package com.tom.magify.launcher

import android.content.Context

/** Counts applications that were actually opened from the search experience. */
object SearchHistory {
    private const val PREFS_NAME = "search_history"
    private const val COUNTS_PREFIX = "count_"
    private const val VERSION_KEY = "version"

    // Bump this to wipe every stored count the next time the launcher starts.
    private const val CURRENT_VERSION = 2

    private fun preferences(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun resetIfOutdated(context: Context) {
        val prefs = preferences(context)
        if (prefs.getInt(VERSION_KEY, 0) >= CURRENT_VERSION) return
        prefs.edit().clear().putInt(VERSION_KEY, CURRENT_VERSION).apply()
    }

    fun increment(context: Context, packageName: String) {
        val prefs = preferences(context)
        val key = COUNTS_PREFIX + packageName
        prefs.edit().putInt(key, prefs.getInt(key, 0) + 1).apply()
    }

    fun counts(context: Context): Map<String, Int> =
        preferences(context).all
            .asSequence()
            .filter { it.key.startsWith(COUNTS_PREFIX) && it.value is Number }
            .associate { (key, value) ->
                key.removePrefix(COUNTS_PREFIX) to (value as Number).toInt()
            }
}
