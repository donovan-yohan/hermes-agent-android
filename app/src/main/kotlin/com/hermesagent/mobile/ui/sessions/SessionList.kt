package com.hermesagent.mobile.ui.sessions

import android.animation.ValueAnimator
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.DropdownMenu
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.hermesagent.mobile.data.session.ProjectSummary
import com.hermesagent.mobile.data.prefs.SidebarGrouping
import com.hermesagent.mobile.data.session.SessionListRow
import com.hermesagent.mobile.data.session.SessionStatus
import com.hermesagent.mobile.data.session.SessionSummary
import com.hermesagent.mobile.data.session.ALL_PINNED_NOTE
import com.hermesagent.mobile.data.session.PINNED_SECTION_LABEL
import com.hermesagent.mobile.data.session.RESULTS_SECTION_LABEL
import com.hermesagent.mobile.data.session.noSessionsMatch
import com.hermesagent.mobile.data.session.displayStatus
import com.hermesagent.mobile.data.session.isUnread
import com.hermesagent.mobile.data.session.label
import com.hermesagent.mobile.data.profiles.HermesProfile
import com.hermesagent.mobile.ui.chat.ArchivedPoolState
import com.hermesagent.mobile.ui.chat.ProjectProfileScope
import com.hermesagent.mobile.ui.common.EmptyState
import com.hermesagent.mobile.ui.common.Hairline
import com.hermesagent.mobile.ui.common.DitherMark
import com.hermesagent.mobile.ui.common.HermesIcon
import com.hermesagent.mobile.ui.common.HermesIconButton
import com.hermesagent.mobile.ui.common.HermesIconGlyph
import com.hermesagent.mobile.ui.common.LabelledField
import com.hermesagent.mobile.ui.common.PrimaryButton
import com.hermesagent.mobile.ui.common.ProfileTag
import com.hermesagent.mobile.ui.common.SearchField
import com.hermesagent.mobile.ui.common.SectionLabel
import com.hermesagent.mobile.ui.common.StatusDot
import com.hermesagent.mobile.ui.common.TextButton
import com.hermesagent.mobile.ui.theme.HermesTheme
import com.hermesagent.mobile.ui.theme.HermesTokens
import kotlin.math.abs

/**
 * Sessions.
 *
 * On a phone this is a drawer; on a wide screen it is the persistent rail.
 * Same composable either way — the layout decides where it lives, not what it
 * is, which is what keeps "switching context is a re-home, not a reboot"
 * (`apps/desktop/AGENTS.md` @ `3ca096de`) true on both.
 *
 * Flat by construction: dividers only where the calendar bucket changes, no
 * per-row card, no nested rounded boxes. The active row is marked by fill and
 * by an accent edge, not by an outline.
 */
@Composable
fun SessionList(
    rows: List<SessionListRow>,
    projects: List<ProjectSummary>,
    projectsAvailable: Boolean?,
    sidebarGrouping: SidebarGrouping,
    selectedProject: ProjectSummary?,
    projectLoading: Boolean,
    activeSessionId: String?,
    query: String,
    canCreate: Boolean,
    onQueryChange: (String) -> Unit,
    onSidebarGroupingChange: (SidebarGrouping) -> Unit,
    onSelectProject: (String) -> Unit,
    onExitProject: () -> Unit,
    onCreateProject: (name: String, folderPath: String) -> Unit,
    onSelect: (String) -> Unit,
    onCreate: () -> Unit,
    modifier: Modifier = Modifier,
    onRenameSession: (suspend (String, String) -> Unit)? = null,
    onDeleteSession: (suspend (String) -> Unit)? = null,
    /** Not `suspend`: the row that owns the press leaves the list it was on. */
    onSetSessionPinned: ((String, Boolean) -> Unit)? = null,
    onSetSessionUnread: ((String, Boolean) -> Unit)? = null,
    onSetSessionArchived: ((String, Boolean) -> Unit)? = null,
    /** Desktop's `Archived` filter: the list is a view of the archived set instead. */
    archivedVisible: Boolean = false,
    /** What that set's own read has said. `Nothing archived` waits on it. */
    archivedPool: ArchivedPoolState = ArchivedPoolState.Idle,
    onArchivedVisibleChange: (Boolean) -> Unit = {},
    /** Loaded rows that are still unread; Desktop hides the action at zero. */
    unreadCount: Int = 0,
    /**
     * Desktop's `showSessionSkeletons` (`sidebar/index.tsx:1423` @ `3ca096de`):
     * a live-pool page is on the wire and this scope has no rows yet. The blank
     * state is a claim about the *account*, so it waits behind this exactly as
     * Desktop's does at `:1426-1427,1912`.
     */
    sessionsLoading: Boolean = false,
    onMarkAllRead: () -> Unit = {},
    /**
     * Rail chrome above the section header — the connection switcher. A slot
     * rather than a parameter block so this list keeps knowing only about
     * sessions.
     */
    header: @Composable () -> Unit = {},
    /** The foot rail's profiles and scope; empty means no Gateway has answered. */
    profileRail: ProfileRailState = ProfileRailState(),
    profileRailActions: ProfileRailActions = ProfileRailActions(),
    /** How the project catalog relates to the profile scope the sidebar is in. */
    projectScope: ProjectProfileScope = ProjectProfileScope.Own,
) {
    val tokens = HermesTheme.tokens
    val showingProjectOverview = sidebarGrouping == SidebarGrouping.Project && selectedProject == null
    val title = selectedProject?.label ?: if (showingProjectOverview) "Projects" else "Sessions"
    var menuVisible by rememberSaveable { mutableStateOf(false) }
    var searchVisible by rememberSaveable { mutableStateOf(false) }
    var projectCreateVisible by rememberSaveable { mutableStateOf(false) }
    val searchIsVisible = searchVisible || query.isNotBlank()

    BoxWithConstraints(modifier.fillMaxSize().background(tokens.sidebarSurface)) {
        // A landscape rail with the keyboard up is shorter than this pane's own
        // fixed chrome: the switcher, the title row and the search field
        // together outgrow it before the list is even asked for. A Column
        // overflows in silence, so what actually happened on a device was the
        // focused search field clipped to a sliver, the weighted list measured
        // at zero, and nothing on screen that scrolled — the covered part of
        // the pane was unreachable in the same way the keyboard makes a page
        // unreachable. Below that height the pane scrolls as one and the list
        // takes a fixed floor rather than the remainder, which is also what
        // gives the focused field a scrollable ancestor to bring itself into
        // view within. Above it nothing changes, so the drawer and the
        // portrait rail keep the layout they have.
        val cramped = maxHeight < RAIL_SCROLLS_BELOW
        Column(
            Modifier
                .fillMaxSize()
                .then(if (cramped) Modifier.verticalScroll(rememberScrollState()) else Modifier),
        ) {
            // Exact, not a minimum: a cramped pane measures its children with an
            // unbounded height, and a LazyColumn given one throws.
            val listSlot = if (cramped) Modifier.height(CRAMPED_LIST_HEIGHT) else Modifier.weight(1f)
            header()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 8.dp, end = 4.dp, top = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    DitherMark(tokens.accent)
                    Text(
                        text = title.uppercase(),
                        style = HermesTheme.type.panelLabel,
                        color = tokens.accent,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                HermesIconButton(
                    icon = HermesIcon.Add,
                    contentDescription = if (showingProjectOverview) "New project" else "New session",
                    onClick = {
                        if (showingProjectOverview) projectCreateVisible = true else onCreate()
                    },
                    enabled = canCreate && (!showingProjectOverview || projectsAvailable == true),
                )
                if (selectedProject != null) {
                    HermesIconButton(
                        icon = HermesIcon.ListUnordered,
                        contentDescription = "All projects",
                        onClick = onExitProject,
                    )
                }
                Box {
                    HermesIconButton(
                        icon = HermesIcon.ListFilter,
                        contentDescription = "Filters",
                        onClick = { menuVisible = !menuVisible },
                        active = menuVisible || query.isNotBlank() || archivedVisible ||
                            sidebarGrouping != SidebarGrouping.Date,
                    )
                    SidebarViewMenu(
                        expanded = menuVisible,
                        grouping = sidebarGrouping,
                        projectGroupingAvailable = projectsAvailable != false,
                        searchVisible = searchIsVisible,
                        searchesProjects = showingProjectOverview,
                        archivedVisible = archivedVisible,
                        unreadCount = unreadCount,
                        // Desktop's option rows deliberately keep the menu open
                        // (`filter-menu.tsx:124-126` @ `3ca096de`); only the
                        // actions at the bottom dismiss it.
                        onToggleArchived = { onArchivedVisibleChange(!archivedVisible) },
                        onMarkAllRead = {
                            menuVisible = false
                            onMarkAllRead()
                        },
                        onDismiss = { menuVisible = false },
                        onGroupingChange = { grouping ->
                            menuVisible = false
                            onSidebarGroupingChange(grouping)
                        },
                        onToggleSearch = {
                            menuVisible = false
                            searchVisible = !searchIsVisible
                            if (searchIsVisible) onQueryChange("")
                        },
                    )
                }
            }

            if (searchIsVisible) {
                // Desktop's own field, verbatim: placeholder `Search sessions…`
                // with the ellipsis (`i18n/en.ts:2201`), spoken as `Search
                // sessions` (`:2200`), behind the leading search glyph
                // (`components/ui/search-field.tsx:69`) — all @ `3ca096de`.
                // `Search projects` is this app's own: Desktop has no
                // project-overview search field to copy, and the ledger in
                // `docs/parity/session-search.md` says so.
                SearchField(
                    value = query,
                    onValueChange = onQueryChange,
                    placeholder = if (showingProjectOverview) "Search projects" else "Search sessions…",
                    spokenName = if (showingProjectOverview) "Search projects" else "Search sessions",
                    leadingGlyph = HermesIcon.Search,
                    modifier = Modifier.padding(horizontal = HermesTheme.spacing.pageInset, vertical = 4.dp),
                )
            }

            if (sidebarGrouping == SidebarGrouping.Project && projectsAvailable == null) {
                Text(
                    text = if (canCreate) "Loading projects…" else "Connect to a Gateway to load projects.",
                    style = HermesTheme.type.scaffoldMeta,
                    color = tokens.textTertiary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            } else if (sidebarGrouping == SidebarGrouping.Project && projectScope != ProjectProfileScope.Own) {
                // The catalog is one profile's either way; only the next action
                // differs between browsing everything and standing in another
                // profile, where there is nothing here to browse.
                Text(
                    text = when (projectScope) {
                        ProjectProfileScope.Unified ->
                            "Projects come from one profile on this Gateway, not from every profile in view."
                        else ->
                            "Projects come from one profile on this Gateway. Switch to the default profile to browse them."
                    },
                    style = HermesTheme.type.scaffoldMeta,
                    color = tokens.textTertiary,
                    modifier = Modifier
                        .padding(horizontal = HermesTheme.spacing.pageInset, vertical = 4.dp)
                        .testTag(PROJECT_PROFILE_SCOPE_NOTE),
                )
            } else if (sidebarGrouping == SidebarGrouping.Project && projectsAvailable == false) {
                Text(
                    text = "Project views aren’t available on this Gateway.",
                    style = HermesTheme.type.scaffoldMeta,
                    color = tokens.textTertiary,
                    modifier = Modifier.padding(horizontal = HermesTheme.spacing.pageInset, vertical = 4.dp),
                )
            }

            when {
                showingProjectOverview && !projectScope.showsCatalog -> Spacer(listSlot)

                showingProjectOverview && projectsAvailable == true && projects.isEmpty() -> EmptyState(
                    title = if (query.isBlank()) "No projects" else "Nothing matches",
                    description = when {
                        query.isNotBlank() -> "No project or recent session contains “$query”."
                        canCreate -> "Create a project with the + above."
                        else -> "No projects were returned by this Gateway."
                    },
                    modifier = listSlot,
                )

                showingProjectOverview -> LazyColumn(
                    modifier = listSlot.testTag("Project list"),
                    contentPadding = PaddingValues(bottom = 12.dp),
                ) {
                    items(items = projects, key = { "project-${it.id}" }) { project ->
                        ProjectRow(
                            project = project,
                            activeSessionId = activeSessionId,
                            onOpen = { onSelectProject(project.id) },
                            onSelectSession = onSelect,
                        )
                    }
                }

                projectLoading -> EmptyState(
                    title = "Opening project…",
                    description = "Hermes is loading this project’s session history.",
                    modifier = listSlot,
                )

                // The archived set is read on its own, and until that read has
                // answered nothing here knows whether the account has archived
                // chats. `Nothing archived` is a claim about the account, so it
                // waits — exactly as the project slot above waits on its own
                // load. Desktop keeps the same marker but spends it only on
                // re-entry (`store/sidebar-archive.ts:12,19,28` @ `3ca096de`:
                // `$archivedSessionsLoading` gates the fetch and nothing
                // renders it), and its `catch` sets the set to `[]` — so on
                // Desktop a failed read reads as an empty account. On a phone,
                // where the Gateway is across a network that drops, that is the
                // sentence this app refuses to write; see
                // `docs/parity/session-list-sections.md`.
                rows.isEmpty() && archivedVisible && query.isBlank() -> when (archivedPool) {
                    ArchivedPoolState.Idle, ArchivedPoolState.Loading -> EmptyState(
                        title = "Loading archived chats…",
                        description = "Hermes is loading the chats you archived.",
                        modifier = listSlot.testTag(ARCHIVED_LOADING_STATE),
                    )

                    // The Gateway cannot be asked at all, so the honest sentence
                    // is about the Gateway rather than about the account.
                    ArchivedPoolState.Unsupported -> EmptyState(
                        title = "Archived chats unavailable",
                        description = "Archived chats need a newer Hermes on this Gateway.",
                        modifier = listSlot.testTag(ARCHIVED_UNSUPPORTED_STATE),
                    )

                    ArchivedPoolState.Failed -> EmptyState(
                        title = "Couldn’t load archived chats",
                        description = "Check the Gateway, then turn Archived off and on again.",
                        modifier = listSlot.testTag(ARCHIVED_FAILED_STATE),
                    )

                    // Desktop's archived empty state, verbatim
                    // (`i18n/en.ts:1154-1155` @ `3ca096de`). The Archived view
                    // is its own set, so "no sessions" would be the wrong
                    // sentence: there are sessions, none of them archived.
                    ArchivedPoolState.Loaded -> EmptyState(
                        title = "Nothing archived",
                        description = "Archive a chat to hide it here.",
                        modifier = listSlot.testTag(ARCHIVED_EMPTY_STATE),
                    )
                }

                // Reachable with a live query only inside the Archived view,
                // where the list stays a local filter over its own pool and so
                // renders no `Results` section to hold the sentence. Desktop
                // says the same thing either way, so this is the same note the
                // `Results` section draws — one sentence and no second line,
                // exactly as Desktop's search empty has none
                // (`sidebar/index.tsx:1618-1622` @ `3ca096de`). It is the
                // section's own note rather than a shared [EmptyState], so the
                // centring the wrapped sentence needs stays here instead of
                // reaching every other empty state in the app.
                rows.isEmpty() && query.isNotBlank() -> Box(listSlot) {
                    NoResultsNote(query.trim())
                }

                // Desktop's `SidebarSessionSkeletons`
                // (`section-states.tsx:11-24` @ `3ca096de`), and the reason the
                // blank state below cannot be reached during a fetch:
                // `showSessionSections` is true while `showSessionSkeletons` is
                // (`sidebar/index.tsx:1426-1427`), so `No sessions yet` is never
                // the sentence on screen during the first list read or after a
                // reconnect. This app's rail says the same thing the same way.
                rows.isEmpty() && sessionsLoading ->
                    Box(listSlot) { SidebarSessionSkeletons(SESSION_SKELETON_TAG) }

                // Desktop's `SidebarBlankState`
                // (`apps/desktop/src/app/chat/sidebar/section-states.tsx:26-42`
                // @ `3ca096de5f8183cb2e0ec23673f294d5978656a3`), which it
                // renders on exactly this condition: nothing filtered, nothing
                // loading, no sessions and no projects (`sidebar/index.tsx:1427,1912`).
                rows.isEmpty() -> SidebarBlankState(
                    canCreateProject = canCreate && projectsAvailable == true,
                    onNewProject = { projectCreateVisible = true },
                    // Desktop's sidebar is never disconnected, so it has no
                    // sentence for this. A phone's is, and losing the one line
                    // that says which action comes first would cost more than
                    // the divergence does. See `docs/parity/empty-states.md`.
                    disconnectedNote = "Connect to a Gateway to start a session.".takeIf { !canCreate },
                    modifier = listSlot,
                )

                else -> {
                    LazyColumn(
                        modifier = listSlot.testTag("Session list"),
                        contentPadding = PaddingValues(bottom = 12.dp),
                    ) {
                        items(items = rows, key = { it.key() }) { row ->
                            when (row) {
                                is SessionListRow.Divider -> SectionLabel(
                                    text = row.bucket.label(),
                                    modifier = Modifier.padding(
                                        start = HermesTheme.spacing.pageInset,
                                        top = 14.dp,
                                        bottom = 4.dp,
                                    ),
                                )

                                is SessionListRow.PinnedLabel -> SectionLabel(
                                    text = PINNED_SECTION_LABEL,
                                    modifier = Modifier
                                        .padding(
                                            start = HermesTheme.spacing.pageInset,
                                            top = 14.dp,
                                            bottom = 4.dp,
                                        )
                                        .testTag(PINNED_SECTION_TAG),
                                )

                                is SessionListRow.AllPinnedNote -> Text(
                                    text = ALL_PINNED_NOTE,
                                    style = HermesTheme.type.scaffoldMeta,
                                    color = tokens.textTertiary,
                                    modifier = Modifier.padding(
                                        horizontal = HermesTheme.spacing.pageInset,
                                        vertical = 12.dp,
                                    ),
                                )

                                is SessionListRow.ResultsLabel -> SectionLabel(
                                    text = RESULTS_SECTION_LABEL,
                                    modifier = Modifier
                                        .padding(
                                            start = HermesTheme.spacing.pageInset,
                                            top = 14.dp,
                                            bottom = 4.dp,
                                        )
                                        .testTag(RESULTS_SECTION_TAG),
                                )

                                is SessionListRow.NoResultsNote -> NoResultsNote(row.query)

                                is SessionListRow.SearchSkeletons ->
                                    SidebarSessionSkeletons(SEARCH_SKELETON_TAG)

                                is SessionListRow.Row -> SessionRow(
                                    session = row.session,
                                    active = row.session.id == activeSessionId,
                                    onClick = { onSelect(row.session.id) },
                                    // A single-profile scope already says which
                                    // profile every row belongs to, so the tag only
                                    // earns its place in the unified view.
                                    owner = if (profileRail.scope.isAll) {
                                        profileRail.owner(row.session.remoteProfile)
                                    } else {
                                        null
                                    },
                                    onRename = onRenameSession?.let { rename -> { newTitle -> rename(row.session.id, newTitle) } },
                                    onDelete = onDeleteSession?.let { delete -> { delete(row.session.id) } },
                                    onSetPinned = onSetSessionPinned?.let { set -> { pinned -> set(row.session.id, pinned) } },
                                    onSetUnread = onSetSessionUnread?.let { set -> { unread -> set(row.session.id, unread) } },
                                    onSetArchived = onSetSessionArchived?.let { set -> { archived -> set(row.session.id, archived) } },
                                )
                            }
                        }
                    }
                }
            }

            ProfileRail(state = profileRail, actions = profileRailActions)
        }
    }

    if (projectCreateVisible) {
        ProjectCreateDialog(
            onDismiss = { projectCreateVisible = false },
            onCreate = { name, folderPath ->
                projectCreateVisible = false
                onCreateProject(name, folderPath)
            },
        )
    }
}

@Composable
private fun SidebarViewMenu(
    expanded: Boolean,
    grouping: SidebarGrouping,
    projectGroupingAvailable: Boolean,
    searchVisible: Boolean,
    searchesProjects: Boolean,
    onDismiss: () -> Unit,
    onGroupingChange: (SidebarGrouping) -> Unit,
    onToggleSearch: () -> Unit,
    archivedVisible: Boolean = false,
    unreadCount: Int = 0,
    onToggleArchived: () -> Unit = {},
    onMarkAllRead: () -> Unit = {},
) {
    val tokens = HermesTheme.tokens
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = Modifier
            .widthIn(min = 220.dp)
            .border(1.dp, tokens.strokePrimary, RoundedCornerShape(6.dp))
            .testTag("Sidebar view menu"),
        shape = RoundedCornerShape(6.dp),
        containerColor = tokens.cardSurface,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Column(Modifier.selectableGroup()) {
            Text(
                text = "GROUPING",
                style = HermesTheme.type.panelLabel,
                color = tokens.textTertiary,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            )
            SidebarGroupingOption(
                label = "Updated",
                icon = HermesIcon.Clock,
                selected = grouping == SidebarGrouping.Date,
                onClick = { onGroupingChange(SidebarGrouping.Date) },
            )
            SidebarGroupingOption(
                label = "Project",
                icon = HermesIcon.RootFolder,
                selected = grouping == SidebarGrouping.Project,
                enabled = projectGroupingAvailable,
                onClick = { onGroupingChange(SidebarGrouping.Project) },
            )
        }
        Hairline()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = HermesTheme.spacing.touchTarget)
                .clickable(role = Role.Button, onClick = onToggleSearch)
                .semantics {
                    contentDescription = when {
                        searchVisible -> "Hide search"
                        searchesProjects -> "Search projects"
                        // `Search sessions` (`i18n/en.ts:2200` @ `3ca096de`).
                        else -> "Search sessions"
                    }
                }
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            HermesIconGlyph(HermesIcon.Search, size = 13.sp)
            Text(
                text = if (searchVisible) "Hide search" else "Search",
                style = HermesTheme.type.scaffold,
                color = tokens.textSecondary,
                modifier = Modifier.weight(1f),
            )
        }
        // Desktop's own checkbox row and its own bare label, at the foot of the
        // filter group (`app/chat/sidebar/filter-menu.tsx:393-397` @
        // `3ca096de`) — the word is a literal there, not an i18n key, and the
        // option carries no glyph: `OptionGlyph` returns `null` without an
        // `icon` or a `dot` (`:116-122`).
        SidebarToggleOption(
            label = ARCHIVED_FILTER,
            checked = archivedVisible,
            onClick = onToggleArchived,
            testTag = ARCHIVED_FILTER_OPTION,
        )
        // Desktop's own item, in Desktop's own place: last, after the rule that
        // closes the option group, plain, no glyph, and *disabled* rather than
        // hidden at zero unread (`filter-menu.tsx:404,411-413` @ `3ca096de`).
        Hairline()
        SidebarActionOption(
            label = MARK_ALL_READ,
            enabled = unreadCount > 0,
            onClick = onMarkAllRead,
            testTag = MARK_ALL_READ_OPTION,
        )
    }
}

/**
 * A checkbox row in the filter menu: Desktop's `OptionCheckbox`
 * (`filter-menu.tsx:128-141` @ `3ca096de`). Selecting one deliberately leaves
 * the menu open — `keepOpen` (`:124-126`: "Every option row … leaves the menu
 * open, so a whole view can be set up in one pass. Only the actions at the
 * bottom dismiss it").
 */
@Composable
private fun SidebarToggleOption(
    label: String,
    checked: Boolean,
    onClick: () -> Unit,
    testTag: String,
) {
    val tokens = HermesTheme.tokens
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = HermesTheme.spacing.touchTarget)
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = { onClick() })
            .testTag(testTag)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = label,
            style = HermesTheme.type.scaffold,
            color = tokens.textSecondary,
            modifier = Modifier.weight(1f),
        )
        if (checked) HermesIconGlyph(HermesIcon.Check, color = tokens.accent, size = 13.sp)
    }
}

/**
 * A plain action row at the foot of the filter menu: Desktop's
 * `DropdownMenuItem` (`filter-menu.tsx:411-413` @ `3ca096de`). No glyph, and it
 * stays mounted when it cannot act — a control that vanishes at zero teaches
 * nobody it exists.
 */
@Composable
private fun SidebarActionOption(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    testTag: String,
) {
    val tokens = HermesTheme.tokens
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = HermesTheme.spacing.touchTarget)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .testTag(testTag)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = label,
            style = HermesTheme.type.scaffold,
            color = if (enabled) tokens.textSecondary else tokens.textQuaternary,
            modifier = Modifier.weight(1f),
        )
    }
}

/** Desktop's own literal for the archived filter (`filter-menu.tsx:396` @ `3ca096de`). */
private const val ARCHIVED_FILTER = "Archived"

/** `Mark all as read` (`i18n/en.ts:2356` @ `3ca096de`). */
private const val MARK_ALL_READ = "Mark all as read"

internal const val ARCHIVED_FILTER_OPTION = "Archived filter option"
internal const val MARK_ALL_READ_OPTION = "Mark all as read option"

@Composable
private fun SidebarGroupingOption(
    label: String,
    icon: HermesIcon,
    selected: Boolean,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    val tokens = HermesTheme.tokens
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = HermesTheme.spacing.touchTarget)
            .selectable(
                selected = selected,
                enabled = enabled,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .semantics {
                contentDescription = "$label grouping"
            }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val contentColor = if (enabled) tokens.textSecondary else tokens.textQuaternary
        HermesIconGlyph(icon, color = contentColor, size = 13.sp)
        Text(
            text = label,
            style = HermesTheme.type.scaffold,
            color = contentColor,
            modifier = Modifier.weight(1f),
        )
        if (selected) HermesIconGlyph(HermesIcon.Check, color = tokens.accent, size = 13.sp)
    }
}

@Composable
private fun ProjectCreateDialog(
    onDismiss: () -> Unit,
    onCreate: (name: String, folderPath: String) -> Unit,
) {
    val tokens = HermesTheme.tokens
    var name by rememberSaveable { mutableStateOf("") }
    var folderPath by rememberSaveable { mutableStateOf("") }
    val canSubmit = name.isNotBlank() && folderPath.isNotBlank()

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .widthIn(max = 420.dp)
                .fillMaxWidth()
                .background(tokens.cardSurface, RoundedCornerShape(10.dp))
                // A dialog is its own window and keeps `decorFitsSystemWindows`,
                // so the keyboard resizes it rather than drawing over it — the
                // IME inset here is always zero and padding for it would be a
                // lie. What the resize can do is leave the window shorter than
                // two fields and two buttons, so the content scrolls instead of
                // putting "Create project" out of reach.
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("New project", style = HermesTheme.type.bodyStrong, color = tokens.textPrimary)
            Text(
                "Projects group sessions by a folder on the connected Gateway.",
                style = HermesTheme.type.caption,
                color = tokens.textTertiary,
            )
            LabelledField(
                label = "Name",
                value = name,
                placeholder = "Project name",
                onValueChange = { name = it },
            )
            LabelledField(
                label = "Remote folder",
                value = folderPath,
                placeholder = "/home/you/project",
                onValueChange = { folderPath = it },
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(label = "Cancel", onClick = onDismiss, color = tokens.textSecondary)
                PrimaryButton(
                    label = "Create project",
                    onClick = { onCreate(name.trim(), folderPath.trim()) },
                    enabled = canSubmit,
                )
            }
        }
    }
}

@Composable
private fun ProjectRow(
    project: ProjectSummary,
    activeSessionId: String?,
    onOpen: () -> Unit,
    onSelectSession: (String) -> Unit,
) {
    val tokens = HermesTheme.tokens
    val countLabel = if (project.sessionCount == 1) "1 session" else "${project.sessionCount} sessions"
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = HermesTheme.spacing.touchTarget)
                .clickable(onClick = onOpen)
                .testTag("Project row ${project.id}")
                .padding(horizontal = HermesTheme.spacing.pageInset, vertical = 8.dp)
                .semantics { contentDescription = "Open project ${project.label}. $countLabel" },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = project.label,
                style = HermesTheme.type.sessionTitle,
                color = if (project.isHome) tokens.accent else tokens.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = project.sessionCount.toString(),
                style = HermesTheme.type.scaffoldMeta,
                color = tokens.textTertiary,
            )
        }
        project.previewSessions.forEach { session ->
            SessionRow(
                session = session,
                active = session.id == activeSessionId,
                onClick = { onSelectSession(session.id) },
            )
        }
    }
}

/** The one line that says the project catalog belongs to a single profile. */
internal const val PROJECT_PROFILE_SCOPE_NOTE = "Project profile scope note"

private fun SessionListRow.key(): String = when (this) {
    is SessionListRow.Divider -> "divider-${bucket.name}"
    is SessionListRow.PinnedLabel -> "divider-pinned"
    is SessionListRow.AllPinnedNote -> "note-all-pinned"
    is SessionListRow.ResultsLabel -> "label-results"
    is SessionListRow.NoResultsNote -> "note-no-results-${query}"
    is SessionListRow.SearchSkeletons -> "search-skeletons"
    is SessionListRow.Row -> session.id
}

/** The leading `Pinned` section label. */
internal const val PINNED_SECTION_TAG = "Pinned section"

/** The one section a live query renders, in place of Pinned and Recents. */
internal const val RESULTS_SECTION_TAG = "Results section"

/** The placeholder rows shown while the backend search is still in flight. */
internal const val SEARCH_SKELETON_TAG = "Search skeletons"

/** The Archived view with nothing in it. */
internal const val ARCHIVED_EMPTY_STATE = "Archived empty state"
internal const val ARCHIVED_LOADING_STATE = "Archived loading state"
internal const val ARCHIVED_FAILED_STATE = "Archived failed state"
internal const val ARCHIVED_UNSUPPORTED_STATE = "Archived unsupported state"

/** Desktop's `SidebarBlankState`: no filter, no sessions, no projects. */
internal const val SIDEBAR_BLANK_STATE = "Sidebar blank state"

/** The same five bars, standing in for the live list read rather than a search. */
internal const val SESSION_SKELETON_TAG = "Session skeletons"

/**
 * `sidebar.noSessions` and `sidebar.projects.newButton`, verbatim
 * (`apps/desktop/src/i18n/en.ts:2218,2223` @
 * `3ca096de5f8183cb2e0ec23673f294d5978656a3`). Note the missing full stop:
 * `commandCenter.noSessions` at `:1560` reads `No sessions yet.` **with** one,
 * and the sidebar's is the other string.
 */
internal const val NO_SESSIONS_YET = "No sessions yet"
internal const val NEW_PROJECT_BUTTON = "New project"

/**
 * Desktop's `SidebarBlankState`
 * (`apps/desktop/src/app/chat/sidebar/section-states.tsx:26-42` @
 * `3ca096de5f8183cb2e0ec23673f294d5978656a3`): a `root-folder` codicon in the
 * quaternary ink, the caption in the tertiary, and a ghost `New project` under
 * them, all `place-items-center` in the height the list section leaves.
 *
 * @param canCreateProject the same gate the header's `+` uses in project mode.
 *   A Gateway that serves no project RPC leaves the button visible and
 *   disabled rather than removing it.
 * @param disconnectedNote this app's own extra line, and null whenever Desktop
 *   would have nothing to add. Ledgered in `docs/parity/empty-states.md`.
 */
@Composable
private fun SidebarBlankState(
    canCreateProject: Boolean,
    onNewProject: () -> Unit,
    modifier: Modifier = Modifier,
    disconnectedNote: String? = null,
) {
    val tokens = HermesTheme.tokens
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .testTag(SIDEBAR_BLANK_STATE),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            HermesIconGlyph(
                icon = HermesIcon.RootFolder,
                color = tokens.textQuaternary,
                size = 20.sp,
            )
            Text(
                text = NO_SESSIONS_YET,
                style = HermesTheme.type.caption,
                color = tokens.textTertiary,
                textAlign = TextAlign.Center,
            )
            if (disconnectedNote != null) {
                Text(
                    text = disconnectedNote,
                    style = HermesTheme.type.caption,
                    color = tokens.textQuaternary,
                    textAlign = TextAlign.Center,
                )
            }
            GhostAction(
                label = NEW_PROJECT_BUTTON,
                icon = HermesIcon.Add,
                enabled = canCreateProject,
                onClick = onNewProject,
            )
        }
    }
}

/**
 * Desktop's `variant="ghost"` button: no fill, no border, the glyph inside the
 * label's own target. One target, not two — [HermesIconGlyph] clears itself out
 * of the tree, so the label is the only thing left to name the control.
 *
 * There is deliberately **no** `contentDescription` here. `clickable` merges its
 * descendants, and a name set on the merging node *concatenates* with the
 * label's own text rather than replacing it — the second-name hazard
 * `OutlineButton` documents. Desktop's ghost button is named by its text too.
 */
@Composable
private fun GhostAction(
    label: String,
    icon: HermesIcon,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = HermesTheme.tokens
    val ink = if (enabled) tokens.textSecondary else tokens.textQuaternary
    Row(
        modifier = modifier
            .heightIn(min = HermesTheme.spacing.touchTarget)
            .clip(RoundedCornerShape(6.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            // `clickable(enabled = false)` drops the click action but publishes
            // no disabled state, so a screen reader announces an ordinary
            // button that silently does nothing.
            .semantics { if (!enabled) disabled() }
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        HermesIconGlyph(icon, color = ink, size = 12.sp)
        Text(text = label, style = HermesTheme.type.caption, color = ink)
    }
}

/**
 * Desktop's `SidebarSessionSkeletons`, at this rail's scale
 * (`apps/desktop/src/app/chat/sidebar/section-states.tsx:12-24` @
 * `3ca096de5f8183cb2e0ec23673f294d5978656a3`): five placeholder rows, each a
 * short title bar and a trailing action square, in the session row's own
 * chrome. The widths are Desktop's five — `w-32 w-40 w-28 w-36 w-24`, which is
 * Tailwind's 8/10/7/9/6 rem — so the ragged edge that says "these are
 * placeholders, not content" is the same ragged edge.
 *
 * Hidden from the accessibility tree, exactly as Desktop hides it
 * (`aria-hidden` at `:14`): there is nothing here to read out, and the
 * surrounding `Results` label already says what is happening.
 *
 * @param tag which wait this stands in for. Desktop draws one component for
 *   both — the search read and the list read — and so does this; the tag keeps
 *   the two distinguishable in a journey without a second drawing of the same
 *   five bars.
 */
@Composable
private fun SidebarSessionSkeletons(tag: String) {
    val tokens = HermesTheme.tokens
    Column(Modifier.testTag(tag).clearAndSetSemantics {}) {
        SKELETON_WIDTHS.forEach { width ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = HermesTheme.spacing.pageInset, vertical = 2.dp)
                    .heightIn(min = 32.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .height(12.dp)
                        .width(width)
                        .background(tokens.strokeQuaternary, RoundedCornerShape(2.dp)),
                )
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier
                        .size(14.dp)
                        .background(tokens.strokeQuaternary.copy(alpha = 0.6f), RoundedCornerShape(2.dp)),
                )
            }
        }
    }
}

/** Desktop's `w-32 w-40 w-28 w-36 w-24` (`section-states.tsx:15` @ the pin). */
private val SKELETON_WIDTHS = listOf(128.dp, 160.dp, 112.dp, 144.dp, 96.dp)

/**
 * The `Results` section settled on nothing: `No sessions match “{query}”.`
 * (`i18n/en.ts:2203`, chosen at `sidebar/index.tsx:1618-1622` @ `3ca096de`).
 *
 * Centred, because the sentence quotes the reader's own query and a long one
 * wraps; centring it here rather than in the shared `EmptyState` keeps the
 * change inside the surface that needs it.
 */
@Composable
private fun NoResultsNote(query: String) {
    Text(
        text = noSessionsMatch(query),
        style = HermesTheme.type.scaffoldMeta,
        color = HermesTheme.tokens.textTertiary,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = HermesTheme.spacing.pageInset, vertical = 12.dp),
    )
}

@Composable
private fun SessionRow(
    session: SessionSummary,
    active: Boolean,
    onClick: () -> Unit,
    /** The owning profile's chip, or null when the scope already names it. */
    owner: HermesProfile? = null,
    onRename: (suspend (String) -> Unit)? = null,
    onDelete: (suspend () -> Unit)? = null,
    onSetPinned: ((Boolean) -> Unit)? = null,
    onSetUnread: ((Boolean) -> Unit)? = null,
    onSetArchived: ((Boolean) -> Unit)? = null,
) {
    val tokens = HermesTheme.tokens
    val archived = session.archived == true
    val status = session.displayStatus()
    val dot = status.dot(tokens)
    // The dot is the *resolved* state, where a louder one outranks unread; the
    // menu item reads the two raw sources instead, exactly as Desktop does
    // (`session-actions-menu.tsx:314-315,319` @ `3ca096de` — `unread ||
    // isUnread`, the row's own flag or membership of the finished-unread set).
    // A row that is working and carries the watermark is still unread, and must
    // still offer `Mark as read`.
    val unread = session.isUnread()

    // The outline is paint-only. Keeping it as a sibling layer means it cannot
    // change the row's size, click target, or one authoritative spoken label.
    Box(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = HermesTheme.spacing.touchTarget)
                .clickable(onClick = onClick)
                .testTag("Session row ${session.id}")
                .background(
                    // `--ui-row-active-background` at styles.css:308-312 @
                    // 3ca096de5f8183cb2e0ec23673f294d5978656a3. This must be
                    // a semantic token: all skins use the same Desktop mix.
                    color = if (active) tokens.sessionRowActiveSurface else tokens.sidebarSurface,
                    shape = SessionRowShape,
                )
                .padding(
                    start = HermesTheme.spacing.pageInset - 4.dp,
                    // The overlaid actions control is one whole touch target
                    // wide and sits on top of the row, so anything the row
                    // draws inside that band is unreachable: a tap there opens
                    // the menu instead of the session. Reserve the control's
                    // full width, not the distance to its glyph — the glyph is
                    // centred but the hit box is not.
                    end = HermesTheme.spacing.touchTarget,
                    top = 8.dp,
                    bottom = 8.dp,
                )
                .semantics {
                    selected = active
                    contentDescription = "${session.title}. ${if (archived) ARCHIVED_ROW_STATE else dot.description}"
                },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Active edge: a 2dp accent bar, not a box around the row.
            Box(
                Modifier
                    .width(2.dp)
                    .height(28.dp)
                    .background(
                        if (active) tokens.accent else Color.Transparent,
                        RoundedCornerShape(1.dp),
                    ),
            )

            // An archived session has no live status to paint, so the archive
            // glyph takes the lead slot the dot would occupy rather than adding
            // a column of its own — Desktop's own rule and its own ink
            // (`apps/desktop/src/app/chat/sidebar/session-row.tsx:284-290` @
            // `3ca096de`, `--ui-text-quaternary`).
            if (archived) {
                HermesIconGlyph(
                    HermesIcon.Archive,
                    color = tokens.textQuaternary,
                    size = 11.sp,
                    modifier = Modifier.testTag(ARCHIVED_ROW_MARK),
                )
            } else {
                StatusDot(
                    color = dot.color,
                    filled = dot.filled,
                    contentDescription = null,
                    size = if (status == SessionStatus.Idle) 6.dp else 7.dp,
                )
            }

            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    text = session.title,
                    style = HermesTheme.type.sessionTitle,
                    color = if (active) tokens.textPrimary else tokens.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (session.preview.isNotBlank()) {
                    Text(
                        text = session.preview,
                        style = HermesTheme.type.sessionPreview,
                        color = tokens.textTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            if (owner != null) ProfileTag(profile = owner)
        }

        if (status.showsRunningOutline()) {
            RunningSessionOutline(Modifier.matchParentSize())
        }

        // An overlay, exactly as Desktop pins its kebab (`absolute right-0`,
        // `session-row.tsx:320`): the 48dp target then cannot grow the row or
        // reflow it. Desktop swaps its trailing row meta out for the kebab on
        // hover; touch has no hover, so the row reserves the space instead and
        // the mark is always visible. It is its own semantics node, so the row
        // above keeps the one authoritative spoken label it already owns.
        SessionActionsControl(
            sessionId = session.id,
            sessionTitle = session.title,
            modifier = Modifier.align(Alignment.CenterEnd),
            pinned = session.pinned == true,
            unread = unread,
            archived = archived,
            onRename = onRename,
            onDelete = onDelete,
            onSetPinned = onSetPinned,
            onSetUnread = onSetUnread,
            onSetArchived = onSetArchived,
        )
    }
}

/**
 * Below this the sidebar pane scrolls as one instead of pinning its chrome.
 *
 * It is what the pane needs to hold everything at once — roughly the switcher,
 * the title row and the search field, plus [CRAMPED_LIST_HEIGHT] and the foot
 * rail — so the two numbers move together. A landscape rail with the keyboard
 * open lands far below it; a phone in portrait with the keyboard open does not.
 */
private val RAIL_SCROLLS_BELOW = 360.dp

/** What the list is worth once the pane scrolls: a few rows, not a sliver. */
private val CRAMPED_LIST_HEIGHT = 180.dp

private val SessionRowShape = RoundedCornerShape(6.dp)

/** The archived row's lead mark, in place of the status dot. */
internal const val ARCHIVED_ROW_MARK = "Archived session mark"

/** What an archived row says in place of a live status. */
private const val ARCHIVED_ROW_STATE = "Archived"

/** CSS 160deg in Android's y-down coordinate space. */
internal val SessionRunningOutlineDirection = Offset(0.34202015f, 0.9396926f)

/**
 * The linear-gradient line that covers a transformed layer: CSS resolves its
 * length by projecting the layer bounds onto the gradient direction, not by
 * choosing the larger side. Kept pure so the 300%-layer geometry stays pinned.
 */
internal fun sessionRunningOutlineGradientLength(
    layerWidth: Float,
    layerHeight: Float,
    direction: Offset = SessionRunningOutlineDirection,
): Float = abs(layerWidth * direction.x) + abs(layerHeight * direction.y)

/** Desktop's `showsRunningArc`: only a turn that is working or stalled owns the ring. */
internal fun SessionStatus.showsRunningOutline(): Boolean = this == SessionStatus.Working || this == SessionStatus.Stalled

/**
 * Paint-only Android port of Desktop's `.arc-border.arc-row` at
 * `3ca096de5f8183cb2e0ec23673f294d5978656a3`:
 * `apps/desktop/src/styles.css:994-1008,1011-1040,1085-1113,1129-1144`.
 *
 * The 300%-sized, 160-degree gradient travels from -10% to -50% over 2.23s,
 * matching Desktop's compositor travel. A Canvas layer keeps the 1.25dp ring
 * flush with the row and out of its semantics and pointer-input trees.
 */
@Composable
private fun RunningSessionOutline(modifier: Modifier = Modifier) {
    val tokens = HermesTheme.tokens
    // `areAnimatorsEnabled()` follows Android's system duration scale. Do not
    // compose an infinite clock at all when the user has removed animations;
    // phase zero is still a deliberately visible arc. Rows are lazy content,
    // so an off-screen session owns neither this layer nor its animation.
    val phase = if (ValueAnimator.areAnimatorsEnabled()) RunningOutlinePhase() else 0f
    val c1 = tokens.sessionRunningOutline
    val c2 = c1.copy(alpha = c1.alpha * 0.45f)

    Canvas(modifier.clearAndSetSemantics {}) {
        val strokeWidth = 1.25.dp.toPx()
        val layerWidth = size.width * 3f
        val layerHeight = size.height * 3f
        // Desktop's `translate(-10%, -10%)` to `translate(-50%, -50%)` on the
        // 300% pseudo-element. CSS angle 160deg points down and slightly right
        // in Android's y-down coordinate space.
        val offset = -(0.10f + 0.40f * phase)
        val layerCenter = Offset(
            x = layerWidth * (0.5f + offset),
            y = layerHeight * (0.5f + offset),
        )
        val direction = SessionRunningOutlineDirection
        val gradientLength = sessionRunningOutlineGradientLength(layerWidth, layerHeight, direction)
        val gradientStart = layerCenter - direction * (gradientLength / 2f)
        val gradientEnd = layerCenter + direction * (gradientLength / 2f)
        val transparent = Color.Transparent

        drawRoundRect(
            brush = Brush.linearGradient(
                colorStops = arrayOf(
                    0.00f to transparent,
                    0.15f to transparent,
                    0.20f to c1,
                    0.25f to c2,
                    0.35f to transparent,
                    0.40f to transparent,
                    0.55f to transparent,
                    0.60f to c1,
                    0.65f to c2,
                    0.75f to transparent,
                    0.80f to transparent,
                    0.95f to transparent,
                    1.00f to c1,
                ),
                start = gradientStart,
                end = gradientEnd,
            ),
            topLeft = Offset(strokeWidth / 2f, strokeWidth / 2f),
            size = size.copy(width = size.width - strokeWidth, height = size.height - strokeWidth),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(6.dp.toPx() - strokeWidth / 2f),
            style = Stroke(width = strokeWidth),
        )
    }
}

@Composable
private fun RunningOutlinePhase(): Float {
    val transition = rememberInfiniteTransition(label = "session-running-outline")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2_230, easing = LinearEasing),
        ),
        label = "session-running-outline-travel",
    )
    return phase
}

/** The dot lookup. Colour and fill carry the meaning; nothing animates. */
private data class DotStyle(val color: Color, val filled: Boolean, val description: String)

private fun SessionStatus.dot(tokens: HermesTokens): DotStyle = when (this) {
    SessionStatus.NeedsInput -> DotStyle(tokens.statusNeedsInput, true, "Waiting for your answer")
    SessionStatus.Working -> DotStyle(tokens.statusWorking, true, "Running")
    SessionStatus.Stalled -> DotStyle(tokens.statusWorking, false, "Running, nothing arriving")
    SessionStatus.Background -> DotStyle(tokens.textTertiary, false, "Background process still open")
    SessionStatus.Unread -> DotStyle(tokens.statusUnread, true, "Finished, unread")
    SessionStatus.Idle -> DotStyle(tokens.statusIdle, false, "Idle")
}
