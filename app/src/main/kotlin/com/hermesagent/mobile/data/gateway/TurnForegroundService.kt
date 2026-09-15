package com.hermesagent.mobile.data.gateway

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.hermesagent.mobile.MainActivity
import com.hermesagent.mobile.data.notifications.AndroidNotificationSurface
import com.hermesagent.mobile.data.notifications.ACTIVITY_GROUP_KEY
import com.hermesagent.mobile.data.notifications.GatewayActivityCounts
import com.hermesagent.mobile.data.notifications.NotificationCopy
import com.hermesagent.mobile.data.notifications.registerChannels

/**
 * Keeps this process and its gateway WebSocket alive while the connection
 * reports live work or an input/approval is pending.
 *
 * Android freezes cached processes and destroys live sockets when backgrounded.
 * A dataSync foreground service helps keep the process runnable and network
 * available while the connection reports that work is live.
 *
 * Android may still stop this service for a timeout, force-stop, reboot,
 * sign-out, or permission refusal.
 */
class TurnForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        registerChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val counts = GatewayActivityCounts(
            chats = intent?.getIntExtra(EXTRA_CHAT_COUNT, 0) ?: 0,
            waiting = intent?.getIntExtra(EXTRA_WAITING_COUNT, 0) ?: 0,
        )
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification(counts), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIFICATION_ID, notification(counts))
            }
        } catch (_: IllegalStateException) {
            stopSelf()
            onServiceFailure?.invoke()
            return START_NOT_STICKY
        } catch (_: SecurityException) {
            stopSelf()
            onServiceFailure?.invoke()
            return START_NOT_STICKY
        }
        return START_NOT_STICKY
    }

    /** Android 15 (API 35+) dataSync foreground service timeout callback. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        stopSelf()
        onServiceFailure?.invoke()
    }

    private fun notification(counts: GatewayActivityCounts): Notification =
        NotificationCompat.Builder(this, AndroidNotificationSurface.ACTIVITY_CHANNEL_ID)
        .setSmallIcon(SMALL_ICON)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setContentTitle(NotificationCopy.ACTIVITY_TITLE)
        .setContentText(NotificationCopy.activitySummary(counts))
        .setOngoing(true)
        .setSilent(true)
        .setOnlyAlertOnce(true)
        .setCategory(NotificationCompat.CATEGORY_STATUS)
        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        .setGroup(ACTIVITY_GROUP_KEY)
        .setGroupSummary(true)
        .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
        .setContentIntent(openAppIntent())
        .build()

    private fun openAppIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        /** Distinct from the wake-word service's 42 and sign-in service's 43. */
        internal const val NOTIFICATION_ID = 44
        /** The app's own mark: this notification is Hermes working, not a sync. */
        internal val SMALL_ICON = com.hermesagent.mobile.R.drawable.ic_stat_hermes
        internal const val EXTRA_CHAT_COUNT = "com.hermesagent.mobile.gateway.extra.CHAT_COUNT"
        internal const val EXTRA_WAITING_COUNT = "com.hermesagent.mobile.gateway.extra.WAITING_COUNT"

        /** Single-registrant main-thread-only callback for foreground service failure/refusal notifications. */
        @Volatile
        internal var onServiceFailure: (() -> Unit)? = null

        fun start(context: Context, counts: GatewayActivityCounts): Boolean {
            return try {
                context.startForegroundService(intent(context, counts))
                true
            } catch (_: IllegalStateException) {
                // Degrades gracefully when background start restrictions apply
                false
            } catch (_: SecurityException) {
                // Degrades gracefully if permission is missing
                false
            }
        }

        fun update(context: Context, counts: GatewayActivityCounts) {
            try {
                context.startService(intent(context, counts))
            } catch (_: IllegalStateException) {
                // A missed activity summary update is not worth crashing the app.
            } catch (_: SecurityException) {
                // A missed activity summary update is not worth crashing the app.
            }
        }

        private fun intent(context: Context, counts: GatewayActivityCounts) =
            Intent(context, TurnForegroundService::class.java)
                .putExtra(EXTRA_CHAT_COUNT, counts.chats)
                .putExtra(EXTRA_WAITING_COUNT, counts.waiting)

        /**
         * Leaves [onServiceFailure] registered: the controller re-takes protection
         * after a normal stop, and a later refused start must still reach it.
         * The callback is inert while the controller holds no protection.
         */
        fun stop(context: Context) {
            context.stopService(Intent(context, TurnForegroundService::class.java))
        }
    }
}
