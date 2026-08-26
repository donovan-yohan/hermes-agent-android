package com.hermesagent.mobile.ui.relay

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.hermesagent.mobile.ui.OverlayScaffold
import com.hermesagent.mobile.ui.RelayActions
import com.hermesagent.mobile.ui.common.EmptyState
import com.hermesagent.mobile.ui.common.Hairline
import com.hermesagent.mobile.ui.common.TextButton
import com.hermesagent.mobile.ui.common.scrollToTail
import com.hermesagent.mobile.ui.theme.AppearanceSelection
import com.hermesagent.mobile.ui.theme.HermesTheme
import com.hermesagent.mobile.ui.theme.HermesThemeMode
import kotlinx.coroutines.flow.first

/**
 * The Relay workspace: channels, then one channel's transcript.
 *
 * Desktop puts both panes side by side and keeps a pane dock
 * (hermes-plugin-relay @ `563a8c8`, `desktop/plugin.js:1108-1285`). A phone has
 * room for one, so this is a full-screen destination that drills in and back
 * out through a single header affordance — the same shape every other route
 * overlay in this app uses.
 *
 * Read-only by construction: there is no composer here, and nothing this
 * screen shows is written anywhere.
 */
@Composable
fun RelayScreen(
    state: RelayUiState,
    actions: RelayActions,
    /** Leaves the Relay destination. Navigation stays with the app shell. */
    onLeave: () -> Unit,
    /** The app's existing sign-in path, for the one state that needs it. */
    onOpenGateways: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Desktop's interval dies with the pane; the phone's equivalent of closing
    // the pane is the surface leaving the foreground, so the poll is bounded by
    // exactly that and nothing else.
    LifecycleResumeEffect(Unit) {
        actions.onResume()
        onPauseOrDispose { actions.onPause() }
    }

    val inTranscript = state.showingTranscript
    BackHandler(enabled = inTranscript) { actions.onClearSelection() }

    OverlayScaffold(
        title = state.selectedChannelTitle ?: RELAY_TITLE,
        subtitle = ARCHIVED_NOTE.takeIf { inTranscript && state.selectedChannelArchived },
        backDescription = if (inTranscript) "Back to channels" else "Back",
        onBack = { if (inTranscript) actions.onClearSelection() else onLeave() },
        modifier = modifier,
    ) {
        // Staleness only means something once there is a previous answer to be
        // stale. Before that the pane's own retry state is the whole story.
        val loaded = if (inTranscript) state.transcriptLoaded else state.channelsLoaded
        if (state.stale && loaded) StaleLine()
        state.notice?.let { RelayNoticeBlock(it, actions.onRetry, onOpenGateways) }
        if (state.showsContent) {
            if (inTranscript) {
                TranscriptPane(state, actions, Modifier.weight(1f))
            } else {
                ChannelsPane(state, actions, Modifier.weight(1f))
            }
        }
    }
}

/**
 * The quiet half of "keep prior data on a failed refresh": the screen still
 * shows Relay's last real answer, and says so in one line instead of throwing
 * an error at someone whose data is merely a few seconds old.
 */
@Composable
private fun StaleLine() {
    Text(
        text = STALE_MESSAGE,
        style = HermesTheme.type.scaffold,
        color = HermesTheme.tokens.textTertiary,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(STALE_TAG)
            .padding(horizontal = HermesTheme.spacing.pageInset, vertical = 6.dp),
    )
}

/**
 * A state, not an error. Desktop's connection banner shape
 * (`desktop/plugin.js:384-412`) in this app's inline widget grammar: shared
 * radius, mode-derived fill, no border.
 */
@Composable
private fun RelayNoticeBlock(
    notice: RelayNotice,
    onRetry: () -> Unit,
    onOpenGateways: () -> Unit,
) {
    val tokens = HermesTheme.tokens
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = HermesTheme.spacing.pageInset, vertical = 8.dp)
            .background(tokens.widgetSurface, RoundedCornerShape(8.dp))
            .testTag(NOTICE_TAG)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // With no title of its own, the description *is* the headline.
        val headline = notice.title == null
        notice.title?.let {
            Text(it, style = HermesTheme.type.bodyStrong, color = tokens.textSecondary)
        }
        Text(
            text = notice.description,
            style = if (headline) HermesTheme.type.bodyStrong else HermesTheme.type.caption,
            color = if (headline) tokens.textSecondary else tokens.textTertiary,
        )
        // Relay's host wrote this one; it sits under this app's sentence and
        // never replaces it.
        notice.detail?.let {
            Text(it, style = HermesTheme.type.caption, color = tokens.textTertiary)
        }
        when (notice.action) {
            RelayNoticeAction.Retry -> TextButton(label = "Try again", onClick = onRetry)
            RelayNoticeAction.OpenGateways ->
                TextButton(label = "Open Gateways", onClick = onOpenGateways)
            null -> Unit
        }
    }
}

/**
 * What a pane can be showing. Both panes answer this the same way — only their
 * copy and their content differ — so the decision lives once and a new
 * terminal state cannot be added to one pane and forgotten in the other.
 */
internal enum class RelayPanePhase { Retry, Loading, Silent, Empty, Content }

internal fun relayPanePhase(
    loaded: Boolean,
    stale: Boolean,
    relayReady: Boolean,
    isEmpty: Boolean,
): RelayPanePhase = when {
    !loaded && stale -> RelayPanePhase.Retry
    // Loading means a request is out. Only a ready lane is ever asked, so a
    // cold start on an offline, unauthorized or errored lane lands on Silent
    // below with the notice as the whole story — never on a spinner that no
    // request will ever resolve.
    !loaded && relayReady -> RelayPanePhase.Loading
    // Nothing has loaded and nothing is being asked — because Relay has not
    // answered at all, or because it answered with a lane this surface never
    // polls. The notice above already says which; a spinner under it would
    // only lie.
    !loaded -> RelayPanePhase.Silent
    isEmpty -> RelayPanePhase.Empty
    else -> RelayPanePhase.Content
}

@Composable
private fun ChannelsPane(state: RelayUiState, actions: RelayActions, modifier: Modifier) {
    when (
        relayPanePhase(
            loaded = state.channelsLoaded,
            stale = state.stale,
            relayReady = state.relayReady,
            isEmpty = state.channels.isEmpty(),
        )
    ) {
        RelayPanePhase.Retry -> RetryState(
            title = "Channels could not be loaded",
            description = "Relay did not return a usable channel list.",
            label = "Retry channels",
            onRetry = actions.onRetry,
            modifier = modifier,
        )

        RelayPanePhase.Loading -> EmptyState(
            title = "Loading channels…",
            description = "Hermes is asking Relay which channels are available to you.",
            modifier = modifier,
        )

        RelayPanePhase.Silent -> Spacer(modifier)

        RelayPanePhase.Empty -> EmptyState(
            title = "No Relay channels yet",
            // Desktop's own wording (`desktop/plugin.js:467`).
            description = "Connect or authorize Relay, then retry to load the channels available to you.",
            modifier = modifier,
        )

        RelayPanePhase.Content -> LazyColumn(
            modifier = modifier.fillMaxWidth().testTag(CHANNEL_LIST_TAG),
            contentPadding = PaddingValues(bottom = 12.dp),
        ) {
            items(items = state.channels, key = { it.id }) { row ->
                ChannelRow(row) { actions.onSelectChannel(row.id) }
            }
        }
    }
}

/**
 * Flat row, no card, no per-row outline — the session list's grammar, because
 * a channel is the same kind of thing to pick.
 *
 * No selected treatment: Desktop keeps the list beside the transcript, so a
 * highlighted row tells its reader which pane they are looking at. Here the
 * transcript replaces the list, so a row can never be both selected and
 * visible — a fill and a `selected` semantics flag nobody can ever perceive
 * would only lie to TalkBack.
 *
 * Desktop annotates an archived channel on the name line rather than hiding or
 * re-sorting it (`desktop/plugin.js:492`); the suffix arrives already applied.
 */
@Composable
private fun ChannelRow(row: RelayChannelRow, onClick: () -> Unit) {
    val tokens = HermesTheme.tokens
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = HermesTheme.spacing.touchTarget)
            .clickable(onClick = onClick)
            .testTag("Relay channel ${row.id}")
            .padding(horizontal = HermesTheme.spacing.pageInset, vertical = 8.dp)
            .semantics {
                role = Role.Button
                contentDescription = row.description
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                text = row.title,
                style = HermesTheme.type.sessionTitle,
                color = if (row.archived) tokens.textTertiary else tokens.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            row.preview?.let {
                Text(
                    text = it,
                    style = HermesTheme.type.sessionPreview,
                    color = tokens.textTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            row.classification?.let {
                Text(
                    text = it,
                    style = HermesTheme.type.scaffold,
                    color = tokens.scaffoldMeta,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        row.timestamp?.let {
            Text(text = it, style = HermesTheme.type.scaffoldMeta, color = tokens.scaffoldMeta)
        }
    }
}

@Composable
private fun TranscriptPane(state: RelayUiState, actions: RelayActions, modifier: Modifier) {
    when (
        relayPanePhase(
            loaded = state.transcriptLoaded,
            stale = state.stale,
            relayReady = state.relayReady,
            isEmpty = state.transcript.isEmpty(),
        )
    ) {
        RelayPanePhase.Retry -> RetryState(
            title = "Transcript could not be loaded",
            description = "Relay did not return a usable message window.",
            label = "Retry history",
            onRetry = actions.onRetry,
            modifier = modifier,
        )

        RelayPanePhase.Loading -> EmptyState(
            title = "Loading messages…",
            description = "Hermes is asking Relay for this channel's latest messages.",
            modifier = modifier,
        )

        RelayPanePhase.Silent -> Spacer(modifier)

        RelayPanePhase.Empty -> EmptyState(
            title = "No messages in this channel yet",
            description = "Messages appear here as Relay returns them.",
            modifier = modifier,
        )

        RelayPanePhase.Content -> {
            val listState = rememberLazyListState()
            val transcript = rememberUpdatedState(state.transcript)
            // Newest content is at the bottom, so opening a channel lands
            // there and growth keeps following — but only for a reader who is
            // still at the bottom. This is ChatScreen's rule, and the reason
            // it is a rule rather than an unconditional scroll: a poll every
            // three seconds that yanks someone back mid-message is worse than
            // a transcript that waits. A backward scroll disarms following;
            // reaching the bottom again re-arms it. `scrollToTail` lands on
            // the bottom *edge*, because `scrollToItem` alone would put the
            // newest row's top edge on screen and hide a long message's tail.
            //
            // Following is a plain local: nothing outside this loop reads it,
            // and it resets with the effect when the channel changes.
            LaunchedEffect(listState, state.selectedChannelId) {
                snapshotFlow {
                    listState.layoutInfo.totalItemsCount > 0 &&
                        listState.layoutInfo.viewportEndOffset >
                        listState.layoutInfo.viewportStartOffset
                }.first { ready -> ready }

                listState.scrollToTail()
                var following = true

                snapshotFlow {
                    Triple(
                        transcript.value,
                        listState.canScrollForward,
                        listState.isScrollInProgress && listState.lastScrolledBackward,
                    )
                }.collect { (_, canScrollForward, scrolledBackward) ->
                    if (!canScrollForward) {
                        following = true
                        return@collect
                    }
                    if (scrolledBackward) following = false
                    if (following) listState.scrollToTail()
                }
            }
            LazyColumn(
                modifier = modifier.fillMaxWidth().testTag(TRANSCRIPT_TAG),
                state = listState,
                contentPadding = PaddingValues(bottom = 12.dp),
            ) {
                items(items = state.transcript, key = { it.id }) { row -> TranscriptRow(row) }
            }
        }
    }
}

/**
 * Flat prose under an uppercase attribution label, divided by a hairline —
 * Desktop's message row (`desktop/plugin.js:502-517`). No bubble: a Relay
 * transcript has many authors, so singling one out with a bubble would say
 * something untrue about who is speaking.
 *
 * Desktop renders the body as plain pre-wrapped text and parses no markdown on
 * the read path, so neither does this.
 */
@Composable
private fun TranscriptRow(row: RelayTranscriptRow) {
    val tokens = HermesTheme.tokens
    Column(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("Relay message ${row.id}")
                .padding(horizontal = HermesTheme.spacing.pageInset, vertical = 10.dp)
                .semantics(mergeDescendants = true) { contentDescription = row.description },
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = row.attribution.uppercase(),
                    style = HermesTheme.type.sectionLabel,
                    color = row.senderKind.ink(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Text(text = row.status, style = HermesTheme.type.scaffold, color = tokens.scaffoldMeta)
                if (row.truncated) {
                    Text(
                        text = "Truncated",
                        style = HermesTheme.type.scaffold,
                        color = tokens.scaffoldMeta,
                    )
                }
                row.timestamp?.let {
                    Text(text = it, style = HermesTheme.type.scaffoldMeta, color = tokens.scaffoldMeta)
                }
            }
            Text(text = row.text, style = HermesTheme.type.body, color = tokens.textPrimary)
        }
        Hairline(Modifier.padding(start = HermesTheme.spacing.pageInset))
    }
}

/**
 * Who is speaking, in ink only. Three kinds is Desktop's whole vocabulary, and
 * an unrecognised one lands on the quietest of them rather than on a colour
 * this app made up for it.
 */
@Composable
private fun RelaySenderKind.ink(): Color = when (this) {
    RelaySenderKind.Human -> HermesTheme.tokens.textSecondary
    RelaySenderKind.Agent -> HermesTheme.tokens.accent
    RelaySenderKind.System -> HermesTheme.tokens.scaffoldText
}

/** Empty state plus the one action that can change it. */
@Composable
private fun RetryState(
    title: String,
    description: String,
    label: String,
    onRetry: () -> Unit,
    modifier: Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        EmptyState(title = title, description = description)
        TextButton(label = label, onClick = onRetry)
    }
}

private const val RELAY_TITLE = "Relay channels"
private const val ARCHIVED_NOTE = "Archived. Relay still returns its previous messages."
private const val STALE_MESSAGE = "Showing the last answer Relay returned."
internal const val CHANNEL_LIST_TAG = "Relay channel list"
internal const val TRANSCRIPT_TAG = "Relay transcript"
internal const val NOTICE_TAG = "Relay notice"
internal const val STALE_TAG = "Relay stale"

// ── Previews ──────────────────────────────────────────────────────────────
// Phone light and dark, both panes. Fixtures only: no host, channel or person
// here corresponds to anything real.

private fun previewChannels() = RelayUiState(
    channels = listOf(
        RelayChannelRow(
            id = "c1",
            title = "product",
            archived = false,
            classification = "Channel · Public",
            preview = "Ada: the parity gate is green again",
            timestamp = "09:14",
            description = "product. Channel · Public. Ada: the parity gate is green again.",
        ),
        RelayChannelRow(
            id = "c2",
            title = "launch-notes · archived",
            archived = true,
            classification = "Channel · Private",
            preview = "Grace: closing this out",
            timestamp = "2 Aug 17:40",
            description = "launch-notes. Archived. Channel · Private. Grace: closing this out.",
        ),
    ),
    channelsLoaded = true,
    relayAnswered = true,
    relayReady = true,
)

private fun previewTranscript() = RelayUiState(
    channels = emptyList(),
    channelsLoaded = true,
    selectedChannelId = "c1",
    selectedChannelTitle = "product",
    transcript = listOf(
        RelayTranscriptRow(
            id = "m1",
            attribution = "Ada",
            senderKind = RelaySenderKind.Human,
            text = "Did the theme parity gate pass on the last run?",
            timestamp = "09:12",
            status = "Delivered",
            truncated = false,
            description = "Ada. 09:12. Did the theme parity gate pass on the last run?. Delivered.",
        ),
        RelayTranscriptRow(
            id = "m2",
            attribution = "Hermes",
            senderKind = RelaySenderKind.Agent,
            text = "Yes — every built-in matched, and the offline gate is green.",
            timestamp = "09:14",
            status = "Delivered",
            truncated = false,
            description = "Hermes. 09:14. Yes — every built-in matched. Delivered.",
        ),
    ),
    transcriptLoaded = true,
    relayAnswered = true,
    relayReady = true,
)

@Composable
private fun PreviewRelay(selection: AppearanceSelection, state: RelayUiState) {
    HermesTheme(selection) {
        RelayScreen(
            state = state,
            actions = RelayActions(),
            onLeave = {},
            onOpenGateways = {},
        )
    }
}

@Preview(name = "Relay channels · dark", widthDp = 412, heightDp = 892)
@Composable
private fun RelayChannelsPreviewDark() =
    PreviewRelay(AppearanceSelection("nous", HermesThemeMode.Dark), previewChannels())

@Preview(name = "Relay channels · light", widthDp = 412, heightDp = 892)
@Composable
private fun RelayChannelsPreviewLight() =
    PreviewRelay(AppearanceSelection("nous", HermesThemeMode.Light), previewChannels())

@Preview(name = "Relay transcript · dark", widthDp = 412, heightDp = 892)
@Composable
private fun RelayTranscriptPreviewDark() =
    PreviewRelay(AppearanceSelection("nous", HermesThemeMode.Dark), previewTranscript())

@Preview(name = "Relay transcript · light", widthDp = 412, heightDp = 892)
@Composable
private fun RelayTranscriptPreviewLight() =
    PreviewRelay(AppearanceSelection("nous", HermesThemeMode.Light), previewTranscript())
