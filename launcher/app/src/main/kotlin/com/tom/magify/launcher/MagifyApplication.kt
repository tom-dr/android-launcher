package com.tom.magify.launcher

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async

/**
 * Starts enumerating + decoding app icons the instant the process exists,
 * instead of waiting for MainActivity.onCreate(). By the time the first
 * frame is laid out, this background work is usually already done or close
 * to it, so the initial list render doesn't have to wait on it from scratch.
 */
class MagifyApplication : Application() {

    lateinit var repository: AppRepository
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    lateinit var preloadedFolders: Deferred<List<LauncherFolder>>
        private set

    override fun onCreate() {
        super.onCreate()
        repository = AppRepository(this)
        preloadedFolders = appScope.async { repository.buildFolders() }
    }
}
