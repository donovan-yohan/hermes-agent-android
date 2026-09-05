package com.hermesagent.mobile.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.gestures.scrollable
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.union
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hermesagent.mobile.data.attachments.ImageRefLines
import com.hermesagent.mobile.data.gateway.ApprovalMode
import com.hermesagent.mobile.data.gateway.GatewayConnectionState
import com.hermesagent.mobile.data.gateway.GatewayConnectionStatus
import com.hermesagent.mobile.data.session.AssistantTurn
import com.hermesagent.mobile.data.session.ContextMeterState
import com.hermesagent.mobile.data.session.SessionStatus
import com.hermesagent.mobile.data.session.SessionSummary
import com.hermesagent.mobile.data.session.ToolActivity
import com.hermesagent.mobile.data.session.ToolState
import com.hermesagent.mobile.data.session.TranscriptEntry
import com.hermesagent.mobile.data.session.UserTurn
import com.hermesagent.mobile.data.session.buildSessionRows
import com.hermesagent.mobile.data.session.isUnread
import com.hermesagent.mobile.ui.ChatActions
import com.hermesagent.mobile.ui.common.Hairline
import com.hermesagent.mobile.ui.common.HermesIcon
import com.hermesagent.mobile.ui.common.HermesIconGlyph
import com.hermesagent.mobile.ui.common.QuietIconButton
import com.hermesagent.mobile.ui.common.StatusAction
import com.hermesagent.mobile.ui.common.statusAction
import com.hermesagent.mobile.ui.common.scrollToTail
import com.hermesagent.mobile.ui.common.VerticalHairline
import com.hermesagent.mobile.ui.gateway.ConnectionsCopy
import com.hermesagent.mobile.ui.sessions.ProfileRailActions
import com.hermesagent.mobile.ui.sessions.SessionActionsControl
import com.hermesagent.mobile.ui.sessions.SessionList
import com.hermesagent.mobile.ui.theme.AppearanceSelection
import com.hermesagent.mobile.ui.theme.HermesTheme
import com.hermesagent.mobile.ui.theme.HermesThemeMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Chat is the home surface (`apps/desktop/DESIGN.md:48-49` @ `3ca096de`).
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
    /** "Manage profiles…" leaves the sidebar, so the app shell owns where it goes. */
    onOpenProfiles: () -> Unit = {},
    /**
     * The Gateways surface, for the chrome that names it.
     *
     * A connection that needs signing in reports itself twice here — in the
     * header subtitle and on the composer — and until now neither could be
     * tapped, so the door out of a broken connection was three taps and a
     * scroll away through Settings. Same destination the sidebar's
     * "Manage gateways…" reaches, so the shell still owns where it goes.
     */
    onOpenGateways: () -> Unit = {},
    /** Injectable only for layout tests; production uses the device navigation bars. */
    wideRailInsets: WindowInsets = WindowInsets.navigationBars,
    /**
     * The keyboard, which the sidebar needs as much as the composer does: its
     * search field is the app's other text input, and both the drawer and the
     * rail put the session list directly under it. Injectable only for layout
     * tests; production uses the device keyboard. `OverlayScaffold` states the
     * whole rule — Chat is the one route that is not inside that scaffold, so
     * it answers for itself here.
     */
    imeInsets: WindowInsets = WindowInsets.ime,
    /**
     * Appearance's `Intro Splash` (`i18n/en.ts:588` @ `3ca096de`). On, an empty
     * chat is the wordmark; off, it is the plain note. It is a saved appearance
     * preference rather than chat state, so it arrives beside the theme.
     */
    introSplashEnabled: Boolean = true,
    /** Rail chrome above the session header — the connection switcher. */
    sidebarHeader: @Composable () -> Unit = {},
) {
    // Derived once, here, because this is where the policy lives: which
    // connection states are a door, and which surface that door opens. Both
    // layouts and the composer then carry one nullable rather than re-deriving
    // the same predicate three times.
    val gatewayDoor = StatusAction(ConnectionsCopy.MANAGE_GATEWAYS, onOpenGateways)
        .takeIf { state.gatewayNeedsAttention }
    var contextUsageOpen by rememberSaveable { mutableStateOf(false) }
    val onOpenContextUsage = { contextUsageOpen = true }
    BoxWithConstraints(modifier.fillMaxSize().background(HermesTheme.tokens.chatSurface)) {
        if (maxWidth >= WIDE_BREAKPOINT) {
            WideLayout(state, actions, onOpenSettings, onOpenProfiles, gatewayDoor, wideRailInsets, imeInsets, sidebarHeader, introSplashEnabled, onOpenContextUsage)
        } else {
            CompactLayout(state, actions, onOpenSettings, onOpenProfiles, gatewayDoor, imeInsets, sidebarHeader, introSplashEnabled, onOpenContextUsage)
        }
    }
    // The meter disappears whenever its session does — a switch, a reconnect, an
    // endpoint change. Without this the sheet would vanish with it and then
    // re-open by itself when the next breakdown landed, and `rememberSaveable`
    // would carry that latent `true` across an Activity recreate.
    LaunchedEffect(state.contextMeter) {
        if (state.contextMeter == null) contextUsageOpen = false
    }
    if (contextUsageOpen && state.contextMeter != null) {
        ContextUsageSheet(
            meterState = state.contextMeter,
            onDismiss = { contextUsageOpen = false },
        )
    }
}

/** Two panes need roughly a rail plus a readable column; below that, one. */
private val WIDE_BREAKPOINT: Dp = 720.dp
private val RAIL_WIDTH: Dp = 300.dp
private const val WIDE_RAIL_TAG = "Wide sessions rail"

/** The chrome's title line, so a test can measure where the block sits. */
internal const val CHAT_TITLE_TAG = "chat-title"

/** The chrome's status line. */
internal const val CHAT_SUBTITLE_TAG = "chat-subtitle"

/** The status line and the two figures that share it. */
internal const val CHAT_SUBTITLE_ROW_TAG = "chat-subtitle-row"

@Composable
private fun CompactLayout(
    state: ChatUiState,
    actions: ChatActions,
    onOpenSettings: () -> Unit,
    onOpenProfiles: () -> Unit,
    gatewayDoor: StatusAction?,
    imeInsets: WindowInsets,
    sidebarHeader: @Composable () -> Unit,
    introSplashEnabled: Boolean,
    onOpenContextUsage: () -> Unit = {},
) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    ModalNavigationDrawer(
        drawerState = drawerState,
        scrimColor = HermesTheme.tokens.overlayScrim,
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
                    header = sidebarHeader,
                    // The drawer still does not inherit the rail's navigation-bar
                    // inset, which is a deliberate difference. The keyboard is
                    // not that: search is at the top of this pane and the list
                    // it filters runs to the bottom edge, so without this the
                    // matches the search just produced are the part covered.
                    modifier = Modifier.statusBarsPadding().windowInsetsPadding(imeInsets),
                    onSelectSession = { id ->
                        actions.onSelectSession(id)
                        scope.launch { drawerState.close() }
                    },
                    onCreateSession = {
                        actions.onCreateSession()
                        scope.launch { drawerState.close() }
                    },
                    onManageProfiles = {
                        onOpenProfiles()
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
                sessionId = state.activeSession?.id,
                onOpenSessions = {
                    actions.onRefreshNavigation()
                    scope.launch { drawerState.open() }
                },
                onOpenSettings = onOpenSettings,
                gatewayDoor = gatewayDoor,
                onRenameSession = actions.onRenameSession,
                onDeleteSession = actions.onDeleteSession,
                onSetSessionPinned = actions.onSetSessionPinned,
                onSetSessionUnread = actions.onSetSessionUnread,
                onSetSessionArchived = actions.onSetSessionArchived,
                sessionPinned = state.activeSession?.pinned == true,
                sessionUnread = state.activeSession.isUnread(),
                sessionArchived = state.activeSession?.archived == true,
                contextMeter = state.contextMeter,
                onOpenContextUsage = onOpenContextUsage,
                approvalMode = state.approvalMode,
                onSelectApprovalMode = actions.onSelectApprovalMode,
                modifier = Modifier.statusBarsPadding(),
            )
            TranscriptPane(
                state = state,
                onShowEarlier = actions.onShowEarlierMessages,
                onBranchFromReply = actions.onBranchFromReply,
                onRegenerateReply = actions.onRegenerateReply,
                onToggleReadAloud = actions.onToggleReadAloud,
                introSplashEnabled = introSplashEnabled,
                modifier = Modifier.weight(1f),
            )
            ComposerPane(state, actions, gatewayDoor)
        }
    }
}

@Composable
private fun WideLayout(
    state: ChatUiState,
    actions: ChatActions,
    onOpenSettings: () -> Unit,
    onOpenProfiles: () -> Unit,
    gatewayDoor: StatusAction?,
    railInsets: WindowInsets,
    imeInsets: WindowInsets,
    sidebarHeader: @Composable () -> Unit,
    introSplashEnabled: Boolean,
    onOpenContextUsage: () -> Unit = {},
) {
    Row(Modifier.fillMaxSize().statusBarsPadding()) {
        // The rail owns its bottom edge in the wide layout. Keep its surface
        // painted through the inset, but keep its list above three-button
        // navigation. Compact drawer content deliberately does not inherit it.
        SessionsPane(
            state,
            actions,
            sidebarHeader,
            Modifier
                .width(RAIL_WIDTH)
                .fillMaxHeight()
                // This tag stays on the full rail surface; the inset only
                // changes the content area inside it.
                .testTag(WIDE_RAIL_TAG)
                .background(HermesTheme.tokens.sidebarSurface)
                // `union`, not a second padding pass: the keyboard draws over
                // the navigation bar rather than stacking on top of it, so the
                // rail owes the taller of the two and never their sum.
                .windowInsetsPadding(railInsets.union(imeInsets)),
            onManageProfiles = onOpenProfiles,
        )
        VerticalHairline(Modifier.fillMaxHeight())
        Column(Modifier.weight(1f)) {
            ChatTopBar(
                title = state.activeSession?.title ?: "Hermes",
                subtitle = state.chromeSubtitle(),
                sessionId = state.activeSession?.id,
                onOpenSessions = null,
                onOpenSettings = onOpenSettings,
                gatewayDoor = gatewayDoor,
                onRenameSession = actions.onRenameSession,
                onDeleteSession = actions.onDeleteSession,
                onSetSessionPinned = actions.onSetSessionPinned,
                onSetSessionUnread = actions.onSetSessionUnread,
                onSetSessionArchived = actions.onSetSessionArchived,
                sessionPinned = state.activeSession?.pinned == true,
                sessionUnread = state.activeSession.isUnread(),
                sessionArchived = state.activeSession?.archived == true,
                contextMeter = state.contextMeter,
                onOpenContextUsage = onOpenContextUsage,
                approvalMode = state.approvalMode,
                onSelectApprovalMode = actions.onSelectApprovalMode,
            )
            TranscriptPane(
                state = state,
                onShowEarlier = actions.onShowEarlierMessages,
                onBranchFromReply = actions.onBranchFromReply,
                onRegenerateReply = actions.onRegenerateReply,
                onToggleReadAloud = actions.onToggleReadAloud,
                introSplashEnabled = introSplashEnabled,
                modifier = Modifier.weight(1f),
            )
            ComposerPane(state, actions, gatewayDoor)
        }
    }
}

@Composable
private fun SessionsPane(
    state: ChatUiState,
    actions: ChatActions,
    header: @Composable () -> Unit = {},
    modifier: Modifier = Modifier,
    onSelectSession: (String) -> Unit = actions.onSelectSession,
    onCreateSession: () -> Unit = actions.onCreateSession,
    onManageProfiles: () -> Unit = {},
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
        onRenameSession = actions.onRenameSession,
        onDeleteSession = actions.onDeleteSession,
        onSetSessionPinned = actions.onSetSessionPinned,
        onSetSessionUnread = actions.onSetSessionUnread,
        onSetSessionArchived = actions.onSetSessionArchived,
        archivedVisible = state.archivedVisible,
        archivedPool = state.archivedPool,
        onArchivedVisibleChange = actions.onArchivedVisibleChange,
        unreadCount = state.unreadCount,
        sessionsLoading = state.sessionsLoading,
        onMarkAllRead = actions.onMarkAllSessionsRead,
        header = header,
        profileRail = state.profileRail,
        projectScope = state.projectScope,
        profileRailActions = remember(actions, onManageProfiles) {
            ProfileRailActions(
                onSelectProfile = actions.onSelectProfile,
                onShowAllProfiles = actions.onShowAllProfiles,
                onManageProfiles = onManageProfiles,
            )
        },
    )
}

@Composable
private fun TranscriptPane(
    state: ChatUiState,
    onShowEarlier: () -> Unit,
    onBranchFromReply: ((String) -> Unit)?,
    onRegenerateReply: ((String) -> Unit)?,
    onToggleReadAloud: (String) -> Unit,
    introSplashEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val transcript = rememberUpdatedState(state.transcript)
    val scope = rememberCoroutineScope()
    // Rows the transcript renders before the first turn. `Show earlier
    // messages` is a leading item inside the scroll, so every index this pane
    // maps between the list and the transcript passes through it.
    val leadingItems = if (state.canShowEarlierMessages) 1 else 0
    val leadingItemsNow = rememberUpdatedState(leadingItems)
    // Where the viewport sat when `Show earlier messages` was pressed, held
    // until the older page lands.
    var prependAnchor by remember(state.activeSession?.id) { mutableStateOf<TranscriptAnchor?>(null) }
    var showJump by remember(state.activeSession?.id) { mutableStateOf(false) }
    var hasUnseenActivity by remember(state.activeSession?.id) { mutableStateOf(false) }
    // This remains UI-local. The authoritative transcript supplies the turn
    // owning the visible response; following only describes this reader's
    // viewport. Desktop: apps/desktop/src/components/assistant-ui/thread/list.tsx:194-232,350-372
    // @ 45fcaaa54aae2d03ab816fb61c6ba312d3ac67b8.
    var following by remember(state.activeSession?.id) { mutableStateOf(true) }

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
        var observedTranscript = transcript.value

        snapshotFlow {
            Triple(
                transcript.value,
                listState.canScrollForward,
                listState.isScrollInProgress && listState.lastScrolledBackward,
            )
        }.collect { (currentTranscript, canScrollForward, scrolledBackward) ->
            val contentChanged = currentTranscript != observedTranscript
            val grewAtHead = grewAtHead(observedTranscript, currentTranscript)
            observedTranscript = currentTranscript

            // History the reader asked for is not activity below them and is not
            // a reason to follow the tail. Left to the ordinary path this would
            // light the jump control's unseen-activity dot on every press, and —
            // on a transcript short enough that `canScrollForward` was still
            // false — would run `scrollToTail()` in the same emission the anchor
            // restore fires in, throwing the reader to the bottom of the page
            // they just asked to leave.
            if (grewAtHead) {
                following = false
                // The page is not activity below the reader, but it does change
                // whether there IS anywhere below them: a transcript short
                // enough that the tail was already on screen becomes one that
                // scrolls. Leaving the control's visibility stale would strand
                // the reader at the top of the page they just asked for with no
                // way back to the latest turn until something else changed.
                showJump = canScrollForward
                return@collect
            }

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

    // A prepend slides everything on screen down by the height of the rows it
    // added. Desktop anchors before the prepend and re-applies the anchor in
    // the same commit the taller tree lands in, because otherwise the view is
    // stranded near the top for a frame or two
    // (`apps/desktop/src/components/assistant-ui/thread/list.tsx:497-505,762-770`
    // @ `3ca096de5f8183cb2e0ec23673f294d5978656a3`). The `LazyListState`
    // equivalent of its distance-from-bottom is the row that was on top and how
    // far into that row the viewport began — keyed on the row, so the rows
    // inserted above it cannot invalidate it.
    //
    // The latch is the anchor row's own INDEX, not the transcript's size.
    // Desktop guards the same restore with `loadSettledRef` so a recorded
    // distance cannot be re-applied by an unrelated commit; here a press that
    // fetches nothing — the in-flight guard swallowing a second tap, a refused
    // page, a page whose rows all dedupe away — leaves the row exactly where it
    // was, and only rows landing ABOVE it move it down. So a live turn
    // appending at the tail can never fire this, however long the anchor waits.
    LaunchedEffect(state.transcript, prependAnchor) {
        val anchor = prependAnchor ?: return@LaunchedEffect
        val index = state.transcript.indexOfFirst { it.id == anchor.entryId }
        // The row the reader was on is gone — a session swap, or a compaction
        // rewrite. There is nothing to restore and nothing left to wait for.
        if (index < 0) {
            prependAnchor = null
            return@LaunchedEffect
        }
        if (index <= anchor.entryIndex) return@LaunchedEffect
        prependAnchor = null
        listState.scrollToItem(index + leadingItems, anchor.scrollOffset)
    }

    Box(modifier.fillMaxWidth()) {
        Transcript(
            entries = state.transcript,
            imageLoader = state.imageLoader,
            listState = listState,
            onBranchFromReply = onBranchFromReply,
            onRegenerateReply = onRegenerateReply,
            onShowEarlier = if (!state.canShowEarlierMessages) {
                null
            } else {
                {
                    val entries = transcript.value
                    val top = (listState.firstVisibleItemIndex - leadingItemsNow.value)
                        .coerceIn(0, entries.lastIndex.coerceAtLeast(0))
                    prependAnchor = entries.getOrNull(top)?.let { row ->
                        TranscriptAnchor(row.id, listState.firstVisibleItemScrollOffset, top)
                    }
                    onShowEarlier()
                }
            },
            readAloud = state.readAloud,
            onToggleReadAloud = onToggleReadAloud,
            isWorking = state.activeSession?.status == SessionStatus.Working,
            activityStartedAtMillis = state.activeSession?.activityStartedAtMillis,
            progress = state.activeSession?.progress,
            contentPadding = PaddingValues(
                start = HermesTheme.spacing.pageInset,
                end = HermesTheme.spacing.pageInset,
                top = HermesTheme.spacing.blockGap,
                bottom = HermesTheme.spacing.blockGap,
            ),
            // Read from the homed id rather than from the summary: a session
            // created a frame ago has an id and no row yet, and that is exactly
            // the frame a splash must not appear in. The count comes from the
            // summary for the same reason in reverse — a null one means the
            // row has not landed, so an empty transcript proves nothing yet.
            showIntroSplash = shouldShowIntroSplash(
                enabled = introSplashEnabled,
                activeSessionId = state.activeSessionId,
                transcriptEmpty = state.transcriptIsEmpty,
                turnRunning = state.activeSession?.status == SessionStatus.Working,
                sessionMessageCount = state.activeSession?.messageCount,
            ),
            introSplashContext = IntroSplashContext(
                projectLabel = state.activeSessionProject?.label,
                worktreePath = state.activeSession?.worktreePath,
            ),
        )
        if (showJump) {
            JumpToLatestButton(
                hasUnseenActivity = hasUnseenActivity,
                onClick = { scope.launch { listState.scrollToTail() } },
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp),
            )
        }
        // Scrolling changes firstVisibleItemIndex at every item boundary. Read
        // it inside a derived state so only the pin — not the whole pane —
        // recomposes, and only when the *owning* turn actually changes.
        val prompt by remember(listState, state.transcript, leadingItems) {
            derivedStateOf {
                val entries = state.transcript
                if (entries.isEmpty()) return@derivedStateOf null
                val firstVisible = (listState.firstVisibleItemIndex - leadingItems)
                    .coerceIn(0, entries.lastIndex)
                if (entries.getOrNull(firstVisible) is UserTurn) return@derivedStateOf null
                for (index in firstVisible - 1 downTo 0) {
                    (entries[index] as? UserTurn)?.let { return@derivedStateOf it }
                }
                null
            }
        }
        if (prompt != null) {
            val owner = prompt!!
            val promptBody = remember(owner.id, owner.text) {
                ImageRefLines.split(owner.text).first.takeIf(String::isNotBlank)
            }
            if (promptBody != null) {
                // Capture the id and the transcript holder, never the whole
                // ChatUiState: this lambda must keep one identity across every
                // scroll recomposition.
                val onReturn = remember(state.activeSession?.id, owner.id) {
                    {
                        val currentIndex = transcript.value.indexOfFirst { it.id == owner.id }
                        if (currentIndex >= 0) {
                            following = false
                            scope.launch { listState.scrollToItem(currentIndex + leadingItemsNow.value) }
                        }
                    }
                }
                StickyCurrentPrompt(
                    promptId = owner.id,
                    body = promptBody,
                    listState = listState,
                    onClick = onReturn,
                    modifier = Modifier.align(Alignment.TopCenter),
                )
            }
        }
    }
}

/**
 * Where the viewport sat before an older page was prepended: the transcript row
 * that was on top, how far into it the viewport began, and where that row sat in
 * the transcript at that moment — which is how the restore knows rows landed
 * *above* the reader rather than firing on the press itself or on a later
 * append at the tail.
 */
private data class TranscriptAnchor(
    val entryId: String,
    val scrollOffset: Int,
    val entryIndex: Int,
)

/**
 * Whether [current] is [previous] with rows added only at the head — the shape a
 * prepended older page makes, and nothing else does.
 *
 * The two ends are enough to say it: the list grew, the row that used to be
 * first is now exactly that far down, and the last row is unchanged. A streamed
 * delta rewrites an entry in place and does not grow the list; an appended turn
 * moves the last row.
 *
 * A page landing in the same emission as a turn appended at the tail is both
 * shapes at once, and it answers false — it falls through to the ordinary path
 * deliberately. That emission carries real activity below the reader, which the
 * head path would swallow. The anchor restore fires on it as well, so the two
 * can order a scroll in one frame: if the reader had returned to the tail with
 * an anchor still armed, the restore pulls them back up and the follow pushes
 * them down. The loser is a viewport for a frame, never a row — and the case
 * needs a backfill page and a live turn to settle into one snapshot, which is
 * why it is left as the honest fall-through rather than guessed at.
 */
private fun grewAtHead(previous: List<TranscriptEntry>, current: List<TranscriptEntry>): Boolean {
    val added = current.size - previous.size
    if (added <= 0 || previous.isEmpty()) return false
    return current[added].id == previous.first().id && current.last().id == previous.last().id
}

/**
 * Mobile form of Desktop's sticky human-turn continuity. It retains the user
 * bubble grammar and returns to its source without owning transcript state.
 * Desktop: `apps/desktop/src/components/assistant-ui/thread/list.tsx:194-232,350-372`,
 * `apps/desktop/src/components/assistant-ui/thread/user-message.tsx:28-52,321-367`, and
 * `apps/desktop/src/styles.css:1538-1569` @ 45fcaaa54aae2d03ab816fb61c6ba312d3ac67b8.
 */
@Composable
private fun StickyCurrentPrompt(
    promptId: String,
    body: String,
    listState: LazyListState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = HermesTheme.tokens
    var overflowing by remember(promptId, body) { mutableStateOf(false) }
    val fade = remember(tokens.userBubble) {
        Brush.verticalGradient(0f to tokens.userBubble.copy(alpha = 0f), 1f to tokens.userBubble)
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(tokens.chatSurface)
            .padding(
                start = HermesTheme.spacing.pageInset,
                end = HermesTheme.spacing.pageInset,
                top = HermesTheme.spacing.turnGap,
                bottom = HermesTheme.spacing.turnGap,
            ),
    ) {
        UserTurnBubble(
            body = body,
            contentDescription = "Current prompt: $body",
            modifier = Modifier
                .fillMaxWidth()
                // This is a sibling overlay, so explicitly share the
                // transcript's scroll state with identical gesture direction.
                // Clickable keeps taps while this recognizer owns drags and
                // flings begun on the bubble.
                .scrollable(
                    state = listState,
                    orientation = Orientation.Vertical,
                    reverseDirection = ScrollableDefaults.reverseDirection(
                        layoutDirection = LocalLayoutDirection.current,
                        orientation = Orientation.Vertical,
                        reverseScrolling = false,
                    ),
                )
                .heightIn(min = HermesTheme.spacing.touchTarget)
                .clipToBounds(),
            maxLines = 4,
            overflow = TextOverflow.Clip,
            onClick = onClick,
            onClickLabel = "Return to prompt",
            onTextLayout = { overflowing = it.hasVisualOverflow },
        ) {
            // The clipped fourth line reads as a hard cut without this; the
            // fade is what says "there is more of this prompt above".
            if (overflowing) {
                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(18.dp).background(fade))
            }
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

@Composable
private fun ComposerPane(state: ChatUiState, actions: ChatActions, gatewayDoor: StatusAction?) {
    val composerStatus = state.activeSession?.composerStatus
    val hasQueue = state.composer.runtime.queueEntries.isNotEmpty()
    val fuseStatusStack = composerStatusGroupCount(composerStatus, hasQueue) == 1 &&
        state.composer.runtime.pendingInput == null && state.backgroundPendingInput == null
    val latestSettledToolId = state.transcript.asReversed().firstNotNullOfOrNull { entry ->
        (entry as? ToolActivity)?.takeIf { it.state != ToolState.Running }?.id
    }
    Column(Modifier.imePadding().navigationBarsPadding()) {
        LaunchedEffect(state.activeSession?.id) { actions.onComposerStatusOpened() }
        LaunchedEffect(
            state.activeSession?.id,
            state.activeSession?.worktreePath,
            state.isStreaming,
            latestSettledToolId,
        ) {
            actions.onRefreshCodingContext()
        }
        ComposerStatusStack(
            activeSessionId = state.activeSession?.id,
            status = state.activeSession?.composerStatus,
            onRefreshProcesses = actions.onRefreshProcesses,
            onKillProcess = actions.onKillProcess,
            hasQueue = hasQueue,
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
            fusedToComposer = fuseStatusStack,
            modifier = Modifier.padding(
                start = HermesTheme.spacing.pageInset + 8.dp,
                top = 4.dp,
                end = HermesTheme.spacing.pageInset + 8.dp,
                bottom = if (fuseStatusStack) 0.dp else 4.dp,
            ),
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
        CodingReviewSheet(
            state = state.composer.codingReview,
            onDismiss = actions.onDismissCodingReview,
        )
        Composer(
            draft = state.draft,
            onDraftChange = actions.onDraftChange,
            onSend = actions.onSend,
            onStop = actions.onStop,
            isStreaming = state.isStreaming && state.connection.status == GatewayConnectionStatus.Connected,
            canSend = state.canSend,
            connected = state.connection.status == GatewayConnectionStatus.Connected,
            statusLine = state.composerStatus(),
            // A notice about a project or a profile is reporting, not
            // directing, and `composerStatus` renders it ahead of the
            // connection — so it must not inherit the connection's door.
            statusAction = gatewayDoor.takeIf { state.notice == null },
            editorIdentity = state.activeSession?.id,
            codingHeader = {
                CodingStatusRow(
                    context = state.composer.codingContext,
                    onOpenReview = actions.onOpenCodingReview,
                )
            },
            fusedStatusAbove = fuseStatusStack,
            controls = state.composer,
            onSelectModel = actions.onSelectModel,
            onSelectReasoning = actions.onSelectReasoning,
            onSelectFast = actions.onSelectFast,
            onToggleModelVisible = actions.onToggleModelVisible,
            onSetProviderModelsVisible = actions.onSetProviderModelsVisible,
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
    /** The open session, so the header offers the same menu its row does. */
    sessionId: String?,
    onOpenSessions: (() -> Unit)?,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    /** Where [subtitle] goes while it names a connection problem; null otherwise. */
    gatewayDoor: StatusAction? = null,
    onRenameSession: (suspend (String, String) -> Unit)? = null,
    onDeleteSession: (suspend (String) -> Unit)? = null,
    onSetSessionPinned: ((String, Boolean) -> Unit)? = null,
    onSetSessionUnread: ((String, Boolean) -> Unit)? = null,
    onSetSessionArchived: ((String, Boolean) -> Unit)? = null,
    /** The open session's own durable flags, so the header menu says the same words its row does. */
    sessionPinned: Boolean = false,
    sessionUnread: Boolean = false,
    sessionArchived: Boolean = false,
    contextMeter: ContextMeterState? = null,
    onOpenContextUsage: () -> Unit = {},
    approvalMode: ApprovalMode? = null,
    onSelectApprovalMode: (ApprovalMode) -> Unit = {},
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
            // The title and its status line are one block, centred on the icon
            // row's centre line. Every interactive part of the status line
            // takes its touch floor as overflow rather than as height
            // (`touchTargetOverflow`), so this Column is exactly two lines of
            // type tall and the title cannot float above the chrome's middle.
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
                    modifier = Modifier.testTag(CHAT_TITLE_TAG),
                )
                Row(
                    modifier = Modifier.fillMaxWidth().testTag(CHAT_SUBTITLE_ROW_TAG),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    // The only flexible child, so the two figures keep their
                    // full words on a 360dp phone and the prose is what gives
                    // way: a truncated `Sm` names no approval posture at all.
                    Text(
                        text = subtitle,
                        style = HermesTheme.type.scaffoldMeta,
                        color = tokens.scaffoldMeta,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .testTag(CHAT_SUBTITLE_TAG)
                            .statusAction(subtitle, gatewayDoor),
                    )
                    // Desktop's right-hand statusbar array is ordered
                    // `context-usage`, `session-timer`, then `approval-mode`
                    // (`apps/desktop/src/app/shell/hooks/use-statusbar-items.tsx:547-572`
                    // @ `3ca096de`) and is laid out in a plain flex row, so
                    // array order is left to right
                    // (`apps/desktop/src/app/shell/statusbar-controls.tsx:119-123`):
                    // Approvals sits *after* the meter, and the subtitle row
                    // keeps that order. They travel as one cluster, because
                    // `SpaceBetween` would otherwise push the two figures to
                    // opposite ends of a line Desktop keeps together.
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        if (contextMeter != null && contextMeter.label.isNotEmpty()) {
                            ContextMeter(state = contextMeter, onClick = onOpenContextUsage)
                        }
                        if (approvalMode != null) {
                            ApprovalModeChip(mode = approvalMode, onSelect = onSelectApprovalMode)
                        }
                    }
                }
            }
            // The open session's actions, identical to its sidebar row's:
            // both read the one item list in `sessionActionItems`.
            if (sessionId != null) {
                SessionActionsControl(
                    sessionId = sessionId,
                    sessionTitle = title,
                    tint = tokens.textSecondary,
                    pinned = sessionPinned,
                    unread = sessionUnread,
                    archived = sessionArchived,
                    onRename = onRenameSession?.let { rename -> { newTitle -> rename(sessionId, newTitle) } },
                    onDelete = onDeleteSession?.let { delete -> { delete(sessionId) } },
                    onSetPinned = onSetSessionPinned?.let { set -> { pinned -> set(sessionId, pinned) } },
                    onSetUnread = onSetSessionUnread?.let { set -> { unread -> set(sessionId, unread) } },
                    onSetArchived = onSetSessionArchived?.let { set -> { archived -> set(sessionId, archived) } },
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

/**
 * Whether the chrome is currently naming a connection problem the Gateways
 * surface is the answer to.
 *
 * `Connecting` is deliberately not one: it is a report on something already
 * happening, and a door out of it would be a door out of the thing the person
 * is waiting for.
 */
private val ChatUiState.gatewayNeedsAttention: Boolean
    get() = connection.status == GatewayConnectionStatus.NeedsAttention ||
        connection.status == GatewayConnectionStatus.Disconnected

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
    isStreaming -> "Hermes is responding — use Stop to end the turn"
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
