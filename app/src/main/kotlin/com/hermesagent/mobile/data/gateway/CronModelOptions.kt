package com.hermesagent.mobile.data.gateway

import com.hermesagent.mobile.data.composer.ModelCatalog
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Read the editor owner's configured models without borrowing the active conversation. */
internal class GatewayCronModelOptions(
    private val clients: StateFlow<GatewayRpcClient?>,
    private val endpoint: StateFlow<Long>,
    private val fence: EndpointDispatchFence,
) {
    suspend fun load(profile: String): ModelCatalog {
        if (!Regex("[a-z0-9][a-z0-9_-]{0,63}").matches(profile) || profile in setOf("all", "current")) {
            throw GatewayRpcException("Choose a profile before loading its models.")
        }
        val generation = endpoint.value
        val client = clients.value as? EndpointDispatchingGatewayRpcClient
            ?: throw GatewayRpcException("Connect to load models.")
        val owns = { endpoint.value == generation && clients.value === client }
        val lease = fence.leaseAt(generation, owns)
            ?: throw GatewayRpcException("The connection changed. Load models again.")
        val reply = client.requestAtEndpointDispatch("model.options", buildJsonObject {
            put("profile", profile)
            put("explicit_only", true)
        }) { send -> fence.dispatchIfCurrent(lease, owns, send) }
        if (!owns()) throw GatewayRpcException("The connection changed. Load models again.")
        val root = reply as? JsonObject ?: throw GatewayRpcException("Models could not be loaded.")
        val providers = root["providers"] as? JsonArray ?: throw GatewayRpcException("Models could not be loaded.")
        val selectable = JsonArray(providers.filter { value ->
            val provider = value as? JsonObject ?: return@filter false
            provider["authenticated"] != JsonPrimitive(false)
        })
        return parseModelCatalog(JsonObject(root + ("providers" to selectable)))
    }
}
