package com.hermesagent.mobile.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hermesagent.mobile.data.gateway.GatewayConnectionState
import com.hermesagent.mobile.data.gateway.GatewayConnectionStatus
import com.hermesagent.mobile.data.session.AssistantTurn
import com.hermesagent.mobile.data.session.SessionStatus
import com.hermesagent.mobile.data.session.SessionSummary
import com.hermesagent.mobile.data.session.UserTurn
import com.hermesagent.mobile.data.session.buildSessionRows
import com.hermesagent.mobile.ui.ChatActions
import com.hermesagent.mobile.ui.common.Hairline
import com.hermesagent.mobile.ui.common.HermesIcon
import com.hermesagent.mobile.ui.common.HermesIconGlyph
import com.hermesagent.mobile.ui.common.QuietIconButton
import com.hermesagent.mobile.ui.common.VerticalHairline
import com.hermesagent.mobile.ui.sessions.SessionList
import com.hermesagent.mobile.ui.theme.AppearanceSelection
import com.hermesagent.mobile.ui.theme.HermesTheme
import com.hermesagent.mobile.ui.theme.HermesThemeMode
import kotlinx.coroutines.flow.first
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
                onOpenSessions = {
                    actions.onRefreshNavigation()
                    scope.launch { drawerState.open() }
                },
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
        projects = state.projects,
        projectsAvailable = state.projectsAvailable,
        sidebarGrouping = state.sidebarGrouping,
        selectedProject = state.selectedProject,
        projectLoading = state.projectLoading,
        activeSessionId = state.activeSession?.id,
        query = state.query,
        canCreate = state.canCreateSession,
        onQueryChange = actions.onQueryChange,
        onSidebarGroupingChange = actions.onSidebarGroupingChange,
        onSelectProject = actions.onSelectProject,
        onExitProject = actions.onExitProject,
        onCreateProject = actions.onCreateProject,
        onSelect = onSelectSession,
        onCreate = onCreateSession,
        modifier = modifier,
    )
}

@Composable
private fun TranscriptPane(state: ChatUiState, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    val transcript = rememberUpdatedState(state.transcript)
    val scope = rememberCoroutineScope()
    var showJump by remember(state.activeSession?.id) { mutableStateOf(false) }
    var hasUnseenActivity by remember(state.activeSession?.id) { mutableStateOf(false) }

    // Landing on a session jumps to the tail; growth after that only follows a
    // reader who is still there. Scrolling up is deliberate, and yanking
    // someone back mid-sentence is the worse failure.
    //
    // Wait for a real viewport before establishing the parked anchor. On a
    // physical device the history can arrive before the first LazyColumn
    // measure; the old eager scroll then returned with zero items and treated
    // the top as the user's chosen position.
    //
    // The full transcript value is observed because a streamed delta rewrites
    // an existing entry under the same id. A backward scroll disarms following;
    // layout reflow does not. Reaching the bottom manually or through the jump
    // control re-arms it.
    LaunchedEffect(listState, state.activeSession?.id) {
        snapshotFlow {
            listState.layoutInfo.totalItemsCount > 0 &&
                listState.layoutInfo.viewportEndOffset > listState.layoutInfo.viewportStartOffset
        }.first { ready -> ready }

        listState.scrollToTail()
        var following = true
        var observedTranscript = transcript.value

        snapshotFlow {
            Triple(
                transcript.value,
                listState.canScrollForward,
                listState.isScrollInProgress && listState.lastScrolledBackward,
            )
        }.collect { (currentTranscript, canScrollForward, scrolledBackward) ->
            val contentChanged = currentTranscript != observedTranscript
            observedTranscript = currentTranscript

            if (!canScrollForward) {
                following = true
                showJump = false
                hasUnseenActivity = false
                return@collect
            }

            if (scrolledBackward) following = false

            if (!following) {
                showJump = true
                if (contentChanged) hasUnseenActivity = true
                return@collect
            }

            listState.scrollToTail()
            showJump = false
            hasUnseenActivity = false
        }
    }

    Box(modifier.fillMaxWidth()) {
        Transcript(
            entries = state.transcript,
            imageLoader = state.imageLoader,
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
        if (showJump) {
            JumpToLatestButton(
                hasUnseenActivity = hasUnseenActivity,
                onClick = { scope.launch { listState.scrollToTail() } },
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp),
            )
        }
    }
}

/**
 * Mobile form of Desktop's pinned `ScrollToBottomButton`: the same arrow-down
 * action, with a short label only when transcript activity arrived while the
 * reader was away from the tail. The 48dp target is Android's touch adaptation.
 */
@Composable
private fun JumpToLatestButton(
    hasUnseenActivity: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = HermesTheme.tokens
    val shape = RoundedCornerShape(24.dp)
    val description = if (hasUnseenActivity) {
        "New activity. Scroll to bottom"
    } else {
        "Scroll to bottom"
    }
    Row(
        modifier = modifier
            .heightIn(min = HermesTheme.spacing.touchTarget)
            .widthIn(min = HermesTheme.spacing.touchTarget)
            .background(tokens.cardSurface, shape)
            .border(1.dp, tokens.strokeSecondary, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description }
            .padding(horizontal = if (hasUnseenActivity) 12.dp else 0.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HermesIconGlyph(
            icon = HermesIcon.ArrowDown,
            color = if (hasUnseenActivity) tokens.accent else tokens.textSecondary,
            size = 16.sp,
        )
        if (hasUnseenActivity) {
            Spacer(Modifier.width(6.dp))
            Text(
                text = "New activity",
                style = HermesTheme.type.caption,
                color = tokens.accent,
            )
        }
    }
}

/**
 * Mobile form of Desktop's composer coding-status strip: the server-reported
 * git branch for this session's working directory, rendered as a quiet bar
 * above the composer. Android has no authorized local git transport, so the
 * label is exactly what the Gateway reported — no path or worktree claims.
 */
@Composable
internal fun SessionBranchStrip(branch: String, modifier: Modifier = Modifier) {
    val tokens = HermesTheme.tokens
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 28.dp)
            .semantics { contentDescription = "Working branch $branch" }
            .testTag("Session branch strip")
            .padding(horizontal = HermesTheme.spacing.pageInset + 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        HermesIconGlyph(
            icon = HermesIcon.GitBranch,
            color = tokens.textTertiary,
            size = 12.sp,
        )
        Text(
            text = branch,
            style = HermesTheme.type.scaffoldMeta,
            color = tokens.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
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
    Column(Modifier.imePadding().navigationBarsPadding()) {
        LaunchedEffect(state.activeSession?.id) { actions.onComposerStatusOpened() }
        state.activeSession?.gitBranch?.takeIf(String::isNotBlank)?.let { branch ->
            SessionBranchStrip(branch = branch)
        }
        ComposerStatusStack(
            activeSessionId = state.activeSession?.id,
            status = state.activeSession?.composerStatus,
            onRefreshProcesses = actions.onRefreshProcesses,
            onKillProcess = actions.onKillProcess,
            hasQueue = state.composer.runtime.queueEntries.isNotEmpty(),
            queueContent = {
                ComposerQueueSection(
                    durableSessionId = state.composer.runtime.activeDurableId,
                    entries = state.composer.runtime.queueEntries,
                    parked = state.composer.runtime.queueParked,
                    editingEntryId = state.composer.runtime.queueEditingEntryId,
                    editingText = state.composer.runtime.queueEditingText,
                    redirectableEntryId = state.composer.runtime.queueEntries.firstOrNull()
                        ?.takeIf { state.composer.runtime.canRedirect }?.id,
                    onEdit = actions.onEditQueuedEntry,
                    onEditTextChange = actions.onQueueEditTextChange,
                    onSaveEdit = actions.onSaveQueueEdit,
                    onCancelEdit = actions.onCancelQueueEdit,
                    onDelete = actions.onDeleteQueuedEntry,
                    onSendNext = actions.onSendNext,
                    onRedirectNow = actions.onRedirectQueuedEntry,
                    onResume = actions.onResumeQueue,
                    onMarkReadyAfterReview = actions.onMarkQueuedEntryReady,
                )
            },
            modifier = Modifier.padding(horizontal = HermesTheme.spacing.pageInset, vertical = 4.dp),
        )
        PendingInputSurface(
            pending = state.composer.runtime.pendingInput,
            background = state.backgroundPendingInput,
            isSubmitting = false,
            onRespond = actions.onRespondToPendingInput,
            onOpenSession = actions.onSelectSession,
            modifier = Modifier.padding(horizontal = HermesTheme.spacing.pageInset, vertical = 4.dp),
        )
        val securePrompt = state.composer.runtime.pendingInput?.takeIf { it.isSecurePrompt() }
        if (securePrompt != null) {
            SecurePendingDialog(
                pending = securePrompt,
                isSubmitting = false,
                errorText = null,
                onRespond = actions.onRespondToPendingInput,
                onDismiss = { actions.onDismissSecurePending() },
            )
        }
        Composer(
            draft = state.draft,
            onDraftChange = actions.onDraftChange,
            onSend = actions.onSend,
            onStop = actions.onStop,
            isStreaming = state.isStreaming && state.connection.status == GatewayConnectionStatus.Connected,
            canSend = state.canSend,
            connected = state.connection.status == GatewayConnectionStatus.Connected,
            statusLine = state.composerStatus(),
            editorIdentity = state.activeSession?.id,
            controls = state.composer,
            onSelectModel = actions.onSelectModel,
            onSelectReasoning = actions.onSelectReasoning,
            onSelectFast = actions.onSelectFast,
            onEditorSelectionChange = actions.onEditorSelectionChange,
            onCompletionSelected = actions.onCompletionSelected,
            onInsertText = actions.onInsertText,
            onPickFiles = actions.onPickFiles,
            attachments = state.composer.runtime.attachments,
            attachmentThumbnails = state.composer.runtime.attachmentThumbnails,
            onRemoveAttachment = actions.onRemoveAttachment,
            voiceState = state.voice,
            onToggleDictation = actions.onToggleDictation,
            onToggleConversation = actions.onToggleConversation,
            onToggleMute = actions.onToggleVoiceMute,
            busyKind = state.composer.runtime.busyKind,
            queueCount = state.composer.runtime.queueEntries.size,
            canRedirect = state.composer.runtime.canRedirect,
            canQueue = state.composer.runtime.canQueue,
            onRedirect = actions.onRedirect,
            onQueue = actions.onQueue,
            onSendNext = {
                state.composer.runtime.queueEntries.firstOrNull()?.id?.let(actions.onSendNext)
            },
            canUndo = state.composer.runtime.undoRedo.canUndo,
            canRedo = state.composer.runtime.undoRedo.canRedo,
            onUndo = actions.onUndoDraft,
            onRedo = actions.onRedoDraft,
            onHistoryOlder = actions.onHistoryOlder,
            onHistoryNewer = actions.onHistoryNewer,
        )
    }
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
