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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.hermesagent.mobile.data.session.ProjectSummary
import com.hermesagent.mobile.data.prefs.SidebarGrouping
import com.hermesagent.mobile.data.session.SessionListRow
import com.hermesagent.mobile.data.session.SessionStatus
import com.hermesagent.mobile.data.session.SessionSummary
import com.hermesagent.mobile.data.session.label
import com.hermesagent.mobile.data.profiles.HermesProfile
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
                        active = menuVisible || query.isNotBlank() || sidebarGrouping != SidebarGrouping.Date,
                    )
                    SidebarViewMenu(
                        expanded = menuVisible,
                        grouping = sidebarGrouping,
                        projectGroupingAvailable = projectsAvailable != false,
                        searchVisible = searchIsVisible,
                        searchesProjects = showingProjectOverview,
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
                SearchField(
                    value = query,
                    onValueChange = onQueryChange,
                    placeholder = if (showingProjectOverview) "Search projects" else "Search sessions",
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

                rows.isEmpty() -> EmptyState(
                    title = when {
                        query.isBlank() -> "No sessions"
                        else -> "Nothing matches"
                    },
                    description = when {
                        query.isNotBlank() -> "No session title or preview contains “$query”."
                        canCreate -> "Start one with the + above."
                        else -> "Connect to a Gateway to start a session."
                    },
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
    }
}

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
    is SessionListRow.Row -> session.id
}

@Composable
private fun SessionRow(
    session: SessionSummary,
    active: Boolean,
    onClick: () -> Unit,
    /** The owning profile's chip, or null when the scope already names it. */
    owner: HermesProfile? = null,
) {
    val tokens = HermesTheme.tokens
    val dot = session.status.dot(tokens)

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
                    contentDescription = "${session.title}. ${dot.description}"
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

            StatusDot(
                color = dot.color,
                filled = dot.filled,
                contentDescription = null,
                size = if (session.status == SessionStatus.Idle) 6.dp else 7.dp,
            )

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

        if (session.status.showsRunningOutline()) {
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
            modifier = Modifier.align(Alignment.CenterEnd),
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
