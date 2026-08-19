package com.hermesagent.mobile.ui.ssh

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.hermesagent.mobile.data.ssh.AuthMethod
import com.hermesagent.mobile.data.ssh.DestinationParse
import com.hermesagent.mobile.data.ssh.HostProfile
import com.hermesagent.mobile.data.ssh.HostProfileStore
import com.hermesagent.mobile.data.ssh.ProbeFailure
import com.hermesagent.mobile.data.ssh.ProbeResult
import com.hermesagent.mobile.data.ssh.SshCredential
import com.hermesagent.mobile.data.ssh.SshProbe
import com.hermesagent.mobile.data.ssh.SshjProbe
import com.hermesagent.mobile.data.ssh.parseSshDestination
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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

/** A fingerprint waiting for an explicit human decision. */
data class PendingHostKey(val fingerprint: String, val keyType: String)

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
    val status: ProbeStatus = ProbeStatus.Idle,
    val pendingHostKey: PendingHostKey? = null,
) {
    private val parsedDestination: DestinationParse get() = parseSshDestination(destination)

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
}

/**
 * Drives the SSH onboarding slice.
 *
 * The honest boundary this screen exists to state: Termux proving the host is
 * reachable does **not** hand this app Termux's keys, agent, or `~/.ssh/config`
 * — different package, different sandbox. So the app collects its own material,
 * keeps it in memory, and persists only the non-secret profile plus the
 * fingerprint the user accepted.
 */
class SshViewModel(
    private val store: HostProfileStore,
    private val probe: SshProbe = SshjProbe(),
) : ViewModel() {

    private val _uiState = MutableStateFlow(SshUiState())
    val uiState: StateFlow<SshUiState> = _uiState.asStateFlow()

    /** Held out of [SshUiState] so no PEM can be captured in a state snapshot. */
    private var privateKeyPem: String? = null
    private var probeJob: Job? = null

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
     * fingerprint accepted for the real host on the way past.
     */
    fun setDestination(value: String) {
        edited = true
        _uiState.update { it.copy(destination = value, status = ProbeStatus.Idle) }
        val parsed = parseSshDestination(value)
        if (parsed is DestinationParse.Valid) editProfile { it.withDestination(parsed.destination) }
    }

    fun setAuthMethod(method: AuthMethod) = editProfile { it.copy(authMethod = method) }

    fun setPassword(value: String) = _uiState.update { it.copy(password = value, status = ProbeStatus.Idle) }

    fun setKeyPassphrase(value: String) = _uiState.update { it.copy(keyPassphrase = value) }

    /** Called with the contents of a document the user picked through SAF. */
    fun importPrivateKey(pem: String, displayName: String) {
        edited = true
        privateKeyPem = pem
        _uiState.update {
            it.copy(
                profile = it.profile.copy(authMethod = AuthMethod.PrivateKey, importedKeyName = displayName),
                privateKeyLoaded = true,
                status = ProbeStatus.Idle,
            )
        }
    }

    fun forgetPrivateKey() {
        privateKeyPem = null
        _uiState.update {
            it.copy(
                profile = it.profile.copy(importedKeyName = null),
                privateKeyLoaded = false,
                keyPassphrase = "",
            )
        }
    }

    /**
     * Accept a pending first-use fingerprint. This is the *only* way a
     * fingerprint is ever trusted, and it is always a deliberate tap.
     */
    fun acceptPendingHostKey() {
        val pending = _uiState.value.pendingHostKey ?: return
        editProfile { it.copy(acceptedFingerprint = pending.fingerprint) }
        _uiState.update { it.copy(pendingHostKey = null, status = ProbeStatus.Idle) }
    }

    fun dismissPendingHostKey() = _uiState.update { it.copy(pendingHostKey = null) }

    /** Drop a previously accepted key so the next probe is a first use again. */
    fun forgetAcceptedHostKey() = editProfile { it.copy(acceptedFingerprint = null) }

    fun runProbe() {
        val state = _uiState.value
        if (!state.canProbe) return

        _uiState.update { it.copy(status = ProbeStatus.Running, pendingHostKey = null) }
        probeJob = viewModelScope.launch {
            val credential = state.credential(privateKeyPem)
            val result = try {
                probe.probe(state.profile, credential)
            } finally {
                credential.clear()
            }
            applyResult(result)
        }
    }

    fun cancelProbe() {
        probeJob?.cancel()
        probeJob = null
        _uiState.update {
            if (it.status == ProbeStatus.Running) {
                it.copy(status = ProbeStatus.Failed(ProbeFailure.Cancelled, "Probe cancelled."))
            } else {
                it
            }
        }
    }

    private fun applyResult(result: ProbeResult) {
        _uiState.update { state ->
            when (result) {
                is ProbeResult.Ok -> state.copy(
                    status = ProbeStatus.Succeeded(result.output, result.serverVersion, result.elapsedMillis),
                )

                is ProbeResult.HostKeyPending -> state.copy(
                    status = ProbeStatus.Idle,
                    pendingHostKey = PendingHostKey(result.fingerprint, result.keyType),
                )

                is ProbeResult.HostKeyMismatch -> state.copy(
                    status = ProbeStatus.KeyMismatch(result.expected, result.actual),
                    pendingHostKey = null,
                )

                is ProbeResult.Failed -> state.copy(status = ProbeStatus.Failed(result.kind, result.message))
            }
        }
    }

    private fun editProfile(transform: (HostProfile) -> HostProfile) {
        edited = true
        val updated = transform(_uiState.value.profile)
        _uiState.update { it.copy(profile = updated, status = ProbeStatus.Idle) }
        viewModelScope.launch { store.saveHostProfile(updated) }
    }

    override fun onCleared() {
        privateKeyPem = null
        super.onCleared()
    }

    companion object {
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
private fun SshUiState.credential(pem: String?): SshCredential = when (profile.authMethod) {
    AuthMethod.TailscaleSsh -> SshCredential.none()
    AuthMethod.Password -> SshCredential.password(password)
    AuthMethod.PrivateKey -> SshCredential.privateKey(pem.orEmpty(), keyPassphrase)
}
