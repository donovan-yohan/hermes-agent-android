package com.hermesagent.mobile.data.notifications

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Where the user is, as far as notifications are concerned.
 *
 * Desktop reads two independent things — is the window away
 * (`native-notifications.ts:119-129`), and which session is selected
 * (`$activeSessionId`) — and this keeps them independent for the same reason:
 * a session stays selected while the app is in the background, and that
 * combination is exactly what a completion notification is for.
 *
 * [appForegrounded] is process lifecycle, not activity lifecycle, so a
 * configuration change does not read as leaving the app.
 */
class NotificationPresence {
    private val foreground = MutableStateFlow(false)
    private val visible = MutableStateFlow<String?>(null)

    /** False means no resumed Activity — Android's answer to Desktop's `isBackgrounded()`. */
    val appForegrounded: StateFlow<Boolean> = foreground.asStateFlow()

    /** The durable id of the conversation the chat surface currently has open. */
    val visibleSessionId: StateFlow<String?> = visible.asStateFlow()

    fun applicationForegroundChanged(inForeground: Boolean) {
        foreground.value = inForeground
    }

    fun visibleSessionChanged(durableSessionId: String?) {
        visible.value = durableSessionId
    }
}
