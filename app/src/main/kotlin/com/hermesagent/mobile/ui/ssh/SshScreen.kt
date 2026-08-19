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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
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

/** Section anchors, so a test can pin the order the screen reads in. */
internal const val SECTION_AUTHENTICATION = "ssh-section-authentication"
internal const val SECTION_HOST_KEY = "ssh-section-host-key"
internal const val SECTION_PROBE = "ssh-section-probe"

/**
 * SSH onboarding + probe — Desktop's *Connect via SSH*, minus everything Phase 1
 * does not have (`apps/desktop/src/i18n/en.ts:866-874` @ `f82f2dba`).
 *
 * The copy is load-bearing, not decoration, and it carries four claims the code
 * has to keep true: this build tests SSH access and nothing more; Android
 * sandboxes packages, so Termux's SSH files are unreachable here; the first host
 * key is reviewed and pinned and a change fails closed; and credentials live in
 * memory for this screen only. The last of those is why [SecureScreenLifetime]
 * exists — the ViewModel outlives the screen, so leaving has to end the lifetime
 * the copy promises.
 */
@Composable
fun SshScreen(
    state: SshUiState,
    actions: SshActions,
    modifier: Modifier = Modifier,
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
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "Tests SSH access to a Hermes host. Launching Hermes there and tunneling the " +
                    "gateway are not implemented yet.",
                style = HermesTheme.type.caption,
                color = tokens.textSecondary,
            )
            Text(
                "Reaching the host from Termux proves the route, not this app's access: Android " +
                    "sandboxes packages, so Termux's keys, agent and ~/.ssh/config are unreadable " +
                    "here. Hermes brings its own — credentials it holds in memory, or Tailscale " +
                    "SSH, which needs none.",
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
                "`user@host`, as you would type it after `ssh`. Port 22 unless you add one " +
                    "(`you@hermes-box:2222`); an IPv6 address goes in brackets.",
                style = HermesTheme.type.scaffoldMeta,
                color = tokens.scaffoldMeta,
            )
            state.destinationError?.let { problem ->
                ErrorState(title = "Fix the destination", description = problem)
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
                    "Nothing to type: the tailnet already authenticated this device, so SSH " +
                        "uses auth type `none`. It works only if the target runs Tailscale SSH " +
                        "and your tailnet SSH policy allows this connection — a box running " +
                        "ordinary OpenSSH over Tailscale still wants a password or a key.",
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

            // Outside the `when`: a refused document is reported before the
            // method switches, so this must be visible whichever is selected.
            state.keyImportProblem?.let { problem ->
                ErrorState(title = "That key was not imported", description = problem.message())
            }

            Text(
                "Credentials stay in memory for this screen and are dropped when a probe uses " +
                    "them or when you leave. A probe that stops at the fingerprint review has " +
                    "not used them, so accepting and retrying does not ask again. Nothing " +
                    "secret is written to disk, logged, or backed up: only host, port, " +
                    "username, method and the fingerprint you accept are saved. Screenshots and " +
                    "the recent-apps preview are blocked here.",
                style = HermesTheme.type.scaffoldMeta,
                color = tokens.scaffoldMeta,
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionLabel("Host key", Modifier.testTag(SECTION_HOST_KEY))
            Text(
                "The first key a host presents is reviewed by you and pinned; a later change " +
                    "fails closed, and there is no accept path for it here. Tailscale SSH is " +
                    "reviewed the same way — this app cannot read Tailscale's known_hosts.",
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

            state.profile.acceptedFingerprint?.let { accepted ->
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
        SectionLabel("Probe", Modifier.testTag(SECTION_PROBE))
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
 * One effect owns both halves of "this screen is the protected one".
 *
 * **Secure window.** `FLAG_SECURE` keeps the surface out of screenshots, screen
 * recordings, casts and the recent-apps preview for exactly as long as it is
 * composed. Scoped here rather than set once on the Activity: this is the only
 * surface holding a password, a passphrase or a host-key decision, and a
 * process-wide flag would also black out a chat transcript nobody asked to
 * protect.
 *
 * **Secret lifetime.** [onLeave] ends the screen's credential lifetime. It is in
 * this effect, and ahead of `clearFlags`, on purpose. The ViewModel is
 * Activity-scoped while this screen is one destination inside a single
 * composition, so navigating away destroys nothing by itself; putting the wipe
 * in a second `DisposableEffect` would work but would leave the order to
 * Compose's disposal sequence rather than stating it. Two statements, one after
 * the other, is the whole guarantee: nothing secret is still held once the
 * window has stopped being a secure one.
 *
 * [onLeave] is read through [rememberUpdatedState] so a recomposition with a new
 * lambda does not re-run the effect — re-running it would clear and re-add the
 * flag, and would fire a wipe while the screen is still on screen.
 */
@Composable
private fun SecureScreenLifetime(onLeave: () -> Unit) {
    val window = LocalContext.current.findActivityWindow()
    val leave by rememberUpdatedState(onLeave)

    DisposableEffect(window) {
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose {
            leave()
            window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
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

/**
 * A labelled text entry whose *editor* is the touch target.
 *
 * The shape this replaces drew a 48 dp bordered [Box] and put a bare
 * [BasicTextField] inside it. That looks right and measures wrong: the box is
 * decoration with no semantics, so the focusable, TalkBack-reachable,
 * tap-to-focus node was the text line itself — about 22 dp, less than half the
 * Android floor, on a screen where two of the four fields are credentials.
 *
 * Handing the border and the padding to `decorationBox` inverts it. The chrome
 * becomes the field's own decoration, so the node that owns the minimum height
 * is the node a finger and an accessibility service both address, and the
 * height is stated once — `HermesTheme.spacing.touchTarget` — rather than on a
 * wrapper that only looks like the target. `CenterStart` is what keeps typed
 * and placeholder text on the same optical line once the box is taller than a
 * line of body text.
 */
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
                .heightIn(min = HermesTheme.spacing.touchTarget)
                .semantics { contentDescription = label },
            decorationBox = { editor ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .border(1.dp, tokens.strokeSecondary, RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (value.isEmpty() && placeholder.isNotEmpty()) {
                        Text(placeholder, style = HermesTheme.type.body, color = tokens.textQuaternary)
                    }
                    editor()
                }
            },
        )
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
