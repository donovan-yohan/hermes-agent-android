package com.hermesagent.mobile.data.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.hermesagent.mobile.MainActivity
import java.util.concurrent.ConcurrentHashMap

/**
 * The OS half of notifying: two channels, one notification per (session, kind),
 * grouped per conversation.
 *
 * Nothing here decides *whether* to notify — [SessionNotifier] owns that — so
 * this file is only ever wrong about how a notification looks, never about
 * when one appears.
 *
 * Privacy shape: the body carries a redacted session title and nothing else,
 * and the notification is `VISIBILITY_PRIVATE` with a [publicVersion] that
 * drops even that. A locked screen therefore says "Approval needed" and which
 * app, and no more.
 */
class AndroidNotificationSurface(context: Context) : NotificationSurface {
    private val context = context.applicationContext
    private val manager = NotificationManagerCompat.from(this.context)

    /** Live (session, kind) tags, so a group summary can be withdrawn with its last child. */
    private val live = ConcurrentHashMap<String, MutableSet<NotificationKind>>()

    init {
        registerChannels(this.context)
    }

    override fun post(post: NotificationPost) {
        val builder = builder(post.kind, post.durableSessionId, post.title, post.body)

        post.approval?.let { target ->
            builder
                .addAction(0, NotificationCopy.APPROVE_ACTION, respondIntent(target, CHOICE_APPROVE))
                .addAction(0, NotificationCopy.REJECT_ACTION, respondIntent(target, CHOICE_DENY))
        }

        show(post.kind, post.durableSessionId, builder)
    }

    override fun degradeApproval(durableSessionId: String) {
        // Same shape, no buttons, and quiet: this replaces a notification the
        // user has already been alerted to.
        val builder = builder(
            kind = NotificationKind.Approval,
            durableSessionId = durableSessionId,
            title = NotificationCopy.APPROVAL_TITLE,
            body = NotificationCopy.OPEN_TO_RESPOND,
        ).setOnlyAlertOnce(true)

        show(NotificationKind.Approval, durableSessionId, builder)
    }

    /** The shape every notification this app posts shares. */
    private fun builder(
        kind: NotificationKind,
        durableSessionId: String,
        title: String,
        body: String,
    ) = NotificationCompat.Builder(context, kind.channelId)
        .setSmallIcon(SMALL_ICON)
        .setContentTitle(title)
        .setContentText(body)
        .setCategory(categoryFor(kind))
        .setPriority(priorityFor(kind))
        .setGroup(groupKey(durableSessionId))
        .setAutoCancel(true)
        .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        .setPublicVersion(publicVersion(kind, title))
        .setContentIntent(openSessionIntent(durableSessionId))

    override fun clear(kind: NotificationKind, durableSessionId: String) {
        manager.cancel(childTag(kind, durableSessionId), NOTIFICATION_ID)
        val remaining = live[durableSessionId]?.also { it.remove(kind) }
        if (remaining.isNullOrEmpty()) {
            live.remove(durableSessionId)
            manager.cancel(summaryTag(durableSessionId), NOTIFICATION_ID)
        }
    }

    override fun clearSession(durableSessionId: String) {
        for (kind in NotificationKind.entries) {
            manager.cancel(childTag(kind, durableSessionId), NOTIFICATION_ID)
        }
        live.remove(durableSessionId)
        manager.cancel(summaryTag(durableSessionId), NOTIFICATION_ID)
    }

    private fun show(kind: NotificationKind, durableSessionId: String, builder: NotificationCompat.Builder) {
        // Posting is a no-op without the runtime grant, and the OS reports that
        // by doing nothing at all rather than by throwing.
        if (!manager.areNotificationsEnabled()) return
        val kinds = live.getOrPut(durableSessionId) { ConcurrentHashMap.newKeySet() }
        val firstOfItsKind = kinds.add(kind)
        manager.notify(childTag(kind, durableSessionId), NOTIFICATION_ID, builder.build())
        // Android bundles a group only once a summary exists; without one, two
        // notifications for the same conversation arrive as two unrelated rows.
        // Only when this conversation's set of live kinds actually changed:
        // re-posting an identical summary on a high-importance channel is a
        // second alert for news the user has already been told.
        if (firstOfItsKind) {
            manager.notify(summaryTag(durableSessionId), NOTIFICATION_ID, summary(durableSessionId))
        }
    }

    private fun summary(durableSessionId: String) =
        NotificationCompat.Builder(context, APPROVALS_CHANNEL_ID)
            .setSmallIcon(SMALL_ICON)
            .setGroup(groupKey(durableSessionId))
            .setGroupSummary(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(openSessionIntent(durableSessionId))
            .build()

    /** What a locked screen is allowed to render: the kind, and nothing about the conversation. */
    private fun publicVersion(kind: NotificationKind, title: String) =
        NotificationCompat.Builder(context, kind.channelId)
            .setSmallIcon(SMALL_ICON)
            .setContentTitle(title)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()

    private fun openSessionIntent(durableSessionId: String): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .setAction(ACTION_OPEN_SESSION)
            .putExtra(EXTRA_DURABLE_SESSION_ID, durableSessionId)
            // The app has one Activity at the task root, so this reuses the
            // running task and arrives at `onNewIntent` rather than stacking a
            // second copy of the whole app on top of itself.
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            context,
            requestCode("open", durableSessionId),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * Immutable on purpose: every field the receiver acts on is fixed when the
     * notification is built, so nothing that can reach this PendingIntent can
     * redirect which request gets answered or with what choice.
     */
    private fun respondIntent(target: ApprovalTarget, choice: String): PendingIntent {
        val intent = Intent(context, NotificationActionReceiver::class.java)
            .setAction(ACTION_RESPOND_TO_APPROVAL)
            .putExtra(EXTRA_DURABLE_SESSION_ID, target.durableSessionId)
            .putExtra(EXTRA_RUNTIME_SESSION_ID, target.key.runtimeSessionId)
            .putExtra(EXTRA_REQUEST_ID, target.key.requestId)
            .putExtra(EXTRA_CONNECTION_GENERATION, target.key.connectionGeneration)
            .putExtra(EXTRA_CHOICE, choice)
        return PendingIntent.getBroadcast(
            context,
            requestCode(choice, target.durableSessionId + target.key.requestId),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private companion object {
        /**
         * A monochrome status-bar glyph, borrowed from the framework for the
         * same reason [com.hermesagent.mobile.data.voice.WakeWordForegroundService]
         * does: the launcher mark is a full-colour bitmap and would render as a
         * white block. A drawn Hermes notification mark is design work.
         */
        const val SMALL_ICON = android.R.drawable.stat_notify_chat

        /** Tags carry the identity; one id is enough because (tag, id) is the key. */
        const val NOTIFICATION_ID = 0x48

        fun categoryFor(kind: NotificationKind): String =
            if (kind in ATTENTION_KINDS) NotificationCompat.CATEGORY_CALL else NotificationCompat.CATEGORY_MESSAGE

        fun priorityFor(kind: NotificationKind): Int =
            if (kind in ATTENTION_KINDS) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT

        fun requestCode(role: String, discriminator: String): Int = (role + discriminator).hashCode()
    }
}

internal fun groupKey(durableSessionId: String): String = "hermes.session.$durableSessionId"

internal fun childTag(kind: NotificationKind, durableSessionId: String): String =
    "hermes:${kind.key}:$durableSessionId"

internal fun summaryTag(durableSessionId: String): String = "hermes:group:$durableSessionId"

/**
 * Two channels, created once and never re-described: the OS keeps the first
 * name and importance it is given, and a user's own change to either must
 * survive an app update.
 */
fun registerChannels(context: Context) {
    val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    manager.createNotificationChannel(
        NotificationChannel(
            APPROVALS_CHANNEL_ID,
            NotificationCopy.APPROVALS_CHANNEL_NAME,
            NotificationManager.IMPORTANCE_HIGH,
        ).apply { description = NotificationCopy.APPROVALS_CHANNEL_DESCRIPTION },
    )
    manager.createNotificationChannel(
        NotificationChannel(
            RESPONSES_CHANNEL_ID,
            NotificationCopy.RESPONSES_CHANNEL_NAME,
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply { description = NotificationCopy.RESPONSES_CHANNEL_DESCRIPTION },
    )
}

const val ACTION_OPEN_SESSION: String = "com.hermesagent.mobile.notifications.OPEN_SESSION"
const val ACTION_RESPOND_TO_APPROVAL: String = "com.hermesagent.mobile.notifications.RESPOND"
const val EXTRA_DURABLE_SESSION_ID: String = "com.hermesagent.mobile.notifications.extra.SESSION_ID"
const val EXTRA_RUNTIME_SESSION_ID: String = "com.hermesagent.mobile.notifications.extra.RUNTIME_ID"
const val EXTRA_REQUEST_ID: String = "com.hermesagent.mobile.notifications.extra.REQUEST_ID"
const val EXTRA_CONNECTION_GENERATION: String = "com.hermesagent.mobile.notifications.extra.GENERATION"
const val EXTRA_CHOICE: String = "com.hermesagent.mobile.notifications.extra.CHOICE"

/** The Gateway's own approval vocabulary (`gateway/platforms/api_server.py:77` @ the pin). */
const val CHOICE_APPROVE: String = "once"
const val CHOICE_DENY: String = "deny"
