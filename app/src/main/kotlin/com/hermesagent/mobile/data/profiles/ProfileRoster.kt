package com.hermesagent.mobile.data.profiles

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** What the rail and the roster panel know about this Gateway's profiles. */
data class ProfileRosterState(
    val profiles: List<HermesProfile> = emptyList(),
    /** False until one `profiles.list` has actually answered. */
    val loaded: Boolean = false,
)

/**
 * The cache of profile truth, with the same authority rules as
 * [com.hermesagent.mobile.data.session.SessionCache] applied to what a roster
 * actually is.
 *
 * - **One answer is the whole roster.** `profiles.list` enumerates every
 *   profile and emits every field of each row
 *   (`tui_gateway/methods_profiles.py:194-246` @
 *   `29112bef099274229cadff79cdff7bf7b99c4b77`), so a successful answer decides
 *   both which profiles exist and what each one says. Layering fields would
 *   only ever resurrect a model, a colour or a display name the host cleared.
 * - **A failed refresh keeps the last good answer.** Nothing calls [publish] on
 *   failure, and [loaded] never goes back to false while a roster is held.
 * - **Preserve reference identity on a no-op**, so Compose does not recompose
 *   the rail on every refresh.
 * - **Guard against the past.** A response from a previous connection must not
 *   clobber the roster the current one just served — Desktop's own
 *   `profileListEpoch` (`apps/desktop/src/store/profile.ts:49-74`), which
 *   shipped because a dying backend's late answer collapsed the rail.
 */
class ProfileRosterCache {
    private val _state = MutableStateFlow(ProfileRosterState())
    val state: StateFlow<ProfileRosterState> = _state.asStateFlow()

    private val epochLock = Any()
    private var epoch = 0L

    /** The epoch a refresh must still be in to be allowed to write. */
    fun currentEpoch(): Long = synchronized(epochLock) { epoch }

    /** Strand every in-flight refresh: their answers resolve but stop writing here. */
    fun invalidate() {
        synchronized(epochLock) { epoch += 1 }
    }

    /** Drop the roster entirely — the Gateway this roster described is gone. */
    fun clear() = synchronized(epochLock) {
        epoch += 1
        _state.update { current -> if (current == ProfileRosterState()) current else ProfileRosterState() }
    }

    /**
     * Publish one complete answer, unless a newer connection has moved on. The
     * epoch check and the write are one step, so an [invalidate] cannot land
     * between them and let a stranded answer through.
     */
    fun publish(epoch: Long, rows: List<HermesProfile>): Boolean = synchronized(epochLock) {
        if (epoch != this.epoch) return false
        _state.update { current ->
            if (current.loaded && rows == current.profiles) current
            else current.copy(profiles = rows, loaded = true)
        }
        true
    }
}
