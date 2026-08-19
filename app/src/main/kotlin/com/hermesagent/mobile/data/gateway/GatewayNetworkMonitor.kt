package com.hermesagent.mobile.data.gateway

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network

/** Tears down connection-scoped state when Android moves to another network. */
internal class GatewayNetworkMonitor(
    context: Context,
    private val onChanged: () -> Unit,
) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private var current: Network? = null
    private var started = false
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val previous = current
            current = network
            if (previous != null && previous != network) onChanged()
        }

        override fun onLost(network: Network) {
            if (current == network) {
                current = null
                onChanged()
            }
        }
    }

    fun start() {
        if (started) return
        started = true
        connectivity.registerDefaultNetworkCallback(callback)
    }
}
