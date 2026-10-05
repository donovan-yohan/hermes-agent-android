package com.hermesagent.mobile.device

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Android ICU portability of synthetic owner reduction only, not focus causality.
 * Robolectric uses the host JDK regex engine, which accepts the unescaped closing braces.
 * This test opts out of failure diagnostics by having no Activity or readiness rule.
 */
@RunWith(AndroidJUnit4::class)
class OwnerMetadataTest {
    @Test
    fun syntheticCurrentOwnerRetainsAllowlistedMetadata() {
        // Pure parser fixture: no Activity, readiness rule, shell capture or captured system data.
        val input = "  FocusedWindows:\n    displayId=0, name='abc PRIVATE'\n" +
            "  FocusRequests: <none>\n  Display: 0\n    Windows:\n" +
            "      0: name='abc PRIVATE', id=1, displayId=0, inputConfig=NOT_TOUCH_MODAL | TRUSTED_OVERLAY, " +
            "alpha=1.00, frame=[0,0][10,10], globalScale=1.000000, applicationInfo.name=PRIVATE, " +
            "applicationInfo.token=PRIVATE, touchableRegion=[0,0][10,10], ownerPid=514, ownerUid=1000, " +
            "dispatchingTimeout=5000ms, hasToken=PRIVATE, touchOcclusionMode=BLOCK_UNTRUSTED\n" +
            "  Global Monitors: <none>\n"
        val windows = "  Window #0 Window{abc u0 PRIVATE}:\n" +
            "    mDisplayId=0 rootTaskId=1\n    mOwnerUid=1000 showForAllUsers=false\n" +
            "    mAttrs={(0,0)(fillxfill) ty=SYSTEM_DIALOG}\n"

        assertEquals(
            OwnerMetadata.Record("SYSTEM_SERVER", "SYSTEM_DIALOG", listOf("NOT_TOUCH_MODAL", "TRUSTED_OVERLAY")),
            OwnerMetadata.reduce(input, FocusOwner.Record(0, 514, 1000), "514 1000 system_server\n", windows),
        )
    }
}
