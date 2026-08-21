package com.hermesagent.mobile

import android.app.Application
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Process-scoped live Gateway graph and backend-authoritative session cache. */
class HermesApplication : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http by lazy {
        OkHttpClient.Builder()
            .pingInterval(30, TimeUnit.SECONDS)
            .build()
    }

    val cache: SessionCache by lazy(::SessionCache)
    val preferences: HermesPreferences by lazy { HermesPreferences(this) }
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
    internal val sessionRepository: LiveGatewaySessionRepository by lazy {
        LiveGatewaySessionRepository(cache, gatewayConnection, appScope)
    }
    private var networkMonitor: GatewayNetworkMonitor? = null

    override fun onCreate() {
        super.onCreate()
        // Force the process graph once. There is no demo seed: sessions arrive
        // only from an authenticated Gateway connection.
        sessionRepository
        networkMonitor = GatewayNetworkMonitor(this, gatewayConnection::networkAvailabilityChanged).also { it.start() }
        appScope.launch {
            restoreSavedRemoteGateway(preferences, gatewayConnection)
        }
    }
}

/** Restores only a valid saved shared route; interactive sign-in remains user initiated. */
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
        connection.disconnect()
    }
}
