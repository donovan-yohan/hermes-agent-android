package com.hermesagent.mobile.ui.chat

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import com.hermesagent.mobile.data.session.ComposerBackgroundProcess
import com.hermesagent.mobile.data.session.ComposerBackgroundProcessState
import com.hermesagent.mobile.data.session.ComposerStatusState
import com.hermesagent.mobile.ui.theme.AppearanceSelection
import com.hermesagent.mobile.ui.theme.HermesTheme
import com.hermesagent.mobile.ui.theme.HermesThemeMode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The bounded stand-in for Desktop's 5 second `process.list` interval
 * (`apps/desktop/src/app/chat/composer/status-stack/index.tsx:41-43,151-163`
 * @ `564aef2946`).
 *
 * Time is the test's, never the wall's: the rungs are driven by the Compose
 * test clock, so what is asserted is the *shape* — armed by a Running claim,
 * gated on the foreground, and finite.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ComposerSilentExitReconcileTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private var refreshes = 0
    private val status = mutableStateOf(ComposerStatusState(backgroundProcesses = listOf(running("build"))))

    @Test
    fun `a row that claims Running is re-checked on a ladder that ends`() {
        setContent()

        compose.mainClock.advanceTimeBy(9_000)
        assertEquals("the first rung has not come due", 0, refreshes)
        compose.mainClock.advanceTimeBy(2_000)
        assertEquals(1, refreshes)
        compose.mainClock.advanceTimeBy(30_000)
        assertEquals(2, refreshes)
        compose.mainClock.advanceTimeBy(90_000)
        assertEquals(3, refreshes)

        // The point of the whole design: it is a ladder, not an interval. Ten
        // more minutes of the same unchanged claim buy no further round trips,
        // and the Background group's Refresh stays the explicit escape.
        compose.mainClock.advanceTimeBy(600_000)
        assertEquals(3, refreshes)
    }

    @Test
    fun `a settled process arms nothing at all`() {
        status.value = ComposerStatusState(
            backgroundProcesses = listOf(
                ComposerBackgroundProcess("build", "Build", ComposerBackgroundProcessState.Done),
            ),
        )
        setContent()

        compose.mainClock.advanceTimeBy(600_000)
        assertEquals("nothing claims to be running, so nothing is asked", 0, refreshes)
    }

    @Test
    fun `the answer retiring the row ends the ladder with it`() {
        setContent()

        compose.mainClock.advanceTimeBy(10_000)
        assertEquals(1, refreshes)
        // What a refresh that finds a dead process does to the state it feeds.
        compose.runOnIdle {
            status.value = ComposerStatusState(
                backgroundProcesses = listOf(
                    ComposerBackgroundProcess("build", "Build", ComposerBackgroundProcessState.Done),
                ),
            )
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(600_000)
        assertEquals(1, refreshes)
    }

    @Test
    fun `a new running process is new evidence, so it starts a fresh ladder`() {
        setContent()

        compose.mainClock.advanceTimeBy(130_000)
        assertEquals("the ladder is exhausted", 3, refreshes)
        compose.runOnIdle {
            status.value = ComposerStatusState(
                backgroundProcesses = listOf(running("build"), running("tests")),
            )
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(10_000)
        assertEquals(4, refreshes)
    }

    @Test
    fun `a backgrounded app asks nothing, and returning to it re-arms the ladder`() {
        setContent()

        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.mainClock.advanceTimeBy(600_000)
        assertEquals("a backgrounded app never wakes the radio for this", 0, refreshes)

        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.mainClock.advanceTimeBy(10_000)
        // Coming back is the edge a silent exit is most likely to hide behind,
        // and it costs one round trip rather than an interval's worth.
        assertEquals(1, refreshes)
    }

    private fun setContent() {
        compose.setContent {
            HermesTheme(AppearanceSelection("nous", HermesThemeMode.Dark)) {
                Box(Modifier.width(360.dp)) {
                    ComposerStatusStack(
                        activeSessionId = "session-a",
                        status = status.value,
                        onRefreshProcesses = { refreshes += 1 },
                    )
                }
            }
        }
    }

    private companion object {
        fun running(id: String) =
            ComposerBackgroundProcess(id, "Build", ComposerBackgroundProcessState.Running)
    }
}
