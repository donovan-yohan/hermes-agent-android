package com.hermesagent.mobile.data.gateway

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.hermesagent.mobile.data.notifications.NotificationCopy

/**
 * Keeps this process out of the background for the length of one sign-in.
 *
 * The reason is not politeness, it is that the network is switched off without
 * it. Android 17 puts a cached app's uid into `blocked=APP_BACKGROUND` and netd
 * destroys its live TCP sockets — measured on the emulator as
 * `Destroyed live tcp sockets for uids={10233}` 0.8 s before the callback, with
 * the token POST then failing in 2 ms with `UnknownHostException`, three times
 * over. This is stock behaviour, not a rig setting: Data Saver was off,
 * restrict-background was false and appops was default-allow, and the target
 * Pixel 10 Pro runs the same OS (#114, comment 5473822102 and the rig-config
 * check beneath it). Nothing inside the app can work around a uid-level block —
 * no pool eviction, no retry, no timeout — because the request never reaches a
 * network at all.
 *
 * A `dataSync` foreground service is the sanctioned way to say "this work
 * outlives the screen". It also covers the other half of the problem for free:
 * a process with a running foreground service is not a cached process, so the
 * Android 12+ cached-app freezer cannot stop the loopback accept loop either.
 * The Custom Tabs binding stays as belt and braces, and because it is what
 * makes the tab itself fast.
 *
 * Bounded by the sign-in it serves: started before the browser opens and
 * stopped in the same `finally` that closes the callback listener, so the
 * five-minute login timeout is also this service's ceiling.
 */
class SignInForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification())
        }
        // Never restarted on its own: a sign-in that died with the process has
        // no code left to redeem, and a service that came back without one
        // would be a notification about nothing.
        return START_NOT_STICKY
    }

    private fun notification(): Notification = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setContentTitle(NotificationCopy.SIGN_IN_TITLE)
        .setContentText(NotificationCopy.SIGN_IN_BODY)
        .setOngoing(true)
        .build()

    /**
     * Its own channel, at low importance. The session channels are about a
     * session's work and a person may silence them; this one is the visible
     * half of a service they are actively waiting on.
     */
    private fun createChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                NotificationCopy.SIGN_IN_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = NotificationCopy.SIGN_IN_CHANNEL_DESCRIPTION },
        )
    }

    companion object {
        internal const val CHANNEL_ID = "gateway_sign_in"

        /** Distinct from the wake-word service's 42. */
        internal const val NOTIFICATION_ID = 43

        fun start(context: Context) {
            context.startForegroundService(Intent(context, SignInForegroundService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SignInForegroundService::class.java))
        }
    }
}
