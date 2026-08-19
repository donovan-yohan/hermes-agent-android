package com.hermesagent.mobile

import android.app.Application
import com.hermesagent.mobile.data.gateway.GatewayConnectionManager
import com.hermesagent.mobile.data.gateway.GatewayNetworkMonitor
import com.hermesagent.mobile.data.gateway.LiveGatewaySessionRepository
import com.hermesagent.mobile.data.prefs.HermesPreferences
import com.hermesagent.mobile.data.session.SessionCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Process-scoped live Gateway graph and backend-authoritative session cache. */
class HermesApplication : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val cache: SessionCache by lazy(::SessionCache)
    val preferences: HermesPreferences by lazy { HermesPreferences(this) }
    internal val gatewayConnection: GatewayConnectionManager by lazy {
        GatewayConnectionManager(appScope, preferences)
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
        networkMonitor = GatewayNetworkMonitor(this, gatewayConnection::networkChanged).also { it.start() }
    }
}
