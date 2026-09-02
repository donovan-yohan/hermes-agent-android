package com.hermesagent.mobile.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hermesagent.mobile.data.gateway.GatewayConnectionStatus
import com.hermesagent.mobile.data.gateway.SignInOrigin
import com.hermesagent.mobile.ui.appearance.AppearanceScreen
import com.hermesagent.mobile.ui.chat.ChatScreen
import com.hermesagent.mobile.ui.chat.ChatUiState
import com.hermesagent.mobile.ui.common.Hairline
import com.hermesagent.mobile.ui.common.QuietIconButton
import com.hermesagent.mobile.ui.gateway.ConnectionsUiState
import com.hermesagent.mobile.ui.gateway.GatewayScreen
import com.hermesagent.mobile.ui.gateway.GatewaySettingsUiState
import com.hermesagent.mobile.ui.profiles.ProfilesScreen
import com.hermesagent.mobile.ui.profiles.profileCount
import com.hermesagent.mobile.ui.relay.RelayScreen
import com.hermesagent.mobile.ui.relay.RelayUiState
import com.hermesagent.mobile.ui.sessions.ConnectionSwitcherBar
import com.hermesagent.mobile.ui.settings.SettingsScreen
import com.hermesagent.mobile.ui.system.SystemActions
import com.hermesagent.mobile.ui.system.SystemCopy
import com.hermesagent.mobile.ui.system.SystemScreen
import com.hermesagent.mobile.ui.system.SystemUiState
import com.hermesagent.mobile.ui.system.UpdatesOverlay
import com.hermesagent.mobile.ui.ssh.SshUiState
import com.hermesagent.mobile.ui.theme.AppearanceSelection
import com.hermesagent.mobile.ui.theme.HermesTheme

/**
 * Chat is home. Settings has two short child surfaces, so a saved destination
 * is sufficient without a navigation graph.
 */
enum class HermesDestination { Chat, Settings, Appearance, Gateways, System, Relay, Profiles }

/**
 * A navigation ask from outside the composition — today, a sign-in coming back
 * from the browser into the surface it was started from.
 *
 * [token] is what makes two identical asks two asks. Without it a second
 * hand-back to a destination the person has since navigated away from would be
 * the same value as the first and change nothing, which is the failure this
 * exists to fix rather than a smaller version of it.
 */
data class HermesNavigationAsk(val destination: HermesDestination, val token: Long)

/**
 * What a finished sign-in asks the shell for, or null when it asks for nothing.
 *
 * The whole navigation rule of the hand-back, in one place that can be read and
 * failed. A sign-in started in the sessions drawer is a journey towards a
 * session, so finishing it belongs at [HermesDestination.Chat]; one started on
 * the Gateways pane already ends where its result is shown, and an unstamped
 * hand-back is every build before this one — both keep "come forward, change
 * nothing", which is the behaviour a person who navigated somewhere else in the
 * meantime expects.
 */
internal fun handBackDestination(origin: SignInOrigin?): HermesDestination? = when (origin) {
    SignInOrigin.Sessions -> HermesDestination.Chat
    SignInOrigin.Gateways, null -> null
}

@Composable
fun HermesApp(
    chatState: ChatUiState,
    gatewayState: GatewaySettingsUiState,
    sshState: SshUiState,
    appearance: AppearanceSelection,
    chatActions: ChatActions,
    appearanceActions: AppearanceActions,
    gatewayActions: GatewayActions,
    sshActions: SshActions,
    relayState: RelayUiState,
    relayActions: RelayActions,
    systemState: SystemUiState = SystemUiState(),
    systemActions: SystemActions = SystemActions(),
    connectionsState: ConnectionsUiState = ConnectionsUiState(),
    connectionsActions: ConnectionsActions = ConnectionsActions(),
    /** Honoured once per [HermesNavigationAsk.token]; see that type. */
    navigationAsk: HermesNavigationAsk? = null,
    /**
     * Told which surface a sign-in started from, because the launcher that has
     * to stamp that on its hand-back Intent is wired outside this composition
     * and the entry points are inside it.
     */
    onSignInOriginChange: (SignInOrigin) -> Unit = {},
) {
    var destination by rememberSaveable { mutableStateOf(HermesDestination.Chat) }

    /**
     * Where a sign-in started, which is where the Gateways pane was entered
     * from: reaching it from the sessions drawer is a sessions journey that
     * happens to pass through settings, and a person who finishes signing in
     * belongs back at the sessions they were trying to reach. Reaching it
     * through Settings is not, and keeps the behaviour it has.
     *
     * Saved, because the browser round trip is exactly when Android is free to
     * destroy this Activity: a rebuilt shell that forgot would send the next
     * sign-in back to the wrong place.
     */
    var signInOrigin by rememberSaveable { mutableStateOf(SignInOrigin.Gateways) }
    LaunchedEffect(signInOrigin) { onSignInOriginChange(signInOrigin) }
    LaunchedEffect(navigationAsk) { navigationAsk?.let { destination = it.destination } }

    val onBack = { destination = destination.backDestination() }
    // Four surfaces name this one destination: the sidebar's "Manage gateways…",
    // Settings, Relay, and the chat chrome's connection line. They divide by
    // *journey*, not by destination: the two that leave from the sessions
    // surface are a person heading for a session, and a sign-in they lead to
    // finishes there rather than on the pane it passed through.
    val openGateways = { origin: SignInOrigin ->
        signInOrigin = origin
        destination = HermesDestination.Gateways
    }
    val onOpenGateways = { openGateways(SignInOrigin.Gateways) }
    val onOpenGatewaysFromSessions = { openGateways(SignInOrigin.Sessions) }
    BackHandler(enabled = destination != HermesDestination.Chat) {
        onBack()
    }

    HermesTheme(appearance) {
        when (destination) {
            HermesDestination.Chat -> ChatScreen(
                state = chatState,
                actions = chatActions,
                onOpenSettings = { destination = HermesDestination.Settings },
                onOpenProfiles = { destination = HermesDestination.Profiles },
                // The chat chrome's connection line is the failure site; this
                // is the same destination its sidebar's "Manage gateways…"
                // reaches, so both routes out of a broken connection land in
                // one place rather than two. Both leave from sessions, so both
                // mark the journey as one: this is the owner's Scenario A —
                // a drawer switch to a signed-out gateway, and the sign-in it
                // leads to belongs back here.
                onOpenGateways = onOpenGatewaysFromSessions,
                sidebarHeader = {
                    ConnectionSwitcherBar(
                        state = connectionsState,
                        actions = connectionsActions,
                        onManage = onOpenGatewaysFromSessions,
                    )
                },
            )

            // "Manage profiles…" is a sidebar affordance, so its back goes home
            // rather than through Settings.
            HermesDestination.Profiles -> OverlayScaffold(
                title = "Profiles",
                subtitle = chatState.profiles.profiles.size
                    .takeIf { chatState.profiles.loaded && it > 0 }
                    ?.let(::profileCount),
                onBack = onBack,
            ) {
                ProfilesScreen(chatState.profiles)
            }

            HermesDestination.Settings -> OverlayScaffold(
                title = "Settings",
                onBack = onBack,
            ) {
                SettingsScreen(
                    onOpenAppearance = { destination = HermesDestination.Appearance },
                    onOpenGateways = onOpenGateways,
                    onOpenSystem = { destination = HermesDestination.System },
                    onOpenRelay = { destination = HermesDestination.Relay },
                    relayAvailable = !relayState.unavailableOnGateway,
                    systemAvailable =
                        gatewayState.connection.status == GatewayConnectionStatus.Connected,
                )
            }

            HermesDestination.Appearance -> OverlayScaffold(
                title = "Appearance",
                onBack = onBack,
            ) {
                AppearanceScreen(selection = appearance, actions = appearanceActions)
            }

            HermesDestination.Gateways -> OverlayScaffold(
                title = "Gateways",
                onBack = onBack,
            ) {
                GatewayScreen(
                    state = gatewayState,
                    gatewayActions = gatewayActions,
                    sshState = sshState,
                    sshActions = sshActions,
                    connectionsState = connectionsState,
                    connectionsActions = connectionsActions,
                )
            }

            // The updates sheet is hosted here rather than beside the panel's
            // own content, because it is a window of its own: hosting it inside
            // the scaffold would put it under the scaffold's insets, and an
            // apply outlives the screen that started it anyway.
            HermesDestination.System -> OverlayScaffold(
                title = SystemCopy.TITLE,
                onBack = onBack,
            ) {
                SystemScreen(state = systemState, actions = systemActions)
                if (systemState.sheetOpen) {
                    UpdatesOverlay(state = systemState, actions = systemActions)
                }
            }

            // Relay wears the same overlay chrome as its peers but supplies
            // its own back meaning: it drills one level deeper, and one header
            // whose affordance means "the pane you came from" is clearer than
            // two stacked back arrows.
            HermesDestination.Relay -> RelayScreen(
                state = relayState,
                actions = relayActions,
                onLeave = onBack,
                onOpenGateways = onOpenGateways,
            )
        }
    }
}

/**
 * Route overlays are short tasks: one back affordance, no nested chrome.
 *
 * A destination that drills deeper still gets exactly one header — it says so
 * through [backDescription] and its own `onBack`, rather than forking this
 * chrome or stacking a second back arrow inside it.
 *
 * ## The keyboard rule
 *
 * This is where the app's one soft-keyboard pattern lives, so state it here.
 * The activity is edge to edge ([androidx.activity.enableEdgeToEdge]), which
 * means `adjustResize` no longer resizes anything: the keyboard arrives as
 * [WindowInsets.ime] and draws *over* whatever is on screen. A surface that
 * ignores that inset does not merely look wrong — the part of it under the
 * keyboard becomes unreachable, because its scroll container still believes it
 * owns the full height and so has nothing left to scroll.
 *
 * The rule, one line: **the surface that owns the window pads for the IME at
 * its root, outside the scroll modifier.** Here that is
 * `windowInsetsPadding(imeInsets)` above `navigationBarsPadding()` — the
 * keyboard inset is consumed first, so the navigation-bar pass adds only what
 * is left rather than both. Every route in [HermesApp] but Chat is inside this
 * column, so none of them re-states it. The two surfaces outside it answer for
 * themselves, because neither is inside this window: Chat pads its composer
 * directly (`ComposerPane`), and a `ModalBottomSheet` is its own window —
 * created with `SOFT_INPUT_ADJUST_NOTHING` from API 30 up (material3 1.4.0,
 * `ModalBottomSheetDialogWrapper`: `SDK_INT >= 30 ? ADJUST_NOTHING :
 * ADJUST_RESIZE`), so nothing moves on its own — which is why every sheet pads
 * its own content root and why `scripts/check-repo-invariants.sh` fails a sheet
 * that forgets. Below 30 the sheet window still resizes and the inset reads
 * zero, so the same modifier is simply inert there.
 *
 * Nesting is safe rather than forbidden: `windowInsetsPadding` *consumes* what
 * it applies, so a child that also calls `imePadding()` — `SshScreen`, which is
 * hosted standalone in tests — measures zero here instead of padding twice.
 */
@Composable
internal fun OverlayScaffold(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /** Second header line, for a destination whose title needs qualifying. */
    subtitle: String? = null,
    /** What back means here, when it is not simply leaving the destination. */
    backDescription: String = "Back",
    /** Injectable only for layout tests; production uses the device keyboard. */
    imeInsets: WindowInsets = WindowInsets.ime,
    content: @Composable ColumnScope.() -> Unit,
) {
    val tokens = HermesTheme.tokens
    Column(
        modifier
            .fillMaxSize()
            .background(tokens.chatSurface)
            .windowInsetsPadding(imeInsets)
            .navigationBarsPadding(),
    ) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            QuietIconButton(
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = backDescription,
                onClick = onBack,
            )
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = HermesTheme.type.screenTitle,
                    color = tokens.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = HermesTheme.type.scaffold,
                        color = tokens.scaffoldMeta,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Hairline()
        content()
    }
}

internal fun HermesDestination.backDestination(): HermesDestination = when (this) {
    HermesDestination.Chat -> HermesDestination.Chat
    HermesDestination.Settings -> HermesDestination.Chat
    HermesDestination.Profiles -> HermesDestination.Chat
    HermesDestination.Appearance,
    HermesDestination.Gateways,
    HermesDestination.System,
    HermesDestination.Relay,
    -> HermesDestination.Settings
}
