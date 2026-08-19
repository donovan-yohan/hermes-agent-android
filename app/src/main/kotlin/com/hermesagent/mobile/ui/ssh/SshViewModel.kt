package com.hermesagent.mobile.ui.ssh

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.hermesagent.mobile.data.ssh.AuthMethod
import com.hermesagent.mobile.data.ssh.DestinationParse
import com.hermesagent.mobile.data.ssh.HostAnchor
import com.hermesagent.mobile.data.ssh.HostProfile
import com.hermesagent.mobile.data.ssh.HostProfileStore
import com.hermesagent.mobile.data.ssh.KeyImportProblem
import com.hermesagent.mobile.data.ssh.ProbeFailure
import com.hermesagent.mobile.data.ssh.ProbeResult
import com.hermesagent.mobile.data.ssh.SshCredential
import com.hermesagent.mobile.data.ssh.SshProbe
import com.hermesagent.mobile.data.ssh.SshjProbe
import com.hermesagent.mobile.data.ssh.looksLikePrivateKey
import com.hermesagent.mobile.data.ssh.parseSshDestination
import com.hermesagent.mobile.data.ssh.sanitizeKeyDisplayName
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.CharBuffer

/** Where the probe is. Every state has its own copy on screen. */
sealed interface ProbeStatus {
    data object Idle : ProbeStatus
    data object Running : ProbeStatus
    data class Succeeded(val output: String, val serverVersion: String, val elapsedMillis: Long) : ProbeStatus
    data class Failed(val kind: ProbeFailure, val message: String) : ProbeStatus

    /**
     * The host key changed. Terminal: there is no "accept" action for this
     * state anywhere in the app.
     */
    data class KeyMismatch(val expected: String, val presented: String) : ProbeStatus
}

/**
 * A fingerprint waiting for an explicit human decision, and the `(host, port)`
 * that offered it.
 *
 * The anchor is the load-bearing half. Without it, retargeting the destination
 * while a review is on screen and then tapping accept files host A's key under
 * host B — after which B's real key reads as a mismatch, and any endpoint that
 * happens to present A's key is trusted without a review of its own.
 */
data class PendingHostKey(val fingerprint: String, val keyType: String, val anchor: HostAnchor)

data class SshUiState(
    val profile: HostProfile = HostProfile(),
    /**
     * The destination field exactly as typed. Non-secret, and not persisted in
     * this form — [profile] holds the parsed halves, which are the canonical
     * copy.
     */
    val destination: String = "",
    /** In memory for the life of this screen. Never written anywhere. */
    val password: String = "",
    val keyPassphrase: String = "",
    val privateKeyLoaded: Boolean = false,
    /** Runtime-only label for the loaded document; never enters [HostProfile]. */
    val importedKeyName: String? = null,
    val status: ProbeStatus = ProbeStatus.Idle,
    /**
     * The review the last probe produced. Read [pendingHostKey] instead — this
     * is the raw record, not the decision the form is waiting on.
     */
    val hostKeyReview: PendingHostKey? = null,
    /** Why the last key import was refused, or null. Non-secret. */
    val keyImportProblem: KeyImportProblem? = null,
    /**
     * Bumped by every edit to the profile or the credential.
     *
     * It is how work started against one form is told apart from the form as it
     * is now: a probe captures the generation it started at, and a result whose
     * generation has moved on is discarded instead of painting a screen that is
     * already pointing somewhere else.
     */
    val generation: Long = 0,
) {
    private val parsedDestination: DestinationParse get() = parseSshDestination(destination)

    /**
     * The fingerprint review this form is waiting on, or null.
     *
     * A review stops counting the moment the destination points at a different
     * `(host, port)` — it is a decision about the box that offered the key, and
     * the field stays editable while it is on screen. Deriving it here rather
     * than clearing it at each edit means there is one rule, in one place, that
     * both the screen and [SshViewModel.acceptPendingHostKey] read: a review
     * that is not for the current host is neither shown nor acceptable.
     */
    val pendingHostKey: PendingHostKey?
        get() {
            val destinationAnchor = (parsedDestination as? DestinationParse.Valid)
                ?.destination
                ?.let { HostAnchor(it.host, it.port) }
            return hostKeyReview?.takeIf { it.anchor == destinationAnchor }
        }

    /**
     * Why the destination cannot be used, or null. A blank field is not an
     * error — it is a screen nobody has filled in yet, and shouting at it on
     * first open is noise. It is still not probeable, which [canProbe] answers.
     */
    val destinationError: String?
        get() = (parsedDestination as? DestinationParse.Invalid)
            ?.reason
            ?.takeIf { destination.isNotBlank() }

    /**
     * Both halves have to agree: the field must parse, *and* the profile it
     * parsed into must be dialable. Otherwise a field the user has emptied
     * could still dial the host they typed a minute ago.
     */
    val canProbe: Boolean
        get() = parsedDestination is DestinationParse.Valid && profile.isValid &&
            status != ProbeStatus.Running && hasCredential

    private val hasCredential: Boolean
        get() = when (profile.authMethod) {
            // Nothing to collect: the tailnet already authenticated the node.
            AuthMethod.TailscaleSsh -> true
            AuthMethod.Password -> password.isNotEmpty()
            AuthMethod.PrivateKey -> privateKeyLoaded
        }

    /**
     * Hand-written, because the generated one prints every field — and two of
     * them are the password and the key passphrase. A state object reaches a
     * crash report, a diagnostic dump or a stray log line as its `toString`, so
     * the generated one is a credential leak with no call site to grep for.
     */
    override fun toString(): String = "SshUiState(profile=$profile, destination=$destination, " +
        "password=<redacted>, keyPassphrase=<redacted>, privateKeyLoaded=$privateKeyLoaded, " +
        "status=$status, hostKeyReview=$hostKeyReview, keyImportProblem=$keyImportProblem, " +
        "generation=$generation)"
}

/**
 * Drives the SSH onboarding slice.
 *
 * The honest boundary this screen exists to state: Termux proving the host is
 * reachable does **not** hand this app Termux's keys, agent, or `~/.ssh/config`
 * — different package, different sandbox. So the app collects its own material,
 * keeps it in memory, and persists only the non-secret profile plus the
 * fingerprint the user accepted.
 *
 * Two rules hold everything else together:
 *
 * - **Work is bound to the form that started it.** Every edit bumps
 *   [SshUiState.generation]; an in-flight probe is cancelled by the edit and
 *   its answer is dropped if it arrives anyway. A host-key review carries the
 *   `(host, port)` that offered it and stops counting the moment the form
 *   points somewhere else.
 * - **The screen stops holding a secret once a probe has used it.** A first-use
 *   review is the one exception, because nothing was sent yet and the retry
 *   after accepting is the same attempt. Leaving the screen ends that lifetime
 *   too — see [releaseScreen], which is what makes "for this screen only" true
 *   of a ViewModel that outlives the screen.
 */
class SshViewModel(
    private val store: HostProfileStore,
    private val probe: SshProbe = SshjProbe(),
) : ViewModel() {

    private val _uiState = MutableStateFlow(SshUiState())
    val uiState: StateFlow<SshUiState> = _uiState.asStateFlow()

    /**
     * Held out of [SshUiState] so no PEM can be captured in a state snapshot,
     * and held as a `char[]` so it is one of the two things on this screen that
     * really can be wiped. The other is the copy handed to the probe.
     */
    private var privateKeyPem: CharArray? = null
    private var probeJob: Job? = null
    /** The credential copy owned by [probeJob], retained only so stop is a synchronous wipe. */
    private var probeCredential: SshCredential? = null
    private val persistenceMutex = Mutex()
    private var persistenceRevision = 0L

    /**
     * True once the user has touched the form. The stored profile loads
     * asynchronously, and an edit made while that read is in flight must win —
     * otherwise a fast typist watches their host name get replaced by the
     * saved one a moment later.
     */
    private var edited = false

    init {
        viewModelScope.launch {
            val stored = store.hostProfile.first()
            if (!edited) _uiState.update { it.copy(profile = stored, destination = stored.destination) }
        }
    }

    /**
     * The whole destination, as typed.
     *
     * Only a value that parses reaches the profile. A half-typed host is not a
     * host: persisting one would replace a working profile with a prefix, and
     * running it through [HostProfile.withDestination] would drop the
     * fingerprint accepted for the real host on the way past. An unparseable
     * edit still counts as retargeting, so it still stops a probe in flight.
     */
    fun setDestination(value: String) {
        val parsed = parseSshDestination(value)
        editProfile { profile ->
            if (parsed is DestinationParse.Valid) profile.withDestination(parsed.destination) else profile
        }
        _uiState.update { it.copy(destination = value) }
    }

    fun setAuthMethod(method: AuthMethod) {
        if (_uiState.value.profile.authMethod == method) return
        editProfile { it.copy(authMethod = method) }
        // Material for one auth method is never carried invisibly into another.
        dropScreenSecrets()
    }

    fun setPassword(value: String) = editCredential { it.copy(password = value) }

    fun setKeyPassphrase(value: String) = editCredential {
        // An interrupted key attempt clears the key before this transform runs;
        // do not retain a newly typed passphrase with nothing it can unlock.
        if (it.privateKeyLoaded) it.copy(keyPassphrase = value) else it
    }

    /**
     * Called with the contents of a document the user picked through SAF.
     *
     * Takes ownership of [pem] — the caller must not keep or reuse the array —
     * and wipes it on the spot if the document is not a key. The shape check
     * lives here rather than at the picker so that every caller (the Activity,
     * a test, a future import path) gets the same refusal.
     */
    fun importPrivateKey(pem: CharArray, displayName: String) {
        if (!looksLikePrivateKey(CharBuffer.wrap(pem))) {
            pem.fill(NUL)
            reportKeyImportProblem(KeyImportProblem.NotAPrivateKey)
            return
        }

        val name = sanitizeKeyDisplayName(displayName)
        // Switching method is an edit, so it stops a probe in flight and drops
        // what that probe was holding — before the new key is adopted.
        editProfile { it.copy(authMethod = AuthMethod.PrivateKey) }

        // A valid replacement is still a replacement. Clear the old key and
        // every credential tied to its former method before adopting the new
        // array, even when no probe happened to be running.
        dropScreenSecrets()
        privateKeyPem = pem
        _uiState.update {
            it.copy(
                importedKeyName = name,
                privateKeyLoaded = true,
                keyImportProblem = null,
            )
        }
    }

    /**
     * Called when the picked document could not be read, was too large, or was
     * not a private key. A refused import leaves any key already loaded alone —
     * it is a failed replacement, not a reason to forget what works.
     */
    fun reportKeyImportProblem(problem: KeyImportProblem) =
        _uiState.update { it.copy(keyImportProblem = problem) }

    fun forgetPrivateKey() {
        wipePrivateKey()
        _uiState.update {
            it.copy(
                importedKeyName = null,
                privateKeyLoaded = false,
                keyPassphrase = "",
                keyImportProblem = null,
            )
        }
    }

    /**
     * Accept a pending first-use fingerprint. This is the *only* way a
     * fingerprint is ever trusted, and it is always a deliberate tap.
     *
     * [SshUiState.pendingHostKey] is the guard: it is null unless the review is
     * for the `(host, port)` the form points at right now, so a tap that lands
     * after the destination moved trusts nothing.
     */
    fun acceptPendingHostKey() {
        val pending = _uiState.value.pendingHostKey ?: return

        editProfile { it.copy(acceptedFingerprint = pending.fingerprint) }
        _uiState.update { it.copy(hostKeyReview = null, status = ProbeStatus.Idle) }
    }

    fun dismissPendingHostKey() = _uiState.update { it.copy(hostKeyReview = null) }

    /**
     * Ends this screen's secret lifetime, synchronously, when the screen goes.
     *
     * This ViewModel is Activity-scoped and the Gateways surface is one
     * destination inside a single composition, so leaving it — toolbar back,
     * system back, or the Activity itself going away — destroys nothing on its
     * own. Without this call a password, a passphrase, an imported key and a
     * running probe would all outlive the screen the user believes they closed,
     * and would still be there when it is reopened.
     *
     * Ordering is the contract, and it is why this is a call rather than a
     * `DisposableEffect` of its own: `SshScreen` runs it inside the same
     * disposal that clears `FLAG_SECURE`, and runs it *first*, so nothing
     * secret is still held once the window stops being a secure one. Everything
     * here is synchronous — the cancel, the zeroing and the state reset are all
     * done before this returns.
     *
     * Scoped to composition disposal, not to backgrounding: stopping the
     * Activity does not dispose the composition, so stepping out to the
     * document picker and back keeps the form that is waiting for the key.
     *
     * Idempotent, and safe on a screen that never held anything.
     */
    fun releaseScreen() {
        stopProbe()
        dropScreenSecrets()
        // The generation bump is what makes a probe that had already answered
        // stale: cancelling its job does not unqueue a result that is a few
        // instructions from being applied.
        _uiState.update {
            it.copy(
                status = ProbeStatus.Idle,
                hostKeyReview = null,
                keyImportProblem = null,
                generation = it.generation + 1,
            )
        }
    }

    /** Drop a previously accepted key so the next probe is a first use again. */
    fun forgetAcceptedHostKey() = editProfile { it.copy(acceptedFingerprint = null) }

    fun runProbe() {
        val state = _uiState.value
        if (!state.canProbe) return

        val generation = state.generation
        val profile = state.profile
        val credential = state.credential(privateKeyPem)
        probeCredential = credential

        _uiState.update { it.copy(status = ProbeStatus.Running, hostKeyReview = null, keyImportProblem = null) }
        // Start through the try/finally before returning to the event loop. A
        // cancellation immediately after this method returns therefore reaches
        // a probe that already owns and will clear its credential copy.
        probeJob = viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            val result = try {
                probe.probe(profile, credential)
            } finally {
                credential.clear()
                if (probeCredential === credential) probeCredential = null
            }
            applyResult(result, generation, profile.anchor)
        }
    }

    /**
     * The generation bump is the half that matters. Cancelling the job stops
     * the transport, but a probe that had already returned when the tap landed
     * is a few instructions from publishing its answer over the words "Probe
     * cancelled" — moving the generation on is what makes that answer stale.
     */
    fun cancelProbe() {
        if (_uiState.value.status != ProbeStatus.Running) return

        stopProbe()
        _uiState.update {
            it.copy(
                status = ProbeStatus.Failed(ProbeFailure.Cancelled, "Probe cancelled."),
                generation = it.generation + 1,
            )
        }
        dropScreenSecrets()
    }

    /**
     * Publishes a probe's answer, or drops it.
     *
     * A result is only allowed to paint the screen if the form has not moved on
     * since the probe started. Anything else — a slow authenticated result
     * arriving after the destination was retargeted, a first-use review for a
     * host that is no longer in the field — is about a screen that no longer
     * exists.
     */
    private fun applyResult(result: ProbeResult, generation: Long, anchor: HostAnchor) {
        if (_uiState.value.generation != generation) return

        _uiState.update { state ->
            when (result) {
                is ProbeResult.Ok -> state.copy(
                    status = ProbeStatus.Succeeded(result.output, result.serverVersion, result.elapsedMillis),
                )

                is ProbeResult.HostKeyPending -> state.copy(
                    status = ProbeStatus.Idle,
                    hostKeyReview = PendingHostKey(result.fingerprint, result.keyType, anchor),
                )

                is ProbeResult.HostKeyMismatch -> state.copy(
                    status = ProbeStatus.KeyMismatch(result.expected, result.actual),
                    hostKeyReview = null,
                )

                is ProbeResult.Failed -> state.copy(status = ProbeStatus.Failed(result.kind, result.message))
            }
        }

        // A first use sent nothing and is one tap from a retry, so the material
        // it did not use stays. Everything else is done with it.
        if (result !is ProbeResult.HostKeyPending) dropScreenSecrets()
    }

    /**
     * Every profile edit: bump the generation and stop work aimed at the old
     * form. A review that is no longer about this host needs no clearing —
     * [SshUiState.pendingHostKey] stops returning it.
     */
    private fun editProfile(transform: (HostProfile) -> HostProfile) {
        edited = true
        val before = _uiState.value
        val updated = transform(before.profile)
        val interrupted = before.status == ProbeStatus.Running

        stopProbe()
        _uiState.update { state ->
            state.copy(
                profile = updated,
                status = ProbeStatus.Idle,
                generation = state.generation + 1,
                keyImportProblem = null,
            )
        }

        if (interrupted) dropScreenSecrets()
        if (updated != before.profile) persistProfile()
    }

    /** A credential edit supersedes a probe just as a destination edit does. */
    private fun editCredential(transform: (SshUiState) -> SshUiState) {
        edited = true
        val interrupted = _uiState.value.status == ProbeStatus.Running
        stopProbe()
        if (interrupted) dropScreenSecrets()
        _uiState.update { state ->
            transform(state).copy(
                status = ProbeStatus.Idle,
                generation = state.generation + 1,
                keyImportProblem = null,
            )
        }
    }

    private fun stopProbe() {
        probeJob?.cancel()
        probeJob = null
        // Cancellation schedules the coroutine's finally block; wipe the
        // mutable credential now so a caller that just cancelled cannot still
        // observe or reuse its probe-owned copy in that scheduling window.
        probeCredential?.clear()
        probeCredential = null
    }

    /**
     * Forgets the password, passphrase and key this screen was holding.
     *
     * Honest about what that means. The key is a `char[]`, so it is zeroed —
     * as is the copy the probe was given ([SshCredential.clear]). The password
     * and the passphrase come out of a text field as JVM `String`s: dropping
     * the reference is all anything can do with those, and it does not scrub
     * them out of the heap. What this does guarantee is that no later read of
     * this screen's state, and no snapshot taken from it, still has them.
     */
    private fun dropScreenSecrets() {
        val state = _uiState.value
        val holdsSomething = privateKeyPem != null || state.password.isNotEmpty() ||
            state.keyPassphrase.isNotEmpty() || state.privateKeyLoaded ||
            state.importedKeyName != null
        if (!holdsSomething) return

        wipePrivateKey()
        _uiState.update {
            it.copy(
                password = "",
                keyPassphrase = "",
                privateKeyLoaded = false,
                importedKeyName = null,
            )
        }
    }

    /** Serialises writes so an old suspended save cannot finish last. */
    private fun persistProfile() {
        val requestedRevision = ++persistenceRevision
        viewModelScope.launch {
            persistenceMutex.withLock {
                // A queued write is obsolete if a later form edit requested a
                // save before this one reached the store; save only the latest
                // intent. A write already in the store completes before the
                // newer revision obtains this mutex.
                if (requestedRevision == persistenceRevision) {
                    store.saveHostProfile(_uiState.value.profile)
                }
            }
        }
    }

    private fun wipePrivateKey() {
        privateKeyPem?.fill(NUL)
        privateKeyPem = null
    }

    override fun onCleared() {
        stopProbe()
        wipePrivateKey()
        super.onCleared()
    }

    companion object {
        /** Explicit NUL: an invisible space literal in a wipe is a bug in waiting. */
        private const val NUL = '\u0000'

        fun factory(store: HostProfileStore): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = SshViewModel(store) as T
            }
    }
}

/**
 * The one place a credential is built. One method, one credential, no
 * fallback: Tailscale SSH gets an empty one, so there is no code path on which
 * a secret the user typed for another method could reach a keyless probe.
 */
private fun SshUiState.credential(pem: CharArray?): SshCredential = when (profile.authMethod) {
    AuthMethod.TailscaleSsh -> SshCredential.none()
    AuthMethod.Password -> SshCredential.password(password)
    AuthMethod.PrivateKey -> SshCredential.privateKey(pem ?: CharArray(0), keyPassphrase)
}
