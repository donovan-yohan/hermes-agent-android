package com.hermesagent.mobile.ui.ssh

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.hermesagent.mobile.data.ssh.AuthMethod
import com.hermesagent.mobile.data.ssh.HostProfile
import com.hermesagent.mobile.data.ssh.ProbeFailure
import com.hermesagent.mobile.ui.SshActions
import com.hermesagent.mobile.ui.common.ErrorState
import com.hermesagent.mobile.ui.common.Hairline
import com.hermesagent.mobile.ui.common.LogView
import com.hermesagent.mobile.ui.common.PrimaryButton
import com.hermesagent.mobile.ui.common.ScaffoldRow
import com.hermesagent.mobile.ui.common.StatusDot
import com.hermesagent.mobile.ui.common.SectionLabel
import com.hermesagent.mobile.ui.common.SegmentedControl
import com.hermesagent.mobile.ui.common.TextButton
import com.hermesagent.mobile.ui.common.WorkingDots
import com.hermesagent.mobile.ui.theme.AppearanceSelection
import com.hermesagent.mobile.ui.theme.HermesTheme
import com.hermesagent.mobile.ui.theme.HermesThemeMode

/**
 * SSH onboarding + probe.
 *
 * The copy here is load-bearing, not decoration. It is the screen where the
 * app tells the truth about the Termux question: reachability transfers,
 * credentials do not, because Android sandboxes packages from each other.
 */
@Composable
fun SshScreen(
    state: SshUiState,
    actions: SshActions,
    modifier: Modifier = Modifier,
) {
    val tokens = HermesTheme.tokens
    val inset = HermesTheme.spacing.pageInset

    Column(
        modifier
            .fillMaxSize()
            .background(tokens.chatSurface)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = inset, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "Reaching this box from Termux proves the network route and that sshd accepts " +
                    "your account. It does not hand this app Termux's keys, agent, or " +
                    "~/.ssh/config — those live in another app's sandbox and are unreadable here. " +
                    "So Hermes asks for its own credentials.",
                style = HermesTheme.type.caption,
                color = tokens.textTertiary,
            )
        }

        Hairline()

        LabelledField(
            label = "Host",
            value = state.profile.host,
            placeholder = "hermes-box.local",
            onValueChange = actions.onHostChange,
        )
        LabelledField(
            label = "Port",
            value = state.profile.port.takeIf { it > 0 }?.toString().orEmpty(),
            placeholder = "22",
            onValueChange = actions.onPortChange,
            keyboardType = KeyboardType.Number,
        )
        LabelledField(
            label = "Username",
            value = state.profile.username,
            placeholder = "you",
            onValueChange = actions.onUsernameChange,
        )

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionLabel("Authentication")
            SegmentedControl(
                options = AuthMethod.entries,
                selected = state.profile.authMethod,
                label = { if (it == AuthMethod.Password) "Password" else "Private key" },
                onSelect = actions.onAuthMethodChange,
            )

            when (state.profile.authMethod) {
                AuthMethod.Password -> LabelledField(
                    label = "Password",
                    value = state.password,
                    placeholder = "",
                    onValueChange = actions.onPasswordChange,
                    secret = true,
                )

                AuthMethod.PrivateKey -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (state.privateKeyLoaded) {
                        ScaffoldRow(label = "Key loaded: ${state.profile.importedKeyName ?: "unnamed"}")
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

            Text(
                "Credentials stay in memory for this screen only. Nothing secret is written to " +
                    "disk, logged, or backed up. Only host, port, username, method and the " +
                    "fingerprint you accept are saved.",
                style = HermesTheme.type.scaffoldMeta,
                color = tokens.scaffoldMeta,
            )
        }

        if (state.validationErrors.isNotEmpty()) {
            ErrorState(
                title = "Fix these first",
                description = state.validationErrors.joinToString("\n"),
            )
        }

        state.pendingHostKey?.let { pending ->
            HostKeyReview(
                fingerprint = pending.fingerprint,
                keyType = pending.keyType,
                onAccept = actions.onAcceptHostKey,
                onDismiss = actions.onDismissHostKey,
            )
        }

        state.profile.acceptedFingerprint?.let { accepted ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SectionLabel("Trusted host key")
                LogView(accepted)
                TextButton("Forget this host key", actions.onForgetHostKey, color = tokens.textTertiary)
            }
        }

        ProbeSection(state = state, onProbe = actions.onProbe, onCancel = actions.onCancelProbe)
    }
}

@Composable
private fun ProbeSection(state: SshUiState, onProbe: () -> Unit, onCancel: () -> Unit) {
    val tokens = HermesTheme.tokens

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel("Probe")
        Text(
            "Connects, verifies the host key, authenticates, runs " +
                "`printf HERMES_ANDROID_SSH_OK`, and disconnects. Nothing else.",
            style = HermesTheme.type.caption,
            color = tokens.textTertiary,
        )

        when (val status = state.status) {
            ProbeStatus.Idle -> PrimaryButton(
                label = "Run probe",
                onClick = onProbe,
                enabled = state.canProbe,
                modifier = Modifier.fillMaxWidth(),
            )

            ProbeStatus.Running -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                WorkingDots()
                Text("Connecting…", style = HermesTheme.type.caption, color = tokens.textSecondary)
                TextButton("Cancel", onCancel, color = tokens.textTertiary)
            }

            is ProbeStatus.Succeeded -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ScaffoldRow(
                    label = "Probe succeeded",
                    meta = "${status.elapsedMillis}ms",
                    leading = {
                        StatusDot(
                            color = tokens.statusUnread,
                            filled = true,
                            contentDescription = null,
                            size = 7.dp,
                        )
                    },
                )
                LogView("${status.serverVersion}\n${status.output}")
                PrimaryButton("Run probe again", onProbe, Modifier.fillMaxWidth(), state.canProbe)
            }

            is ProbeStatus.Failed -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ErrorState(title = status.kind.title(), description = status.message)
                PrimaryButton("Try again", onProbe, Modifier.fillMaxWidth(), state.canProbe)
            }

            is ProbeStatus.KeyMismatch -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ErrorState(
                    title = "Host key CHANGED — refusing to connect",
                    description = "The key this host presents is not the one you trusted. That means " +
                        "the server was rebuilt, or someone is between you and it. Nothing was sent. " +
                        "Verify out of band, then forget the stored key above and probe again.",
                )
                LogView("expected ${status.expected}\npresented ${status.presented}")
            }
        }
    }
}

private fun ProbeFailure.title(): String = when (this) {
    ProbeFailure.Unreachable -> "Could not reach the host"
    ProbeFailure.AuthFailed -> "Authentication was refused"
    ProbeFailure.Timeout -> "The host did not answer"
    ProbeFailure.Cancelled -> "Probe cancelled"
    ProbeFailure.Unknown -> "The probe failed"
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
            "Hermes has never seen this host's key. Compare the fingerprint with what the " +
                "server reports (`ssh-keygen -lf /etc/ssh/ssh_host_${keyType.removePrefix("ssh-")}_key.pub`) " +
                "before accepting. Nothing has been authenticated yet.",
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

@Composable
private fun LabelledField(
    label: String,
    value: String,
    placeholder: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
    secret: Boolean = false,
) {
    val tokens = HermesTheme.tokens
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SectionLabel(label)
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .border(1.dp, tokens.strokeSecondary, RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 11.dp),
        ) {
            if (value.isEmpty() && placeholder.isNotEmpty()) {
                Text(placeholder, style = HermesTheme.type.body, color = tokens.textQuaternary)
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = HermesTheme.type.body.copy(color = tokens.textPrimary),
                cursorBrush = SolidColor(tokens.accent),
                visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (secret) KeyboardType.Password else keyboardType,
                    imeAction = ImeAction.Next,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = label },
            )
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
                profile = HostProfile(host = "hermes-box.local", username = "hermes"),
                password = "••••",
                pendingHostKey = PendingHostKey(
                    fingerprint = "SHA256:0pXQ0M2fEXAMPLEfingerprintDEMOonlyNOTreal01",
                    keyType = "ssh-ed25519",
                ),
            ),
            actions = SshActions(),
        )
    }
}
