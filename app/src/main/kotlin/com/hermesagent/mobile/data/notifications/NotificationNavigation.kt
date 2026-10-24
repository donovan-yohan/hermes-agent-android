package com.hermesagent.mobile.data.notifications

/** Single pending tap owner. Cold taps wait for readiness; stale endpoints are consumed, never rerouted. */
internal class NotificationNavigation {
    var pending: NotificationTarget? = null
        private set
    fun offer(target: NotificationTarget) { pending = target }
    fun ready(endpoint: String?, connected: Boolean, session: (NotificationTarget.Session) -> Unit,
        room: (NotificationTarget.Room) -> Unit) {
        val target = pending ?: return
        if (endpoint == null) return
        if (target.endpoint != endpoint) { pending = null; return }
        if (!connected) return
        pending = null
        when (target) {
            is NotificationTarget.Session -> session(target)
            is NotificationTarget.Room -> room(target)
        }
    }
}
