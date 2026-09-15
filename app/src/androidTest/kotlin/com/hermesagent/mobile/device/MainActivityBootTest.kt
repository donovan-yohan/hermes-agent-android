package com.hermesagent.mobile.device

import android.os.StrictMode
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hermesagent.mobile.MainActivity
import org.junit.After
import org.junit.Before
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
    private lateinit var previousPolicy: StrictMode.ThreadPolicy

    @Before
    fun enableStrictNetworkPolicy() {
        previousPolicy = StrictMode.getThreadPolicy()
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder(previousPolicy)
                .detectNetwork()
                .penaltyDeath()
                .build(),
        )
    }

    @After
    fun restoreStrictNetworkPolicy() {
        StrictMode.setThreadPolicy(previousPolicy)
    }

    @Test
    fun coldLaunchDoesNotPerformNetworkWorkOnMainThread() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                check(!activity.isFinishing) { "MainActivity finished during cold launch" }
            }
        }
    }
}
