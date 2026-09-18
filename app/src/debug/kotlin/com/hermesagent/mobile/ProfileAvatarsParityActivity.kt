package com.hermesagent.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hermesagent.mobile.data.gateway.EndpointDispatchFence
import com.hermesagent.mobile.data.gateway.EndpointDispatchingGatewayRpcClient
import com.hermesagent.mobile.data.gateway.GatewayEvent
import com.hermesagent.mobile.data.gateway.GatewayRpcClient
import com.hermesagent.mobile.data.gateway.GatewayRpcException
import com.hermesagent.mobile.data.profiles.AvatarRosterCoordinator
import com.hermesagent.mobile.data.profiles.GatewayProfileRepository
import com.hermesagent.mobile.data.profiles.ProfileAvatarRepository
import com.hermesagent.mobile.plugins.GatewayPluginHost
import com.hermesagent.mobile.plugins.PluginHostResult
import com.hermesagent.mobile.plugins.bots.BotRosterRow
import com.hermesagent.mobile.plugins.bots.BotSectionBlock
import com.hermesagent.mobile.plugins.bots.BotsActions
import com.hermesagent.mobile.plugins.bots.BotsRosterPhase
import com.hermesagent.mobile.plugins.bots.BotsRosterScreen
import com.hermesagent.mobile.plugins.bots.BotsRosterUiState
import com.hermesagent.mobile.plugins.bots.BotsViewModel
import com.hermesagent.mobile.plugins.bots.BotsPluginRepository
import com.hermesagent.mobile.ui.common.LocalProfileAvatarRoster
import com.hermesagent.mobile.ui.common.ProfileGlyph
import com.hermesagent.mobile.ui.theme.AppearanceSelection
import com.hermesagent.mobile.ui.theme.HermesTheme
import com.hermesagent.mobile.ui.theme.HermesThemeMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Base64

/** Debug-only production-component fixture for read-only avatar parity capture. */
class ProfileAvatarsParityActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val state = ProfileAvatarsFixtureState.parse(intent.getStringExtra(EXTRA_STATE))
        val mode = if (intent.getStringExtra(EXTRA_THEME) == "light") HermesThemeMode.Light else HermesThemeMode.Dark
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        lifecycle.addObserver(object : androidx.lifecycle.DefaultLifecycleObserver {
            override fun onDestroy(owner: androidx.lifecycle.LifecycleOwner) { scope.cancel() }
        })
        val rpc = AvatarFixtureRpc(state)
        val clients = MutableStateFlow<GatewayRpcClient?>(rpc)
        val endpoint = MutableStateFlow(0L)
        val host = GatewayPluginHost(scope, clients, endpoint, EndpointDispatchFence())
        val coordinator = AvatarRosterCoordinator(host, ProfileAvatarRepository(scope))
        val core = coordinator.open(AvatarRosterCoordinator.Kind.Core)
        val bots = coordinator.open(AvatarRosterCoordinator.Kind.Bots, host)
        val profiles = GatewayProfileRepository({ error("fixture uses captured host") }, avatarProducer = core)
        val botsRepository = BotsPluginRepository(host, bots)
        val botsStateHolder = BotsFixtureStateHolder(scope, botsRepository)
        setContent {
            val profileState by profiles.roster.collectAsState()
            val botsState by botsStateHolder.state.collectAsState()
            LaunchedEffect(state) {
                profiles.refreshProfiles()
                botsRepository.loadRoster()
            }
            HermesTheme(AppearanceSelection("mono", mode)) {
                CompositionLocalProvider(LocalProfileAvatarRoster provides coordinator) {
                    when (state) {
                        ProfileAvatarsFixtureState.ProfileReady,
                        ProfileAvatarsFixtureState.ProfileFallback,
                        ProfileAvatarsFixtureState.ProfileLoading,
                        ProfileAvatarsFixtureState.ProfileUnavailable -> {
                            val profile = profileState.profiles.firstOrNull()
                            if (profile != null) {
                                Row(
                                    Modifier.fillMaxSize().padding(32.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                                ) {
                                    ProfileGlyph(profile = profile, size = 96.dp, contentDescription = "Synthetic avatar")
                                }
                            }
                        }
                        ProfileAvatarsFixtureState.BotsReady,
                        ProfileAvatarsFixtureState.BotsFallback ->
                            BotsRosterScreen(
                                state = botsState,
                                onBack = {},
                                actions = BotsActions(),
                                modifier = Modifier.fillMaxSize(),
                            )
                    }
                }
            }
        }
    }

    companion object {
        const val EXTRA_STATE = "visual_parity_state"
        const val EXTRA_THEME = "visual_parity_theme"
    }
}

enum class ProfileAvatarsFixtureState(val wireValue: String) {
    ProfileReady("profile-ready"),
    ProfileFallback("profile-fallback"),
    ProfileLoading("profile-loading"),
    ProfileUnavailable("profile-unavailable"),
    BotsReady("bots-ready"),
    BotsFallback("bots-fallback");

    companion object {
        fun parse(value: String?): ProfileAvatarsFixtureState = entries.firstOrNull { it.wireValue == value }
            ?: error("unsupported avatar parity state")
    }
}

private class BotsFixtureStateHolder(
    scope: CoroutineScope,
    repository: BotsPluginRepository,
) {
    val state = MutableStateFlow(BotsRosterUiState(phase = BotsRosterPhase.Loading))
    init {
        scope.launch {
            when (val loaded = repository.loadRoster()) {
                is com.hermesagent.mobile.plugins.bots.BotsRosterLoad.Loaded -> state.value = BotsRosterUiState(
                    phase = BotsRosterPhase.Ready,
                    sections = listOf(BotSectionBlock(null, "section:unassigned", "Unassigned", loaded.rows)),
                )
                else -> state.value = state.value.copy(phase = BotsRosterPhase.Empty)
            }
        }
    }
}

internal class AvatarFixtureRpc(private val state: ProfileAvatarsFixtureState) : EndpointDispatchingGatewayRpcClient {
    internal val calls = java.util.Collections.synchronizedList(mutableListOf<String>())
    internal val loadingAssetStarted = kotlinx.coroutines.CompletableDeferred<Unit>()

    override val events: Flow<GatewayEvent> = emptyFlow()
    override suspend fun request(method: String, params: JsonObject): JsonElement = error("ordinary fixture path")
    override suspend fun requestAtEndpointDispatch(method: String, params: JsonObject, dispatch: (() -> Boolean) -> Boolean): JsonElement {
        if (!dispatch { calls += method; true }) return buildJsonObject { put("found", false) }
        return when (method) {
            "profiles.list" -> roster(state)
            "profiles.get_asset" -> when (state) {
                ProfileAvatarsFixtureState.ProfileReady,
                ProfileAvatarsFixtureState.BotsReady -> asset()
                ProfileAvatarsFixtureState.ProfileFallback -> buildJsonObject { put("found", false) }
                ProfileAvatarsFixtureState.ProfileLoading -> {
                    loadingAssetStarted.complete(Unit)
                    kotlinx.coroutines.awaitCancellation()
                }
                ProfileAvatarsFixtureState.ProfileUnavailable -> throw GatewayRpcException("synthetic avatar refusal")
                ProfileAvatarsFixtureState.BotsFallback -> buildJsonObject { put("found", false) }
            }
            else -> buildJsonObject { put("found", false) }
        }
    }
    override fun close() = Unit

    private fun roster(state: ProfileAvatarsFixtureState) = buildJsonObject {
        put("profiles", buildJsonArray {
            add(buildJsonObject {
                put("name", "synthetic-avatar")
                put("display_name", "Synthetic Avatar")
                put("handle", "synthetic-avatar")
                put("has_avatar", when (state) {
                    ProfileAvatarsFixtureState.ProfileReady,
                    ProfileAvatarsFixtureState.ProfileFallback,
                    ProfileAvatarsFixtureState.ProfileLoading,
                    ProfileAvatarsFixtureState.ProfileUnavailable,
                    ProfileAvatarsFixtureState.BotsReady -> true
                    ProfileAvatarsFixtureState.BotsFallback -> false
                })
            })
        })
    }

    private fun asset() = buildJsonObject {
        val bytes = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=")
        put("found", true)
        put("mime", "image/png")
        put("size", bytes.size)
        put("data", "data:image/png;base64,${Base64.getEncoder().encodeToString(bytes)}")
    }
}
