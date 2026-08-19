package com.hermesagent.mobile.ui.ssh

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.Window
import android.view.WindowManager
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.hermesagent.mobile.data.ssh.AuthMethod
import com.hermesagent.mobile.data.ssh.HostAnchor
import com.hermesagent.mobile.data.ssh.HostProfile
import com.hermesagent.mobile.data.ssh.ProbeFailure
import com.hermesagent.mobile.data.ssh.SshProbe
import com.hermesagent.mobile.data.ssh.hostKeyPublicKeyPath
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

    SecureWhileVisible()

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
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "Reaching this box from Termux proves the network route and that sshd accepts " +
                    "your account. It does not hand this app Termux's keys, agent, or " +
                    "~/.ssh/config — those live in another app's sandbox and are unreadable here. " +
                    "So Hermes brings its own: credentials it holds in memory, or Tailscale SSH, " +
                    "which needs none.",
                style = HermesTheme.type.caption,
                color = tokens.textTertiary,
            )
        }

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
                "The same thing you would type after `ssh`. Port 22 unless you add one " +
                    "(`you@hermes-box:2222`); an IPv6 address goes in brackets. On a tailnet " +
                    "the short MagicDNS name works from any signed-in device.",
                style = HermesTheme.type.scaffoldMeta,
                color = tokens.scaffoldMeta,
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionLabel("Authentication")
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
                    "No password, no key, nothing secret sent: the tailnet already " +
                        "authenticated this device, so the SSH layer uses auth type `none`. " +
                        "That works only if the target runs Tailscale SSH *and* your tailnet " +
                        "SSH policy allows this connection. Sharing a tailnet by itself gives " +
                        "you the route and the name — a box running ordinary OpenSSH over " +
                        "Tailscale still wants a password or a key.",
                    style = HermesTheme.type.caption,
                    color = tokens.textSecondary,
                )

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
                "Credentials stay in memory for this screen only, and are dropped as soon as a " +
                    "probe has used them — a probe that stops at the fingerprint review has not, " +
                    "so accepting and retrying does not ask again. Nothing secret is written to " +
                    "disk, logged, or backed up. Only host, port, username, method and the " +
                    "fingerprint you accept are saved. This screen is excluded from screenshots " +
                    "and from the recent-apps preview.",
                style = HermesTheme.type.scaffoldMeta,
                color = tokens.scaffoldMeta,
            )
        }

        state.keyImportProblem?.let { problem ->
            ErrorState(title = "That key was not imported", description = problem.message())
        }

        state.destinationError?.let { problem ->
            ErrorState(title = "Fix the destination", description = problem)
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
    ProbeFailure.TailscaleSshRefused -> "Reachable, but not over Tailscale SSH"
    ProbeFailure.Timeout -> "The host did not answer"
    ProbeFailure.Cancelled -> "Probe cancelled"
    ProbeFailure.CryptoUnavailable -> "This device cannot run the handshake"
    ProbeFailure.BadCommandResult -> "Connected, but the probe command did not check out"
    ProbeFailure.Unknown -> "The probe failed"
}

/**
 * Keeps this surface out of screenshots, screen recordings, casts and the
 * recent-apps preview for exactly as long as it is on screen.
 *
 * Scoped to the composable rather than set once on the Activity: this is the
 * only surface that holds a password, a passphrase or a host-key decision, and
 * a process-wide `FLAG_SECURE` would also block screenshots of a chat transcript
 * nobody asked to protect.
 */
@Composable
private fun SecureWhileVisible() {
    val window = LocalContext.current.findActivityWindow()

    DisposableEffect(window) {
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
}

/** Null in a `@Preview` or any other host that is not an Activity. */
private tailrec fun Context.findActivityWindow(): Window? = when (this) {
    is Activity -> window
    is ContextWrapper -> baseContext.findActivityWindow()
    else -> null
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
            buildString {
                append("Hermes has never seen this host's key. Compare the fingerprint with what ")
                append("the server reports")
                // The wire name is not the file name — `ecdsa-sha2-nistp256`
                // lives in `ssh_host_ecdsa_key.pub`. A type with no known file
                // gets no command rather than a path that is not there.
                hostKeyPublicKeyPath(keyType)?.let { append(" (`ssh-keygen -lf $it`)") }
                append(" before accepting. Nothing has been authenticated yet.")
            },
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
                    .heightIn(min = 48.dp)
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
                    // A host name is not a sentence: an IME that capitalises or
                    // autocorrects it produces a destination that cannot resolve.
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
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
