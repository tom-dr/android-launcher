package com.tom.magify.launcher

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Checks the quotas every ~15 min while the launcher isn't in front (locked
 * phone, another app open), so crossing the 90 % mark can be notified.
 *
 * A job, not a plain background coroutine: Android 16's background firewall
 * cuts the launcher's network as soon as it leaves the screen, but a running
 * job with a network constraint is given access. While the phone sleeps,
 * Doze batches these runs into its maintenance windows, so they can be late.
 */
class AiQuotaJobService : JobService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var running: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val manager = (application as MagifyApplication).aiQuotas
        running = scope.launch {
            manager.refreshInBackground()
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        running?.cancel()
        // Periodic anyway: the next run will catch up.
        return false
    }

    companion object {
        private const val JOB_ID = 4201
        private const val INTERVAL_MS = 15 * 60_000L

        /** Idempotent: an already scheduled job keeps its timing. */
        fun schedule(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java)
            if (scheduler.getPendingJob(JOB_ID) != null) return
            // Called from Application.onCreate: a refusal must never crash the launcher.
            runCatching {
                scheduler.schedule(
                JobInfo.Builder(JOB_ID, ComponentName(context, AiQuotaJobService::class.java))
                    .setPeriodic(INTERVAL_MS)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPersisted(true)
                    .build()
                )
            }
        }
    }
}
