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

enum class PendingInputKind { Clarify, Approval, Sudo, Secret }

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
}

/** Typed result of one response attempt; ambiguous transport errors keep the request pending. */
sealed interface PendingInputResponse {
    data object Resolved : PendingInputResponse
    /** The Gateway reported the request expired; safe to clear locally. */
    data object Expired : PendingInputResponse
    /** Transport failed/ambiguous; the request stays pending for an explicit retry. */
    data object Retryable : PendingInputResponse

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
