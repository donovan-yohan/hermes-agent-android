package com.hermesagent.mobile.plugins

import com.hermesagent.mobile.data.gateway.EndpointDispatchFence
import com.hermesagent.mobile.data.gateway.EndpointDispatchingGatewayHttp
import com.hermesagent.mobile.data.gateway.GatewayHttp
import com.hermesagent.mobile.data.gateway.GatewayHttpRequest
import com.hermesagent.mobile.data.gateway.GatewayHttpResult
import com.hermesagent.mobile.data.gateway.consumeBody
import com.hermesagent.mobile.data.gateway.consumeEnvelope
import com.hermesagent.mobile.data.ssh.redact
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/** MCP selection only. No inventory paths, raw JSON, or credentials cross this door. */
data class McpServer(val name: String, val enabled: Boolean, val source: McpSource)

/** Unknown provenance is read-only and never retained as backend-authored text. */
enum class McpSource { Config, Plugin, Other }

sealed interface McpResult<out T> {
    data class Success<T>(val value: T) : McpResult<T>
    data class Failure(val reason: McpFailure, val mutationMayHaveApplied: Boolean = false) : McpResult<Nothing>
}

enum class McpFailure {
    MissingCapability, InvalidScope, StaleScope, UnavailableOnGateway,
    Refused, Unreachable, InvalidResponse, Unconfirmed,
}

/** Core host capability, deliberately NOT an escape from PluginRest's namespace. */
interface PluginMcp {
    /** A new, irrevocably closable dialog authority; never reuse it on close/reopen or A→B→A. */
    fun openScope(expectedEndpoint: Long, profile: String): PluginMcpScope
}

interface PluginMcpScope : AutoCloseable {
    suspend fun listServers(): McpResult<List<McpServer>>
    /** Autosaved individual delta; Success requires an exact receipt AND a fresh named readback. */
    suspend fun setEnabled(name: String, enabled: Boolean): McpResult<List<McpServer>>
    override fun close()
}

internal object UnavailablePluginMcp : PluginMcp {
    override fun openScope(expectedEndpoint: Long, profile: String): PluginMcpScope =
        FailedMcpScope(McpFailure.MissingCapability)
}

private class FailedMcpScope(private val reason: McpFailure) : PluginMcpScope {
    override suspend fun listServers(): McpResult<List<McpServer>> = McpResult.Failure(reason)
    override suspend fun setEnabled(name: String, enabled: Boolean): McpResult<List<McpServer>> =
        McpResult.Failure(reason)
    override fun close() = Unit
}

internal class GatewayPluginMcp(
    private val owner: CoroutineScope,
    private val http: () -> GatewayHttp?,
    private val endpoint: StateFlow<Long>,
    private val fence: EndpointDispatchFence,
) : PluginMcp {
    override fun openScope(expectedEndpoint: Long, profile: String): PluginMcpScope {
        if (profile == "current" || !Regex("[a-z0-9][a-z0-9_-]{0,63}").matches(profile))
            return FailedMcpScope(McpFailure.InvalidScope)
        // Capture once, synchronously, before credentials/dispatchers can suspend.
        val transport = http() as? EndpointDispatchingGatewayHttp
            ?: return FailedMcpScope(McpFailure.MissingCapability)
        val owns = { endpoint.value == expectedEndpoint && http() === transport }
        val lease = fence.leaseAt(expectedEndpoint, owns)
            ?: return FailedMcpScope(McpFailure.StaleScope)
        return BoundMcpScope(owner, transport, profile, fence, lease, owns)
    }
}

private class BoundMcpScope(
    private val owner: CoroutineScope,
    private val transport: EndpointDispatchingGatewayHttp,
    private val profile: String,
    private val fence: EndpointDispatchFence,
    private val lease: Long,
    private val owns: () -> Boolean,
) : PluginMcpScope {
    private val lock = Any()
    private var closed = false
    override fun close() { synchronized(lock) { closed = true } }
    private fun current(): Boolean = fence.leaseAt(lease, owns) != null &&
        synchronized(lock) { !closed && owns() }

    override suspend fun listServers(): McpResult<List<McpServer>> = owned { readServers() }

    private suspend fun readServers(): McpResult<List<McpServer>> =
        when (val reply = exchange(GatewayHttpRequest(
            path = "/api/mcp/servers", method = "GET", body = null, timeoutMillis = 20_000,
            query = mapOf("profile" to profile), maxResponseBytes = 2L * 1024 * 1024,
        ))) {
            is McpResult.Failure -> reply
            is McpResult.Success -> parseServers(reply.value)
                ?.let { McpResult.Success(it) } ?: McpResult.Failure(McpFailure.InvalidResponse)
        }

    override suspend fun setEnabled(name: String, enabled: Boolean): McpResult<List<McpServer>> = owned { operation ->
        if (!validMcpName(name)) return@owned McpResult.Failure(McpFailure.InvalidScope)
        val before = readServers()
        if (before is McpResult.Failure) return@owned before
        val target = (before as McpResult.Success).value.singleOrNull { it.name == name }
            ?: return@owned McpResult.Failure(McpFailure.InvalidScope)
        if (target.source != McpSource.Config) return@owned McpResult.Failure(McpFailure.Refused)
        val receipt = exchange(GatewayHttpRequest(
            path = "/api/mcp/servers/${encodedMcpName(name)}/enabled", method = "PUT", timeoutMillis = 20_000,
            query = mapOf("profile" to profile), maxResponseBytes = 16_384,
            body = buildJsonObject {
                put("enabled", enabled); put("profile", profile)
            }.toString().toRequestBody("application/json".toMediaType()),
        ), operation)
        if (receipt is McpResult.Failure) return@owned receipt
        val value = (receipt as McpResult.Success).value as? JsonObject
        if (value?.get("ok") != JsonPrimitive(true) || value["name"] != JsonPrimitive(name) ||
            value["enabled"] != JsonPrimitive(enabled))
            return@owned McpResult.Failure(McpFailure.Unconfirmed)
        val actual = readServers()
        if (actual !is McpResult.Success ||
            actual.value.singleOrNull { it.name == name } != McpServer(name, enabled, McpSource.Config))
            McpResult.Failure(McpFailure.Unconfirmed)
        else actual
    }

    private class Operation(var mutationDispatched: Boolean = false)

    private suspend fun <T> owned(block: suspend (Operation) -> McpResult<T>): McpResult<T> {
        val task = owner.async {
            if (!current()) return@async McpResult.Failure(McpFailure.StaleScope)
            val operation = Operation()
            val result = try { block(operation) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { McpResult.Failure(McpFailure.Unreachable) }
            if (!current()) McpResult.Failure(McpFailure.StaleScope, operation.mutationDispatched)
            else if (result is McpResult.Failure) result.copy(mutationMayHaveApplied = operation.mutationDispatched)
            else result
        }
        return try { task.await() } finally { task.cancel() }
    }

    private suspend fun dispatch(request: GatewayHttpRequest, mutation: Operation? = null): GatewayHttpResult =
        transport.executeAtDispatch(request) { send ->
            fence.dispatchIfCurrent(lease, owns) {
                // Close and enqueue share this monitor. A predicate alone is NOT an atomic dialog fence.
                synchronized(lock) {
                    if (closed) false else send().also { admitted ->
                        if (admitted) mutation?.mutationDispatched = true
                    }
                }
            }
        }
    private suspend fun exchange(request: GatewayHttpRequest, mutation: Operation? = null): McpResult<JsonElement> {
        val result = dispatch(request, mutation)
        return when (result) {
            is GatewayHttpResult.Success -> result.consumeBody { bytes ->
                if (!current()) McpResult.Failure(McpFailure.StaleScope)
                else try { McpResult.Success(Json.parseToJsonElement(bytes.toString(Charsets.UTF_8))) }
                catch (_: Exception) { McpResult.Failure(McpFailure.InvalidResponse) }
            }
            is GatewayHttpResult.Rejected -> result.consumeEnvelope {
                McpResult.Failure(if (!current()) McpFailure.StaleScope else when (result.statusCode) {
                    0 -> McpFailure.Unreachable
                    404 -> if (request.method == "GET" && routeAbsent()) McpFailure.UnavailableOnGateway else McpFailure.Refused
                    else -> McpFailure.Refused
                })
            }
        }
    }
    /** A named profile can itself be missing. Never turn its 404 into a route claim. */
    private suspend fun routeAbsent(): Boolean = try {
        val probe = dispatch(GatewayHttpRequest(
            path = "/api/mcp/servers", method = "GET", body = null, timeoutMillis = 20_000,
            maxResponseBytes = 1, // never parse or publish unscoped inventory
        ))
        when (probe) {
            is GatewayHttpResult.Success -> probe.consumeBody { false }
            is GatewayHttpResult.Rejected -> probe.consumeEnvelope { probe.statusCode == 404 }
        }
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { false }
}

private fun parseServers(value: JsonElement): List<McpServer>? {
    val rows = (value as? JsonObject)?.get("servers") as? JsonArray ?: return null
    val seen = mutableSetOf<String>()
    return rows.map { element ->
        val row = element as? JsonObject ?: return null
        val name = (row["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        if (!validMcpName(name) || !seen.add(name)) return null
        val enabled = (row["enabled"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull ?: return null
        val source = when ((row["source"] as? JsonPrimitive)?.takeIf { it.isString }?.content) {
            "config" -> McpSource.Config
            "plugin" -> McpSource.Plugin
            else -> McpSource.Other
        }
        McpServer(name, enabled, source)
    }
}

private fun validMcpName(name: String): Boolean = name.isNotBlank() && name == name.trim() &&
    name.length <= 256 && name != "." && name != ".." &&
    name.none { it.isISOControl() || it == '/' || it == '\\' } && redact(name) == name

private fun encodedMcpName(name: String): String = okhttp3.HttpUrl.Builder()
    .scheme("https").host("unused.invalid").addPathSegment(name).build().encodedPath.drop(1)
