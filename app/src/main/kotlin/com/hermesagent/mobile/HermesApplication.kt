package com.hermesagent.mobile

import android.app.Application
import com.hermesagent.mobile.data.draft.AndroidSessionDraftStore
import com.hermesagent.mobile.data.composer.AndroidComposerQueueStoreFactory
import com.hermesagent.mobile.data.composer.ComposerQueueController
import com.hermesagent.mobile.data.composer.ComposerQueueScope
import com.hermesagent.mobile.data.composer.ComposerQueueSubmitter
import com.hermesagent.mobile.data.composer.ProfileSwitchingComposerQueueStore
import com.hermesagent.mobile.data.composer.QueueSubmissionOutcome
import com.hermesagent.mobile.data.connections.ConnectionRegistryStore
import com.hermesagent.mobile.data.connections.ConnectionSwitchController
import com.hermesagent.mobile.data.gateway.AndroidGatewayTokenStore
import com.hermesagent.mobile.data.gateway.GatewayConnectionController
import com.hermesagent.mobile.data.gateway.GatewayConnectionMode
import com.hermesagent.mobile.data.gateway.GatewayConnectionManager
import com.hermesagent.mobile.data.gateway.GatewayNetworkMonitor
import com.hermesagent.mobile.data.gateway.LiveGatewaySessionRepository
import com.hermesagent.mobile.data.gateway.LoopbackGatewayNativeLogin
import com.hermesagent.mobile.data.gateway.NativeGatewayAuthenticator
import com.hermesagent.mobile.data.gateway.OkHttpGatewayNativeAuthApi
import com.hermesagent.mobile.data.gateway.OkHttpGatewayRpcClient
import com.hermesagent.mobile.data.gateway.RemoteGatewayConnector
import com.hermesagent.mobile.data.gateway.RemoteGatewayProfileStore
import com.hermesagent.mobile.data.prefs.HermesPreferences
import com.hermesagent.mobile.data.session.SessionCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Process-scoped live Gateway graph and backend-authoritative session cache. */
class HermesApplication : Application() {
    internal val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http by lazy {
        OkHttpClient.Builder()
            .pingInterval(30, TimeUnit.SECONDS)
            .build()
    }

    val cache: SessionCache by lazy(::SessionCache)
    val preferences: HermesPreferences by lazy { HermesPreferences(this) }
    val draftStore: AndroidSessionDraftStore by lazy { AndroidSessionDraftStore(this) }
    /**
     * Queue text is private per saved endpoint/profile scope. The selected
     * store changes atomically before the ViewModel presents it; neither
     * runtime session ids nor remote paths participate in that identity.
     */
    private val composerQueueStore by lazy {
        ProfileSwitchingComposerQueueStore(
            factory = AndroidComposerQueueStoreFactory(this),
            initialScope = ComposerQueueScope.forConnectionProfile("bootstrap", "default"),
        )
    }
    internal val gatewayConnection: GatewayConnectionManager by lazy {
        val authApi = OkHttpGatewayNativeAuthApi(http)
        val authenticator = NativeGatewayAuthenticator(
            api = authApi,
            store = AndroidGatewayTokenStore(this),
            login = LoopbackGatewayNativeLogin(authApi),
        )
        GatewayConnectionManager(
            scope = appScope,
            installStore = preferences,
            http = http,
            remoteConnector = RemoteGatewayConnector(authenticator) { baseUrl, ticket ->
                OkHttpGatewayRpcClient.connectRemote(http, baseUrl, ticket)
            },
        )
    }
    internal val gatewayHttp: com.hermesagent.mobile.data.gateway.GatewayHttp? get() = gatewayConnection.gatewayHttp.value

    /** The one place a connection switch is performed; see its own doc for the order. */
    internal val connectionSwitch: ConnectionSwitchController by lazy {
        ConnectionSwitchController(
            store = preferences,
            gateway = gatewayConnection,
            cache = cache,
            drafts = draftStore,
        )
    }

    internal val voiceRepository: com.hermesagent.mobile.data.voice.GatewayVoiceRepository by lazy {
        com.hermesagent.mobile.data.voice.GatewayVoiceRepository { gatewayHttp }
    }

    internal val codingContextProvider: com.hermesagent.mobile.ui.chat.CodingContextProvider by lazy {
        com.hermesagent.mobile.ui.chat.GatewayCodingContextProvider { gatewayHttp }
    }

    internal val relayRepository: com.hermesagent.mobile.data.relay.RelayPluginRepository by lazy {
        com.hermesagent.mobile.data.relay.RelayPluginRepository { gatewayHttp }
    }

    /**
     * Availability is process-scoped because it follows the one live Gateway
     * connection, not a screen. It probes on a connection edge and on an
     * explicit refresh only — never on a timer — so holding it costs nothing
     * while no Relay surface is looking.
     */
    internal val relayAvailability: com.hermesagent.mobile.data.relay.RelayAvailabilityController by lazy {
        com.hermesagent.mobile.data.relay.RelayAvailabilityController(
            scope = appScope,
            probe = relayRepository,
            connection = gatewayConnection.state,
            credentials = object : com.hermesagent.mobile.data.relay.RelayCredentialRefresher {
                override suspend fun refreshOnce(): Boolean = gatewayConnection.refreshCredential()
                override suspend fun signInAvailable(): Boolean = gatewayConnection.signInAvailable()
            },
        )
    }

    internal val wakeWordRepository: com.hermesagent.mobile.data.voice.WakeWordRepository by lazy {
        com.hermesagent.mobile.data.voice.WakeWordRepository(rpc = { gatewayConnection.client.value })
    }

    internal val sessionRepository: LiveGatewaySessionRepository by lazy {
        LiveGatewaySessionRepository(cache, gatewayConnection, appScope)
    }
    internal val composerQueueController: ComposerQueueController by lazy {
        ComposerQueueController(
            store = composerQueueStore,
            submitter = object : ComposerQueueSubmitter {
                override suspend fun submitQueued(durableSessionId: String, text: String): QueueSubmissionOutcome = try {
                    when (sessionRepository.submit(durableSessionId, text, queued = true)) {
                        com.hermesagent.mobile.data.gateway.GatewaySubmitOutcome.Accepted -> QueueSubmissionOutcome.Accepted
                        com.hermesagent.mobile.data.gateway.GatewaySubmitOutcome.Ambiguous -> QueueSubmissionOutcome.Ambiguous
                    }
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    QueueSubmissionOutcome.Rejected
                }
            },
        )
    }

    internal suspend fun switchComposerQueueScope(scope: ComposerQueueScope) {
        composerQueueStore.switchScope(scope)
    }
    private var networkMonitor: GatewayNetworkMonitor? = null

    override fun onCreate() {
        super.onCreate()
        // Force the process graph once. There is no demo seed: sessions arrive
        // only from an authenticated Gateway connection.
        sessionRepository
        networkMonitor = GatewayNetworkMonitor(this, gatewayConnection::networkAvailabilityChanged).also { it.start() }
        // Desktop parity (use-gateway-boot.ts): the window becoming visible is
        // a reconnect nudge. Mobile also stops automatic redials while the app
        // is backgrounded; an already-open socket is not torn down.
        val processLifecycle = androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle
        gatewayConnection.applicationForegroundChanged(
            processLifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED),
        )
        processLifecycle.addObserver(
            object : androidx.lifecycle.DefaultLifecycleObserver {
                override fun onStart(owner: androidx.lifecycle.LifecycleOwner) {
                    gatewayConnection.applicationForegroundChanged(true)
                }

                override fun onStop(owner: androidx.lifecycle.LifecycleOwner) {
                    gatewayConnection.applicationForegroundChanged(false)
                }
            },
        )
        appScope.launch {
            followActiveConnection(
                connections = preferences,
                profiles = preferences,
                connection = gatewayConnection,
                routeGeneration = connectionSwitch.routeGeneration,
            )
        }
    }
}

/**
 * Restores the *active* saved connection's Remote Gateway, and re-arms when the
 * active connection changes.
 *
 * Switching connections is the only event that re-dials on its own; editing a
 * URL is not, because the person doing the editing has not finished typing.
 * That is why this keys on the active row's id, plus the explicit re-arm the
 * switch controller raises once a re-address is persisted — never on the route
 * values themselves, which change on every keystroke.
 */
internal suspend fun followActiveConnection(
    connections: ConnectionRegistryStore,
    profiles: RemoteGatewayProfileStore,
    connection: GatewayConnectionController,
    routeGeneration: Flow<Long> = flowOf(0L),
) {
    combine(
        connections.connectionRegistry.map { it.active?.id },
        routeGeneration,
    ) { activeId, generation -> activeId to generation }
        .distinctUntilChanged()
        .collectLatest { restoreSavedRemoteGateway(profiles, connection) }
}

/**
 * Restores only a valid saved Remote Gateway; interactive sign-in remains user initiated.
 *
 * It stops at the first persisted route edit and leaves the teardown to
 * whoever made that edit — the Gateways form disconnects in a `finally` after
 * every save, and a connection switch disconnects before it moves the active
 * marker. One owner for the teardown means a re-arm cannot disconnect the
 * connection the re-arm just opened.
 */
internal suspend fun restoreSavedRemoteGateway(
    profiles: RemoteGatewayProfileStore,
    connection: GatewayConnectionController,
) {
    val routes = combine(
        profiles.gatewayConnectionMode,
        profiles.remoteGatewayProfile,
    ) { mode, profile -> mode to profile }.distinctUntilChanged()
    val initial = routes.first()
    if (initial.first != GatewayConnectionMode.Remote || !initial.second.isValid) return

    // Keep observing through the first persisted route edit. The predicate
    // catches an edit even if it lands between the two flow subscriptions.
    coroutineScope {
        val restore = launch(start = CoroutineStart.UNDISPATCHED) {
            connection.restoreRemote(initial.second)
        }
        try {
            routes.first { it != initial }
        } finally {
            restore.cancelAndJoin()
        }
    }
}
