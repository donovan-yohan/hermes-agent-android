package com.hermesagent.mobile.data.notifications

import com.hermesagent.mobile.data.gateway.PendingInputKey

/**
 * Everything needed to answer one approval from the shade.
 *
 * It carries the repository's own [PendingInputKey] rather than a copy of its
 * fields, which is the whole point: the shade is not a second writer with its
 * own idea of what is pending. A key from a dead connection generation is
 * refused by the repository rather than answered, so an action button that
 * survived a reconnect cannot resolve a prompt on the new socket.
 *
 * The durable id rides alongside because the key identifies the *request* and
 * a notification is filed under the *conversation*.
 */
data class ApprovalTarget(
    val key: PendingInputKey,
    val durableSessionId: String,
)

/** One notification the notifier decided should exist. */
data class NotificationPost(
    val kind: NotificationKind,
    val durableSessionId: String,
    val title: String,
    val body: String,
    /** Non-null only for an approval that can still be answered from the shade. */
    val approval: ApprovalTarget? = null,
)

/**
 * The OS side of notifying, behind an interface so the gating rules can be
 * tested on virtual time with no Android runtime in the way.
 */
interface NotificationSurface {
    fun post(post: NotificationPost)

    /** Withdraw one (session, kind) notification — the prompt resolved, or the turn was read. */
    fun clear(kind: NotificationKind, durableSessionId: String)

    /** Withdraw everything for one session — the user opened it. */
    fun clearSession(durableSessionId: String)

    /**
     * The connection can no longer answer this approval. The notification stays
     * so the request is not silently lost, but its buttons go and its body says
     * where the answer still lives.
     */
    fun degradeApproval(durableSessionId: String)
}
