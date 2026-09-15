package com.hermesagent.mobile.plugins.bots

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The needs-attention badge's classification.
 *
 * Direct Kotlin port of Desktop's `BOT_ATTENTION_CLASSES` and
 * `attentionReasonFromError` (`apps/desktop/src/plugins/hermes-bots/data.ts:52-116`
 * @ `437116f9497c80d242ce034ff7f5d81dc277a337`). The four classes are the
 * *attention-worthy* failures; everything transient classifies to null and
 * therefore **never badges**. That asymmetry is the whole point of the rule —
 * a retryable rate limit, an overloaded backend or a timeout is not something
 * a person can act on, so badging it teaches people to ignore the badge.
 */

/** The four attention-worthy failure classes. */
enum class BotAttentionClass(val wireName: String) {
    AgentBlocked("agent_blocked"),
    ProviderAuthOrAccess("provider_auth_or_access"),
    ProviderQuotaLimit("provider_quota_limit"),
    MissingConfig("missing_config");

    companion object {
        private val byWireName: Map<String, BotAttentionClass> = entries.associateBy { it.wireName }

        /** The class a `#93091` reason code names, or null. */
        fun fromWireName(value: String): BotAttentionClass? = byWireName[value]
    }
}

/** One badge: what to say, when it was recorded, and the raw text behind it. */
data class BotAttention(
    val reason: BotAttentionClass,
    val at: Long,
    val message: String,
)

/**
 * One-line user hint per attention class — the roster badge's tooltip.
 * `data.ts:65-70` @ the pin, verbatim.
 */
val BOT_ATTENTION_HINTS: Map<BotAttentionClass, String> = mapOf(
    BotAttentionClass.ProviderAuthOrAccess to "Sign in again for this profile",
    BotAttentionClass.ProviderQuotaLimit to "Quota or balance exhausted",
    BotAttentionClass.MissingConfig to "Provider not configured — run hermes model",
    BotAttentionClass.AgentBlocked to "Bot is blocked — see its last message",
)

// ── classification ────────────────────────────────────────────────────────────
//
// Transient failures are tested FIRST, so a message that carries both a
// retryable signal and a quota keyword ("429 rate limit; quota check skipped")
// reads as transient rather than sticking a badge on it. The patterns are
// Desktop's, order included.

private val TRANSIENT_ERROR = Regex(
    "rate.?limit|too many requests|\\b429\\b|\\b5\\d\\d\\b|server error|overloaded|timed?.?out|timeout|temporar",
)
private val MISSING_CONFIG_ERROR = Regex(
    "no llm provider|no access token|not configured|no api key|missing api key",
)
private val AUTH_ERROR = Regex(
    "\\b401\\b|\\b403\\b|unauthorized|forbidden|authentication|invalid.?api.?key|credentials? (are )?(invalid|expired)",
)
private val QUOTA_ERROR = Regex(
    "quota|out of funds|insufficient (credits?|funds|balance)|payment required|\\b402\\b|billing",
)
private val BLOCKED_ERROR = Regex("\\bblocked\\b")

/**
 * Map an error — a `#93091` reason code or raw error text — to an attention
 * class, or null when the failure is transient.
 *
 * Pure, and the single answer to "should this badge?". A rate limit, a 5xx, a
 * timeout or any temporary failure answers null by construction, which is the
 * behaviour the roster's tests pin.
 */
fun attentionReasonFromError(errorTextOrReason: String?): BotAttentionClass? {
    val raw = errorTextOrReason.orEmpty().trim()
    if (raw.isEmpty()) {
        return null
    }

    // A payload that is already a reason code is taken at face value.
    BotAttentionClass.fromWireName(raw)?.let { return it }

    val text = raw.lowercase()

    // Transient failures first, so a retryable error never sticks a badge.
    if (TRANSIENT_ERROR.containsMatchIn(text)) {
        return null
    }
    if (MISSING_CONFIG_ERROR.containsMatchIn(text)) {
        return BotAttentionClass.MissingConfig
    }
    if (AUTH_ERROR.containsMatchIn(text)) {
        return BotAttentionClass.ProviderAuthOrAccess
    }
    if (QUOTA_ERROR.containsMatchIn(text)) {
        return BotAttentionClass.ProviderQuotaLimit
    }
    if (BLOCKED_ERROR.containsMatchIn(text)) {
        return BotAttentionClass.AgentBlocked
    }

    return null
}

/**
 * Per-bot attention, keyed by roster key.
 *
 * Display-only presentation state: never persisted, never alters delivery.
 * The latest failure wins, and the bot's next good turn clears it. A hidden
 * bot keeps its entry — hiding is a roster-display concern only.
 *
 * Port of `data.ts:122-157` @ the pin. Unlike Desktop this is an instance
 * rather than a module global, so a test (or a second connection later) can
 * hold its own.
 */
class BotAttentionStore(private val clock: () -> Long = System::currentTimeMillis) {
    private val _entries = MutableStateFlow<Map<String, BotAttention>>(emptyMap())
    val entries: StateFlow<Map<String, BotAttention>> = _entries.asStateFlow()

    /**
     * Record attention after a failed turn or delivery. A transient error
     * classifies to null and writes nothing, so a retry in flight cannot
     * resurrect a cleared badge.
     */
    fun note(key: String, errorTextOrReason: String?) {
        val reason = attentionReasonFromError(errorTextOrReason) ?: return
        if (key.isEmpty()) {
            return
        }
        val message = errorTextOrReason.orEmpty().trim().take(BotsRosterLimits.ATTENTION_MESSAGE_LIMIT)
        _entries.update { current ->
            current + (key to BotAttention(reason = reason, at = clock(), message = message))
        }
    }

    /** A good turn clears the badge. A bot with no entry is left untouched. */
    fun clear(key: String) {
        if (key.isEmpty() || _entries.value[key] == null) {
            return
        }
        _entries.update { current -> current - key }
    }

    /**
     * Forget every badge, because this device has changed endpoint.
     *
     * The store is an endpoint-scoped copy of backend truth for the same reason
     * the roster is: it is keyed by roster key alone, and the next Gateway is a
     * different machine that recycles the same ids. Badges recorded against the
     * machine this device just left would otherwise paint on the new one's
     * same-named bot — the roster's drop calls this beside its own clear, and
     * that drop is the only caller.
     *
     * A whole-map clear rather than a per-key [clear], because the keys to
     * forget are the ones the roster just dropped and it no longer holds them.
     */
    fun clearAll() {
        if (_entries.value.isEmpty()) {
            return
        }
        _entries.update { emptyMap() }
    }

    fun forKey(key: String): BotAttention? = _entries.value[key]
}
