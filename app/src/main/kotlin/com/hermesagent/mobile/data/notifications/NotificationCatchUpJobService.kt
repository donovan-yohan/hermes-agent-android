package com.hermesagent.mobile.data.notifications

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*

/** Implemented by the Application's lightweight graph only. Must not start
 * live sockets, pending sends, foreground services, SSH or interactive login. */
internal interface NotificationCatchUpHost { suspend fun runNotificationCatchUp() }
internal object NotificationCatchUpScheduler {
    private const val JOB_ID = 0x484e01
    fun schedule(context: Context) {
        val scheduler = context.getSystemService(JobScheduler::class.java)
        if (scheduler.getPendingJob(JOB_ID) != null) return
        val job = JobInfo.Builder(JOB_ID, ComponentName(context, NotificationCatchUpJobService::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPeriodic(TimeUnit.MINUTES.toMillis(30), TimeUnit.MINUTES.toMillis(10))
            .setPersisted(true)
            .build()
        scheduler.schedule(job)
    }
    fun cancel(context: Context) { context.getSystemService(JobScheduler::class.java).cancel(JOB_ID) }
}
class NotificationCatchUpJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var work: Job? = null
    override fun onStartJob(params: JobParameters?): Boolean {
        val host = application as? NotificationCatchUpHost ?: return false
        if (params == null) return false
        work?.cancel()
        work = scope.launch {
            try { withTimeout(55_000) { host.runNotificationCatchUp() } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Next periodic tick retries. Never expose raw failures. */ }
            finally { if (isActive) jobFinished(params, false) }
        }
        return true
    }
    override fun onStopJob(params: JobParameters?): Boolean { work?.cancel(); work = null; return false }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
