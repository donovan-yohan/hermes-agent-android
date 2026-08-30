package com.hermesagent.mobile.data.notifications

/** What the notifier asked the OS to do, without an OS. */
internal class RecordingNotificationSurface : NotificationSurface {
    val posts = mutableListOf<NotificationPost>()
    val cleared = mutableListOf<Pair<NotificationKind, String>>()
    val clearedSessions = mutableListOf<String>()
    val degraded = mutableListOf<String>()

    override fun post(post: NotificationPost) {
        posts += post
    }

    override fun clear(kind: NotificationKind, durableSessionId: String) {
        cleared += kind to durableSessionId
    }

    override fun clearSession(durableSessionId: String) {
        clearedSessions += durableSessionId
    }

    override fun degradeApproval(durableSessionId: String) {
        degraded += durableSessionId
    }

    fun posted(): List<Pair<NotificationKind, String>> = posts.map { it.kind to it.durableSessionId }
}
