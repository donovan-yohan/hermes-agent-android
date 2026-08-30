package com.hermesagent.mobile.data.notifications

/** What, if anything, the app should do about the OS notification permission. */
enum class NotificationPermissionStep {
    /** Nothing to ask for, or nothing worth asking for yet. */
    None,

    /** Say why first, then ask. */
    Rationale,
}

/**
 * When to ask for `POST_NOTIFICATIONS`.
 *
 * "First point of need" is deliberately not first launch. A permission dialog
 * on a cold install, before there is a Gateway to be notified about, is a
 * dialog with no context and the cheapest possible No. The first moment the
 * grant buys the user anything is the first moment a live connection could
 * raise a prompt, so that is when it is asked for.
 *
 * Asked once, ever. Android silently ignores a second request after two
 * refusals, so a second ask is not a second chance — it is a dialog that does
 * nothing. Turning notifications back on is a job for the settings screen and
 * the OS, not for a prompt that reappears.
 *
 * Below API 33 the permission is granted at install and there is nothing to
 * request; the wake-word foreground service's own notification is suppressed
 * without it from 33 up, which is why this gate fixes that as a side effect.
 */
fun notificationPermissionStep(
    sdkInt: Int,
    granted: Boolean,
    alreadyAsked: Boolean,
    gatewayConnected: Boolean,
): NotificationPermissionStep = when {
    sdkInt < ANDROID_TIRAMISU -> NotificationPermissionStep.None
    granted -> NotificationPermissionStep.None
    alreadyAsked -> NotificationPermissionStep.None
    !gatewayConnected -> NotificationPermissionStep.None
    else -> NotificationPermissionStep.Rationale
}

/** `Build.VERSION_CODES.TIRAMISU`, named here so the rule is testable off-device. */
const val ANDROID_TIRAMISU: Int = 33
