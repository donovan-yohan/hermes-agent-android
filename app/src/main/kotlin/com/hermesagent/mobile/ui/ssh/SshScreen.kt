package com.hermesagent.mobile.ui.ssh

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.hermesagent.mobile.data.ssh.AuthMethod
import com.hermesagent.mobile.data.ssh.HostAnchor
import com.hermesagent.mobile.data.ssh.HostProfile
import com.hermesagent.mobile.data.ssh.ProbeFailure
import com.hermesagent.mobile.data.ssh.SshProbe
import com.hermesagent.mobile.data.gateway.GatewayConnectionStatus
import com.hermesagent.mobile.ui.SshActions
import com.hermesagent.mobile.ui.common.ErrorState
import com.hermesagent.mobile.ui.common.Hairline
import com.hermesagent.mobile.ui.common.LabelledField
import com.hermesagent.mobile.ui.common.LogView
import com.hermesagent.mobile.ui.common.PrimaryButton
import com.hermesagent.mobile.ui.common.ScaffoldRow
import com.hermesagent.mobile.ui.common.SecureScreenLifetime
import com.hermesagent.mobile.ui.common.StatusDot
import com.hermesagent.mobile.ui.common.SectionLabel
import com.hermesagent.mobile.ui.common.SegmentedControl
import com.hermesagent.mobile.ui.common.TextButton
import com.hermesagent.mobile.ui.common.WorkingDots
import com.hermesagent.mobile.ui.theme.AppearanceSelection
import com.hermesagent.mobile.ui.theme.HermesTheme
import com.hermesagent.mobile.ui.theme.HermesThemeMode

/** Section anchors, so a test can pin the order the screen reads in. */
internal const val SECTION_AUTHENTICATION = "ssh-section-authentication"
internal const val SECTION_HOST_KEY = "ssh-section-host-key"
internal const val SECTION_PROBE = "ssh-section-probe"

/**
 * Connect via SSH, adapted from Hermes Desktop's remote connection task at the
 * frozen `f82f2dba` authority contract. Security mechanics stay in code/docs;
 * the primary surface is task, state, outcome, and next action.
 */
@Composable
fun SshScreen(
    state: SshUiState,
    actions: SshActions,
    modifier: Modifier = Modifier,
    /**
     * Page content that belongs above this form — the Gateways page's
     * Connection mode cards. Desktop scrolls the mode grid with the rest of
     * the page (`gateway-settings.tsx:1044-1084` @ `f82f2dba`) rather than
     * pinning it, and four stacked cards on a phone are far too tall to pin.
     */
    header: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit = {},
    /** Page content that belongs below this form — the Gateways page's connections registry. */
    footer: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit = {},
) {
    val tokens = HermesTheme.tokens
    val inset = HermesTheme.spacing.pageInset

    SecureScreenLifetime(onLeave = actions.onLeaveScreen)

    Column(
        modifier
            .fillMaxSize()
            .background(tokens.chatSurface)
            // Edge to edge means the keyboard and the gesture bar draw over
            // this column, and the actions that matter are at the bottom of it.
            // `imePadding` consumes the IME inset first, so the navigation-bar
            // pass only adds what is left rather than doubling it.
            .imePadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = inset, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        header()

        Text(
            "Connect this app to a remote Hermes Gateway over SSH.",
            style = HermesTheme.type.caption,
            color = tokens.textSecondary,
        )

        Hairline()

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            LabelledField(
                label = "SSH destination",
                value = state.destination,
                placeholder = "you@hermes-box",
                onValueChange = actions.onDestinationChange,
                keyboardType = KeyboardType.Email,
            )
            Text(
                "Use user@host. Add :port only when the host does not use port 22.",
                style = HermesTheme.type.scaffoldMeta,
                color = tokens.scaffoldMeta,
            )
            state.destinationError?.let { problem ->
                ErrorState(title = "Fix the destination", description = problem)
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            LabelledField(
                label = "Hermes profile (optional)",
                value = state.profile.remoteHermesProfile,
                placeholder = "default",
                onValueChange = actions.onRemoteProfileChange,
            )
            state.remoteProfileError?.let { problem ->
                ErrorState(title = "Check the profile name", description = problem)
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionLabel("Authentication", Modifier.testTag(SECTION_AUTHENTICATION))
            SegmentedControl(
                options = AuthMethod.entries,
                selected = state.profile.authMethod,
                label = AuthMethod::label,
                onSelect = actions.onAuthMethodChange,
                // A segment read out as just "Password" is ambiguous with the
                // field below it, to a screen reader and to a test.
                describe = { "Authenticate with ${it.label()}" },
            )

            when (state.profile.authMethod) {
                AuthMethod.TailscaleSsh -> Text(
                    "Uses Tailscale SSH; the host and tailnet policy must allow this connection.",
                    style = HermesTheme.type.caption,
                    color = tokens.textSecondary,
                )

                AuthMethod.Password -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    LabelledField(
                        label = "Password",
                        value = state.password,
                        placeholder = "",
                        onValueChange = actions.onPasswordChange,
                        secret = true,
                    )
                    Text("Used for this connection only.", style = HermesTheme.type.scaffoldMeta, color = tokens.scaffoldMeta)
                }

                AuthMethod.PrivateKey -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (state.privateKeyLoaded) {
                        ScaffoldRow(label = "Key loaded: ${state.importedKeyName ?: "unnamed"}")
                        LabelledField(
                            label = "Passphrase (if the key has one)",
                            value = state.keyPassphrase,
                            placeholder = "",
                            onValueChange = actions.onPassphraseChange,
                            secret = true,
                        )
                        TextButton("Forget key", actions.onForgetKey, color = tokens.destructive)
                    } else {
                        TextButton("Import an OpenSSH private key…", actions.onImportKey)
                    }
                }
            }

            // Outside the `when`: a refused document is reported before the
            // method switches, so this must be visible whichever is selected.
            state.keyImportProblem?.let { problem ->
                ErrorState(title = "That key was not imported", description = problem.message())
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionLabel("Host key", Modifier.testTag(SECTION_HOST_KEY))
            Text(
                "Review the host key on first connect; a changed key is blocked.",
                style = HermesTheme.type.caption,
                color = tokens.textTertiary,
            )

            state.pendingHostKey?.let { pending ->
                HostKeyReview(
                    fingerprint = pending.fingerprint,
                    keyType = pending.keyType,
                    onAccept = actions.onAcceptHostKey,
                    onDismiss = actions.onDismissHostKey,
                )
            }

            state.profile.acceptedFingerprint?.let {
                ScaffoldRow(label = "Host key trusted")
                TextButton("Forget host key", actions.onForgetHostKey, color = tokens.textTertiary)
            }
        }

        GatewaySection(state, actions)
        DiagnosticSection(state = state, onProbe = actions.onProbe, onCancel = actions.onCancelProbe)
        footer()
    }
}

@Composable
private fun GatewaySection(state: SshUiState, actions: SshActions) {
    val tokens = HermesTheme.tokens
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel("Gateway", Modifier.testTag(SECTION_PROBE))
        ScaffoldRow(
            label = state.connection.status.label,
            leading = {
                StatusDot(
                    color = when (state.connection.status) {
                        GatewayConnectionStatus.Connected -> tokens.statusUnread
                        GatewayConnectionStatus.Connecting -> tokens.statusWorking
                        GatewayConnectionStatus.NeedsAttention -> tokens.destructive
                        GatewayConnectionStatus.Disconnected -> tokens.statusIdle
                    },
                    filled = state.connection.status != GatewayConnectionStatus.Disconnected,
                    contentDescription = null,
                    size = 7.dp,
                )
            },
        )
        if (state.connection.status == GatewayConnectionStatus.NeedsAttention) {
            ErrorState(
                title = "Gateway needs attention",
                description = state.connection.message ?: "Check the connection settings and try again.",
            )
        }

        when (state.connection.status) {
            GatewayConnectionStatus.Connected ->
                PrimaryButton("Disconnect", actions.onDisconnect, Modifier.fillMaxWidth())

            GatewayConnectionStatus.Connecting -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                WorkingDots()
                Text("Connecting…", style = HermesTheme.type.caption, color = tokens.textSecondary)
                TextButton("Cancel", actions.onDisconnect, color = tokens.textTertiary)
            }

            GatewayConnectionStatus.Disconnected,
            GatewayConnectionStatus.NeedsAttention,
            -> PrimaryButton("Connect", actions.onConnect, Modifier.fillMaxWidth(), state.canConnect)
        }

        if (
            state.connection.status != GatewayConnectionStatus.Connected &&
            state.connection.status != GatewayConnectionStatus.Connecting
        ) {
            state.connectionCredentialPrompt?.let { prompt ->
                Text(prompt, style = HermesTheme.type.caption, color = tokens.textSecondary)
            }
        }

        if (state.status is ProbeStatus.KeyMismatch) {
            ErrorState(
                title = "Host key changed",
                description = "Verify the host, then forget the saved key before reconnecting.",
            )
        }
    }
}

@Composable
private fun DiagnosticSection(state: SshUiState, onProbe: () -> Unit, onCancel: () -> Unit) {
    val tokens = HermesTheme.tokens
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionLabel("Diagnostics")

        when (val status = state.status) {
            ProbeStatus.Idle -> TextButton(
                label = "Test SSH only",
                onClick = onProbe,
                enabled = state.canProbe,
                color = tokens.textSecondary,
            )

            ProbeStatus.Running -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                WorkingDots()
                Text("Testing SSH…", style = HermesTheme.type.caption, color = tokens.textSecondary)
                TextButton("Cancel", onCancel, color = tokens.textTertiary)
            }

            is ProbeStatus.Succeeded -> ScaffoldRow(label = "SSH test passed", meta = "${status.elapsedMillis}ms")

            is ProbeStatus.Failed -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ErrorState(title = status.kind.title(), description = status.kind.nextAction())
                TextButton("Test again", onProbe, enabled = state.canProbe, color = tokens.textSecondary)
            }

            is ProbeStatus.KeyMismatch -> Unit
        }
    }
}

private fun ProbeFailure.title(): String = when (this) {
    ProbeFailure.Unreachable -> "Could not reach the host"
    ProbeFailure.AuthFailed -> "Authentication was refused"
    ProbeFailure.TailscaleSshRefused -> "Reachable, but not over Tailscale SSH"
    ProbeFailure.Timeout -> "The host did not answer"
    ProbeFailure.Cancelled -> "SSH test cancelled"
    ProbeFailure.CryptoUnavailable -> "This device cannot run the handshake"
    ProbeFailure.BadCommandResult -> "Connected, but the probe command did not check out"
    ProbeFailure.Unknown -> "The SSH test failed"
}

private fun ProbeFailure.nextAction(): String = when (this) {
    ProbeFailure.Unreachable -> "Check the destination and network, then try again."
    ProbeFailure.AuthFailed -> "Check the selected credentials and try again."
    ProbeFailure.TailscaleSshRefused -> "Enable and allow Tailscale SSH, or choose another method."
    ProbeFailure.Timeout -> "Check the host and network, then try again."
    ProbeFailure.Cancelled -> "Run the test again when you are ready."
    ProbeFailure.CryptoUnavailable -> "Update the app or use another supported device."
    ProbeFailure.BadCommandResult -> "Check the account's login shell and try again."
    ProbeFailure.Unknown -> "Check the connection settings and try again."
}


/** Short enough for a third of a segmented control, and still the real name. */
private fun AuthMethod.label(): String = when (this) {
    AuthMethod.TailscaleSsh -> "Tailscale SSH"
    AuthMethod.Password -> "Password"
    AuthMethod.PrivateKey -> "Private key"
}

/**
 * First-use review. Destructive-styled, explicit, and the only path to trust.
 * The fingerprint is shown in `ssh-keygen -lf` format so it can be compared
 * with what the server prints.
 */
@Composable
private fun HostKeyReview(
    fingerprint: String,
    keyType: String,
    onAccept: () -> Unit,
    onDismiss: () -> Unit,
) {
    val tokens = HermesTheme.tokens
    Column(
        Modifier
            .fillMaxWidth()
            .border(1.dp, tokens.composerRing, RoundedCornerShape(10.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("First connection to this host", style = HermesTheme.type.bodyStrong, color = tokens.textPrimary)
        Text(
            "Compare this fingerprint with the host before accepting.",
            style = HermesTheme.type.caption,
            color = tokens.textSecondary,
        )
        LogView("$keyType\n$fingerprint")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PrimaryButton("Accept this key", onAccept)
            TextButton("Not now", onDismiss, color = tokens.textTertiary)
        }
    }
}

@Preview(name = "SSH · first use", widthDp = 412, heightDp = 892)
@Composable
private fun SshPreviewFirstUse() {
    val selection = AppearanceSelection("nous", HermesThemeMode.Dark)
    HermesTheme(selection) {
        SshScreen(
            state = SshUiState(
                profile = HostProfile(
                    host = "hermes-box.local",
                    username = "hermes",
                    authMethod = AuthMethod.Password,
                ),
                destination = "hermes@hermes-box.local",
                password = "••••",
                hostKeyReview = PendingHostKey(
                    fingerprint = "SHA256:0pXQ0M2fEXAMPLEfingerprintDEMOonlyNOTreal01",
                    keyType = "ssh-ed25519",
                    anchor = HostAnchor("hermes-box.local", 22),
                ),
            ),
            actions = SshActions(),
        )
    }
}

/**
 * The case this screen exists to explain: on the tailnet, name resolves, host
 * key trusted — and the target is still ordinary OpenSSH.
 */
@Preview(name = "SSH · Tailscale SSH refused", widthDp = 412, heightDp = 892)
@Composable
private fun SshPreviewTailscaleRefused() {
    val selection = AppearanceSelection("nous", HermesThemeMode.Dark)
    HermesTheme(selection) {
        SshScreen(
            state = SshUiState(
                profile = HostProfile(
                    host = "hermes-box",
                    username = "hermes",
                    acceptedFingerprint = "SHA256:0pXQ0M2fEXAMPLEfingerprintDEMOonlyNOTreal01",
                ),
                destination = "hermes@hermes-box",
                status = ProbeStatus.Failed(
                    ProbeFailure.TailscaleSshRefused,
                    SshProbe.TAILSCALE_SSH_REFUSED,
                ),
            ),
            actions = SshActions(),
        )
    }
}
