package com.hermesagent.mobile.data.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * User-started microphone foreground service feeding the Gateway's wake
 * detector. Android policy: a mic FGS cannot be newly started from the
 * background, so wake always starts from a visible gesture and shows a
 * persistent notification while listening.
 */
class WakeWordForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var feedJob: Job? = null
    private var capture: AndroidMicCapture? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            else -> startListening()
        }
        return START_STICKY
    }

    private fun startListening() {
        if (feedJob?.isActive == true) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(phrase = null),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification(phrase = null))
        }
        val repository = (application as? com.hermesagent.mobile.HermesApplication)?.wakeWordRepository
            ?: run { stopSelf(); return }
        val mic = AndroidMicCapture(Dispatchers.IO)
        capture = mic
        feedJob = scope.launch {
            val started = repository.start(persist = true, sessionRuntimeId = null)
            if (started !is WakeStart.Started || !started.captureClientSide || !mic.start()) {
                stopSelf()
                return@launch
            }
            // Feed loop: bounded 16 kHz frames at the detector's contract
            // (max 64,000 bytes = 2 s) until the service is stopped. pump()
            // retains internally; the retained buffer drains in slices.
            while (feedJob?.isActive == true && mic.isRecording) {
                mic.pump()
                kotlinx.coroutines.delay(100)
            }
            mic.close()
            stopSelf()
        }
    }

    private fun buildNotification(phrase: String?): Notification {
        val text = if (phrase.isNullOrBlank()) {
            "Listening for your wake phrase"
        } else {
            "Listening for \"$phrase\""
        }
        return notificationBuilder()
            .setContentTitle("Hermes wake word")
            .setContentText(text)
            .setOngoing(true)
            .build()
    }

    private fun notificationBuilder(): androidx.core.app.NotificationCompat.Builder {
        return androidx.core.app.NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_LOW)
    }

    private fun createChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Wake word", NotificationManager.IMPORTANCE_LOW),
        )
    }

    override fun onDestroy() {
        feedJob?.cancel()
        val closing = capture
        capture = null
        scope.launch {
            closing?.close()
            scope.cancel()
        }
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "wake_word"
        private const val NOTIFICATION_ID = 42
        const val ACTION_STOP = "com.hermesagent.mobile.voice.WAKE_STOP"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, WakeWordForegroundService::class.java))
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, WakeWordForegroundService::class.java).setAction(ACTION_STOP),
            )
        }
    }
}
