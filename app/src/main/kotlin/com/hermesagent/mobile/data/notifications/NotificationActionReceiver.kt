package com.hermesagent.mobile.data.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.hermesagent.mobile.HermesApplication
import com.hermesagent.mobile.data.gateway.GatewaySessionRepository
import com.hermesagent.mobile.data.gateway.PendingInputAction
import com.hermesagent.mobile.data.gateway.PendingInputKey
import com.hermesagent.mobile.data.gateway.PendingInputKind
import com.hermesagent.mobile.data.gateway.PendingInputResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Approve / Reject from the shade.
 *
 * It answers through the *same* `respondToPendingInput` the in-app bar uses,
 * so there is one writer, one session token, one connection-generation fence
 * and one in-flight guard. The alternative — a second RPC path that knows how
 * to answer approvals — is how two surfaces end up disagreeing about what is
 * still pending.
 *
 * Not exported: the only thing that can send this intent is a PendingIntent
 * this app built, with every field already fixed.
 */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_RESPOND_TO_APPROVAL) return
        val app = context.applicationContext as? HermesApplication ?: return
        val durableSessionId = intent.getStringExtra(EXTRA_DURABLE_SESSION_ID)?.takeIf(String::isNotBlank) ?: return
        val runtimeSessionId = intent.getStringExtra(EXTRA_RUNTIME_SESSION_ID)?.takeIf(String::isNotBlank) ?: return
        val requestId = intent.getStringExtra(EXTRA_REQUEST_ID)?.takeIf(String::isNotBlank) ?: return
        val choice = intent.getStringExtra(EXTRA_CHOICE)?.takeIf(SHADE_CHOICES::contains) ?: return
        // A missing generation is not generation zero: zero is a real, live
        // connection on a process that has only ever dialled once.
        if (!intent.hasExtra(EXTRA_CONNECTION_GENERATION)) return
        val generation = intent.getLongExtra(EXTRA_CONNECTION_GENERATION, -1L)

        val key = PendingInputKey(generation, runtimeSessionId, requestId, PendingInputKind.Approval)
        // A broadcast receiver's process can be killed the moment `onReceive`
        // returns; the RPC is the whole point, so it has to outlive it.
        val finish = goAsync()
        app.appScope.launch {
            try {
                respondFromShade(app.sessionRepository, app.notificationSurface, key, durableSessionId, choice)
            } finally {
                finish.finish()
            }
        }
    }
}

/** Only ever the two the shade offers: `session` and `always` are persistent
 *  grants and stay in the app, one deliberate tap away from a real screen. */
private val SHADE_CHOICES = setOf(CHOICE_APPROVE, CHOICE_DENY)

/**
 * Android-free so the three outcomes can be tested without a device.
 *
 * `approval.respond` answers `{resolved: N}` (`tui_gateway/methods_prompt.py:1496-1517`
 * @ `f82f2dbabd9e66b714f2b4f8a40447fe0c13e732`) and carries no `status`, so
 * `resolved == 0` — nothing was pending, because it was answered somewhere else
 * — arrives here as [PendingInputResponse.Resolved]. That is the intended
 * reading: withdraw the notification without saying anything.
 */
internal suspend fun respondFromShade(
    repository: GatewaySessionRepository,
    surface: NotificationSurface,
    key: PendingInputKey,
    durableSessionId: String,
    choice: String,
) {
    val response = try {
        repository.respondToPendingInput(key, PendingInputAction.ApprovalChoice(choice))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        PendingInputResponse.Retryable
    }
    when (response) {
        PendingInputResponse.Resolved, PendingInputResponse.Expired ->
            surface.clear(NotificationKind.Approval, durableSessionId)
        // The socket moved on, or another answer is already in flight. The
        // request may still be parked, so the notification stays and says so.
        PendingInputResponse.Retryable -> surface.degradeApproval(durableSessionId)
    }
}
