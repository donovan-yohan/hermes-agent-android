package com.hermesagent.mobile.data.notifications

/** What the notifier asked the OS to do, without an OS. */
internal class RecordingNotificationSurface : NotificationSurface {
    val posts = mutableListOf<NotificationPost>()
    val cleared = mutableListOf<Pair<NotificationKind, String>>()
    val clearedSessions = mutableListOf<String>()
    val degraded = mutableListOf<String>()
    val tests = mutableListOf<Pair<String, String>>()
    val degradedKinds = mutableListOf<Pair<NotificationKind, String>>()
    val activity = mutableListOf<List<NotificationActivityChild>>()

    val latestActivity: List<NotificationActivityChild>
        get() = activity.lastOrNull().orEmpty()

    override fun post(post: NotificationPost) {
        posts += post
    }

    override fun postActivity(children: List<NotificationActivityChild>) {
        activity += children
    }

    override fun clear(kind: NotificationKind, durableSessionId: String) {
        cleared += kind to durableSessionId
    }

    override fun clearSession(durableSessionId: String) {
        clearedSessions += durableSessionId
    }

    override fun degrade(kind: NotificationKind, durableSessionId: String) {
        degraded += durableSessionId
        degradedKinds += kind to durableSessionId
    }

    override fun postTest(title: String, body: String) {
        tests += title to body
    }

    fun posted(): List<Pair<NotificationKind, String>> = posts.map { it.kind to it.durableSessionId }
}
