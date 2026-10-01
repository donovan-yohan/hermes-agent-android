package com.hermesagent.mobile

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hermesagent.mobile.data.gateway.*
import com.hermesagent.mobile.data.profiles.AndroidAvatarImporter
import com.hermesagent.mobile.plugins.GatewayPluginHost
import com.hermesagent.mobile.plugins.bots.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64

internal val BOT_AVATAR_CAPTURE_STATES = setOf("bot-avatar-loaded", "bot-avatar-loading", "bot-avatar-picking",
    "bot-avatar-cancel", "bot-avatar-error", "bot-avatar-changed", "bot-avatar-saved", "bot-avatar-cleared")

/** Synthetic-only dispatches the real profiles asset methods through GatewayPluginHost. */
internal class BotAvatarFixtureRpc(private val state: String) : EndpointDispatchingGatewayRpcClient {
    init { require(state in BOT_AVATAR_CAPTURE_STATES) }
    override val events: Flow<GatewayEvent> = emptyFlow()
    val calls = mutableListOf<Pair<String, JsonObject>>()
    var bytes: ByteArray? = fixtureAvatarPng()
    override suspend fun request(method: String, params: JsonObject): JsonElement = error("unfenced fixture")
    override suspend fun requestAtEndpointDispatch(method: String, params: JsonObject, dispatch: (() -> Boolean) -> Boolean): JsonElement {
        check(params["name"] == JsonPrimitive("synthetic-avatar") && params["asset"] == JsonPrimitive("avatar"))
        if (!dispatch { calls += method to params; true }) throw GatewayRpcException("synthetic endpoint changed")
        return when (method) {
            "profiles.get_asset" -> {
                if (state == "bot-avatar-loading") awaitCancellation()
                if (state == "bot-avatar-error") throw GatewayRpcException("synthetic refusal")
                buildJsonObject {
                    put("found", bytes != null)
                    bytes?.let { put("mime", "image/png"); put("size", it.size)
                        put("data", "data:image/png;base64," + Base64.getEncoder().encodeToString(it)) }
                }
            }
            "profiles.set_asset" -> {
                bytes = if (params["clear"] == JsonPrimitive(true)) null else
                    Base64.getDecoder().decode(params.getValue("data").jsonPrimitive.content.removePrefix("data:image/png;base64,"))
                buildJsonObject { put("ok", true); put("asset", "avatar"); put("size", bytes?.size ?: 0) }
            }
            else -> error("unsupported fixture method")
        }
    }
    override fun close() = Unit
}
internal fun fixtureAvatarPng(): ByteArray {
    val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
    bitmap.eraseColor(Color.rgb(60, 140, 220))
    return ByteArrayOutputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); bitmap.recycle(); out.toByteArray() }
}
internal suspend fun stageBotAvatar(state: String, vm: BotsAvatarViewModel) {
    if (state == "bot-avatar-loading" || state == "bot-avatar-error" || state == "bot-avatar-loaded") return
    vm.state.first { !it.loading }
    if (state == "bot-avatar-cleared") { vm.clear(vm.state.value); vm.save(vm.state.value); return }
    val pick = vm.beginPick(vm.state.value) ?: error("fixture picker admission failed")
    if (state == "bot-avatar-picking") return
    if (state == "bot-avatar-cancel") { vm.finishPick(pick, null); return }
    // Actual normalization; neither encoded fixture bytes nor source flags bypass the importer.
    val source = Bitmap.createBitmap(512, 256, Bitmap.Config.ARGB_8888)
    source.eraseColor(Color.rgb(220, 140, 60))
    val bytes = ByteArrayOutputStream().use { out -> source.compress(Bitmap.CompressFormat.PNG, 100, out); source.recycle(); out.toByteArray() }
    val normalized = AndroidAvatarImporter().normalize { ByteArrayInputStream(bytes) } ?: error("fixture decode failed")
    vm.finishPick(pick, normalized)
    if (state == "bot-avatar-saved") vm.save(vm.state.value)
}
@Composable
internal fun BotAvatarParityContent(state: String) {
    val scope = rememberCoroutineScope()
    val rpc = remember(state) { BotAvatarFixtureRpc(state) }
    val host = remember(rpc) { GatewayPluginHost(scope, MutableStateFlow<GatewayRpcClient?>(rpc), MutableStateFlow(0L), EndpointDispatchFence()) }
    val vm = remember(host) { BotsAvatarViewModel(host, scope, {}) }
    LaunchedEffect(vm) {
        host.connected.first { it }
        vm.open(BotManagementTarget("synthetic-avatar", 0L))
        stageBotAvatar(state, vm)
    }
    DisposableEffect(vm) { onDispose { vm.close() } }
    val value by vm.state.collectAsState()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)) {
        BotAvatarEditor(value, botAvatarActions(vm, value))
    }
}
