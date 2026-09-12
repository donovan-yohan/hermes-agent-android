package com.hermesagent.mobile.data.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import com.hermesagent.mobile.HermesApplication
import com.hermesagent.mobile.data.gateway.APPROVAL_ALWAYS
import com.hermesagent.mobile.data.gateway.APPROVAL_DENY
import com.hermesagent.mobile.data.gateway.APPROVAL_ONCE
import com.hermesagent.mobile.data.gateway.APPROVAL_SESSION
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
        when (intent.action) {
            ACTION_RESPOND_TO_APPROVAL -> onApproval(context, intent)
            ACTION_ANSWER_QUESTION -> onAnswer(context, intent)
        }
    }

    private fun onApproval(context: Context, intent: Intent) {
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

    /**
     * A clarify answered from the shade: one of its own choices, or typed.
     *
     * The typed form is the only field on any of these intents that is not
     * fixed when the notification is built, and it does not come from the
     * sender: `RemoteInput` writes it into the intent's own clip data, which is
     * why that PendingIntent is mutable and the button-per-choice one is not.
     *
     * A blank answer is dropped rather than sent. An empty `clarify.respond`
     * with no question id is the Gateway's *batch-wide cancel*
     * (`GatewaySessionRepository`), so a reply box someone opened, cleared and
     * sent would cancel the question instead of answering it.
     */
    private fun onAnswer(context: Context, intent: Intent) {
        val app = context.applicationContext as? HermesApplication ?: return
        val durableSessionId = intent.getStringExtra(EXTRA_DURABLE_SESSION_ID)?.takeIf(String::isNotBlank) ?: return
        val runtimeSessionId = intent.getStringExtra(EXTRA_RUNTIME_SESSION_ID)?.takeIf(String::isNotBlank) ?: return
        val requestId = intent.getStringExtra(EXTRA_REQUEST_ID)?.takeIf(String::isNotBlank) ?: return
        if (!intent.hasExtra(EXTRA_CONNECTION_GENERATION)) return
        val generation = intent.getLongExtra(EXTRA_CONNECTION_GENERATION, -1L)
        val questionId = intent.getStringExtra(EXTRA_QUESTION_ID).orEmpty()

        val typed = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(EXTRA_ANSWER)?.toString()
        val answer = (typed ?: intent.getStringExtra(EXTRA_ANSWER)).orEmpty().trim()
        if (answer.isEmpty()) return

        val key = PendingInputKey(generation, runtimeSessionId, requestId, PendingInputKind.Clarify)
        val finish = goAsync()
        app.appScope.launch {
            try {
                answerFromShade(
                    app.sessionRepository,
                    app.notificationSurface,
                    key,
                    durableSessionId,
                    questionId,
                    answer,
                )
            } finally {
                finish.finish()
            }
        }
    }
}

/**
 * A first fence, not the authority.
 *
 * The authority is the repository, which refuses any choice the *request* did
 * not offer (`GatewaySessionRepository`), so this only has to keep an intent
 * from carrying something that was never an approval answer at all. It is a
 * prefix of the Gateway's vocabulary rather than a copy of it, and an unknown
 * choice is refused here rather than forwarded: a PendingIntent is built by
 * this app and nothing else can send one, but the shade is the surface with
 * the least context around a decision and the least room to take it back.
 *
 * Persistent grants are on this list now. They are gated where the gate can
 * actually hold — `setAuthenticationRequired` on the action, so Android
 * demands an unlock before the intent fires — rather than by being absent.
 */
private val SHADE_CHOICES = setOf(APPROVAL_ONCE, APPROVAL_SESSION, APPROVAL_ALWAYS, APPROVAL_DENY)

/**
 * Android-free so every outcome can be tested without a device.
 *
 * `approval.respond` answers `{resolved: N}` (`tui_gateway/methods_prompt.py:1513-1534`
 * @ `3ca096de5f8183cb2e0ec23673f294d5978656a3`) and carries no `status`, so
 * `resolved == 0` — the request was answered somewhere else — arrives here as
 * [PendingInputResponse.Resolved]. That is the intended reading: withdraw the
 * notification without saying anything.
 *
 * The outcome that must never be confused with it is
 * [PendingInputResponse.Unanswerable]: the request was never sent, because the
 * connection that parked it is gone. A notification outlives the process that
 * posted it, so its buttons routinely arrive at a repository that has never
 * heard of the request — and withdrawing the notification there would tell
 * someone their approval went through while an agent stays blocked behind it.
 */
/**
 * Answer one clarify, through the same repository seam the in-app card uses.
 *
 * The outcome reading is [respondFromShade]'s, for the same reasons: a request
 * this connection cannot answer keeps its notification and says where the
 * answer still lives, because the alternative is telling somebody their answer
 * landed while an agent stays blocked behind it.
 */
internal suspend fun answerFromShade(
    repository: GatewaySessionRepository,
    surface: NotificationSurface,
    key: PendingInputKey,
    durableSessionId: String,
    questionId: String,
    answer: String,
) {
    val response = try {
        repository.respondToPendingInput(
            key,
            // An empty question id is single-question mode, and the repository
            // then sends no `question_id` at all — which is what the Gateway
            // wants for a single, and what it must never see for a batch.
            PendingInputAction.ClarifyAnswer(mapOf(questionId to answer)),
        )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        PendingInputResponse.Retryable
    }
    when (response) {
        PendingInputResponse.Resolved, PendingInputResponse.Expired ->
            surface.clear(NotificationKind.Input, durableSessionId)
        PendingInputResponse.Retryable, PendingInputResponse.Unanswerable ->
            surface.degrade(NotificationKind.Input, durableSessionId)
    }
}

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
        // Finished business on the connection that owned it.
        PendingInputResponse.Resolved, PendingInputResponse.Expired ->
            surface.clear(NotificationKind.Approval, durableSessionId)
        // Nothing was sent. Either the socket moved on, another answer is
        // already in flight, or this process never knew the request at all.
        // The request may still be parked, so the notification stays and says
        // where it can still be answered.
        PendingInputResponse.Retryable, PendingInputResponse.Unanswerable ->
            surface.degrade(NotificationKind.Approval, durableSessionId)
    }
}
