package com.hermesagent.mobile.ui.settings

import com.hermesagent.mobile.data.notifications.NotificationKind

/**
 * The notifications settings screen's strings.
 *
 * Desktop's `settings.notifications` block verbatim wherever Desktop has the
 * string (`apps/desktop/src/i18n/en.ts:588-624` @ `72a3277cd7`). Everything
 * else is Android-only and marked as such: a permission this app can lose, a
 * lock screen Desktop does not have, and two kinds that describe things only a
 * phone's connection does.
 */
object NotificationsCopy {
    /** `en.ts:589` */
    const val TITLE = "Notifications"

    /** `en.ts:590` */
    const val INTRO = "OS notifications (not in-app toasts). Per device."

    /** `en.ts:591` */
    const val MASTER_LABEL = "Enable notifications"

    /** `en.ts:592` */
    const val MASTER_DESCRIPTION = "Off silences every notification below."

    /** Android-only section heading; Desktop's panel is one undivided list. */
    const val KINDS_SECTION = "What to notify me about"

    /** Android-only section heading. */
    const val PRIVACY_SECTION = "On screen"

    /**
     * Android-only. Desktop has no lock screen, no shade a stranger can read
     * over your shoulder, and so no reason to have ever made this a choice.
     */
    const val PREVIEW_LABEL = "Show a preview"

    const val PREVIEW_DESCRIPTION =
        "Include the question, or the line a turn ended on, under the chat's name."

    /** Android-only: the one sentence that says what the toggle cannot expose. */
    const val PRIVACY_FOOTNOTE =
        "A locked phone is only ever told what kind of thing is waiting. Commands, " +
            "tool output, passwords and secret names are never shown."

    /**
     * Android-only. Every switch on this screen is a preference this app
     * stores, and none of them can overrule a system grant that is gone.
     */
    const val BLOCKED_TITLE = "Android is blocking notifications"

    const val BLOCKED_BODY =
        "Hermes can't deliver anything until notifications are allowed for this app in Android settings."

    const val BLOCKED_ACTION = "Open Android settings"

    /** `en.ts:625` — a Desktop action worth having where delivery can fail. */
    const val TEST_ACTION = "Send test notification"

    /** `en.ts:626` */
    const val TEST_TITLE = "Hermes"

    /** `en.ts:627` */
    const val TEST_BODY = "Notifications are working."

    /**
     * `en.ts:628`, trimmed to the phone's own two causes. Desktop names OS
     * permissions and Focus; Android's equivalents are the app's own grant,
     * which this screen already offers a way to, and Do Not Disturb.
     */
    const val TEST_SENT = "Test sent. If nothing appears, check Do Not Disturb."

    /** Desktop's own per-kind label (`en.ts:595-620`), or this app's own. */
    fun label(kind: NotificationKind): String = when (kind) {
        NotificationKind.Approval -> "Approval needed"
        NotificationKind.Input -> "Input needed"
        NotificationKind.TurnDone -> "Response ready"
        NotificationKind.TurnError -> "Turn failed"
        NotificationKind.BackgroundDone -> "Background task finished"
        NotificationKind.Credits -> "Credit alerts"
        NotificationKind.Plugin -> "Plugin notifications"
        NotificationKind.ConnectionLost -> "Connection lost"
        NotificationKind.StillWaiting -> "Still waiting"
    }

    fun description(kind: NotificationKind): String = when (kind) {
        NotificationKind.Approval -> "A command is waiting for you to approve or reject it."
        NotificationKind.Input -> "Hermes asked a question or needs a password or secret."
        NotificationKind.TurnDone -> "A turn finished while Hermes was in the background."
        NotificationKind.TurnError -> "Background turn errors."
        NotificationKind.BackgroundDone -> "A backgrounded terminal command completed."
        NotificationKind.Credits -> "Credit access is paused or restored."
        NotificationKind.Plugin -> "A desktop plugin sent a notification while Hermes was in the background."
        // Android-only, both of them. A desktop renderer is either open or
        // quit; this app's socket can go away mid-turn, and a prompt can sit
        // parked in a shade nobody came back to.
        NotificationKind.ConnectionLost -> "The Gateway went away while a turn or a prompt was still live."
        NotificationKind.StillWaiting -> "A reminder that something is still waiting for your answer."
    }
}
