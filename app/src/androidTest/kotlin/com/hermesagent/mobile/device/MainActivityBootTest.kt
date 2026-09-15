package com.hermesagent.mobile.device

import android.os.StrictMode
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hermesagent.mobile.MainActivity
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The production Activity must survive a cold launch without doing blocking
 * network work on Android's main thread. The old transport implementation made
 * its suspend function look asynchronous while calling OkHttp synchronously,
 * which only failed on a real device when StrictMode caught the DNS lookup.
 */
@RunWith(AndroidJUnit4::class)
class MainActivityBootTest {
    @Test
    fun coldLaunchDoesNotPerformNetworkWorkOnMainThread() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        lateinit var previousPolicy: StrictMode.ThreadPolicy
        instrumentation.runOnMainSync {
            previousPolicy = StrictMode.getThreadPolicy()
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder(previousPolicy)
                    .detectNetwork()
                    .penaltyDeath()
                    .build(),
            )
        }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    check(!activity.isFinishing) { "MainActivity finished during cold launch" }
                }
            }
        } finally {
            instrumentation.runOnMainSync { StrictMode.setThreadPolicy(previousPolicy) }
        }
    }
}
