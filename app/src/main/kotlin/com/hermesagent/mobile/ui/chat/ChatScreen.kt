package com.hermesagent.mobile.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hermesagent.mobile.data.gateway.GatewayConnectionState
import com.hermesagent.mobile.data.gateway.GatewayConnectionStatus
import com.hermesagent.mobile.data.session.AssistantTurn
import com.hermesagent.mobile.data.session.SessionStatus
import com.hermesagent.mobile.data.session.SessionSummary
import com.hermesagent.mobile.data.session.UserTurn
import com.hermesagent.mobile.data.session.buildSessionRows
import com.hermesagent.mobile.ui.ChatActions
import com.hermesagent.mobile.ui.common.Hairline
import com.hermesagent.mobile.ui.common.QuietIconButton
import com.hermesagent.mobile.ui.common.VerticalHairline
import com.hermesagent.mobile.ui.sessions.SessionList
import com.hermesagent.mobile.ui.theme.AppearanceSelection
import com.hermesagent.mobile.ui.theme.HermesTheme
import com.hermesagent.mobile.ui.theme.HermesThemeMode
import kotlinx.coroutines.launch

/**
 * Chat is the home surface (`apps/desktop/DESIGN.md:48-49` @ `f82f2dba`).
 *
 * Two layouts, one content:
 * - **compact** (< 720dp wide): top bar + transcript + composer, with sessions
 *   in a modal drawer behind the menu affordance.
 * - **wide** (>= 720dp): sessions become a persistent rail beside the
 *   transcript, no drawer, no menu button.
 *
 * The breakpoint is a width measurement rather than a device class because
 * that is what actually decides whether two panes fit — a folded foldable, a
 * split-screen tablet and a phone in landscape all answer correctly.
 */
@Composable
fun ChatScreen(
    state: ChatUiState,
    actions: ChatActions,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    /** Injectable only for layout tests; production uses the device navigation bars. */
    wideRailInsets: WindowInsets = WindowInsets.navigationBars,
) {
    BoxWithConstraints(modifier.fillMaxSize().background(HermesTheme.tokens.chatSurface)) {
        if (maxWidth >= WIDE_BREAKPOINT) {
            WideLayout(state, actions, onOpenSettings, wideRailInsets)
        } else {
            CompactLayout(state, actions, onOpenSettings)
        }
    }
}

/** Two panes need roughly a rail plus a readable column; below that, one. */
private val WIDE_BREAKPOINT: Dp = 720.dp
private val RAIL_WIDTH: Dp = 300.dp
private const val WIDE_RAIL_TAG = "Wide sessions rail"

@Composable
private fun CompactLayout(state: ChatUiState, actions: ChatActions, onOpenSettings: () -> Unit) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = HermesTheme.tokens.sidebarSurface,
                drawerContentColor = HermesTheme.tokens.textPrimary,
            ) {
                // Picking or creating a session closes the drawer; both are a
                // "you are done here" gesture, and leaving it open hides the
                // transcript the user just asked for.
                SessionsPane(
                    state = state,
                    actions = actions,
                    modifier = Modifier.statusBarsPadding(),
                    onSelectSession = { id ->
                        actions.onSelectSession(id)
                        scope.launch { drawerState.close() }
                    },
                    onCreateSession = {
                        actions.onCreateSession()
                        scope.launch { drawerState.close() }
                    },
                )
            }
        },
    ) {
        Column(Modifier.fillMaxSize()) {
            ChatTopBar(
                title = state.activeSession?.title ?: "Hermes",
                subtitle = state.chromeSubtitle(),
                onOpenSessions = { scope.launch { drawerState.open() } },
                onOpenSettings = onOpenSettings,
                modifier = Modifier.statusBarsPadding(),
            )
            TranscriptPane(state, Modifier.weight(1f))
            ComposerPane(state, actions)
        }
    }
}

@Composable
private fun WideLayout(
    state: ChatUiState,
    actions: ChatActions,
    onOpenSettings: () -> Unit,
    railInsets: WindowInsets,
) {
    Row(Modifier.fillMaxSize().statusBarsPadding()) {
        // The rail owns its bottom edge in the wide layout. Keep its surface
        // painted through the inset, but keep its list above three-button
        // navigation. Compact drawer content deliberately does not inherit it.
        SessionsPane(
            state,
            actions,
            Modifier
                .width(RAIL_WIDTH)
                .fillMaxHeight()
                // This tag stays on the full rail surface; the inset only
                // changes the content area inside it.
                .testTag(WIDE_RAIL_TAG)
                .background(HermesTheme.tokens.sidebarSurface)
                .windowInsetsPadding(railInsets),
        )
        VerticalHairline(Modifier.fillMaxHeight())
        Column(Modifier.weight(1f)) {
            ChatTopBar(
                title = state.activeSession?.title ?: "Hermes",
                subtitle = state.chromeSubtitle(),
                onOpenSessions = null,
                onOpenSettings = onOpenSettings,
            )
            TranscriptPane(state, Modifier.weight(1f))
            ComposerPane(state, actions)
        }
    }
}

@Composable
private fun SessionsPane(
    state: ChatUiState,
    actions: ChatActions,
    modifier: Modifier = Modifier,
    onSelectSession: (String) -> Unit = actions.onSelectSession,
    onCreateSession: () -> Unit = actions.onCreateSession,
) {
    SessionList(
        rows = state.sessionRows,
        activeSessionId = state.activeSession?.id,
        query = state.query,
        canCreate = state.canCreateSession,
        onQueryChange = actions.onQueryChange,
        onSelect = onSelectSession,
        onCreate = onCreateSession,
        modifier = modifier,
    )
}

@Composable
private fun TranscriptPane(state: ChatUiState, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    val transcript = rememberUpdatedState(state.transcript)

    // Landing on a session jumps to the tail; growth after that only follows a
    // reader who is still there. Scrolling up is deliberate, and yanking
    // someone back mid-sentence is the worse failure.
    //
    // Two details carry the behaviour. The trigger is the last entry's *value*,
    // because a streamed delta rewrites the same entry under the same id — an
    // id/count key stops firing after the first delta and the reply grows
    // off-screen. And "still there" compares the scroll anchor against where
    // the last follow parked it, not `canScrollForward`: growing the tail block
    // makes the list scrollable again at once, which would read as "the reader
    // left" on every delta. `canScrollForward` earns its place in the trigger
    // instead, so a re-measure that lands after the state change still follows.
    LaunchedEffect(listState, state.activeSession?.id) {
        listState.scrollToTail()
        var parked = listState.anchor()

        snapshotFlow { Triple(transcript.value.lastOrNull(), transcript.value.size, listState.canScrollForward) }
            .collect {
                if (listState.anchor() != parked) return@collect
                listState.scrollToTail()
                parked = listState.anchor()
            }
    }

    Box(modifier.fillMaxWidth()) {
        Transcript(
            entries = state.transcript,
            listState = listState,
            isWorking = state.activeSession?.status == SessionStatus.Working,
            activityStartedAtMillis = state.activeSession?.activityStartedAtMillis,
            progress = state.activeSession?.progress,
            contentPadding = PaddingValues(
                start = HermesTheme.spacing.pageInset,
                end = HermesTheme.spacing.pageInset,
                top = HermesTheme.spacing.blockGap,
                bottom = HermesTheme.spacing.blockGap,
            ),
        )
    }
}

/**
 * Scroll to the *bottom* of the transcript, not merely to its last item.
 *
 * A streaming block routinely outgrows the viewport, and `scrollToItem` only
 * puts an item's top edge on screen — which is precisely where the tail
 * disappears. Walking forward until the list reports it cannot scroll any
 * further lands on the growing bottom edge instead. A failed scroll is the
 * terminating condition: it cannot spin if layout cannot make progress, and
 * it does not impose an arbitrary cap on a legitimately long reply.
 */
private suspend fun LazyListState.scrollToTail() {
    val lastIndex = layoutInfo.totalItemsCount - 1
    if (lastIndex < 0) return

    if (firstVisibleItemIndex != lastIndex) scrollToItem(lastIndex)
    val viewport = (layoutInfo.viewportEndOffset - layoutInfo.viewportStartOffset).toFloat()
    if (viewport <= 0f) return

    while (canScrollForward) {
        val before = anchor()
        val consumed = scrollBy(viewport)
        if (consumed <= 0f || anchor() == before) return
    }
}

/** Where the list is parked: the first visible item and how far into it. */
private fun LazyListState.anchor(): Pair<Int, Int> =
    firstVisibleItemIndex to firstVisibleItemScrollOffset

@Composable
private fun ComposerPane(state: ChatUiState, actions: ChatActions) {
    Composer(
        draft = state.draft,
        onDraftChange = actions.onDraftChange,
        onSend = actions.onSend,
        onStop = actions.onStop,
        isStreaming = state.isStreaming && state.connection.status == GatewayConnectionStatus.Connected,
        canSend = state.canSend,
        statusLine = state.composerStatus(),
        modifier = Modifier.imePadding().navigationBarsPadding(),
    )
}

@Composable
private fun ChatTopBar(
    title: String,
    subtitle: String,
    onOpenSessions: (() -> Unit)?,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = HermesTheme.tokens
    Column(modifier.fillMaxWidth().background(tokens.chatSurface)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onOpenSessions != null) {
                QuietIconButton(
                    icon = Icons.Filled.Menu,
                    contentDescription = "Open sessions",
                    onClick = onOpenSessions,
                )
            }
            Column(
                Modifier
                    .weight(1f)
                    .padding(horizontal = if (onOpenSessions == null) HermesTheme.spacing.pageInset else 4.dp),
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                Text(
                    text = title,
                    style = HermesTheme.type.screenTitle,
                    color = tokens.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    style = HermesTheme.type.scaffoldMeta,
                    color = tokens.scaffoldMeta,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            QuietIconButton(
                icon = Icons.Filled.Settings,
                contentDescription = "Open settings",
                onClick = onOpenSettings,
            )
        }
        Hairline()
    }
}

private fun ChatUiState.chromeSubtitle(): String = when {
    connection.status != GatewayConnectionStatus.Connected -> connection.status.label
    isStreaming -> "Streaming · Connected"
    runningCount > 0 && connection.status == GatewayConnectionStatus.Connected ->
        "$runningCount running · Connected"
    else -> connection.status.label
}

private fun ChatUiState.composerStatus(): String = notice ?: when {
    connection.status == GatewayConnectionStatus.Connecting -> "Connecting to Gateway"
    connection.status == GatewayConnectionStatus.NeedsAttention ->
        connection.message ?: "Open Gateways to reconnect"
    connection.status == GatewayConnectionStatus.Disconnected -> "Open Gateways to connect"
    liveStatusText != null -> liveStatusText.orEmpty()
    isStreaming -> "Hermes is responding — tap ■ to stop"
    runningCount > 0 -> "Wait for the running turn to finish"
    else -> "Connected to Gateway"
}

// ── Previews ──────────────────────────────────────────────────────────────
// Phone dark, phone light, the monospace-everything preset and a wide layout.

private const val PREVIEW_NOW = 1_755_600_000_000L
private const val PREVIEW_SESSION = "preview-session"

private fun previewState(): ChatUiState {
    val session = SessionSummary(
        id = PREVIEW_SESSION,
        title = "Remote Hermes session",
        preview = "Gateway transport is ready",
        lastActiveAtMillis = PREVIEW_NOW,
    )
    return ChatUiState(
        sessionRows = buildSessionRows(sessions = listOf(session), nowMillis = PREVIEW_NOW),
        activeSession = session,
        transcript = listOf(
            UserTurn("preview-user", "Show the current Gateway status.", PREVIEW_NOW),
            AssistantTurn("preview-assistant", "The Gateway is connected and ready.", PREVIEW_NOW),
        ),
        draft = "How do I import a key?",
        connection = GatewayConnectionState(GatewayConnectionStatus.Connected),
    )
}

@Composable
private fun PreviewChat(selection: AppearanceSelection) {
    HermesTheme(selection) {
        ChatScreen(state = previewState(), actions = ChatActions(), onOpenSettings = {})
    }
}

@Preview(name = "Chat · nous dark", widthDp = 412, heightDp = 892)
@Composable
private fun ChatPreviewDark() = PreviewChat(AppearanceSelection("nous", HermesThemeMode.Dark))

@Preview(name = "Chat · nous light", widthDp = 412, heightDp = 892)
@Composable
private fun ChatPreviewLight() = PreviewChat(AppearanceSelection("nous", HermesThemeMode.Light))

@Preview(name = "Chat · cyberpunk", widthDp = 412, heightDp = 892)
@Composable
private fun ChatPreviewCyberpunk() = PreviewChat(AppearanceSelection("cyberpunk", HermesThemeMode.Dark))

@Preview(name = "Chat · wide slate", widthDp = 1000, heightDp = 760)
@Composable
private fun ChatPreviewWide() = PreviewChat(AppearanceSelection("slate", HermesThemeMode.Dark))
