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

/** Installed global selection only. No inventory paths, raw JSON, or credentials cross this door. */
data class InstalledSkill(val name: String, val enabled: Boolean)

sealed interface SkillsResult<out T> {
    data class Success<T>(val value: T) : SkillsResult<T>
    data class Failure(val reason: SkillsFailure, val mutationMayHaveApplied: Boolean = false) : SkillsResult<Nothing>
}

enum class SkillsFailure {
    MissingCapability, InvalidScope, StaleScope, UnavailableOnGateway,
    Refused, Unreachable, InvalidResponse, Unconfirmed,
}

/** Core host capability, deliberately NOT an escape from PluginRest's namespace. */
interface PluginSkills {
    /** A new, irrevocably closable dialog authority; never reuse it on close/reopen or A→B→A. */
    fun openScope(expectedEndpoint: Long, profile: String): PluginSkillsScope
}

interface PluginSkillsScope : AutoCloseable {
    suspend fun listInstalled(): SkillsResult<List<InstalledSkill>>
    /** Autosaved individual delta; Success requires an exact receipt AND a fresh named readback. */
    suspend fun toggleInstalled(name: String, enabled: Boolean): SkillsResult<List<InstalledSkill>>
    override fun close()
}

internal object UnavailablePluginSkills : PluginSkills {
    override fun openScope(expectedEndpoint: Long, profile: String): PluginSkillsScope =
        FailedSkillsScope(SkillsFailure.MissingCapability)
}

private class FailedSkillsScope(private val reason: SkillsFailure) : PluginSkillsScope {
    override suspend fun listInstalled(): SkillsResult<List<InstalledSkill>> = SkillsResult.Failure(reason)
    override suspend fun toggleInstalled(name: String, enabled: Boolean): SkillsResult<List<InstalledSkill>> =
        SkillsResult.Failure(reason)
    override fun close() = Unit
}

internal class GatewayPluginSkills(
    private val owner: CoroutineScope,
    private val http: () -> GatewayHttp?,
    private val endpoint: StateFlow<Long>,
    private val fence: EndpointDispatchFence,
) : PluginSkills {
    override fun openScope(expectedEndpoint: Long, profile: String): PluginSkillsScope {
        if (profile == "current" || !Regex("[a-z0-9][a-z0-9_-]{0,63}").matches(profile))
            return FailedSkillsScope(SkillsFailure.InvalidScope)
        // Capture once, synchronously, before credentials/dispatchers can suspend.
        val transport = http() as? EndpointDispatchingGatewayHttp
            ?: return FailedSkillsScope(SkillsFailure.MissingCapability)
        val owns = { endpoint.value == expectedEndpoint && http() === transport }
        val lease = fence.leaseAt(expectedEndpoint, owns)
            ?: return FailedSkillsScope(SkillsFailure.StaleScope)
        return BoundScope(owner, transport, profile, fence, lease, owns)
    }
}

private class BoundScope(
    private val owner: CoroutineScope,
    private val transport: EndpointDispatchingGatewayHttp,
    private val profile: String,
    private val fence: EndpointDispatchFence,
    private val lease: Long,
    private val owns: () -> Boolean,
) : PluginSkillsScope {
    private val lock = Any()
    private var closed = false
    override fun close() { synchronized(lock) { closed = true } }
    private fun current(): Boolean = fence.leaseAt(lease, owns) != null &&
        synchronized(lock) { !closed && owns() }

    override suspend fun listInstalled(): SkillsResult<List<InstalledSkill>> = owned { readInstalled() }

    private suspend fun readInstalled(): SkillsResult<List<InstalledSkill>> =
        when (val reply = exchange(GatewayHttpRequest(
            path = "/api/skills", method = "GET", body = null, timeoutMillis = 20_000,
            query = mapOf("profile" to profile), maxResponseBytes = 2L * 1024 * 1024,
        ))) {
            is SkillsResult.Failure -> reply
            is SkillsResult.Success -> parseInstalled(reply.value)
                ?.let { SkillsResult.Success(it) } ?: SkillsResult.Failure(SkillsFailure.InvalidResponse)
        }

    override suspend fun toggleInstalled(name: String, enabled: Boolean): SkillsResult<List<InstalledSkill>> = owned { operation ->
        if (!validSkillName(name)) return@owned SkillsResult.Failure(SkillsFailure.InvalidScope)
        val before = readInstalled()
        if (before is SkillsResult.Failure) return@owned before
        if ((before as SkillsResult.Success).value.none { it.name == name })
            return@owned SkillsResult.Failure(SkillsFailure.InvalidScope)
        val receipt = exchange(GatewayHttpRequest(
            path = "/api/skills/toggle", method = "PUT", timeoutMillis = 20_000,
            query = mapOf("profile" to profile), maxResponseBytes = 16_384,
            body = buildJsonObject {
                put("name", name); put("enabled", enabled); put("profile", profile)
            }.toString().toRequestBody("application/json".toMediaType()),
        ), operation)
        if (receipt is SkillsResult.Failure) return@owned receipt
        val value = (receipt as SkillsResult.Success).value as? JsonObject
        if (value?.get("ok") != JsonPrimitive(true) || value["name"] != JsonPrimitive(name) ||
            value["enabled"] != JsonPrimitive(enabled))
            return@owned SkillsResult.Failure(SkillsFailure.Unconfirmed)
        val actual = readInstalled()
        if (actual !is SkillsResult.Success || actual.value.singleOrNull { it.name == name }?.enabled != enabled)
            SkillsResult.Failure(SkillsFailure.Unconfirmed)
        else actual
    }

    private class Operation(var mutationDispatched: Boolean = false)

    private suspend fun <T> owned(block: suspend (Operation) -> SkillsResult<T>): SkillsResult<T> {
        val task = owner.async {
            if (!current()) return@async SkillsResult.Failure(SkillsFailure.StaleScope)
            val operation = Operation()
            val result = try { block(operation) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { SkillsResult.Failure(SkillsFailure.Unreachable) }
            if (!current()) SkillsResult.Failure(SkillsFailure.StaleScope, operation.mutationDispatched)
            else if (result is SkillsResult.Failure) result.copy(mutationMayHaveApplied = operation.mutationDispatched)
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
    private suspend fun exchange(request: GatewayHttpRequest, mutation: Operation? = null): SkillsResult<JsonElement> {
        val result = dispatch(request, mutation)
        return when (result) {
            is GatewayHttpResult.Success -> result.consumeBody { bytes ->
                if (!current()) SkillsResult.Failure(SkillsFailure.StaleScope)
                else try { SkillsResult.Success(Json.parseToJsonElement(bytes.toString(Charsets.UTF_8))) }
                catch (_: Exception) { SkillsResult.Failure(SkillsFailure.InvalidResponse) }
            }
            is GatewayHttpResult.Rejected -> result.consumeEnvelope {
                SkillsResult.Failure(if (!current()) SkillsFailure.StaleScope else when (result.statusCode) {
                    0 -> SkillsFailure.Unreachable
                    404 -> if (request.method == "GET" && routeAbsent()) SkillsFailure.UnavailableOnGateway else SkillsFailure.Refused
                    else -> SkillsFailure.Refused
                })
            }
        }
    }
    /** A named profile can itself be missing. Never turn its 404 into a route claim. */
    private suspend fun routeAbsent(): Boolean = try {
        val probe = dispatch(GatewayHttpRequest(
            path = "/api/skills", method = "GET", body = null, timeoutMillis = 20_000,
            maxResponseBytes = 1, // never parse or publish unscoped inventory
        ))
        when (probe) {
            is GatewayHttpResult.Success -> probe.consumeBody { false }
            is GatewayHttpResult.Rejected -> probe.consumeEnvelope { probe.statusCode == 404 }
        }
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { false }
}

private fun parseInstalled(value: JsonElement): List<InstalledSkill>? {
    val rows = value as? JsonArray ?: return null
    val seen = mutableSetOf<String>()
    return rows.map { element ->
        val row = element as? JsonObject ?: return null
        val name = (row["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        if (!validSkillName(name) || !seen.add(name)) return null
        val enabled = (row["enabled"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull ?: return null
        InstalledSkill(name, enabled)
    }
}

private fun validSkillName(name: String): Boolean = name.isNotBlank() && name == name.trim() &&
    name.length <= 256 && name.none { it.isISOControl() } && redact(name) == name
