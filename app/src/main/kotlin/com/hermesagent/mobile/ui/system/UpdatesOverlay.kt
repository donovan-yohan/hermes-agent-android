@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.hermesagent.mobile.ui.system

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.hermesagent.mobile.data.updates.CommitGroup
import com.hermesagent.mobile.data.updates.GatewayUpdateStage
import com.hermesagent.mobile.data.updates.GatewayUpdateState
import com.hermesagent.mobile.ui.common.PrimaryButton
import com.hermesagent.mobile.ui.common.SectionLabel
import com.hermesagent.mobile.ui.common.TextButton
import com.hermesagent.mobile.ui.common.WorkingDots
import com.hermesagent.mobile.ui.theme.HermesTheme

/**
 * Hermes Desktop's updates overlay, backend target
 * (`apps/desktop/src/app/updates-overlay.tsx` @
 * `3ca096de5f8183cb2e0ec23673f294d5978656a3`), as a bottom sheet.
 *
 * The client target does not exist here: this app is not a Hermes install that
 * can update itself, and its own updates come from the Play Store. Everything
 * else is Desktop's, including the phase order (`:72-81`) — manual, then
 * applying, then error, then idle — and the rule that an apply in flight owns
 * the sheet (`:85-98,112`).
 *
 * The work continues whether or not this sheet is on screen: the engine behind
 * it is app-scoped, so dismissing the sheet is only ever hiding a report.
 */
@Composable
fun UpdatesOverlay(state: SystemUiState, actions: SystemActions) {
    val tokens = HermesTheme.tokens
    val locked = state.applyLocked
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        // Desktop hides the close affordance while an update is applying; on a
        // phone the equivalent affordances are the drag handle and the back
        // gesture, so both are refused rather than only the one that has a
        // button.
        confirmValueChange = { value -> !(locked && value == SheetValue.Hidden) },
    )
    ModalBottomSheet(
        onDismissRequest = { if (!locked) actions.onCloseUpdates() },
        sheetState = sheetState,
        containerColor = tokens.cardSurface,
        contentColor = tokens.textPrimary,
        scrimColor = tokens.overlayScrim,
        modifier = Modifier.testTag(UPDATES_SHEET_TAG),
    ) {
        // The sheet is its own window, so this handler runs before the shell's:
        // while an apply is in flight, back does nothing at all rather than
        // dismissing the only report of a six-minute operation. `sheetState`'s
        // `confirmValueChange` covers the drag; this covers the gesture.
        BackHandler(enabled = locked) {}
        Column(
            Modifier
                .fillMaxWidth()
                .imePadding()
                .navigationBarsPadding()
                // Three groups of four changelog rows plus two buttons is
                // taller than a short phone's sheet, and a sheet clips rather
                // than scrolls on its own — which would put `Update now` off
                // the bottom of the one screen that offers it. The keyboard
                // inset is consumed above this, so the viewport shrinks rather
                // than the scrolled content growing.
                .verticalScroll(rememberScrollState())
                .padding(horizontal = HermesTheme.spacing.pageInset, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when {
                state.apply.stage == GatewayUpdateStage.Manual -> ManualView(state.apply, actions)
                state.apply.isApplyingPhase() -> ApplyingView(state.apply)
                state.apply.stage == GatewayUpdateStage.Error -> TerminalView(state, actions)
                state.apply.stage == GatewayUpdateStage.Done -> DoneView(actions)
                else -> IdleView(state, actions)
            }
        }
    }
}

/** The five idle branches, in Desktop's own order (`updates-overlay.tsx:176-280`). */
@Composable
private fun IdleView(state: SystemUiState, actions: SystemActions) {
    val check = state.check
    when {
        check == null && state.checking -> Centered(title = SystemCopy.CHECKING, working = true)

        check == null -> Centered(
            title = SystemCopy.CHECK_FAILED_TITLE,
            action = {
                PrimaryButton(
                    label = SystemCopy.TRY_AGAIN,
                    onClick = actions.onCheckUpdates,
                    modifier = Modifier.testTag(UPDATES_TRY_AGAIN_TAG),
                )
            },
        )

        !check.supported -> Centered(
            title = SystemCopy.NOT_AVAILABLE_TITLE,
            body = check.message ?: SystemCopy.UNSUPPORTED_MESSAGE,
        )

        check.failed -> Centered(
            title = SystemCopy.CHECK_FAILED_TITLE,
            body = SystemCopy.CONNECTION_RETRY,
            action = {
                PrimaryButton(
                    label = SystemCopy.TRY_AGAIN,
                    onClick = actions.onCheckUpdates,
                    enabled = !state.checking,
                    modifier = Modifier.testTag(UPDATES_TRY_AGAIN_TAG),
                )
            },
        )

        // Desktop's own test is the flag *or* a positive count
        // (`updates-overlay.tsx:69-70`): a host that reports `behind: 3` and
        // forgets the flag is still behind.
        !check.offersUpdate -> Centered(
            title = SystemCopy.ALL_SET_TITLE,
            body = SystemCopy.LATEST_BODY_BACKEND,
        )

        else -> AvailableView(check, actions)
    }
}

/** The offer, with its grouped changelog (`updates-overlay.tsx:244-280`). */
@Composable
private fun AvailableView(check: UpdateCheckState, actions: SystemActions) {
    val tokens = HermesTheme.tokens
    Text(
        text = SystemCopy.AVAILABLE_TITLE_BACKEND,
        style = HermesTheme.type.screenTitle,
        color = tokens.textPrimary,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().testTag(UPDATES_TITLE_TAG),
    )
    Text(
        // Desktop degrades to honest "no release notes" copy when it has no
        // commit rows to show — a pip or apt backend (`lib/update-copy.ts:36-41`).
        text = if (check.changelog.isEmpty() || check.moreChangesOnly()) {
            SystemCopy.AVAILABLE_BODY_NO_CHANGELOG
        } else {
            SystemCopy.AVAILABLE_BODY_BACKEND
        },
        style = HermesTheme.type.body,
        color = tokens.textSecondary,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
    for (group in check.changelog) {
        ChangelogGroup(group)
    }
    PrimaryButton(
        label = SystemCopy.UPDATE_NOW,
        onClick = actions.onApplyUpdate,
        modifier = Modifier.fillMaxWidth().testTag(UPDATES_APPLY_TAG),
    )
    TextButton(
        label = SystemCopy.MAYBE_LATER,
        onClick = actions.onCloseUpdates,
        color = tokens.textTertiary,
        modifier = Modifier.fillMaxWidth().testTag(UPDATES_LATER_TAG),
    )
    if (check.moreChanges > 0) {
        Text(
            text = SystemCopy.moreChanges(check.moreChanges),
            style = HermesTheme.type.caption,
            color = tokens.textTertiary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ChangelogGroup(group: CommitGroup) {
    val tokens = HermesTheme.tokens
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionLabel(group.label)
        for (item in group.items) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    Modifier
                        .padding(top = 6.dp)
                        .size(4.dp)
                        .background(tokens.accent, CircleShape),
                )
                Text(text = item, style = HermesTheme.type.caption, color = tokens.textPrimary)
            }
        }
    }
}

/**
 * The apply in progress (`updates-overlay.tsx:385-431`).
 *
 * Desktop's footer — "This window will close while the update runs, then Hermes
 * reopens on its own." (`en.ts:2642`) — is not here: it describes an Electron
 * window closing itself and an app relaunching, neither of which happens on a
 * phone. Saying it anyway would be the one thing product copy must never do,
 * which is describe something that will not occur.
 */
@Composable
private fun ApplyingView(apply: GatewayUpdateState) {
    val tokens = HermesTheme.tokens
    Text(
        text = SystemCopy.stageTitle(apply.stage),
        style = HermesTheme.type.screenTitle,
        color = tokens.textPrimary,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().testTag(UPDATES_TITLE_TAG),
    )
    Text(
        text = SystemCopy.APPLYING_BODY_BACKEND,
        style = HermesTheme.type.body,
        color = tokens.textSecondary,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
    // One line, not two. Desktop overloads `apply.message`: it holds the
    // applyStatus sentence until the first log line arrives and the latest log
    // line after that (`store/updates.ts:571-590`, rendered at
    // `updates-overlay.tsx:406-408`). Said plainly here rather than by
    // overloading a field.
    val current = apply.latestLogLine?.takeIf { it.isNotBlank() }
        ?: apply.status?.let(SystemCopy::applyStatus)
    if (current != null) {
        Text(
            text = current,
            style = HermesTheme.type.caption,
            color = tokens.textTertiary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().testTag(UPDATES_STATUS_TAG),
        )
    }
    // Indeterminate, always: the host reports no percentage for a backend apply
    // (`updates-overlay.tsx:393-396` takes the same branch whenever `percent`
    // is not finite, and no backend path ever sets one).
    LinearProgressIndicator(
        modifier = Modifier.fillMaxWidth(),
        color = tokens.accent,
        trackColor = tokens.widgetSurface,
    )
    // Only when there is more than one line, exactly as Desktop gates it
    // (`:391`): a single line is already the one centred above.
    if (apply.log.size > 1) {
        LogPanel(apply.log.takeLast(APPLYING_LOG_LINES))
    }
}

/**
 * The host refused to update itself in place, and named what to run instead
 * (`updates-overlay.tsx:283-...`, refusal contract `web_server.py:5088-5124`).
 */
@Composable
private fun ManualView(apply: GatewayUpdateState, actions: SystemActions) {
    val tokens = HermesTheme.tokens
    Text(
        text = SystemCopy.stageTitle(GatewayUpdateStage.Manual),
        style = HermesTheme.type.screenTitle,
        color = tokens.textPrimary,
        modifier = Modifier.fillMaxWidth().testTag(UPDATES_TITLE_TAG),
    )
    Text(
        text = apply.message ?: SystemCopy.applyStatus(
            com.hermesagent.mobile.data.updates.GatewayUpdateStatusKey.NotAvailable,
        ),
        style = HermesTheme.type.body,
        color = tokens.textSecondary,
        modifier = Modifier.fillMaxWidth(),
    )
    apply.command?.let { command ->
        LogPanel(listOf(command))
    }
    TextButton(
        label = SystemCopy.DONE,
        onClick = actions.onCloseUpdates,
        color = tokens.textTertiary,
        modifier = Modifier.fillMaxWidth().testTag(UPDATES_CLOSE_TAG),
    )
}

/**
 * The apply ended badly (`updates-overlay.tsx:533-555`).
 *
 * The title is `errorTitle`, not the `error` *stage* label — Desktop uses the
 * stage labels only while an apply is running. The body is whichever of its two
 * sentences applies (`en.ts:2673-2674`), and both buttons are Desktop's: `Try
 * again` re-runs the apply, `Not now` closes.
 */
@Composable
private fun TerminalView(state: SystemUiState, actions: SystemActions) {
    val tokens = HermesTheme.tokens
    Text(
        text = SystemCopy.ERROR_TITLE,
        style = HermesTheme.type.screenTitle,
        color = tokens.textPrimary,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().testTag(UPDATES_TITLE_TAG),
    )
    Text(
        text = state.apply.status?.let(SystemCopy::applyStatus) ?: SystemCopy.ERROR_BODY,
        style = HermesTheme.type.body,
        color = tokens.textSecondary,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().testTag(UPDATES_STATUS_TAG),
    )
    PrimaryButton(
        label = SystemCopy.TRY_AGAIN,
        onClick = actions.onApplyUpdate,
        modifier = Modifier.fillMaxWidth().testTag(UPDATES_APPLY_TAG),
    )
    TextButton(
        label = SystemCopy.NOT_NOW,
        onClick = actions.onCloseUpdates,
        color = tokens.textTertiary,
        modifier = Modifier.fillMaxWidth().testTag(UPDATES_CLOSE_TAG),
    )
}

/**
 * The apply finished. Desktop closes its overlay outright here
 * (`updates.ts:540-541`); a bottom sheet that vanishes with no word is a
 * six-minute wait with no ending, so it says the stage's own title first and
 * closes on the person's own tap.
 */
@Composable
private fun DoneView(actions: SystemActions) {
    val tokens = HermesTheme.tokens
    Text(
        text = SystemCopy.stageTitle(GatewayUpdateStage.Done),
        style = HermesTheme.type.screenTitle,
        color = tokens.textPrimary,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().testTag(UPDATES_TITLE_TAG),
    )
    PrimaryButton(
        label = SystemCopy.DONE,
        onClick = actions.onCloseUpdates,
        modifier = Modifier.fillMaxWidth().testTag(UPDATES_CLOSE_TAG),
    )
}

/** Desktop's `CenteredStatus` (`updates-overlay.tsx:139-168`). */
@Composable
private fun Centered(
    title: String,
    body: String? = null,
    working: Boolean = false,
    action: (@Composable () -> Unit)? = null,
) {
    val tokens = HermesTheme.tokens
    Column(
        Modifier.fillMaxWidth().padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (working) WorkingDots(status = title)
        Text(
            text = title,
            style = HermesTheme.type.screenTitle,
            color = tokens.textPrimary,
            textAlign = TextAlign.Center,
            modifier = Modifier.testTag(UPDATES_TITLE_TAG),
        )
        if (body != null) {
            Text(
                text = body,
                style = HermesTheme.type.body,
                color = tokens.textSecondary,
                textAlign = TextAlign.Center,
            )
        }
        action?.invoke()
    }
}

/** Raw host output: mono, hairline, tight — the app's one shape for it. */
@Composable
private fun LogPanel(lines: List<String>) {
    com.hermesagent.mobile.ui.common.LogView(
        text = lines.joinToString("\n"),
        modifier = Modifier.fillMaxWidth().testTag(UPDATES_LOG_TAG),
    )
}

/**
 * Whether the offer has nothing but the placeholder to show, which is Desktop's
 * `shownItems === 0` test in a shape this app can ask: [buildCommitChangelog]
 * never returns an empty list, so "no changelog" is the fallback group, and the
 * fallback group is what "no release notes for this install type" means.
 */
private fun UpdateCheckState.moreChangesOnly(): Boolean =
    changelog.size == 1 &&
        changelog.single().label == com.hermesagent.mobile.data.updates.FALLBACK_GROUP_LABEL

internal const val UPDATES_SHEET_TAG = "updates-sheet"
internal const val UPDATES_TITLE_TAG = "updates-title"
internal const val UPDATES_STATUS_TAG = "updates-status"
internal const val UPDATES_TRY_AGAIN_TAG = "updates-try-again"
internal const val UPDATES_APPLY_TAG = "updates-apply"
internal const val UPDATES_LATER_TAG = "updates-later"
internal const val UPDATES_CLOSE_TAG = "updates-close"
internal const val UPDATES_LOG_TAG = "updates-log"

/** Desktop's log panel shows the last four entries (`updates-overlay.tsx:418-426`). */
private const val APPLYING_LOG_LINES = 4
