package com.hermesagent.mobile.device

import android.Manifest
import android.app.ActivityManager
import android.app.Application
import android.os.Build
import android.os.Process
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject

/** Exclusive, non-nested DUMP owner: this repo has no other permission-adoption scope. */
internal object CurrentAnrSnapshot {
    private val collector = CurrentAnrCandidate.Collector()

    // Only called after the disposable synthetic nonce attestation in FailureFocusSnapshot.
    fun capture(): JSONObject {
        val result = if (Build.VERSION.SDK_INT != 34)
            CurrentAnrCandidate.unknown(CurrentAnrCandidate.Cause.NOT_ATTESTED)
        else {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val app = CurrentAnrCandidate.Identity(Process.myPid(), Process.myUid(), Application.getProcessName())
            collector.collect(true, object : CurrentAnrCandidate.Api {
                override fun adopt() = instrumentation.uiAutomation.adoptShellPermissionIdentity(Manifest.permission.DUMP)
                override fun query(): CurrentAnrCandidate.Result {
                    val manager = instrumentation.targetContext.getSystemService(ActivityManager::class.java)
                        ?: return CurrentAnrCandidate.unknown(CurrentAnrCandidate.Cause.ACQUISITION_FAILED)
                    val rows = manager.processesInErrorState
                    if (rows != null && rows.size > 32)
                        return CurrentAnrCandidate.unknown(CurrentAnrCandidate.Cause.OVERFLOW)
                    return CurrentAnrCandidate.reduce(rows?.map { row ->
                        row?.let { CurrentAnrCandidate.Row(it.condition, it.processName, it.pid, it.uid, it.shortMsg) }
                    }, app)
                }
                override fun drop() = instrumentation.uiAutomation.dropShellPermissionIdentity()
            })
        }
        return JSONObject().put("source", "ACTIVITY_MANAGER_ERROR_STATE").put("scope", "CALLER_USER")
            .put("status", result.status.name).put("cause", result.cause.name)
            .put("role", result.role.name).put("reason", result.reason.name)
    }
}
