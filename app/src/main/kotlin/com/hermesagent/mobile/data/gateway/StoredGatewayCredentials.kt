package com.hermesagent.mobile.data.gateway

import kotlinx.coroutines.CancellationException

/**
 * Whether one secret slot holds a credential this app could present.
 *
 * Typed over the slot rather than over a saved row on purpose. Which routes
 * have a stored credential, and which slot each one reads, is
 * `SavedConnection.restoreCredentialSlot`'s to say — so this side answers only
 * the question a credential store can answer, and this package does not have to
 * learn what a saved connection is. That keeps the dependency between
 * `data/connections` and `data/gateway` running one way, as every other file in
 * both packages already does.
 *
 * The default says yes, because "assume there is one and wait" is the behaviour
 * this app had before anything could answer, and an unwired probe must not
 * start short-circuiting dials that would have worked.
 */
internal fun interface ConnectionCredentialProbe {
    suspend fun hasCredential(slot: GatewaySecretSlot): Boolean
}

/**
 * Answers [ConnectionCredentialProbe] out of the slot store.
 *
 * Existence only. The credential is read because that is the one question the
 * store answers — [GatewayTokenStore.load] applies the whole binding rule, so a
 * slot holding another Gateway's credential reads as absent here exactly as it
 * does on the dial — and the value is dropped on the same line rather than
 * returned, so nothing reaching this seam's caller can carry a secret.
 */
internal class StoredGatewayCredentials(
    private val tokens: GatewayTokenStore,
) : ConnectionCredentialProbe {
    override suspend fun hasCredential(slot: GatewaySecretSlot): Boolean =
        runCatching { tokens.load(slot) != null }
            // A Keystore that cannot be read has not said the slot is empty.
            // The honest answer is "unknown", and the safe rendering of unknown
            // is the behaviour that existed before this probe: dial, and wait.
            .onFailure { if (it is CancellationException) throw it }
            .getOrDefault(true)
}
