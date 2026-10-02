package com.hermesagent.mobile.device

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry

/** Disposable PR344 only. No probe runs on the successful readiness path. */
internal object FailureFocusSnapshot {
    // Installed only by the disposable synthetic test; invoked only after nonce gating.
    @Volatile var syntheticIdentityProbe: ((String) -> Unit)? = null

    fun capture(test: String, activity: (() -> android.app.Activity)? = null) {
        // Diagnostics must never replace the original readiness failure.
        runCatching {
            val args = InstrumentationRegistry.getArguments()
            val nonce = args.getString("focusSnapshotNonce") ?: return
            if (!nonce.matches(Regex("[a-f0-9]{32}")) ||
                args.getString("focusSnapshotSerial") != "emulator-5554") return
            if (shell("getprop debug.hermes.focus_nonce").trim() != nonce ||
                shell("getprop ro.boot.qemu").trim() != "1" ||
                shell("getprop ro.build.version.sdk").trim() != "34") return
            emit("BEGIN test=$test uptime=${SystemClock.uptimeMillis()}")
            // Input FIRST, while the failing Activity is still alive. Only the
            // first FocusedWindows block is current; never retain focus history.
            fun activityIdentity(phase: String) {
                runCatching {
                    activity?.let { getActivity ->
                        onMain {
                            val owner = getActivity()
                            val decor = owner.window.decorView
                            emit("ACTIVITY_WINDOW_IDENTITY phase=$phase " +
                                "main=${android.os.Looper.myLooper() == android.os.Looper.getMainLooper()} " +
                                "component=${owner.componentName.flattenToShortString()} " +
                                "attached=${decor.isAttachedToWindow} display=${decor.display?.displayId} " +
                                "windowFocus=${decor.hasWindowFocus()} windowIdFocus=${decor.windowId?.isFocused} " +
                                "tokenPresent=${decor.windowToken != null} destroyed=${owner.isDestroyed} " +
                                "uptime=${SystemClock.uptimeMillis()}")
                        }
                    }
                }.onFailure { runCatching { emit("PROBE_ERROR activityIdentity $phase ${it.javaClass.simpleName}") } }
            }
            activityIdentity("before")
            identity("before")
            probe("input") { text ->
                val lines = text.lines()
                val start = lines.indexOfFirst { it.trim() == "FocusedWindows:" }
                if (start < 0) listOf("CURRENT_BLOCK_MISSING") else
                    listOf(lines[start]) + lines.drop(start + 1).takeWhile {
                        it.startsWith("    ") || it.isBlank()
                    }.take(12)
            }
            activityIdentity("after")
            identity("after")
            probe("activity activities") { text -> text.lines().filter {
                it.contains("topResumedActivity=") || it.contains("mResumedActivity:") ||
                    it.trimStart().startsWith("* Task{") || it.startsWith("Display #")
            } }
            probe("window windows") { text -> text.lines().filter {
                it.contains("mCurrentFocus=") || it.contains("mFocusedApp=") ||
                    it.contains("mTopFocusedDisplayId=") || it.trimStart().startsWith("Window #")
            } }
            emit("END test=$test uptime=${SystemClock.uptimeMillis()}")
        }.onFailure { runCatching { emit("CAPTURE_ERROR ${it.javaClass.simpleName}") } }
    }

    private fun identity(phase: String) {
        runCatching { syntheticIdentityProbe?.invoke(phase) }
            .onFailure { runCatching { emit("PROBE_ERROR identity $phase ${it.javaClass.simpleName}") } }
    }

    private fun probe(service: String, select: (String) -> List<String>) {
        runCatching {
            val began = SystemClock.uptimeMillis()
            val fields = select(shell("timeout 3 dumpsys $service"))
            emit("PROBE $service begin=$began end=${SystemClock.uptimeMillis()}")
            fields.forEach { emit(it) }
        }.onFailure { runCatching { emit("PROBE_ERROR $service ${it.javaClass.simpleName}") } }
    }

    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command),
    ).bufferedReader().use { it.readText() }

    private fun emit(value: String) { Log.i("FocusSnapshot", value) }
}
