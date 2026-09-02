package com.hermesagent.mobile.data.notifications

/**
 * Desktop's `NativeNotificationKind`, ported name-for-name and in registry
 * order (`apps/desktop/src/store/native-notifications.ts:15-26` @
 * `3ca096de5f8183cb2e0ec23673f294d5978656a3`).
 *
 * All seven are carried, including the three this client cannot raise yet, for
 * one reason: the preference store is the Desktop settings panel's data model
 * and the panel lists every kind. A kind missing here would be a kind the
 * ported settings screen could not render, which would make that screen a
 * data-layer change rather than the pure UI slice it is meant to be.
 *
 * [key] is the Desktop identifier verbatim. It is the persisted name, so the
 * enum may be reordered without rewriting anyone's saved preferences.
 */
enum class NotificationKind(val key: String) {
    Approval("approval"),
    Input("input"),
    TurnDone("turnDone"),
    TurnError("turnError"),
    BackgroundDone("backgroundDone"),
    Credits("credits"),
    Plugin("plugin"),
}

/**
 * Blocking prompts. They surface even while the app is foregrounded if they
 * belong to a session that is not on screen — Desktop's `ATTENTION_KINDS`
 * (`native-notifications.ts:29`, applied at `:141-143` @ the pin).
 */
val ATTENTION_KINDS: Set<NotificationKind> = setOf(NotificationKind.Approval, NotificationKind.Input)

/**
 * Which OS channel carries a kind.
 *
 * Two channels, named by the issue's own event matrix: blocking prompts are
 * loud and everything else is not. Android has no per-kind importance without
 * a per-kind channel, and a channel's importance cannot be lowered again once
 * the OS has created it, so the split is deliberately coarse and stable.
 */
val NotificationKind.channelId: String
    get() = if (this in ATTENTION_KINDS) APPROVALS_CHANNEL_ID else RESPONSES_CHANNEL_ID

const val APPROVALS_CHANNEL_ID: String = "hermes.approvals"
const val RESPONSES_CHANNEL_ID: String = "hermes.responses"
