package com.hermesagent.mobile

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.lifecycle.lifecycleScope
import com.hermesagent.mobile.data.notifications.*
import kotlinx.coroutines.launch

/** Synthetic senders only. Runs the production inbox, policy and native notification sink. */
class BotNotificationParityActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch { postBotNotificationFixture(this@BotNotificationParityActivity,
            intent.getBooleanExtra("preview", true)) }
        setContent { Text("Synthetic bot notifications. Open the notification shade.") }
    }
}

internal suspend fun postBotNotificationFixture(context: android.content.Context, preview: Boolean) {
    val surface = AndroidBotMessageSurface(context)
    val store = object : NotificationCheckpointStore {
        private var state = NotificationCheckpoint()
        override suspend fun load(endpoint: String) = state
        override suspend fun save(endpoint: String, checkpoint: NotificationCheckpoint) { state = checkpoint }
    }
    val inbox = NotificationInbox(store)
    val notifications = MessageNotifications(inbox::accept, { "synthetic-notification-endpoint" },
        TransientNotificationPreferences(NotificationSettings(preview = preview)), NotificationPresence()) { message, showPreview ->
        surface.postMessage(message, showPreview, fixtureAvatar(if (message.senderId == "research") "R" else "P"))
    }
    val room = MessageConversation("synthetic-notification-endpoint", MessageConversationKind.Room, "synthetic-room")
    notifications.accept(BotMessage(room, "synthetic-1", "research", "Research bot", null,
        "Release planning", "The test results are ready. Two cases need a closer look.", System.currentTimeMillis()))
    notifications.accept(BotMessage(room, "synthetic-2", "planner", "Planning bot", null,
        "Release planning", "I updated the checklist with the remaining work.", System.currentTimeMillis()))
}

private fun fixtureAvatar(letter: String): Bitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888).also {
    val canvas = Canvas(it)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    paint.color = if (letter == "R") Color.rgb(61, 105, 156) else Color.rgb(151, 83, 113)
    canvas.drawCircle(64f, 64f, 64f, paint)
    paint.color = Color.WHITE
    paint.textSize = 64f
    paint.textAlign = Paint.Align.CENTER
    canvas.drawText(letter, 64f, 87f, paint)
}
