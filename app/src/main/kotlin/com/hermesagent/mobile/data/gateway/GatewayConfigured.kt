package com.hermesagent.mobile.data.gateway

import com.hermesagent.mobile.data.ssh.HostProfileStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Whether this app has a Gateway to connect to at all.
 *
 * This is the difference between "you have not set one up yet" and "the one you
 * set up is down", which is not something [GatewayConnectionState] can answer:
 * its [GatewayConnectionStatus.Disconnected] is the same value on a fresh
 * install and on a saved Gateway whose host is asleep, and it is also the
 * seeded value before anything has been attempted.
 *
 * The saved profile is the honest signal, and it is read the way the app itself
 * reads it: only the selected route can produce a connection, so only the
 * selected route's profile decides. `restoreSavedRemoteGateway` gates a
 * cold-start restore on exactly this pair, so a `true` here means a connection
 * attempt is something this app can actually make.
 *
 * *Not* "has this process ever been connected". That answer would call a saved
 * Gateway on a phone that is offline, or one whose first redial has not landed
 * yet, "no Gateway configured" — sending someone to add a Gateway they already
 * have, and making the cold-offline copy worse rather than better.
 */
fun gatewayConfigured(
    profiles: RemoteGatewayProfileStore,
    hosts: HostProfileStore,
): Flow<Boolean> = combine(
    // Deduped on the way in, not only on the way out. These are backed by one
    // DataStore, which re-emits the whole snapshot on any write in the app —
    // a theme change would otherwise re-parse the Gateway URL, for the life of
    // the process, to answer a question that did not change.
    profiles.gatewayConnectionMode.distinctUntilChanged(),
    profiles.remoteGatewayProfile.distinctUntilChanged(),
    hosts.hostProfile.distinctUntilChanged(),
) { mode, remote, host ->
    when (mode) {
        GatewayConnectionMode.Remote -> remote.isValid
        GatewayConnectionMode.Ssh -> host.isValid
    }
}.distinctUntilChanged()
