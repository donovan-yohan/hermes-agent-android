package com.hermesagent.mobile.plugins.bots

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.hermesagent.mobile.ui.OverlayScaffold
import com.hermesagent.mobile.ui.common.EmptyState
import com.hermesagent.mobile.ui.common.Hairline
import com.hermesagent.mobile.ui.common.ComingSoonIconAction
import com.hermesagent.mobile.ui.common.HermesIcon
import com.hermesagent.mobile.ui.common.HermesIconButton
import com.hermesagent.mobile.ui.common.HermesIconGlyph
import com.hermesagent.mobile.ui.common.MenuSectionLabel
import com.hermesagent.mobile.ui.common.ProfileGlyph
import com.hermesagent.mobile.data.profiles.HermesProfile
import com.hermesagent.mobile.ui.common.PrimaryButton
import com.hermesagent.mobile.ui.common.TextButton
import com.hermesagent.mobile.ui.theme.HermesTheme

/**
 * The Bots roster — the full-screen destination the plugin's `routes`
 * contribution renders.
 *
 * Scope note: this is the read path. The filtered roster, its user sections,
 * the pin/hide treatment and the attention badge are wired; the seven
 * empty/error/stale states and the 48dp/accessibility sweep belong to the
 * surface slice, so the states below are the honest subset this one can prove.
 */
class BotsActions(
    val onRefresh: () -> Unit = {},
    val onSearchChange: (String) -> Unit = {},
    val onKindFilterChange: (RosterKindFilter) -> Unit = {},
    val onActivityFilterChange: (RosterActivityFilter) -> Unit = {},
    val onSetHiddenExpanded: (Boolean) -> Unit = {},
    val onClearFilters: () -> Unit = {},
    /**
     * The surface became visible. Desktop refetches the roster the moment its
     * socket opens and then on its poll; this destination is entered and left
     * rather than left mounted, so entering it is what re-reads the roster.
     */
    val onResume: () -> Unit = {},
)

@Composable
fun BotsRosterScreen(
    state: BotsRosterUiState,
    onBack: () -> Unit,
    onOpenBotChat: (BotRosterRow) -> Unit = {},
    /** Open one bot's Routines. Distinct from the row tap, which opens its chat. */
    onOpenRoutines: (BotRosterRow) -> Unit = {},
    modifier: Modifier = Modifier,
    actions: BotsActions = BotsActions(),
) {
    // Desktop's roster has no interval of its own: it refetches on the socket
    // opening and on its query poll. A phone is not holding this pane open
    // while someone works elsewhere, so the surface's own resume is the second
    // trigger, and there is nothing to stop on the way out.
    LifecycleResumeEffect(Unit) {
        actions.onResume()
        onPauseOrDispose {}
    }

    val nowMillis = remember(state.sections, state.hiddenSections) { System.currentTimeMillis() }

    OverlayScaffold(title = BOTS_TITLE, onBack = onBack, modifier = modifier) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                // Desktop renders this before its New menu. Android has no
                // activity-toast preference or notification path for roster
                // activity yet, so it remains visible but cannot pretend it
                // persists a setting.
                ComingSoonIconAction(
                    icon = HermesIcon.BellSlash,
                    label = ACTIVITY_TOASTS_OFF,
                )
            }
            if (state.presentation.showRosterSearch) {
                Spacer(Modifier.height(12.dp))
                RosterSearchField(
                    value = state.searchQuery,
                    onValueChange = actions.onSearchChange,
                )
            }

            if (state.presentation.showRosterFilters) {
                RosterFilters(state, actions)
            }

            Spacer(Modifier.height(12.dp))
            Hairline()
            Spacer(Modifier.height(12.dp))

            if (state.stale) {
                StaleNotice(BotsRosterCopy.refreshFailed(state.connectionUp))
                Spacer(Modifier.height(8.dp))
            }
            state.botChatMessage?.let { message ->
                StaleNotice(message)
                Spacer(Modifier.height(8.dp))
            }

            when {
                state.phase == BotsRosterPhase.Loading -> RosterMessage(
                    title = BOTS_TITLE,
                    description = BotsRosterCopy.WAITING_FOR_GATEWAY,
                )

                state.phase == BotsRosterPhase.UnavailableOnGateway -> RosterMessage(
                    title = BotsRosterCopy.EMPTY_TITLE,
                    description = BotsRosterCopy.rosterUnavailable(UNAVAILABLE_REASON),
                )

                state.phase == BotsRosterPhase.Refused -> RosterError(
                    description = BotsRosterCopy.rosterUnavailable(state.safeMessage ?: REFUSED_REASON),
                    onRetry = actions.onRefresh,
                )

                state.phase == BotsRosterPhase.Empty -> RosterMessage(
                    title = BotsRosterCopy.EMPTY_TITLE,
                    description = BotsRosterCopy.EMPTY_DESC,
                )

                state.presentation.allBotsHidden && !state.hiddenExpanded -> RosterMessage(
                    title = BotsRosterCopy.ALL_HIDDEN,
                    description = BotsRosterCopy.ALL_HIDDEN_DESC,
                ) {
                    // Desktop carries the way out of this state with it —
                    // `allBotsHidden && !hiddenExpanded` renders the explainer
                    // *and* a button that sets `$showHiddenBots`
                    // (`roster-pane-content.tsx:93-105` @ the pin). The reveal
                    // otherwise lives in [RosterList], which this branch never
                    // draws, and the state is a dead end.
                    TextButton(
                        label = BotsRosterCopy.SHOW_HIDDEN,
                        onClick = { actions.onSetHiddenExpanded(true) },
                    )
                }

                state.filteredToNothing -> RosterFailure(
                    description = if (state.searchQuery.isBlank()) {
                        BotsRosterCopy.NO_MATCH_FILTERS
                    } else {
                        BotsRosterCopy.noMatchQuery(state.searchQuery.trim())
                    },
                    retry = false,
                    onAction = actions.onClearFilters,
                )

                else -> RosterList(
                    state = state,
                    nowMillis = nowMillis,
                    actions = actions,
                    onOpenBotChat = onOpenBotChat,
                    onOpenRoutines = onOpenRoutines,
                )
            }
        }
    }
}

@Composable
private fun RosterList(
    state: BotsRosterUiState,
    nowMillis: Long,
    actions: BotsActions,
    onOpenBotChat: (BotRosterRow) -> Unit,
    onOpenRoutines: (BotRosterRow) -> Unit,
) {
    val tokens = HermesTheme.tokens
    // A header belongs to a user section, so with none made Desktop draws the
    // plain flat list and the loose bucket stays unlabelled
    // (`roster-pane-sections.tsx`: "No sections made: the plain list, exactly
    // as before this feature"). Unassigned's label is a drop-zone heading, and
    // there is no drop zone without sections.
    val labelled = state.presentation.hasUserSections
    LazyColumn(Modifier.fillMaxSize()) {
        for (section in state.sections) {
            if (labelled) {
                item(key = section.key) { SectionHeader(section.name) }
            }
            items(section.rows, key = { "${section.key}:${it.rosterKey}" }) { row ->
                BotRowItem(
                    row = row,
                    nowMillis = nowMillis,
                    ageMillis = botRowAgeMillis(row, nowMillis),
                    pinned = row.rosterKey in state.pinnedKeys,
                    hidden = false,
                    attention = state.attentionByKey[row.rosterKey],
                    opening = state.openingBotKey == row.rosterKey,
                    onOpen = { onOpenBotChat(row) },
                    onOpenRoutines = { onOpenRoutines(row) },
                )
            }
        }

        if (state.presentation.showHiddenSection) {
            item(key = "hidden-toggle") {
                TextButton(
                    label = if (state.presentation.showHiddenRows) {
                        BotsRosterCopy.HIDDEN_FROM_ROSTER
                    } else {
                        BotsRosterCopy.SHOW_HIDDEN
                    },
                    onClick = { actions.onSetHiddenExpanded(!state.hiddenExpanded) },
                )
            }
            if (state.presentation.showHiddenRows) {
                if (state.hiddenSections.isEmpty()) {
                    item(key = "hidden-empty") {
                        Text(
                            BotsRosterCopy.NO_HIDDEN_MATCH,
                            style = HermesTheme.type.caption,
                            color = tokens.textTertiary,
                        )
                    }
                } else {
                    for (section in state.hiddenSections) {
                        if (labelled) {
                            item(key = "hidden:${section.key}") {
                                SectionHeader(section.name)
                            }
                        }
                        items(
                            section.rows,
                            key = { "hidden:${section.key}:${it.rosterKey}" },
                        ) { row ->
                            BotRowItem(
                                row = row,
                                nowMillis = nowMillis,
                                ageMillis = botRowAgeMillis(row, nowMillis),
                                pinned = row.rosterKey in state.pinnedKeys,
                                hidden = true,
                                attention = state.attentionByKey[row.rosterKey],
                                opening = state.openingBotKey == row.rosterKey,
                                onOpen = { onOpenBotChat(row) },
                                onOpenRoutines = { onOpenRoutines(row) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * The stale banner: Desktop keeps the last good list and says why it is old
 * rather than blanking it (`roster-pane-content.tsx:75-79` @ the pin).
 */
@Composable
private fun StaleNotice(text: String) {
    Text(
        text = text,
        style = HermesTheme.type.caption,
        color = HermesTheme.tokens.textTertiary,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(STALE_TAG)
            .background(HermesTheme.tokens.cardSurface, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
    )
}

/** The stale banner's test handle. */
internal const val STALE_TAG = "Bots stale"

@Composable
private fun SectionHeader(name: String) {
    Text(
        text = name,
        style = HermesTheme.type.sectionLabel,
        color = HermesTheme.tokens.textTertiary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 4.dp),
    )
}

/**
 * One bot row.
 *
 * The attention badge reads [BotAttention], which only ever exists for an
 * error that classified as one of the four attention-worthy classes — a rate
 * limit, a 5xx or a timeout never does, so it can never badge here.
 */
@Composable
private fun BotRowItem(
    row: BotRosterRow,
    nowMillis: Long,
    /** The stamp the age label reads — chat activity, or a live worker. */
    ageMillis: Long?,
    pinned: Boolean,
    hidden: Boolean,
    attention: BotAttention?,
    opening: Boolean,
    onOpen: () -> Unit,
    /** Open this bot's Routines — its own visible control, never the row's tap. */
    onOpenRoutines: () -> Unit,
) {
    val tokens = HermesTheme.tokens
    val preview = displayPreview(row.activity?.preview)
    val fromBot = previewFromBot(row.activity?.preview) != null
    val name = displayName(row.name, row.displayName)

    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(min = HermesTheme.spacing.touchTarget)
            // The row's own tap target and the Routines control are siblings, so
            // opening chat can never swallow the other destination — Desktop
            // makes the same split between its title button, switch and delete
            // control (`cron.tsx:539-576`), and a nested control would be
            // invalid markup besides.
            .clickable(onClick = onOpen)
            .padding(vertical = 8.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (pinned) {
                HermesIconGlyph(
                    icon = HermesIcon.Pin,
                    color = tokens.accent,
                    modifier = Modifier.padding(end = 6.dp),
                )
            }
            if (hidden) {
                HermesIconGlyph(
                    icon = HermesIcon.Eye,
                    color = tokens.textQuaternary,
                    modifier = Modifier.padding(end = 6.dp),
                )
            }
            ProfileGlyph(
                profile = HermesProfile(
                    name = row.name,
                    displayName = row.displayName,
                    hasAvatar = row.hasAvatar,
                    avatarRef = row.avatarRef,
                ),
                size = 36.dp,
                shape = RoundedCornerShape(22.dp),
                modifier = Modifier.padding(end = 10.dp),
            )
            Text(
                text = name,
                style = HermesTheme.type.bodyStrong,
                color = if (hidden) tokens.textTertiary else tokens.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (opening) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .widthIn(min = 18.dp, max = 18.dp)
                        .semantics { contentDescription = "Opening Bot Chat" },
                    strokeWidth = 2.dp,
                    color = tokens.accent,
                )
            } else {
                ageMillis?.let { stamp ->
                    Text(
                        text = rowAgeLabel(stamp, nowMillis),
                        style = HermesTheme.type.scaffoldMeta,
                        color = tokens.scaffoldMeta,
                    )
                }
            }
            // The one distinct Routines entry for this row: always visible, its
            // own accessible name, and its own 48dp target. Desktop reaches
            // Routines by focusing the bot, because its Bots pane and the
            // Routines tile are both on screen; a phone shows one surface at a
            // time, so the destination needs a control on the row instead —
            // ledgered as mobile-adaptation in `docs/parity/bot-routines.md`.
            HermesIconButton(
                icon = HermesIcon.Watch,
                contentDescription = routinesEntryLabel(name),
                onClick = onOpenRoutines,
                modifier = Modifier.testTag(ROUTINES_ROW_ACTION_TAG),
            )
        }

        Text(
            text = "@${row.handle}",
            style = HermesTheme.type.caption,
            color = tokens.textTertiary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        if (attention != null) {
            val hint = BOT_ATTENTION_HINTS[attention.reason].orEmpty()
            Text(
                text = "$name ${BotsRosterCopy.NEEDS_ATTENTION} · $hint",
                style = HermesTheme.type.caption,
                color = tokens.statusNeedsInput,
            )
        }

        if (preview.isNotEmpty()) {
            Text(
                text = preview,
                style = if (fromBot) {
                    HermesTheme.type.caption.copy(fontStyle = FontStyle.Italic)
                } else {
                    HermesTheme.type.caption
                },
                color = tokens.textSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun RosterSearchField(value: String, onValueChange: (String) -> Unit) {
    val tokens = HermesTheme.tokens
    Box(
        Modifier
            .fillMaxWidth()
            .background(tokens.cardSurface, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        if (value.isEmpty()) {
            Text(
                text = BotsRosterCopy.SEARCH_PLACEHOLDER,
                style = HermesTheme.type.body,
                color = tokens.textTertiary,
                maxLines = 1,
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = HermesTheme.type.body.copy(color = tokens.textPrimary),
            modifier = Modifier
                .fillMaxWidth()
                .clearAndSetSemantics { contentDescription = BotsRosterCopy.SEARCH },
        )
    }
}

@Composable
private fun RosterFilters(state: BotsRosterUiState, actions: BotsActions) {
    val tokens = HermesTheme.tokens
    var expanded by rememberSaveable { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        HermesIconButton(
            icon = HermesIcon.ListFilter,
            contentDescription = FILTER_ROSTER,
            onClick = { expanded = true },
            modifier = Modifier.align(Alignment.CenterEnd),
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            offset = DpOffset(0.dp, 6.dp),
            modifier = Modifier
                .widthIn(min = FILTER_MENU_WIDTH)
                .border(1.dp, tokens.strokePrimary, RoundedCornerShape(6.dp)),
            shape = RoundedCornerShape(6.dp),
            containerColor = tokens.cardSurface,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
        ) {
            MenuSectionLabel(FILTER_KIND)
            RosterFilterOptions(KIND_FILTERS, state.kindFilter) {
                expanded = false
                actions.onKindFilterChange(it)
            }
            Hairline()
            MenuSectionLabel(FILTER_ACTIVITY)
            RosterFilterOptions(ACTIVITY_FILTERS, state.activityFilter) {
                expanded = false
                actions.onActivityFilterChange(it)
            }
        }
    }
}

@Composable
private fun <T> RosterFilterOptions(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    options.forEach { (value, label) ->
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = HermesTheme.spacing.touchTarget)
                .clickable { onSelect(value) }
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = HermesTheme.type.scaffold, color = HermesTheme.tokens.textSecondary, modifier = Modifier.weight(1f))
            if (value == selected) HermesIconGlyph(HermesIcon.Check, color = HermesTheme.tokens.accent, size = HermesTheme.type.scaffold.fontSize)
        }
    }
}

/**
 * A state message: the explainer, and the one action that leaves the state when
 * it has one. Desktop draws both in the same block (`roster-pane-content.tsx`),
 * so a state whose only way out lives in the list it never draws — all-hidden —
 * carries its own button rather than becoming a dead end.
 */
@Composable
private fun RosterMessage(
    title: String,
    description: String,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(top = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        EmptyState(
            title = title,
            description = description,
            icon = HermesIcon.Question,
            centered = true,
        )
        if (action != null) {
            Spacer(Modifier.height(12.dp))
            action()
        }
    }
}

@Composable
private fun RosterFailure(description: String, retry: Boolean, onAction: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(top = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        EmptyState(
            title = BotsRosterCopy.EMPTY_TITLE,
            description = description,
            icon = HermesIcon.Warning,
            centered = true,
        )
        Spacer(Modifier.height(12.dp))
        if (retry) {
            PrimaryButton(label = BotsRosterCopy.RETRY_NOW, onClick = onAction)
        } else {
            TextButton(label = BotsRosterCopy.CLEAR_FILTERS, onClick = onAction)
        }
    }
}

/** Desktop's error slot has its sentence and Retry, but deliberately no heading. */
@Composable
private fun RosterError(description: String, onRetry: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(top = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = description,
            style = HermesTheme.type.body,
            color = HermesTheme.tokens.textTertiary,
        )
        Spacer(Modifier.height(12.dp))
        PrimaryButton(label = BotsRosterCopy.RETRY_NOW, onClick = onRetry)
    }
}

private const val BOTS_TITLE = "Bots"

/**
 * The Routines entry's accessible name on a roster row.
 *
 * It names the bot rather than repeating its own glyph: a list of identical
 * "Scheduled jobs" controls says nothing about which bot each one opens.
 */
internal fun routinesEntryLabel(botName: String): String = "Scheduled jobs for $botName"

/** The roster row's Routines control, so a journey can find it. */
internal const val ROUTINES_ROW_ACTION_TAG = "Bots routines"

private const val UNAVAILABLE_REASON = "this Gateway does not serve profiles.list"

private const val REFUSED_REASON = "the Gateway did not answer"

private const val ACTIVITY_TOASTS_OFF = "Activity toasts off — click to enable"
private const val FILTER_ROSTER = "Filter roster"
private const val FILTER_KIND = "Kind"
private const val FILTER_ACTIVITY = "Activity"
private val FILTER_MENU_WIDTH = 220.dp
private val KIND_FILTERS = listOf(
    RosterKindFilter.All to BotsRosterCopy.BOTS_AND_GROUPS,
    RosterKindFilter.Bots to BotsRosterCopy.BOTS_ONLY,
    RosterKindFilter.Groups to BotsRosterCopy.GROUPS_ONLY,
)
private val ACTIVITY_FILTERS = listOf(
    RosterActivityFilter.All to BotsRosterCopy.ANY_ACTIVITY,
    RosterActivityFilter.Active to BotsRosterCopy.ACTIVE_NOW,
    RosterActivityFilter.Recent to BotsRosterCopy.RECENTLY_ACTIVE,
    RosterActivityFilter.Older to BotsRosterCopy.OLDER,
)
