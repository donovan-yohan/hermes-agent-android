package com.hermesagent.mobile.data.notifications

import android.app.Application
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import com.hermesagent.mobile.data.profiles.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class NotificationAvatarPresenterTest {
    @Test fun usesAcceptedRosterPermissionAndNeverAliasesPeerDisplayName() = runTest {
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val h = AvatarRosterHarness(this, AvatarDecoder { bitmap })
        h.rpc.roster = avatarRosterWire(name = "local-bot")
        h.rpc.answer = { method -> if (method == "profiles.list") h.rpc.roster else buildJsonObject {
            put("found", true); put("data", "data:image/png;base64,iVBORw0KGgo="); put("mime", "image/png"); put("size", 8)
        } }
        h.coreRef()
        val presenter = NotificationAvatarPresenter(h.coordinator) { h.core.roster.value.profiles }
        var local: Bitmap? = null
        presenter.withAvatar("local-bot") { local = it }
        assertSame(bitmap, local)
        assertSame(bitmap, presenter.current("local-bot"))
        val surface = AndroidBotMessageSurface(ApplicationProvider.getApplicationContext())
        presenter.withAvatar("local-bot") { avatar -> surface.postMessage(BotMessage(
            MessageConversation("endpoint", MessageConversationKind.Session, "qualified", "local-bot"),
            "1", "local-bot", "Local bot", "local-bot", "Chat", "Reply", 1), true, avatar) }
        val manager = ApplicationProvider.getApplicationContext<android.content.Context>().getSystemService(android.app.NotificationManager::class.java)
        assertNotNull(androidx.core.app.NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(
            manager.activeNotifications.single().notification)!!.messages.single().person!!.icon)
        val calls = h.assetCalls().size
        presenter.withAvatar(null) { assertNull(it) }
        presenter.withAvatar("peer-display-name") { assertNull(it) }
        assertEquals(calls, h.assetCalls().size)
        h.coordinator.reset()
        presenter.withAvatar("local-bot") { assertNull(it) }
        h.close()
    }
}
