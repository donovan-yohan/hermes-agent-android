package com.hermesagent.mobile.data.gateway

import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject

/**
 * One Gateway request that parked a turn waiting for the user. Requests are
 * repository memory only — never persisted, serialized, logged, or mirrored
 * into [com.hermesagent.mobile.data.session.SessionCache].
 */
sealed interface PendingInputRequest {
    /** Connection-generation + runtime + request-id + kind identity fence. */
    val key: PendingInputKey
    val durableSessionId: String
    val runtimeSessionId: String
}

data class PendingInputKey(
    val connectionGeneration: Long,
    val runtimeSessionId: String,
    val requestId: String,
    val kind: PendingInputKind,
)

/**
 * The question id a single-question clarify answer carries.
 *
 * A batch answer is keyed by its `qid`; a single has no question at all, and
 * both the card and the shade use the empty string as the key that says so
 * (`ui/chat/PendingInputSurface.kt`, `NotificationActionReceiver.kt`).
 */
const val CLARIFY_SINGLE_QUESTION_ID: String = ""

/**
 * The blocking-input family this client can answer, in Desktop's own handler
 * order (`apps/desktop/src/app/session/hooks/use-message-stream/gateway-event/
 * input-requests.ts` and `tui_gateway/contracts/server_requests.py:58-142` @
 * `437116f9497c80d242ce034ff7f5d81dc277a337`).
 *
 * Each kind is raised by one server→client request method — `clarify`,
 * `approval`, `sudo`, `secret`, `vault.code`, `vault.save_login`,
 * `vault.unlock_prompt` — and the mapping lives in one place
 * (`GatewaySessionRepository.pendingInputKind`). Methods with no kind here are
 * desktop surfaces this app does not have, and are left to the backend's own
 * timeout rather than answered with an invented result.
 *
 * Never persisted and never serialized: a notification action intent carries
 * the *fields* of a [PendingInputKey] and rebuilds the kind from the constant
 * it is answering (`NotificationActionReceiver.kt:40`), so this enum may be
 * reordered freely.
 *
 * The three vault kinds are one family with the other two secret-bearing ones,
 * not with clarify: [VaultUnlock] and [VaultSaveLogin] carry a password and
 * [VaultCode] a one-time code, so all three answer in the secure dialog and
 * never reach a notification body.
 */
enum class PendingInputKind { Clarify, Approval, Sudo, Secret, VaultCode, VaultSaveLogin, VaultUnlock }

data class ClarifyQuestion(
    val questionId: String,
    val question: String,
    /** Normalized single-line display choices; empty means open text. */
    val choices: List<String>,
    val multiSelect: Boolean,
)

data class ClarifyPending(
    override val key: PendingInputKey,
    override val durableSessionId: String,
    override val runtimeSessionId: String,
    /** Empty means single-question mode with [question]/[choices]. */
    val questions: List<ClarifyQuestion> = emptyList(),
    val question: String = "",
    val choices: List<String> = emptyList(),
    val multiSelect: Boolean = false,
) : PendingInputRequest

data class ApprovalPending(
    override val key: PendingInputKey,
    override val durableSessionId: String,
    override val runtimeSessionId: String,
    val command: String,
    val description: String,
    /** Only choices the Gateway actually offered, e.g. Run once / Reject / Always allow. */
    val choices: List<String>,
) : PendingInputRequest

data class SudoPending(
    override val key: PendingInputKey,
    override val durableSessionId: String,
    override val runtimeSessionId: String,
) : PendingInputRequest

data class SecretPending(
    override val key: PendingInputKey,
    override val durableSessionId: String,
    override val runtimeSessionId: String,
    val envVarLabel: String,
    val prompt: String,
) : PendingInputRequest

/**
 * A one-time code the site asked for, because no authenticator key is saved
 * with the login (`gateway-event/server-requests.ts:227-236` @ the pin).
 * Answered with one response frame `{value: code}`; `""` skips.
 *
 * [site] and [hint] are display text the Gateway sent, so they are redacted and
 * bounded on the way in like every other pending-input field. The code itself
 * never lands here — it lives in a [PendingInputAction.VaultCode] for the
 * length of one request.
 */
data class VaultCodePending(
    override val key: PendingInputKey,
    override val durableSessionId: String,
    override val runtimeSessionId: String,
    val site: String,
    /** Where the code was sent, when the backend knows; often empty. */
    val hint: String,
) : PendingInputRequest

/**
 * A sign-in page with nothing saved for it
 * (`gateway-event/server-requests.ts:237-246` @ the pin). Answered with one
 * response frame `{value: login}` where `login` is the JSON
 * `{identifier, password}` Desktop sends (`prompt-overlays.tsx:418` @ the
 * pin); `""` declines.
 *
 * The backend stores the pair in its encrypted vault and fills the page with
 * it; the model never sees the password
 * (`tui_gateway/agent_callbacks.py:181-190` @ the pin).
 */
data class VaultSaveLoginPending(
    override val key: PendingInputKey,
    override val durableSessionId: String,
    override val runtimeSessionId: String,
    /** The page's origin, e.g. `https://example.com`. */
    val origin: String,
    /** Display name for the site; the backend falls back to [origin]. */
    val site: String,
) : PendingInputRequest

/**
 * An external password manager asking for its master password
 * (`gateway-event/server-requests.ts:248-260` @ the pin). Answered with one
 * response frame `{value: password}`; `""` keeps it locked, which is a real
 * answer rather than a dismissal — the turn resumes locked.
 */
data class VaultUnlockPending(
    override val key: PendingInputKey,
    override val durableSessionId: String,
    override val runtimeSessionId: String,
    /** The manager's identifier, e.g. `1password`; [displayName] is what is shown. */
    val backend: String,
    val displayName: String,
) : PendingInputRequest

/** What the user decided for one pending request. */
sealed interface PendingInputAction {
    data class ClarifyAnswer(
        /** Null question id targets single-question mode; batch answers are keyed by qid. */
        val answers: Map<String, String>,
        /** True sends the batch-wide cancel (empty answer without a question id). */
        val cancelBatch: Boolean = false,
    ) : PendingInputAction

    data class ApprovalChoice(val choice: String) : PendingInputAction
    data class SudoPassword(val password: CharArray) : PendingInputAction
    data class SecretValue(val value: CharArray) : PendingInputAction

    /** An empty array is Desktop's "Skip" (`prompt-overlays.tsx:548`). */
    data class VaultCode(val code: CharArray) : PendingInputAction

    /**
     * An empty array is Desktop's "Keep locked" (`prompt-overlays.tsx:332`):
     * the manager stays locked and the turn continues without it.
     */
    data class VaultUnlockPassword(val password: CharArray) : PendingInputAction

    /**
     * Both halves of one saved login. [identifier] is not itself a secret —
     * Desktop renders it unmasked (`prompt-overlays.tsx:422-431`) — but it is
     * carried in the same shape as [password] because the two are zeroed
     * together and neither may outlive the one request they answer.
     *
     * Both empty is Desktop's "Don't save" (`prompt-overlays.tsx:446`).
     */
    data class VaultLogin(val identifier: CharArray, val password: CharArray) : PendingInputAction
}

/** Typed result of one response attempt; ambiguous transport errors keep the request pending. */
sealed interface PendingInputResponse {
    data object Resolved : PendingInputResponse
    /** The Gateway reported the request expired; safe to clear locally. */
    data object Expired : PendingInputResponse
    /** Transport failed/ambiguous; the request stays pending for an explicit retry. */
    data object Retryable : PendingInputResponse

    /**
     * One question of a batch clarify was locked and the backend still owes the
     * rest: the request is still parked, its card stays, and the person answers
     * the next question.
     *
     * Deliberately neither of its neighbours. Not [Resolved] — nothing was
     * finished, and a caller that withdrew the prompt's notification would be
     * telling someone their batch was answered while an agent still waits on
     * it. Not [Retryable] — nothing failed, and this same call did exactly what
     * it was asked. The distinction exists so those two callers cannot get it
     * wrong by default.
     */
    data object PartiallyAnswered : PendingInputResponse

    /**
     * This client cannot answer this request and never will: the connection
     * that parked it is gone, so nothing was sent.
     *
     * Deliberately not [Resolved]. Both mean "it is not in the pending map",
     * but they are opposite facts about the world. Resolved means the request
     * was retired on this connection — answered here, answered elsewhere,
     * expired, or died with its turn — and the user owes it nothing. This
     * means the request may still be parked on the Gateway with an agent
     * blocked behind it, and a caller that treats the two alike will tell
     * someone their approval went through when it did not.
     *
     * The case that forces the distinction is an OS notification outliving the
     * process that posted it: its action button arrives at a repository that
     * has never heard of the request.
     */
    data object Unanswerable : PendingInputResponse
}

/** Repository-owned registry of live pending requests, fenced per connection generation. */
interface PendingInputRegistry {
    val requests: StateFlow<Map<PendingInputKey, PendingInputRequest>>

    suspend fun respond(key: PendingInputKey, action: PendingInputAction): PendingInputResponse
}
