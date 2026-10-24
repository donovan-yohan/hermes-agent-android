package com.hermesagent.mobile.data.notifications

import android.app.Application
import android.app.job.JobScheduler
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.Robolectric
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain

class BackgroundHostApplication : Application(), NotificationCatchUpHost {
    var calls = 0
    override suspend fun runNotificationCatchUp() { calls++ }
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class NotificationCatchUpJobTest {
    @Test
    @Config(application = BackgroundHostApplication::class)
    fun jobCallsOnlyLightweightApplicationHost() = kotlinx.coroutines.test.runTest {
        val dispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler)
        kotlinx.coroutines.Dispatchers.setMain(dispatcher)
        val service = Robolectric.buildService(NotificationCatchUpJobService::class.java).create().get()
        try {
            val params = org.robolectric.shadow.api.Shadow.newInstanceOf(android.app.job.JobParameters::class.java)
            assertTrue(service.onStartJob(params))
            testScheduler.runCurrent()
            assertEquals(1, (service.application as BackgroundHostApplication).calls)
        } finally { service.onDestroy(); kotlinx.coroutines.Dispatchers.resetMain() }
    }

    @Suppress("DEPRECATION")
    @Test fun schedulesOnePersistedNetworkConstrainedThirtyMinuteJob() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        NotificationCatchUpScheduler.schedule(context)
        val enrolled = context.getSystemService(JobScheduler::class.java).allPendingJobs.single()
        NotificationCatchUpScheduler.schedule(context)
        val jobs = context.getSystemService(JobScheduler::class.java).allPendingJobs
        assertEquals(1, jobs.size)
        val job = jobs.single()
        assertSame("Process creation must not postpone an already scheduled periodic read", enrolled, job)
        assertTrue(job.isPersisted)
        assertTrue(job.isPeriodic)
        assertEquals(TimeUnit.MINUTES.toMillis(30), job.intervalMillis)
        assertEquals(TimeUnit.MINUTES.toMillis(10), job.flexMillis)
        assertEquals(android.app.job.JobInfo.NETWORK_TYPE_ANY, job.networkType)
        assertEquals(NotificationCatchUpJobService::class.java.name, job.service.className)
    }
    @Test fun missingBackgroundHostFinishesWithoutInitializingInteractiveGraph() {
        val service = Robolectric.buildService(NotificationCatchUpJobService::class.java).create().get()
        assertFalse(service.onStartJob(null))
        service.onDestroy()
    }
}
