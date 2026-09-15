package com.hermesagent.mobile.data.notifications

import com.hermesagent.mobile.data.gateway.PendingInputKey

/**
 * Everything needed to answer one approval from the shade.
 *
 * It carries the repository's own [PendingInputKey] rather than a copy of its
 * fields, which is the whole point: the shade is not a second writer with its
 * own idea of what is pending.
 *
 * A key whose connection is gone answers nothing. The repository reports that
 * as [com.hermesagent.mobile.data.gateway.PendingInputResponse.Unanswerable]
 * — distinct from "already answered" — so an action button that outlived its
 * socket, or its whole process, tells the user to open the app instead of
 * quietly withdrawing a request that is still parked. The generation carried
 * in the key is not what establishes that on its own: it is a per-process
 * counter and a fresh process reaches the same numbers again.
 *
 * The durable id rides alongside because the key identifies the *request* and
 * a notification is filed under the *conversation*.
 */
data class ApprovalTarget(
    val key: PendingInputKey,
    val durableSessionId: String,
    /**
     * What the Gateway offered for *this* request, verbatim.
     *
     * Carried rather than assumed: `approval.request` decides the list per
     * request (`gateway/platforms/api_server.py:107-108` @ `72a3277cd7`), a
     * response is validated against it, and a shade that offered a choice this
     * request never had would post a button that always fails.
     */
    val choices: List<String> = emptyList(),
)

/**
 * Everything needed to answer one question from the shade.
 *
 * Same shape and same reasoning as [ApprovalTarget]: the repository's own key,
 * never a copy of its fields, so the shade is not a second writer with its own
 * idea of what is pending.
 */
data class QuestionTarget(
    val key: PendingInputKey,
    val durableSessionId: String,
    /** Empty for single-question mode; `clarify.respond` then sends no `question_id`. */
    val questionId: String,
    /** Empty means a reply box rather than buttons. */
    val choices: List<String>,
)

/** One notification the notifier decided should exist. */
data class NotificationPost(
    val kind: NotificationKind,
    val durableSessionId: String,
    val title: String,
    val body: String,
    /** Non-null only for an approval that can still be answered from the shade. */
    val approval: ApprovalTarget? = null,
    /** Non-null only for a question the shade can answer honestly; see `shadeQuestion`. */
    val question: QuestionTarget? = null,
    /**
     * The one extra line, when the person asked for one and the kind has a line
     * it is allowed to carry.
     *
     * Null is not "the preference is off" — it is also every kind whose only
     * available text is text a notification may never show. The decision is
     * made once, in the notifier, so this layer cannot leak by forgetting.
     */
    val preview: String? = null,
)

/** One passive activity child as the shade renders it: text already redacted and bounded. */
data class NotificationActivityChild(
    val durableSessionId: String,
    val title: String,
    val statusLine: String,
    val projectLabel: String? = null,
    val preview: String? = null,
)

/**
 * The OS side of notifying, behind an interface so the gating rules can be
 * tested on virtual time with no Android runtime in the way.
 */
interface NotificationSurface {
    fun post(post: NotificationPost)

    /**
     * Reconcile the passive activity children: one per live chat, in the given order, and
     * nothing else. An empty list withdraws every child. Idempotent; the summary belongs to
     * the foreground service, not to this call.
     */
    fun postActivity(children: List<NotificationActivityChild>)

    /** Withdraw one (session, kind) notification — the prompt resolved, or the turn was read. */
    fun clear(kind: NotificationKind, durableSessionId: String)

    /** Withdraw everything for one session — the user opened it. */
    fun clearSession(durableSessionId: String)

    /**
     * The connection can no longer answer this prompt. The notification stays
     * so the request is not silently lost, but its buttons go and its body says
     * where the answer still lives.
     *
     * Takes the kind, because a question that degraded into `Approval needed`
     * would be telling somebody a command is waiting when a question is.
     */
    fun degrade(kind: NotificationKind, durableSessionId: String)

    /**
     * Desktop's `Send test notification`, and it earns more here.
     *
     * On Android a notification can be silently dropped by a revoked grant, by
     * Do Not Disturb, or by a channel the person muted in the OS rather than in
     * this app — none of which the settings screen can see. One notification
     * that either appears or does not tells those apart.
     *
     * Filed outside the per-conversation grouping and answering to no session,
     * because it is about the delivery path rather than about any chat.
     */
    fun postTest(title: String, body: String)
}
