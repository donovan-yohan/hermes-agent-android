package com.hermesagent.mobile.device

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Disposable diagnostic only; no raw system text, names or tokens reach a sink. */
internal object FailureFocusSnapshot {
    private var index = 0
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun shell(command: String): String {
        val fd = instrumentation.uiAutomation.executeShellCommand("timeout 2 $command")
        val executor = Executors.newSingleThreadExecutor()
        return try {
            executor.submit<String> {
                ParcelFileDescriptor.AutoCloseInputStream(fd).reader().use { reader ->
                    val out = StringBuilder()
                    val buffer = CharArray(4096)
                    while (true) {
                        val n = reader.read(buffer)
                        if (n < 0) break
                        if (out.length + n > 2 * 1024 * 1024) throw FocusOwner.Overflow()
                        out.append(buffer, 0, n)
                        if (command == "dumpsys input") {
                            val end = Regex("\\n  Global [Mm]onitors[^\\n]*\\n").find(out)
                            if (end != null) return@use out.substring(0, end.range.last + 1)
                        }
                    }
                    out.toString()
                }
            }.get(2500, TimeUnit.MILLISECONDS)
        } finally {
            runCatching { fd.close() }
            executor.shutdownNow()
        }
    }
    private fun bracket(activity: () -> Activity): JSONObject {
        val latch = CountDownLatch(1)
        var result: JSONObject? = null
        Handler(Looper.getMainLooper()).post {
            try {
                val owner = activity()
                val decor = owner.window.decorView
                result = JSONObject().put("display", decor.display?.displayId ?: -1)
                    .put("appPid", android.os.Process.myPid())
                    .put("appUid", android.os.Process.myUid())
                    .put("attached", decor.isAttachedToWindow)
                    .put("destroyed", owner.isDestroyed)
                    .put("activityFocus", owner.hasWindowFocus())
                    .put("decorFocus", decor.hasWindowFocus())
                    .put("windowIdFocus", decor.windowId?.isFocused ?: false)
            } catch (_: Exception) {
                result = null
            } finally { latch.countDown() }
        }
        check(latch.await(500, TimeUnit.MILLISECONDS))
        return checkNotNull(result)
    }
    private var armedNonce: String? = null
    fun arm() {
        armedNonce = null
        runCatching {
            val args = InstrumentationRegistry.getArguments()
            if (args.getString("focusSnapshotEnabled") != "true") return
            val nonce = args.getString("focusSnapshotNonce") ?: return
            if (!nonce.matches(Regex("[a-f0-9]{32}")) ||
                args.getString("focusSnapshotSerial") != "emulator-5554" ||
                args.getString("focusSnapshotDisposable") != "api34-run-scoped-snapshot") return
            val attestation = shell("getprop debug.hermes.focus_attestation").trim()
            if (attestation != "$nonce:emulator-5554:api34-run-scoped-snapshot" ||
                shell("getprop ro.boot.qemu").trim() != "1" ||
                shell("getprop ro.build.version.sdk").trim() != "34") return
            armedNonce = nonce
        }
    }
    fun capture(activity: () -> Activity) {
        runCatching {
            arm()
            val nonce = armedNonce ?: return
            val record = JSONObject().put("schema", 3)
            val before = runCatching { bracket(activity) }.getOrNull()
            record.put("before", before ?: JSONObject.NULL)
            val input = runCatching { shell("dumpsys input") }
            val reduction = FocusOwner.probe({ input.getOrThrow().lineSequence() }, before?.getInt("display") ?: -1)
            record.put("parserShape", if (reduction.status == FocusOwner.Status.REJECTED) input.getOrNull()?.let { text ->
                // Bounded shape flags only. Raw names/text remain ephemeral and never reach a sink.
                JSONObject().put("focusedSection", text.contains("  FocusedWindows:"))
                    .put("displaySection", Regex("(?m)^  Display: [0-9]+$").containsMatchIn(text))
                    .put("windowSection", text.contains("    Windows:"))
                    .put("numericPid", Regex("ownerPid=[0-9]+,").containsMatchIn(text))
                    .put("numericUid", Regex("ownerUid=[0-9]+,").containsMatchIn(text))
                    .put("windowEntryShape", FocusOwner.hasEntryShape(text.lineSequence()))
            } ?: JSONObject.NULL else JSONObject.NULL)
            record.put("ownerStatus", reduction.status.name)
            val owner = reduction.owner
            record.put("owner", owner?.let {
                JSONObject().put("display", it.display).put("ownerPid", it.ownerPid).put("ownerUid", it.ownerUid)
            } ?: JSONObject.NULL)
            val metadata = owner?.let {
                val ps = runCatching { shell("ps -p ${it.ownerPid} -o PID,UID,NAME") }.getOrDefault("")
                val windows = runCatching { shell("dumpsys window windows") }.getOrDefault("")
                runCatching { OwnerMetadata.reduce(input.getOrDefault(""), it, ps, windows) }.getOrNull()
            }
            record.put("ownerMetadata", metadata?.let {
                JSONObject().put("processRole", it.processRole).put("windowType", it.windowType)
                    .put("inputConfig", it.inputConfig?.let { flags -> org.json.JSONArray(flags) } ?: JSONObject.NULL)
            } ?: JSONObject.NULL)
            // Even failed input capture must not suppress the after bracket.
            record.put("after", runCatching { bracket(activity) }.getOrNull() ?: JSONObject.NULL)
            // AGP uninstalls the target after the suite, deleting internal files.
            // Emit only this reduced numeric/boolean schema into retained test logcat.
            android.util.Log.i("HermesFocusReduced", "$nonce:${index + 1}:$record")
            index++
        }
    }
}
