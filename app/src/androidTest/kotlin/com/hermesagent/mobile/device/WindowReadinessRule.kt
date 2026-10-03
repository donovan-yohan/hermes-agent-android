package com.hermesagent.mobile.device

import android.app.Activity
import android.app.KeyguardManager
import android.os.PowerManager
import android.os.SystemClock
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/** Wait for an awake, unlocked, input-focused window inside Compose's activity lifetime. */
internal class WindowReadinessRule(private val activity: () -> Activity) : TestRule {
    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            runCatching { FailureFocusSnapshot.arm() }
            val deadline = SystemClock.uptimeMillis() + DeviceLane.PLATFORM_TIMEOUT_MILLIS
            while (!ready()) {
                try {
                    check(SystemClock.uptimeMillis() < deadline) {
                        "Activity not awake, unlocked and input-focused within " +
                            "${DeviceLane.PLATFORM_TIMEOUT_MILLIS} ms: ${state()}"
                    }
                } catch (failure: IllegalStateException) {
                    runCatching { FailureFocusSnapshot.capture(activity) }
                    throw failure
                }
                SystemClock.sleep(50)
            }
            // No catch/wrap: preserve the exact original throwable.
            base.evaluate()
        }
    }

    private fun ready(): Boolean = onMain {
        val owner = activity()
        owner.getSystemService(PowerManager::class.java).isInteractive &&
            !owner.getSystemService(KeyguardManager::class.java).isKeyguardLocked &&
            !owner.getSystemService(KeyguardManager::class.java).isDeviceLocked &&
            owner.hasWindowFocus()
    }

    private fun state(): String = onMain {
        val owner = activity()
        val keyguard = owner.getSystemService(KeyguardManager::class.java)
        "activity=${owner.componentName} interactive=" +
            "${owner.getSystemService(PowerManager::class.java).isInteractive} " +
            "keyguardLocked=${keyguard.isKeyguardLocked} deviceLocked=${keyguard.isDeviceLocked} " +
            describeImeBinding(owner)
    }
}
