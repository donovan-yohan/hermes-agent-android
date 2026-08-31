package com.hermesagent.mobile.data.gateway

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.hermesagent.mobile.MainActivity
import com.hermesagent.mobile.data.notifications.NotificationCopy

/**
 * Keeps this process and its gateway WebSocket alive while a turn is running
 * or an input/approval is pending.
 *
 * Android freezes cached processes and destroys live sockets when backgrounded.
 * A dataSync foreground service ensures the process stays runnable and network
 * stays open so that turns finish uninterrupted and turn outcomes or approval
 * requests can notify the user.
 *
 * Held only while active work is in progress (with a short linger grace).
 */
class TurnForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIFICATION_ID, notification())
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

    private fun notification(): Notification = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(SMALL_ICON)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setContentTitle(NotificationCopy.TURN_PROTECTION_TITLE)
        .setContentText(NotificationCopy.TURN_PROTECTION_BODY)
        .setOngoing(true)
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

    private fun createChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                NotificationCopy.TURN_PROTECTION_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = NotificationCopy.TURN_PROTECTION_CHANNEL_DESCRIPTION },
        )
    }

    companion object {
        internal const val CHANNEL_ID = "turn_protection"

        /** Distinct from the wake-word service's 42 and sign-in service's 43. */
        internal const val NOTIFICATION_ID = 44
        internal const val SMALL_ICON = android.R.drawable.stat_notify_sync

        /** Single-registrant main-thread-only callback for foreground service failure/refusal notifications. */
        @Volatile
        internal var onServiceFailure: (() -> Unit)? = null

        fun start(context: Context): Boolean {
            return try {
                context.startForegroundService(Intent(context, TurnForegroundService::class.java))
                true
            } catch (_: IllegalStateException) {
                // Degrades gracefully when background start restrictions apply
                false
            } catch (_: SecurityException) {
                // Degrades gracefully if permission is missing
                false
            }
        }

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
