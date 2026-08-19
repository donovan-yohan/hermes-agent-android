package com.hermesagent.mobile.data.ssh

import kotlinx.coroutines.flow.Flow

/**
 * Where the non-secret host profile lives.
 *
 * Two real implementations, which is what earns the interface:
 * [com.hermesagent.mobile.data.prefs.HermesPreferences] on DataStore, and an
 * in-memory one in the test source set so the SSH ViewModel can be driven on a
 * plain JVM without Android.
 *
 * The type is the guard rail: it only accepts a [HostProfile], and a
 * [HostProfile] cannot carry a password, passphrase or key.
 */
interface HostProfileStore {
    val hostProfile: Flow<HostProfile>

    suspend fun saveHostProfile(profile: HostProfile)
}
