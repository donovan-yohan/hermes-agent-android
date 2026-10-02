package com.hermesagent.mobile.device

import android.app.Dialog
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.LifecycleEventObserver
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule

import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import java.util.concurrent.atomic.AtomicInteger

/** PR344 disposable synthetic collector test. Its readiness failure is DELIBERATE. */
@RunWith(AndroidJUnit4::class)
class SyntheticFocusDenialTest {
    @get:Rule(order = 0)
    val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule(order = 1)
    val denyFocus: TestRule = TestRule { base, description ->
        object : Statement() {
            override fun evaluate() {
                val args = InstrumentationRegistry.getArguments()
                assumeTrue(args.getString("syntheticFocusDenial") == "PR344_ONLY")
                check(args.getString("focusSnapshotNonce")?.matches(Regex("[a-f0-9]{32}")) == true)
                check(args.getString("focusSnapshotSerial") == "emulator-5554")
                val frames = AtomicInteger()
                var running = true
                val callback = object : Choreographer.FrameCallback {
                    override fun doFrame(frameTimeNanos: Long) {
                        frames.incrementAndGet()
                        if (running) Choreographer.getInstance().postFrameCallback(this)
                    }
                }
                val dialog = onMain {
                    val owner = compose.activity
                    owner.lifecycle.addObserver(LifecycleEventObserver { _, event ->
                        emit("LIFECYCLE $event destroyed=${owner.isDestroyed}")
                    })
                    Dialog(owner).apply {
                        setCancelable(false)
                        setContentView(TextView(owner).apply { text = "Synthetic focus denial" })
                        // Deliberately identical WM titles: titles cannot identify the owner.
                        checkNotNull(window).attributes = checkNotNull(window).attributes.apply {
                            title = owner.window.attributes.title
                        }
                        show()
                    }
                }
                // Run-scoped labels for actual client Binder objects, not hash values and
                // never a join to system_server's BinderProxy or WindowState addresses.
                val clientTokens = mutableListOf<android.os.IBinder>()
                fun binding(token: android.os.IBinder?): String {
                    if (token == null) return "null"
                    val existing = clientTokens.indexOfFirst { it === token }
                    if (existing >= 0) return "client-${existing + 1}"
                    clientTokens.add(token)
                    return "client-${clientTokens.size}"
                }
                FailureFocusSnapshot.syntheticIdentityProbe = { phase ->
                    onMain {
                        val activityDecor = compose.activity.window.decorView
                        val dialogDecor = checkNotNull(dialog.window).decorView
                        val activityToken = activityDecor.windowToken
                        val dialogToken = dialogDecor.windowToken
                        Log.i("FocusSnapshot", "WINDOW_IDENTITY phase=$phase " +
                            "main=${android.os.Looper.myLooper() == android.os.Looper.getMainLooper()} " +
                            "activityAttached=${activityDecor.isAttachedToWindow} " +
                            "dialogAttached=${dialogDecor.isAttachedToWindow} " +
                            "activityDisplay=${activityDecor.display?.displayId} " +
                            "dialogDisplay=${dialogDecor.display?.displayId} " +
                            "activityFocus=${activityDecor.hasWindowFocus()} " +
                            "dialogFocus=${dialogDecor.hasWindowFocus()} " +
                            "activityWindowIdFocus=${activityDecor.windowId?.isFocused} " +
                            "dialogWindowIdFocus=${dialogDecor.windowId?.isFocused} " +
                            "distinctTokens=${activityToken != null && dialogToken != null && activityToken !== dialogToken} " +
                            "activityToken=${binding(activityToken)} dialogToken=${binding(dialogToken)} " +
                            "sameWmTitles=${compose.activity.window.attributes.title.toString() == checkNotNull(dialog.window).attributes.title.toString()} " +
                            "activityDestroyed=${compose.activity.isDestroyed} dialogShowing=${dialog.isShowing} " +
                            "uptime=${SystemClock.uptimeMillis()}")
                    }
                }
                try {
                    val deadline = SystemClock.uptimeMillis() + 5_000
                    while (!onMain { dialog.window?.decorView?.hasWindowFocus() == true && !compose.activity.hasWindowFocus() }) {
                        check(SystemClock.uptimeMillis() < deadline) { "Synthetic dialog did not acquire focus" }
                        SystemClock.sleep(20)
                    }
                    onMain { Choreographer.getInstance().postFrameCallback(callback) }
                    emit("DENIAL_ESTABLISHED frames=${frames.get()} ${ownerState()}")
                    try {
                        WindowReadinessRule { compose.activity }.apply(base, description).evaluate()
                    } catch (failure: Throwable) {
                        emit("ORIGINAL_FAILURE type=${failure.javaClass.simpleName} frames=${frames.get()} ${ownerState()}")
                        throw failure
                    }
                } finally {
                    emit("BEFORE_DIALOG_DISMISS frames=${frames.get()} ${ownerState()}")
                    FailureFocusSnapshot.syntheticIdentityProbe = null
                    onMain {
                        running = false
                        Choreographer.getInstance().removeFrameCallback(callback)
                        dialog.dismiss()
                    }
                }
            }
        }
    }

    @Test
    fun deliberateDenialCapturesOwnerBeforeTeardown() {
        error("Synthetic readiness unexpectedly passed; test body must not execute")
    }

    private fun ownerState(): String = onMain {
        val owner = compose.activity
        "lifecycle=${owner.lifecycle.currentState} destroyed=${owner.isDestroyed} " +
            "attached=${owner.window.decorView.isAttachedToWindow} focus=${owner.hasWindowFocus()}"
    }

    private fun emit(value: String) {
        Log.i("FocusSnapshot", "SYNTHETIC $value uptime=${SystemClock.uptimeMillis()}")
    }
}
