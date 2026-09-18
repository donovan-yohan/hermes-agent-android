package com.hermesagent.mobile.plugins.bots

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.hermesagent.mobile.ui.OverlayScaffold
import com.hermesagent.mobile.ui.common.ComingSoonAction
import com.hermesagent.mobile.ui.common.ComingSoonIconAction
import com.hermesagent.mobile.ui.common.EmptyState
import com.hermesagent.mobile.ui.common.Hairline
import com.hermesagent.mobile.ui.common.HermesIcon
import com.hermesagent.mobile.ui.common.HermesIconButton
import com.hermesagent.mobile.ui.common.HermesIconGlyph
import com.hermesagent.mobile.ui.common.PrimaryButton
import com.hermesagent.mobile.ui.common.TextButton
import com.hermesagent.mobile.ui.theme.HermesTheme
import java.util.Locale

/**
 * The bot-scoped Routines destination — one bot's scheduled jobs, read-only.
 *
 * Ported from Desktop's `RoutinesPane` (`cron.tsx:1197-1336`) and its row
 * (`cron.tsx:480-598`) at `d177b119e9c56c9ddc0b7379ffce52341ec06584`, rendered
 * for a phone. Every state it draws is the state [BotsRoutinesViewModel]
 * reaches; the surface makes no claim of its own.
 *
 * **This slice is read-only, and the surface says so.** Desktop's row carries a
 * pause/resume switch and a delete control, and its header an add control; this
 * port renders each of them where Desktop has one, disabled and marked `WIP`,
 * per the app's rule that an unbuilt Desktop control stays visible behind the
 * marker chip rather than being omitted. What is *not* copied is Desktop's
 * automatic pause of legacy delegated routines on load
 * (`cron.tsx:131-166`): this slice issues no mutation, so a routine it did not
 * pause is never labelled paused — those rows say they are legacy instead, and
 * the difference is ledgered in `docs/parity/bot-routines.md`.
 *
 * **No backend prose is rendered.** The row's status line is a closed enum
 * mapped to local copy, the schedule is Desktop's own label (or the Gateway's
 * schedule string, which is a schedule and not prose), and the failure, reason
 * and prompt members are not on [RoutineRow] at all.
 */
class BotsRoutinesActions(
    val onRetry: () -> Unit = {},
    /**
     * The surface became visible. Desktop refetches this pane on its socket
     * opening and then on a 20 s poll; this destination is entered and left
     * rather than left mounted, so entering it is the trigger.
     */
    val onResume: () -> Unit = {},
)

@Composable
fun BotsRoutinesScreen(
    state: BotsRoutinesUiState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    actions: BotsRoutinesActions = BotsRoutinesActions(),
    nowMillis: Long? = null,
) {
    LifecycleResumeEffect(Unit) {
        actions.onResume()
        onPauseOrDispose {}
    }

    OverlayScaffold(title = BotsRoutinesCopy.TITLE, onBack = onBack, modifier = modifier) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
        ) {
            RoutinesOwnerHeader(state)

            Spacer(Modifier.height(12.dp))
            Hairline()
            Spacer(Modifier.height(12.dp))

            if (state.stale) {
                StaleNotice(BotsRoutinesCopy.STALE_NOTICE)
                Spacer(Modifier.height(8.dp))
            }

            val now = nowMillis ?: System.currentTimeMillis()
            when {
                state.phase == BotsRoutinesPhase.Loading -> RoutinesMessage(
                    title = BotsRoutinesCopy.TITLE,
                    description = BotsRosterCopy.WAITING_FOR_GATEWAY,
                    icon = HermesIcon.Clock,
                )

                state.phase == BotsRoutinesPhase.UnavailableOnGateway -> RoutinesMessage(
                    title = BotsRoutinesCopy.TITLE,
                    description = BotsRoutinesCopy.UNAVAILABLE,
                    icon = HermesIcon.Warning,
                    onRetry = actions.onRetry,
                )

                state.phase == BotsRoutinesPhase.MismatchedScope -> RoutinesMessage(
                    title = BotsRoutinesCopy.MISMATCHED_TITLE,
                    description = BotsRoutinesCopy.MISMATCHED_DESC,
                    icon = HermesIcon.Warning,
                    onRetry = actions.onRetry,
                )

                state.phase == BotsRoutinesPhase.Rejected -> RoutinesMessage(
                    title = BotsRoutinesCopy.REJECTED_TITLE,
                    description = BotsRoutinesCopy.REJECTED_DESC,
                    icon = HermesIcon.Warning,
                    onRetry = actions.onRetry,
                )

                state.phase == BotsRoutinesPhase.Refused -> RoutinesMessage(
                    title = BotsRoutinesCopy.FAILED_LOAD,
                    description = BotsRoutinesCopy.READ_FAILURE,
                    icon = HermesIcon.Warning,
                    onRetry = actions.onRetry,
                )

                state.jobs.isEmpty() -> RoutinesMessage(
                    title = BotsRoutinesCopy.EMPTY_TITLE,
                    description = state.emptyHint ?: BotsRoutinesCopy.EMPTY_DESC,
                    icon = HermesIcon.Watch,
                )

                else -> RoutineList(state = state, nowMillis = now)
            }
        }
    }
}

/**
 * The pane header: whose store this is, and the one Desktop control this slice
 * cannot honour.
 *
 * Desktop draws the bot's face, its display name and `@handle`, then the pane's
 * own uppercase noun, with a New-cron control on the right
 * (`cron.tsx:1252-1275`). This app has no bot-avatar surface yet
 * (`docs/parity/bots-roster.md` ledgers that as drift on the roster), so the
 * header names the bot in the same words the roster row does and adds no face.
 * The add control is Desktop's, rendered disabled behind the marker chip.
 */
@Composable
private fun RoutinesOwnerHeader(state: BotsRoutinesUiState) {
    val tokens = HermesTheme.tokens
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                text = state.ownerLabel ?: state.owner ?: BotsRoutinesCopy.TITLE,
                style = HermesTheme.type.bodyStrong,
                color = tokens.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                // `Locale.ROOT`: the section label is product copy, and the
                // default locale's rules would rewrite it (a Turkish device
                // upper-cases the `i` in "Scheduled" to a dotted `İ`).
                text = BotsRoutinesCopy.TITLE.uppercase(Locale.ROOT),
                style = HermesTheme.type.sectionLabel,
                color = tokens.textQuaternary,
                maxLines = 1,
            )
        }
        ComingSoonIconAction(icon = HermesIcon.Add, label = BotsRoutinesCopy.NEW_CRON)
    }
}

@Composable
private fun RoutineList(state: BotsRoutinesUiState, nowMillis: Long) {
    LazyColumn(Modifier.fillMaxSize().testTag(ROUTINES_LIST_TAG)) {
        items(state.jobs, key = { it.id }) { job ->
            RoutineRowItem(job = job, nowMillis = nowMillis)
        }
    }
}

/**
 * One routine.
 *
 * Desktop's row is a two-line card: an active dot, the title, a switch and a
 * delete control on the first line; a calendar-led schedule pill and the
 * next-run label on the second (`cron.tsx:526-597`). This keeps that order and
 * that pairing, with the switch and delete rendered where Desktop has them and
 * disabled behind the marker chip, because this slice issues no mutation.
 *
 * The next-run label follows Desktop's own rule (`cron.tsx:585-589`): an active
 * job with a parsed `next_run_at` shows the relative stamp, and anything else
 * shows the state word — which for a job this app cannot describe at all
 * ([RoutineRunState.Unknown]) is nothing, because the surface has no honest
 * word for it.
 */
@Composable
private fun RoutineRowItem(job: RoutineRow, nowMillis: Long) {
    val tokens = HermesTheme.tokens
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(min = HermesTheme.spacing.touchTarget)
            .border(1.dp, tokens.strokeSecondary, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(6.dp)
                    .background(
                        color = if (job.active) tokens.statusWorking else tokens.textQuaternary,
                        shape = CircleShape,
                    )
                    .clearAndSetSemantics {
                        contentDescription = if (job.active) ACTIVE_DOT else INACTIVE_DOT
                    },
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = job.title,
                style = HermesTheme.type.body,
                color = if (job.active) tokens.textPrimary else tokens.textTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            // Desktop's own controls, in Desktop's order, each one a control
            // this slice cannot honour. The marker chip is what stops a dimmed
            // switch from reading as one that is merely unavailable this second.
            ComingSoonIconAction(
                icon = HermesIcon.StopCircle,
                label = if (job.active) BotsRoutinesCopy.PAUSE_CRON else BotsRoutinesCopy.RESUME_CRON,
            )
            ComingSoonIconAction(icon = HermesIcon.Trash, label = BotsRoutinesCopy.DELETE)
        }

        Spacer(Modifier.height(6.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier
                    .background(tokens.widgetSurface, RoundedCornerShape(999.dp))
                    .border(1.dp, tokens.strokeSecondary, RoundedCornerShape(999.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HermesIconGlyph(icon = HermesIcon.Calendar, color = tokens.textTertiary, size = 11.sp)
                Spacer(Modifier.width(4.dp))
                Text(
                    text = job.scheduleLabel,
                    style = HermesTheme.type.scaffoldMeta,
                    color = tokens.textTertiary,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.weight(1f))
            RoutineStatusLine(job = job, nowMillis = nowMillis)
        }

        job.repeat?.let { repeat ->
            Spacer(Modifier.height(4.dp))
            Text(
                text = BotsRoutinesCopy.repeatTimes(repeat),
                style = HermesTheme.type.scaffoldMeta,
                color = tokens.textQuaternary,
            )
        }

        if (job.legacyDelegated) {
            Spacer(Modifier.height(6.dp))
            // Desktop pauses one of these on load and says it was paused for
            // security (`cron.tsx:591-595`). This slice issues no mutation, so
            // it must not claim a pause it did not perform; the sentence states
            // what is true here instead.
            Text(
                text = BotsRoutinesCopy.LEGACY_NOTICE,
                style = HermesTheme.type.scaffoldMeta,
                color = tokens.textTertiary,
                modifier = Modifier.testTag(LEGACY_NOTICE_TAG),
            )
        }
    }
}

/** The state word, or the relative next-run stamp when there is one. */
@Composable
private fun RoutineStatusLine(job: RoutineRow, nowMillis: Long) {
    val tokens = HermesTheme.tokens
    val nextRun = job.nextRunMillis
    val text = when {
        job.state == RoutineRunState.Unknown -> return
        job.active && nextRun != null -> "${BotsRoutinesCopy.NEXT_PREFIX} ${routineRelativeLabel(nextRun, nowMillis)}"
        job.state == RoutineRunState.Paused -> BotsRoutinesCopy.STATE_PAUSED
        job.state == RoutineRunState.Completed -> BotsRoutinesCopy.STATE_COMPLETED
        job.state == RoutineRunState.Failed -> BotsRoutinesCopy.STATE_FAILED
        // Active with no parseable next run: nothing true to promise, so the
        // line stays empty rather than inventing a schedule.
        else -> return
    }
    Text(
        text = text,
        style = HermesTheme.type.scaffoldMeta,
        color = tokens.textQuaternary,
        maxLines = 1,
    )
}

/**
 * A state message: the explainer, and the one action that leaves the state when
 * it has one. Desktop draws its failure card with Retry and its empty card with
 * the create action (`cron.tsx:1286-1309`); the empty card's action is a
 * mutation this slice does not ship, so the card carries the disabled marker
 * instead of a control that would do nothing.
 */
@Composable
private fun RoutinesMessage(
    title: String,
    description: String,
    icon: HermesIcon,
    onRetry: (() -> Unit)? = null,
) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(top = 32.dp)
            .testTag(ROUTINES_MESSAGE_TAG),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        EmptyState(title = title, description = description, icon = icon, centered = true)
        Spacer(Modifier.height(12.dp))
        if (onRetry != null) {
            PrimaryButton(label = BotsRosterCopy.RETRY_NOW, onClick = onRetry)
        } else {
            ComingSoonAction(label = BotsRoutinesCopy.NEW_CRON)
        }
    }
}

/** Desktop's stale banner for this pane (`cron.tsx:1277-1281`). */
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

/** The list's test handle. */
internal const val ROUTINES_LIST_TAG = "Routines list"

/** A state message's test handle. */
internal const val ROUTINES_MESSAGE_TAG = "Routines message"

/** The legacy-delegation notice's test handle. */
internal const val LEGACY_NOTICE_TAG = "Routines legacy notice"

/** The status dot's spoken form, because a colour is not a claim. */
private const val ACTIVE_DOT = "Runs on a schedule"
private const val INACTIVE_DOT = "Not scheduled to run"
