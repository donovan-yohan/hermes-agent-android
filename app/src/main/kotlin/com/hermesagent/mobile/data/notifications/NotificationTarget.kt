package com.hermesagent.mobile.data.notifications

import android.content.Intent

internal sealed interface NotificationTarget {
    val endpoint: String
    data class Session(override val endpoint: String, val id: String, val profile: String) : NotificationTarget
    data class Room(override val endpoint: String, val id: String) : NotificationTarget
}

internal const val ACTION_OPEN_CONVERSATION = "com.hermesagent.mobile.OPEN_CONVERSATION"
private const val ENDPOINT = "notification.endpoint"
private const val KIND = "notification.kind"
private const val ID = "notification.id"
private const val PROFILE = "notification.profile"

internal fun notificationTarget(intent: Intent?): NotificationTarget? {
    if (intent?.action != ACTION_OPEN_CONVERSATION) return null
    val endpoint = intent.getStringExtra(ENDPOINT)?.takeIf { it.isNotBlank() } ?: return null
    val id = intent.getStringExtra(ID)?.takeIf { it.isNotBlank() } ?: return null
    return when (intent.getStringExtra(KIND)) {
        "session" -> intent.getStringExtra(PROFILE)?.takeIf { it.isNotBlank() && it == it.trim() }
            ?.let { NotificationTarget.Session(endpoint, id, it) }
        "room" -> NotificationTarget.Room(endpoint, id)
        else -> null
    }
}

internal fun NotificationTarget.writeTo(intent: Intent): Intent = intent.setAction(ACTION_OPEN_CONVERSATION)
    .putExtra(ENDPOINT, endpoint).apply {
        when (val target = this@writeTo) {
            is NotificationTarget.Session -> { putExtra(KIND, "session"); putExtra(ID, target.id); putExtra(PROFILE, target.profile) }
            is NotificationTarget.Room -> { putExtra(KIND, "room"); putExtra(ID, target.id); removeExtra(PROFILE) }
        }
    }
