package com.hermesagent.mobile.data.notifications

import com.hermesagent.mobile.data.ssh.redact

/**
 * Every string an OS notification can render, taken from Desktop's own
 * notification copy at `3ca096de5f8183cb2e0ec23673f294d5978656a3`.
 *
 * Titles and action labels are the native block `i18n/en.ts:174-186`; channel
 * descriptions are the settings block `i18n/en.ts:430-473`. Where Desktop has
 * no matching string — an Android channel groups several Desktop kinds, and
 * Desktop has no "the connection went away" state at all — the divergence is
 * marked here and classified in `docs/parity/notifications.md`.
 *
 * The one rule this file exists to keep: a notification says *what is waiting*
 * and *which conversation*. It never carries a command, tool output, a sudo
 * prompt, a secret name, or anything else the Gateway sent.
 */
object NotificationCopy {
    /** `en.ts:175` */
    const val APPROVAL_TITLE = "Approval needed"

    /** `en.ts:178` */
    const val INPUT_TITLE = "Input needed"

    /** `en.ts:179` */
    const val INPUT_BODY = "Hermes is waiting for your response."

    /** `en.ts:180` */
    const val TURN_DONE_TITLE = "Hermes finished"

    /** `en.ts:181` — deliberately empty upstream; the title carries the news. */
    const val TURN_DONE_BODY = ""

    /** `en.ts:182` */
    const val TURN_ERROR_TITLE = "Turn failed"

    /** `en.ts:176` */
    const val APPROVE_ACTION = "Approve"

    /** `en.ts:177`. The label is Desktop's; the choice it sends is `deny`. */
    const val REJECT_ACTION = "Reject"

    /**
     * Android-only. Desktop's buttons resolve against a renderer that is always
     * there; this app's socket can be gone by the time a button is pressed, and
     * a notification that silently does nothing is worse than one that says
     * where the answer still lives.
     */
    const val OPEN_TO_RESPOND = "Open to respond."

    /** Channel name, from the issue's event matrix. Carries [ATTENTION_KINDS]. */
    const val APPROVALS_CHANNEL_NAME = "Approvals"

    /** `en.ts:437` and `en.ts:441` — one sentence per kind on the channel. */
    const val APPROVALS_CHANNEL_DESCRIPTION =
        "A command is waiting for you to approve or reject it. " +
            "Hermes asked a question or needs a password or secret."

    /** Channel name, from the issue's event matrix. */
    const val RESPONSES_CHANNEL_NAME = "Responses"

    /** `en.ts:445` */
    const val RESPONSES_CHANNEL_DESCRIPTION = "A turn finished while Hermes was in the background."

    /**
     * Android-only, and not a notification anyone asked for: it is the price of
     * a foreground service, and the service is what keeps a sign-in's network
     * working while the person is in their browser. Desktop has no equivalent
     * because it has no per-app background network block.
     */
    const val SIGN_IN_CHANNEL_NAME = "Sign-in"

    const val SIGN_IN_CHANNEL_DESCRIPTION =
        "Shown only while Hermes finishes signing in to a Gateway in your browser."

    const val SIGN_IN_TITLE = "Finishing sign-in to Hermes"

    const val SIGN_IN_BODY = "Keeping the connection open until your browser comes back."

    /**
     * Android-only turn-protection foreground service notification strings.
     * Kept alive while a turn is running or waiting for input.
     */
    const val TURN_PROTECTION_CHANNEL_NAME = "Active turn"

    const val TURN_PROTECTION_CHANNEL_DESCRIPTION =
        "Shown while Hermes is working on a turn or waiting for input."

    const val TURN_PROTECTION_TITLE = "Hermes is working"

    const val TURN_PROTECTION_BODY = "Keeping the connection open until your turn finishes."

    /**
     * Android-only, shown before the system permission dialog. Desktop asks
     * nothing — Electron notifications need no runtime grant — so this reuses
     * the settings panel's own vocabulary (`en.ts:431`, `en.ts:434`) rather
     * than inventing a second way to describe the same feature.
     */
    const val PERMISSION_RATIONALE_TITLE = "Notifications"

    /** `en.ts:431` verbatim, plus what this app does with the grant. */
    const val PERMISSION_RATIONALE_BODY =
        "OS notifications (not in-app toasts). Per device. " +
            "Hermes notifies you while it is connected."

    const val PERMISSION_RATIONALE_ALLOW = "Continue"
    const val PERMISSION_RATIONALE_DISMISS = "Not now"

    fun title(kind: NotificationKind): String = when (kind) {
        NotificationKind.Approval -> APPROVAL_TITLE
        NotificationKind.TurnDone -> TURN_DONE_TITLE
        NotificationKind.TurnError -> TURN_ERROR_TITLE
        else -> INPUT_TITLE
    }

    /** Shown when the session has no title yet, so the alert still says something true. */
    fun fallbackBody(kind: NotificationKind): String = when (kind) {
        NotificationKind.TurnDone -> TURN_DONE_BODY
        else -> INPUT_BODY
    }
}

private val NOTIFICATION_WHITESPACE = Regex("\\s+")

/**
 * The only text from a Gateway that reaches the shade: a session title,
 * redacted, collapsed to one line and bounded.
 *
 * Bounded because a notification is a system surface with no scroll, and
 * redacted because [redact] is the repo-wide rule for anything a screen, a
 * screenshot or a screen reader can reach.
 */
internal fun String.notificationSafeTitle(limit: Int = MAX_NOTIFICATION_TITLE): String =
    redact(this).replace(NOTIFICATION_WHITESPACE, " ").trim().take(limit)

internal const val MAX_NOTIFICATION_TITLE = 120
