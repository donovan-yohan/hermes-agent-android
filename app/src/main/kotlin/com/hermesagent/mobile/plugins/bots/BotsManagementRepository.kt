package com.hermesagent.mobile.plugins.bots

import com.hermesagent.mobile.plugins.PluginHost
import com.hermesagent.mobile.plugins.PluginHostResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*

/** All operations carry the endpoint admitted by the UI, never the endpoint at submit time. */
data class BotManagementTarget(val name: String, val endpoint: Long)
data class BotIdentityDraft(
    val name: String = "",
    val title: String = "",
    val description: String = "",
    val soul: String = "",
)
enum class BotManagementResult {
    Confirmed, Rejected, Unsupported, Unconfirmed;
    fun sentence(): String = when (this) {
        Confirmed -> "Saved."
        Rejected -> "The change was not saved. Check the details and refresh before trying again."
        Unsupported -> "This Gateway cannot safely save this change. Update Hermes and reconnect."
        Unconfirmed -> "The change could not be confirmed. Refresh before trying again."
    }
}

internal fun validBotId(name: String): Boolean = Regex("[a-z0-9][a-z0-9_-]{0,63}").matches(name)

/** Bot editor RPC policy only; receipts and readback remain repository-owned. */
internal suspend fun PluginHost.requestBotObject(
    target: BotManagementTarget,
    method: String,
    params: JsonObject,
    timeoutMillis: Long,
    dispatchAllowed: (() -> Boolean)? = null,
): JsonObject? {
    if (!validBotId(target.name)) return null
    return try {
        withTimeoutOrNull(timeoutMillis) {
            val result = if (dispatchAllowed == null) requestAtEndpoint(target.endpoint, method, params)
                else requestAtEndpointGuarded(target.endpoint, method, params, dispatchAllowed)
            (result as? PluginHostResult.Success)?.result as? JsonObject
        }
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { null }
}

internal fun nextBotCopyName(name: String, taken: Set<String>): String? = (2..99)
    .map { suffix -> val tail = "-$suffix"; name.take(64 - tail.length) + tail }
    .firstOrNull { it !in taken }

/**
 * Existing Gateway contracts only, at e27448b231498e79ade668d68c0b6c6206951206:
 * methods_profiles.py:302-405 (create/describe/configure), :591-634 (namespace CAS),
 * methods_tools.py:540-557 (argv-only CLI). No credentials or CLI output are retained.
 * Unlike Desktop's best-effort local fallback, this writer requires a server receipt
 * and readback; lack of CAS must never clobber another client's entire namespace.
 */
class BotsManagementRepository(private val host: PluginHost) {
    private suspend fun call(target: BotManagementTarget, method: String, body: JsonObject): PluginHostResult =
        host.requestAtEndpoint(target.endpoint, method, body)

    private suspend fun roster(target: BotManagementTarget): JsonArray? {
        val response = call(target, "profiles.list", buildJsonObject { put("include_sessions", true) })
        val rows = ((response as? PluginHostResult.Success)?.result as? JsonObject)?.get("profiles") as? JsonArray
            ?: return null
        // A malformed row cannot prove absence for a destructive operation.
        return rows.takeIf { list -> list.all { it is JsonObject && it.string("name") != null } }
    }

    private suspend fun row(target: BotManagementTarget): JsonObject? = roster(target)
        ?.filterIsInstance<JsonObject>()?.singleOrNull { it.string("name") == target.name }

    suspend fun describe(target: BotManagementTarget): BotIdentityDraft? {
        if (!validBotId(target.name)) return null
        val reply = call(target, "profiles.describe", buildJsonObject { put("name", target.name) })
        val value = (reply as? PluginHostResult.Success)?.result as? JsonObject ?: return null
        if (value.string("name") != target.name) return null
        val meta = row(target)?.meta() ?: return null
        return BotIdentityDraft(target.name, meta.string("title").orEmpty(),
            value.string("description") ?: return null, value.string("soul") ?: return null)
    }

    suspend fun patchMeta(target: BotManagementTarget, patch: JsonObject): BotManagementResult {
        if (!validBotId(target.name)) return BotManagementResult.Rejected
        val before = row(target) ?: return BotManagementResult.Unconfirmed
        val revisions = before["ui_meta_revisions"] as? JsonObject ?: return BotManagementResult.Unsupported
        val rawRevision = revisions["hermes-bots"]
        val revision = if (rawRevision == null) 0L else
            (rawRevision as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull?.takeIf { it >= 0 }
                ?: return BotManagementResult.Unconfirmed
        val next = JsonObject(before.meta() + patch)
        val response = call(target, "profiles.configure", buildJsonObject {
            put("name", target.name)
            put("ui_meta", buildJsonObject { put("hermes-bots", next) })
            put("ui_meta_expected_revisions", buildJsonObject { put("hermes-bots", revision) })
        })
        val receipt = receipt(response, setOf("ui_meta"))
        if (receipt != BotManagementResult.Confirmed) return receipt
        val after = row(target)?.meta() ?: return BotManagementResult.Unconfirmed
        return if (patch.all { (key, value) -> after[key] == value }) BotManagementResult.Confirmed
        else BotManagementResult.Unconfirmed
    }

    suspend fun saveIdentity(target: BotManagementTarget, draft: BotIdentityDraft): BotManagementResult {
        if (target.name != draft.name || !validBotId(target.name)) return BotManagementResult.Rejected
        val response = call(target, "profiles.configure", buildJsonObject {
            put("name", target.name); put("description", draft.description); put("soul", draft.soul)
        })
        val result = receipt(response, setOf("description", "soul"))
        if (result != BotManagementResult.Confirmed) return result
        val metaResult = patchMeta(target, buildJsonObject { put("title", draft.title) })
        if (metaResult != BotManagementResult.Confirmed) return BotManagementResult.Unconfirmed
        val after = describe(target) ?: return BotManagementResult.Unconfirmed
        return if (after == draft.copy(description = draft.description.trim())) BotManagementResult.Confirmed
        else BotManagementResult.Unconfirmed
    }

    suspend fun create(
        endpoint: Long,
        draft: BotIdentityDraft,
        cloneFrom: String? = null,
        createdAtMillis: Long,
    ): BotManagementResult {
        if (!validBotId(draft.name) || draft.name == "default" ||
            cloneFrom != null && !validBotId(cloneFrom)) return BotManagementResult.Rejected
        val target = BotManagementTarget(draft.name, endpoint)
        val response = call(target, "profiles.create", buildJsonObject {
            put("name", draft.name); put("description", draft.description)
            if (cloneFrom != null) put("clone_from", cloneFrom)
            if (cloneFrom == null) put("soul", draft.soul)
            // Keep provider credentials on the host. Never copy messaging tokens or history.
            put("mirror_credentials", true); put("share_auth", false)
            put("clone_all", false); put("clone_channels", false); put("no_alias", true)
        })
        if (response === PluginHostResult.UnavailableOnGateway) return BotManagementResult.Unsupported
        val created = (response as? PluginHostResult.Success)?.result as? JsonObject
            ?: return BotManagementResult.Unconfirmed
        if (created.bool("ok") != true || created.string("name") != draft.name) return BotManagementResult.Unconfirmed
        // Creation can partially succeed. Never automatically recreate on any failure below.
        if (cloneFrom == null && draft.soul.isNotBlank() && created.bool("soul_written") != true)
            return BotManagementResult.Unconfirmed
        val meta = if (cloneFrom != null) {
            val source = row(BotManagementTarget(cloneFrom, endpoint)) ?: return BotManagementResult.Unconfirmed
            // Copy appearance only, not roster placement, chat ids, or source age.
            source.meta().filterKeys { it in setOf("color", "shape", "initials", "emoji") }
        } else emptyMap()
        val patch = JsonObject(meta + buildJsonObject {
            put("title", draft.title); put("created", createdAtMillis)
        })
        val result = patchMeta(target, patch)
        if (result != BotManagementResult.Confirmed) return BotManagementResult.Unconfirmed
        val after = describe(target) ?: return BotManagementResult.Unconfirmed
        return if (after.title == draft.title && after.description == draft.description.trim() &&
            (cloneFrom != null || draft.soul.isBlank() || after.soul == draft.soul)) BotManagementResult.Confirmed
        else BotManagementResult.Unconfirmed
    }

    suspend fun delete(target: BotManagementTarget): BotManagementResult {
        if (!validBotId(target.name) || target.name == "default") return BotManagementResult.Rejected
        // CLI argv is supported, but cannot retire the host's pooled backend handles.
        // PluginHost has no lifecycle-aware deleteProfile door. Never fall back to
        // a subprocess that can delete the source profile beneath its live pool.
        return BotManagementResult.Unsupported
    }

    private fun receipt(response: PluginHostResult, fields: Set<String>): BotManagementResult {
        if (response === PluginHostResult.UnavailableOnGateway) return BotManagementResult.Unsupported
        val value = (response as? PluginHostResult.Success)?.result as? JsonObject ?: return BotManagementResult.Unconfirmed
        val applied = value["applied"] as? JsonObject ?: return BotManagementResult.Unconfirmed
        return if (value.bool("ok") == true && fields.all { applied.bool(it) == true }) BotManagementResult.Confirmed
        else BotManagementResult.Unconfirmed
    }
}

internal fun JsonObject.meta(): JsonObject =
    ((this["ui_meta"] as? JsonObject)?.get("hermes-bots") as? JsonObject) ?: JsonObject(emptyMap())
private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull && it.isString }?.content
private fun JsonObject.bool(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
