package com.hermesagent.mobile.plugins.bots

import com.hermesagent.mobile.plugins.PluginHost
import com.hermesagent.mobile.data.ssh.redact
import kotlinx.serialization.json.*

data class BotModelSelection(val provider: String = "", val model: String = "") {
    val valid: Boolean get() = provider.isNotBlank() && model.isNotBlank() &&
        provider == provider.trim() && model == model.trim()
}
data class BotModelProvider(val slug: String, val name: String, val aliases: List<String>, val models: List<String>) {
    fun matches(provider: String): Boolean = provider == slug || provider in aliases
}
sealed interface BotModelSave {
    data object Saved : BotModelSave
    data class Confirmation(val message: String) : BotModelSave
    data object Rejected : BotModelSave
    data object Unconfirmed : BotModelSave
}

/** 587e673e: methods_profiles.py:345-405,637-655. No CLI reset, credentials or session writes. */
class BotsModelRepository(private val host: PluginHost, private val timeoutMillis: Long = 20_000L) {
    private suspend fun call(target: BotManagementTarget, method: String, params: JsonObject): JsonObject? =
        host.requestBotObject(target, method, params, timeoutMillis)

    suspend fun describe(target: BotManagementTarget): BotModelSelection? {
        val value = call(target, "profiles.describe", buildJsonObject { put("name", target.name) }) ?: return null
        if (value.text("name") != target.name) return null
        val model = value["model"] as? JsonObject ?: return null
        return BotModelSelection(model.text("provider") ?: return null, model.text("default") ?: return null)
    }

    suspend fun options(target: BotManagementTarget): List<BotModelProvider>? {
        val value = call(target, "model.options", buildJsonObject {
            put("profile", target.name); put("include_unconfigured", true); put("explicit_only", false)
        }) ?: return null
        val rows = value["providers"] as? JsonArray ?: return null
        return rows.mapNotNull { item ->
            val row = item as? JsonObject ?: return@mapNotNull null
            val slug = row.text("slug")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            BotModelProvider(slug, row.text("name") ?: slug,
                (row["aliases"] as? JsonArray)?.mapNotNull { it.stringValue() }.orEmpty(),
                (row["models"] as? JsonArray)?.mapNotNull {
                    it.stringValue() ?: (it as? JsonObject)?.let { model -> model.text("id") ?: model.text("name") }
                }?.filter { it.isNotBlank() }?.distinct().orEmpty())
        }
    }

    suspend fun save(target: BotManagementTarget, selection: BotModelSelection, confirmed: Boolean = false): BotModelSave {
        if (!validBotId(target.name) || !selection.valid) return BotModelSave.Rejected
        val value = call(target, "profiles.configure", buildJsonObject {
            put("name", target.name); put("provider", selection.provider); put("model", selection.model)
            if (confirmed) put("confirm_expensive_model", true)
        }) ?: return BotModelSave.Unconfirmed
        // A warning is pending consent even when ok/applied claim success.
        if (value.flag("confirm_required") == true) return BotModelSave.Confirmation(
            redact(value.text("confirm_message")?.takeIf { it.isNotBlank() }
                ?: "This model needs confirmation. Review its cost and data policy before saving."))
        if (value.flag("ok") != true || (value["applied"] as? JsonObject)?.flag("model") != true)
            return BotModelSave.Unconfirmed
        return if (describe(target) == selection) BotModelSave.Saved else BotModelSave.Unconfirmed
    }
}
private fun JsonElement.stringValue(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
private fun JsonObject.text(key: String): String? = this[key]?.stringValue()
private fun JsonObject.flag(key: String): Boolean? = (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
