package com.hermesagent.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.lifecycle.lifecycleScope
import com.hermesagent.mobile.data.gateway.GatewayTurnOutcome
import com.hermesagent.mobile.data.gateway.LiveSessionStatus
import com.hermesagent.mobile.data.notifications.*
import com.hermesagent.mobile.data.session.*
import com.hermesagent.mobile.ui.theme.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch

/** Real OS sink, isolated in-memory inputs. Use only a clean debug install; never tap its deep links. */
class NotificationParityActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val preview = when (intent.getStringExtra("visual_parity_state")) {
            "latest-preview" -> true
            "latest-preview-off" -> false
            else -> error("unsupported notification parity state")
        }
        val sink = AndroidNotificationSurface(this)
        // Only fixture-owned identities are replaced; never cancel unrelated notifications.
        sink.clearSession(NOTIFICATION_FIXTURE_SESSION)
        startNotificationParityFixture(lifecycleScope, sink, preview)
        setContent {
            HermesTheme(AppearanceSelection("mono", if (intent.getStringExtra("visual_parity_theme") == "light")
                HermesThemeMode.Light else HermesThemeMode.Dark)) {
                Text("Synthetic notifications: wait five seconds, then open the notification shade.")
            }
        }
    }
}

internal const val NOTIFICATION_FIXTURE_SESSION = "synthetic-notification-parity"
internal const val NOTIFICATION_FIXTURE_LATEST = "The synthetic release checklist now has three reviewed items."
internal const val NOTIFICATION_FIXTURE_COMPLETED = "The synthetic release checklist is ready for review."

/** Production notifier, live-message scope validation, sanitizer and platform sink; no Gateway graph. */
internal fun startNotificationParityFixture(
    scope: CoroutineScope,
    surface: NotificationSurface,
    preview: Boolean,
    clock: () -> Long = System::currentTimeMillis,
) {
    val id = NOTIFICATION_FIXTURE_SESSION
    val outcomes = MutableSharedFlow<GatewayTurnOutcome>()
    val activity = MutableStateFlow(GatewayActivity.Empty)
    val cache = MutableStateFlow(SessionCacheState(sessions = mapOf(
        id to SessionSummary(id, "Synthetic planning", "Stale registry preview", 0L),
    )))
    SessionNotifier(
        pendingInputs = MutableStateFlow(emptyMap()), turnOutcomes = outcomes,
        sessions = cache, socketOpens = emptyFlow(), connected = MutableStateFlow(true),
        activeTurns = MutableStateFlow(emptySet()), activity = activity,
        presence = NotificationPresence(), settingsFlow = MutableStateFlow(NotificationSettings(preview = preview)),
        surface = surface, clock = clock,
    ).start(scope)
    scope.launch {
        // Let the actual notifier's 4-second seed quiet window expire, not a bypass of its guards.
        delay(4_200)
        val liveScope = LiveNotificationScope(1L, "synthetic-runtime", 1L)
        val live = LiveNotificationMessages(1L, mapOf(id to liveScope), mapOf(
            id to LiveNotificationMessage(liveScope, AssistantTurn("synthetic-message", NOTIFICATION_FIXTURE_LATEST, 0L)),
        ))
        activity.value = GatewayActivity(listOf(GatewayActivityChild(
            id, "Synthetic planning", "Stale registry preview", LiveSessionStatus.Working,
            null, 0L, live.previewFor(id),
        )))
        outcomes.emit(GatewayTurnOutcome(id, failed = false, assistantMessagePreview = NOTIFICATION_FIXTURE_COMPLETED))
    }
}
