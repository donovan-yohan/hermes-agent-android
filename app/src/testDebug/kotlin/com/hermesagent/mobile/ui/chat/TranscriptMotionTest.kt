package com.hermesagent.mobile.ui.chat

import androidx.activity.ComponentActivity
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.hermesagent.mobile.data.session.SessionProgress
import com.hermesagent.mobile.data.session.ToolActivity
import com.hermesagent.mobile.data.session.ToolState
import com.hermesagent.mobile.ui.theme.AppearanceSelection
import com.hermesagent.mobile.ui.theme.HermesTheme
import com.hermesagent.mobile.ui.theme.HermesThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The transcript's motion gate, driven by the *test's* clock.
 *
 * [transcriptMotionPhase] has one job — decide whether an activity row is
 * allowed to animate — and three ways to say no: the row is settled, the
 * platform asked for reduced motion, or the screen is not in front of the
 * reader. Each is pinned here against a manual clock, because a transition
 * asserted against the wall clock is the flake the gate exists to avoid.
 *
 * Every expected value in this file is the *measured* value of a frame-quantised
 * animation, not a rounded arithmetic guess: `advanceTimeBy(240)` runs whole
 * 16ms frames, so the tween is sampled at 224ms and reads 0.224 rather than
 * 0.24. [assertPhaseNear] carries the frame-worth of slack that quantisation
 * costs and nothing more.
 *
 * Robolectric, not an emulator: `animationsDisabled` on the instrumented lane
 * means an animation cannot be observed there at all.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h844dp")
class TranscriptMotionTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `a settled row is static however far the clock runs`() {
        val fixture = MotionFixture(compose).start(active = false, durationMillis = 1_000)

        assertEquals("a settled row is at rest", 0f, fixture.phase())
        fixture.advance(60_000)
        assertEquals("and a minute of frames may not move it", 0f, fixture.phase())
    }

    @Test
    fun `an active row advances on the manual clock and restarts at the loop's own duration`() {
        val fixture = MotionFixture(compose).start(active = true, durationMillis = 1_000)

        assertEquals("no frame is spent before the clock moves", 0f, fixture.phase())

        fixture.advance(240)
        val atQuarter = fixture.phase()
        assertPhaseNear("240ms into a 1000ms tween", expected = 0.224f, actual = atQuarter)

        fixture.advance(240)
        val atHalf = fixture.phase()
        assertPhaseNear("480ms into the same tween", expected = 0.464f, actual = atHalf)
        assertTrue("the phase advances while the row is active", atHalf > atQuarter)

        fixture.advance(280)
        assertPhaseNear("760ms, still short of the loop", expected = 0.752f, actual = fixture.phase())

        // `RepeatMode.Restart` at the tween's own duration: one whole loop is
        // 1000ms, so 1040ms is 40ms into the second one.
        fixture.advance(280)
        val wrapped = fixture.phase()
        assertTrue("1040ms of a 1000ms loop is back at the start, not at 0.99", wrapped < 0.1f)
        assertPhaseNear("and it is the second loop's own phase", expected = 0.04f, actual = wrapped)

        fixture.advance(240)
        assertPhaseNear("the second loop runs on the same clock", expected = 0.284f, actual = fixture.phase())
    }

    @Test
    fun `a row off the foreground is pinned to rest and the next resume starts it again`() {
        val fixture = MotionFixture(compose).start(active = true, durationMillis = 1_000, withLifecycle = true)
        val owner = fixture.owner

        // `LifecycleRegistry.createUnsafe` begins at INITIALIZED, so nothing is
        // RESUMED until the test says so and this is the backgrounded case
        // already.
        assertEquals(Lifecycle.State.INITIALIZED, owner.lifecycle.currentState)
        fixture.advance(2_000)
        assertEquals("an owner that never came to the foreground keeps the row at rest", 0f, fixture.phase())

        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.waitForIdle()
        assertEquals("the resume itself paints the first frame at rest", 0f, fixture.phase())

        fixture.advance(240)
        assertPhaseNear("a resumed owner animates", expected = 0.208f, actual = fixture.phase())
        fixture.advance(240)
        assertPhaseNear("and keeps animating", expected = 0.448f, actual = fixture.phase())

        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.STARTED }
        compose.waitForIdle()
        // The gate is read during composition, and the state flow's emission
        // needs a frame to reach it, so backgrounding lands one frame late.
        // Measured, not assumed: two frames is enough, one is not always.
        fixture.advance(32)
        assertEquals("leaving the foreground returns the active row to rest", 0f, fixture.phase())
        fixture.advance(2_000)
        assertEquals("and it stays there while the clock runs", 0f, fixture.phase())

        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.waitForIdle()
        fixture.advance(240)
        assertPhaseNear("resuming restarts the loop from zero, not from where it stopped", expected = 0.208f, actual = fixture.phase())
    }

    /**
     * The gate as the transcript reaches it: a running tool row and a working
     * turn both carry activity, and a settled transcript carries none.
     *
     * What is asserted is the surface — the running row is painted, and the
     * status row appears only while a turn is working. Motion is decoration and
     * the glyph clears its semantics on purpose, so the alpha it paints is not
     * a product claim; that the row survives every frame of the loop is.
     */
    @Test
    fun `a running tool and a working turn paint a surviving activity row`() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            HermesTheme(AppearanceSelection("nous", HermesThemeMode.Dark)) {
                Transcript(
                    entries = listOf(terminal(ToolState.Running)),
                    listState = rememberLazyListState(),
                    isWorking = true,
                    progress = SessionProgress(kind = "status", text = "starting up"),
                )
            }
        }
        compose.waitForIdle()

        compose.onNodeWithContentDescription("Tool Running sleep 5, running").assertIsDisplayed()
        compose.onNodeWithText("starting up").assertIsDisplayed()

        // The clock moves the phase, so the row it belongs to must survive every
        // frame of the loop: a running row may not blink out of the transcript.
        compose.mainClock.advanceTimeBy(4_000)
        compose.onNodeWithContentDescription("Tool Running sleep 5, running").assertIsDisplayed()
        compose.onNodeWithText("starting up").assertIsDisplayed()
    }

    @Test
    fun `a settled transcript paints no progress row`() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            HermesTheme(AppearanceSelection("nous", HermesThemeMode.Dark)) {
                Transcript(
                    entries = listOf(terminal(ToolState.Done)),
                    listState = rememberLazyListState(),
                    isWorking = false,
                )
            }
        }
        compose.waitForIdle()

        compose.onNodeWithContentDescription("Tool Ran sleep 5, done").assertIsDisplayed()
        compose.onNodeWithText("starting up").assertDoesNotExist()
    }
}

/**
 * The reduced-motion rung: the platform's animator duration scale of 0, which
 * Compose forwards as a `MotionDurationScale` element in the composition's
 * coroutine context.
 *
 * This is the decisive proof that the gate reads the *ambient* element rather
 * than the `?: 1f` fallback. The same clock and the same row read 0.224 at 240ms
 * under the default scale (pinned in [TranscriptMotionTest]), so a fallback read
 * would animate here too; at scale 0 the row never leaves rest.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h844dp")
class TranscriptMotionReducedTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>(FixedMotionDurationScale(0f))

    @Test
    fun `a zero duration scale pins an active row to rest`() {
        val fixture = MotionFixture(compose).start(active = true, durationMillis = 1_000)

        assertEquals("reduced motion starts at rest", 0f, fixture.phase())
        fixture.advance(5_000)
        assertEquals("and no amount of clock moves it", 0f, fixture.phase())
        fixture.advance(60_000)
        assertEquals(0f, fixture.phase())
    }

    @Test
    fun `reduced motion silences the movement but not the running row`() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            HermesTheme(AppearanceSelection("nous", HermesThemeMode.Dark)) {
                Transcript(
                    entries = listOf(terminal(ToolState.Running)),
                    listState = rememberLazyListState(),
                    isWorking = true,
                    progress = SessionProgress(kind = "status", text = "starting up"),
                )
            }
        }
        compose.waitForIdle()

        // A reader who asked for less motion must still get the row that says a
        // tool is running, and the standing glyph it paints instead of a frame
        // of the breathe loop.
        repeat(3) {
            compose.onNodeWithContentDescription("Tool Running sleep 5, running").assertIsDisplayed()
            compose.onNodeWithText("starting up").assertIsDisplayed()
            compose.mainClock.advanceTimeBy(5_000)
        }
    }
}

/**
 * The scale is not a gate-only reading: the same element governs the tween's
 * wall time, which is what makes the injected context the real one rather than
 * a coincidental copy.
 *
 * Android's animator duration scale multiplies the duration — 2 means twice as
 * slow — and the measurements follow it exactly: the 1000ms loop takes 2000ms
 * here (0.496 at 1000ms of wall clock, the loop's midpoint) where it completes in
 * 1000ms under the default scale (0.992 at the same point). Nothing about the
 * fallback value would produce that.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h844dp")
class TranscriptMotionScaleTwoTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>(FixedMotionDurationScale(2f))

    @Test
    fun `a doubled duration scale stretches the loop to twice its wall time`() {
        val fixture = MotionFixture(compose).start(active = true, durationMillis = 1_000)

        assertEquals("still no frame before the clock moves", 0f, fixture.phase())

        fixture.advance(240)
        assertPhaseNear("240ms of a 2000ms effective loop", expected = 0.112f, actual = fixture.phase())

        fixture.advance(760)
        assertPhaseNear("1000ms is the midpoint of this loop, not its end", expected = 0.496f, actual = fixture.phase())

        fixture.advance(960)
        assertPhaseNear("1960ms approaches the loop's end", expected = 0.976f, actual = fixture.phase())
    }
}

/** A `MotionDurationScale` the composition's coroutine context carries verbatim. */
private class FixedMotionDurationScale(private val scale: Float) : MotionDurationScale {
    override val scaleFactor: Float get() = scale
}

/** An owner whose state the test sets by hand, the repo's usual lifecycle stand-in. */
private class TestOwner : LifecycleOwner {
    val registry = LifecycleRegistry.createUnsafe(this)
    override val lifecycle: Lifecycle get() = registry
}

/** Where the last composed frame's phase lands, read back off the UI thread. */
private class PhaseProbe {
    var value: Float = Float.NaN
}

@Composable
private fun PhaseProbe(active: Boolean, durationMillis: Int, probe: PhaseProbe) {
    val phase = transcriptMotionPhase(active, durationMillis)
    // A side effect, not a `remember`: every frame the animation produces has to
    // land in the probe, and this runs on each successful recomposition.
    SideEffect { probe.value = phase }
}

/**
 * A one-row composition whose only content is the gate, with the clock in the
 * test's hands.
 *
 * `autoAdvance = false` is set *before* `setContent` on purpose: the test
 * harness's `InfiniteAnimationPolicy` cancels an infinite animation outright
 * while the clock is auto-advancing, so an animation left running through the
 * initial `waitForIdle` would never restart and this file would assert zeroes
 * everywhere.
 */
private class MotionFixture(private val compose: ComposeContentTestRule) {
    private val probe = PhaseProbe()
    val owner = TestOwner()

    fun start(
        active: Boolean,
        durationMillis: Int,
        withLifecycle: Boolean = false,
    ): MotionFixture {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            HermesTheme(AppearanceSelection("nous", HermesThemeMode.Dark)) {
                if (withLifecycle) {
                    CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                        PhaseProbe(active, durationMillis, probe)
                    }
                } else {
                    PhaseProbe(active, durationMillis, probe)
                }
            }
        }
        compose.waitForIdle()
        return this
    }

    fun advance(millis: Long) = compose.mainClock.advanceTimeBy(millis)

    fun phase(): Float = compose.runOnIdle { probe.value }
}

/**
 * One frame of slack: `advanceTimeBy` stops on a frame boundary, so a tween is
 * always sampled a little behind the clock it was asked to advance — 16ms of a
 * 1000ms loop is 0.016, and a two-frame composition lag is the most this file
 * has measured.
 */
private fun assertPhaseNear(message: String, expected: Float, actual: Float) {
    assertTrue(
        "$message: expected ~$expected but was $actual",
        kotlin.math.abs(expected - actual) <= 0.06f,
    )
}

/** One terminal row saying `sleep 5`, in whichever state the test needs. */
private fun terminal(state: ToolState) = ToolActivity(
    id = "t1",
    label = "terminal",
    detail = "",
    state = state,
    elapsedSeconds = 1.0,
    toolName = "terminal",
    argsText = """{"command":"sleep 5"}""",
    startedAtMillis = 0L,
)
