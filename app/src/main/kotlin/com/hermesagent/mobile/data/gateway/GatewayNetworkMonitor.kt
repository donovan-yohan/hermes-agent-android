package com.hermesagent.mobile.data.gateway

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network

/** Reports default-network loss and recovery to the process-scoped connection owner. */
internal class GatewayNetworkMonitor(
    context: Context,
    private val onAvailabilityChanged: (Boolean) -> Unit,
) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private var current: Network? = null
    private var started = false
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val previous = current
            current = network
            if (previous == null || previous != network) onAvailabilityChanged(true)
        }

        override fun onLost(network: Network) {
            if (current == network) {
                current = null
                onAvailabilityChanged(false)
            }
        }
    }

    fun start() {
        if (started) return
        started = true
        connectivity.registerDefaultNetworkCallback(callback)
    }
}
