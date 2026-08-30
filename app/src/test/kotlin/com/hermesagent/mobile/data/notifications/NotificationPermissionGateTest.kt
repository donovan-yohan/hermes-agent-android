package com.hermesagent.mobile.data.notifications

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * When `POST_NOTIFICATIONS` is asked for.
 *
 * The rule this pins down is the one that is easy to lose in a refactor: the
 * ask is tied to a live Gateway, not to app launch. A permission dialog before
 * there is anything to be notified about is the cheapest possible refusal, and
 * Android only allows two.
 */
class NotificationPermissionGateTest {

    @Test
    fun `below API 33 the grant comes with the install and there is nothing to ask`() {
        assertEquals(
            NotificationPermissionStep.None,
            notificationPermissionStep(
                sdkInt = 32,
                granted = false,
                alreadyAsked = false,
                gatewayConnected = true,
            ),
        )
    }

    @Test
    fun `a connected Gateway with no grant is the first point of need`() {
        assertEquals(
            NotificationPermissionStep.Rationale,
            notificationPermissionStep(
                sdkInt = ANDROID_TIRAMISU,
                granted = false,
                alreadyAsked = false,
                gatewayConnected = true,
            ),
        )
    }

    @Test
    fun `nothing is asked before there is a connection to be notified about`() {
        assertEquals(
            NotificationPermissionStep.None,
            notificationPermissionStep(
                sdkInt = ANDROID_TIRAMISU,
                granted = false,
                alreadyAsked = false,
                gatewayConnected = false,
            ),
        )
    }

    @Test
    fun `a granted permission is never asked for again`() {
        assertEquals(
            NotificationPermissionStep.None,
            notificationPermissionStep(
                sdkInt = ANDROID_TIRAMISU,
                granted = true,
                alreadyAsked = false,
                gatewayConnected = true,
            ),
        )
    }

    @Test
    fun `a refusal is respected rather than re-prompted`() {
        assertEquals(
            NotificationPermissionStep.None,
            notificationPermissionStep(
                sdkInt = ANDROID_TIRAMISU,
                granted = false,
                alreadyAsked = true,
                gatewayConnected = true,
            ),
        )
    }
}
