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
import com.hermesagent.mobile.data.gateway.AndroidGatewayNetworkGate
import com.hermesagent.mobile.data.gateway.AndroidGatewaySignInLog
import com.hermesagent.mobile.data.gateway.androidGatewayAppFailureLog
import com.hermesagent.mobile.data.gateway.androidGatewayConnectEventLog
import com.hermesagent.mobile.data.gateway.AndroidGatewayTokenStore
import com.hermesagent.mobile.data.gateway.GatewayConnectionController
import com.hermesagent.mobile.data.gateway.GatewayConnectionMode
import com.hermesagent.mobile.data.gateway.GatewayConnectionManager
import com.hermesagent.mobile.data.gateway.GatewayDashboardTokenResolver
import com.hermesagent.mobile.data.gateway.GatewayNetworkMonitor
import com.hermesagent.mobile.data.gateway.GatewaySignInBrowser
import com.hermesagent.mobile.data.gateway.LiveGatewaySessionRepository
import com.hermesagent.mobile.data.gateway.LocalGatewayConnector
import com.hermesagent.mobile.data.gateway.LoopbackGatewayNativeLogin
import com.hermesagent.mobile.data.gateway.NativeGatewayAuthenticator
import com.hermesagent.mobile.data.gateway.OkHttpGatewayNativeAuthApi
import com.hermesagent.mobile.data.gateway.OkHttpLocalGatewayHealthCheck
import com.hermesagent.mobile.data.gateway.OkHttpGatewayRpcClient
import com.hermesagent.mobile.data.gateway.RemoteGatewayConnector
import com.hermesagent.mobile.data.gateway.RemoteGatewayProfileStore
import com.hermesagent.mobile.data.notifications.AndroidNotificationPreferences
import com.hermesagent.mobile.data.notifications.AndroidNotificationSurface
import com.hermesagent.mobile.data.notifications.NotificationPreferenceStore
import com.hermesagent.mobile.data.notifications.NotificationPresence
import com.hermesagent.mobile.data.notifications.NotificationSurface
import com.hermesagent.mobile.data.notifications.SessionNotifier
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
import kotlinx.coroutines.flow.filterNotNull
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
        val authApi = OkHttpGatewayNativeAuthApi(
            http,
            log = AndroidGatewaySignInLog,
            networkGate = AndroidGatewayNetworkGate(this),
        )
        // One store, two credential shapes: a Remote row's sign-in and a Local
        // row's session token share the slot machinery that names a file after
        // the connection and binds its contents to the address that minted them.
        val secrets = AndroidGatewayTokenStore(this)
        val authenticator = NativeGatewayAuthenticator(
            api = authApi,
            store = secrets,
            login = LoopbackGatewayNativeLogin(authApi, log = AndroidGatewaySignInLog),
            log = AndroidGatewaySignInLog,
        )
        GatewayConnectionManager(
            scope = appScope,
            installStore = preferences,
            http = http,
            remoteConnector = RemoteGatewayConnector(authenticator) { baseUrl, ticket ->
                OkHttpGatewayRpcClient.connectRemote(http, baseUrl, ticket)
            },
            // Without this a crash in this app's own connection plumbing is
            // indistinguishable, on a device, from an unreachable Gateway.
            logAppFailure = androidGatewayAppFailureLog,
            // A cancelled connect publishes nothing by design; without this it
            // leaves no trace anywhere either.
            logConnectEvent = androidGatewayConnectEventLog,
            localConnector = LocalGatewayConnector(
                tokens = secrets,
                health = OkHttpLocalGatewayHealthCheck(http),
                rpcOpen = { baseUrl, token -> OkHttpGatewayRpcClient.connectLocal(http, baseUrl, token) },
                scraper = GatewayDashboardTokenResolver(http),
            ),
        )
    }
    internal val gatewayHttp: com.hermesagent.mobile.data.gateway.GatewayHttp? get() = gatewayConnection.gatewayHttp.value

    /**
     * The sign-in hand-off, process-scoped like the connection it opens.
     *
     * Held here rather than by the Gateways screen because both halves of the
     * hand-off outlive that screen: the Custom Tabs binding that keeps this
     * process runnable while the browser is in front of it, and the intent that
     * brings the app back once the callback is accepted.
     */
    internal val signInBrowser: GatewaySignInBrowser by lazy {
        GatewaySignInBrowser(
            context = this,
            mainActivity = MainActivity::class.java,
            log = AndroidGatewaySignInLog,
        )
    }

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
            configured = com.hermesagent.mobile.data.gateway.gatewayConfigured(
                profiles = preferences,
                hosts = preferences,
            ),
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

    /**
     * Where the user is. Process-scoped because a notification decision has to
     * be answerable while no Activity exists, and because "the app is away" is
     * a fact about the process, not about a screen.
     */
    internal val notificationPresence: NotificationPresence by lazy(::NotificationPresence)

    internal val notificationPreferences: NotificationPreferenceStore by lazy {
        AndroidNotificationPreferences(this)
    }

    /**
     * Held here, not built per use: the shade's Approve/Reject receiver has to
     * withdraw the same notification the notifier posted, and its group
     * bookkeeping only works if there is one of it.
     */
    internal val notificationSurface: NotificationSurface by lazy { AndroidNotificationSurface(this) }

    /**
     * The profile roster follows the one live Gateway connection, so it is
     * process-scoped like the session cache. `profiles.list` is only ever asked
     * on a connection edge or an explicit refresh — never on a timer.
     */
    internal val profileRepository: com.hermesagent.mobile.data.profiles.ProfileRepository by lazy {
        com.hermesagent.mobile.data.profiles.GatewayProfileRepository(
            rpc = { gatewayConnection.client.value },
        )
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
        val startedNow = processLifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)
        gatewayConnection.applicationForegroundChanged(startedNow)
        notificationPresence.applicationForegroundChanged(startedNow)
        processLifecycle.addObserver(
            object : androidx.lifecycle.DefaultLifecycleObserver {
                override fun onStart(owner: androidx.lifecycle.LifecycleOwner) {
                    gatewayConnection.applicationForegroundChanged(true)
                    notificationPresence.applicationForegroundChanged(true)
                }

                override fun onStop(owner: androidx.lifecycle.LifecycleOwner) {
                    gatewayConnection.applicationForegroundChanged(false)
                    notificationPresence.applicationForegroundChanged(false)
                }
            },
        )
        startSessionNotifier()
        appScope.launch {
            followActiveConnection(
                connections = preferences,
                profiles = preferences,
                connection = gatewayConnection,
                routeGeneration = connectionSwitch.routeGeneration,
            )
        }
    }

    /**
     * OS notifications follow the repository, not any transport, so Remote,
     * Managed SSH and Local behave identically — they deliver the same events
     * over the same socket.
     *
     * Connected-only by construction: nothing here holds the connection open,
     * so when the socket is gone nothing arrives. That is the honest T1 shape
     * and it is stated in `status/ROADMAP.md` rather than hidden.
     */
    private fun startSessionNotifier() {
        SessionNotifier(
            pendingInputs = sessionRepository.pendingInputs,
            turnOutcomes = sessionRepository.turnOutcomes,
            sessions = cache.state,
            // A new client instance is a new socket, which is a new replay.
            socketOpens = gatewayConnection.client.filterNotNull().map { },
            presence = notificationPresence,
            settingsFlow = notificationPreferences.notificationSettings,
            surface = notificationSurface,
            clock = System::currentTimeMillis,
        ).start(appScope)
    }
}

/**
 * Restores the *active* saved connection's Gateway, and re-arms when the
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
        .collectLatest { restoreActiveGateway(profiles, connection) }
}

/**
 * Restores whichever route the active row is on, when that route can come up
 * with no one present.
 *
 * Exactly one route is active, so this dispatches rather than racing the two.
 * A Remote row has a stored sign-in and a Local row a stored session token;
 * Managed SSH's credential is created by the connection and died with it, which
 * is why it is not restored at all.
 */
internal suspend fun restoreActiveGateway(
    profiles: RemoteGatewayProfileStore,
    connection: GatewayConnectionController,
) {
    when (profiles.gatewayConnectionMode.first()) {
        GatewayConnectionMode.Remote -> restoreSavedRemoteGateway(profiles, connection)
        GatewayConnectionMode.Local -> restoreSavedLocalGateway(profiles, connection)
        GatewayConnectionMode.Ssh -> Unit
    }
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

/**
 * Restores a saved Hermes on this device, using the session token that row
 * already holds.
 *
 * The same shape as [restoreSavedRemoteGateway] — dial, then stop at the first
 * persisted edit to the route being dialled and leave the teardown to whoever
 * made that edit — and deliberately a separate function rather than a third
 * value in that one's `combine`. Each watches only its own route's address, so
 * editing a Local row cannot cancel a Remote restore, or the reverse.
 */
internal suspend fun restoreSavedLocalGateway(
    profiles: RemoteGatewayProfileStore,
    connection: GatewayConnectionController,
) {
    val routes = combine(
        profiles.gatewayConnectionMode,
        profiles.localGatewayProfile,
    ) { mode, profile -> mode to profile }.distinctUntilChanged()
    val initial = routes.first()
    if (initial.first != GatewayConnectionMode.Local || !initial.second.isValid) return

    coroutineScope {
        val restore = launch(start = CoroutineStart.UNDISPATCHED) {
            connection.restoreLocal(initial.second)
        }
        try {
            routes.first { it != initial }
        } finally {
            restore.cancelAndJoin()
        }
    }
}
