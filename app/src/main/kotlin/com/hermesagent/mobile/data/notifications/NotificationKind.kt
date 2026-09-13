package com.hermesagent.mobile.data.notifications

/**
 * Desktop's `NativeNotificationKind`, ported name-for-name and in registry
 * order (`apps/desktop/src/store/native-notifications.ts:15-26` @
 * `3ca096de5f8183cb2e0ec23673f294d5978656a3`).
 *
 * All seven of Desktop's are carried, including the three that have no mobile
 * source, because the preference store is also the *persisted* shape: dropping
 * an entry would silently orphan a stored boolean. They are not rendered —
 * `docs/parity/notifications.md` classifies them `non-goal`, and the repo rule
 * is that a non-goal is omitted rather than marked.
 *
 * The last two have no Desktop peer at all. A phone's notification surface is
 * not Desktop's: a renderer is either open or quit, while this app's socket can
 * drop while a turn is mid-flight, and a prompt can sit parked in a shade the
 * person walked away from. Those are mobile facts, so they get mobile kinds.
 *
 * [key] is the persisted name — Desktop's identifier verbatim where there is
 * one — so the enum may be reordered without rewriting anyone's saved
 * preferences.
 */
enum class NotificationKind(val key: String) {
    Approval("approval"),
    Input("input"),
    TurnDone("turnDone"),
    TurnError("turnError"),
    BackgroundDone("backgroundDone"),
    Credits("credits"),
    Plugin("plugin"),

    /** Android-only: the Gateway went away with a turn or a prompt still live. */
    ConnectionLost("connectionLost"),

    /** Android-only: a prompt this app already announced is still unanswered. */
    StillWaiting("stillWaiting"),
    ;

    /**
     * Whether anything in this app can raise this kind.
     *
     * The three that cannot are Desktop-only by nature rather than unbuilt —
     * there is no backgrounded terminal, no credit ledger and no desktop plugin
     * host here — so the settings screen omits them rather than rendering a row
     * that will never light up.
     */
    val hasMobileSource: Boolean
        get() = this !in DESKTOP_ONLY_KINDS
}

private val DESKTOP_ONLY_KINDS = setOf(
    NotificationKind.BackgroundDone,
    NotificationKind.Credits,
    NotificationKind.Plugin,
)

/** The kinds the settings screen lists, in the order it lists them. */
val SETTABLE_KINDS: List<NotificationKind> = NotificationKind.entries.filter { it.hasMobileSource }

/**
 * Blocking prompts. They surface even while the app is foregrounded if they
 * belong to a session that is not on screen — Desktop's `ATTENTION_KINDS`
 * (`native-notifications.ts:29`, applied at `:141-143` @ the pin).
 */
val ATTENTION_KINDS: Set<NotificationKind> = setOf(
    NotificationKind.Approval,
    NotificationKind.Input,
    // Android-only, and here rather than beside the quieter kinds because it is
    // a second telling of one of the two above: a reminder about a blocking
    // prompt that inherited a calmer channel than the prompt it reminds you of
    // would be a quieter version of the loudest thing the app has to say.
    NotificationKind.StillWaiting,
)

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
