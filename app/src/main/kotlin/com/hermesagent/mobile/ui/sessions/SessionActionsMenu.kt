package com.hermesagent.mobile.ui.sessions

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hermesagent.mobile.ui.common.COPY_CONFIRM_MILLIS
import com.hermesagent.mobile.ui.common.ClipboardWriter
import com.hermesagent.mobile.ui.common.Hairline
import com.hermesagent.mobile.ui.common.HermesIcon
import com.hermesagent.mobile.ui.common.HermesIconButton
import com.hermesagent.mobile.ui.common.HermesIconGlyph
import com.hermesagent.mobile.ui.common.rememberClipboardWriter
import com.hermesagent.mobile.ui.theme.HermesTheme
import kotlinx.coroutines.delay

/**
 * The per-session actions menu, ported from Desktop's `SessionActionsMenu`
 * (`apps/desktop/src/app/chat/sidebar/session-actions-menu.tsx` and the
 * `ActionsMenu` kit at `apps/desktop/src/components/ui/actions-menu.tsx`)
 * @ `f82f2dbabd9e66b714f2b4f8a40447fe0c13e732`.
 *
 * This slice ships the container, not the verbs: every session row and the
 * chat header get one 48dp control opening one menu, and the *group order* is
 * fixed now so rename, delete, pin, archive and the rest land in Desktop's
 * slots later without moving anything that already renders.
 *
 * The full ledger — group order, codicon map, and every touch deviation — is
 * `docs/parity/session-actions-menu.md`.
 */

/** Desktop's spoken name for the control (`i18n/en.ts:2167`). */
const val SESSION_ACTIONS_LABEL = "Session actions"

internal const val SESSION_ACTIONS_MENU_TAG = "Session actions menu"

/** `Copy ID` (`i18n/en.ts:2156`). */
private const val COPY_ID = "Copy ID"

/**
 * The copy confirmation. Desktop's `CopyButton` swaps the item's own icon and
 * label rather than raising a notice, and the label it swaps in is
 * `t.common.copied` (`components/ui/copy-button.tsx:147-148`; `i18n/en.ts:21`)
 * — the same word, verbatim. Confirming in place is also this app's established
 * clipboard grammar (`Transcript.kt`, `CodingStatusRow.kt`): Android 13+ already
 * raises a system clipboard notice and a second one would be the app talking
 * over the platform.
 */
private const val COPY_ID_DONE = "Copied"

/**
 * The copy failure. Desktop splits this across two surfaces a phone does not
 * have: the item's own label becomes `t.common.failed` while the specific
 * message rides its tooltip and `aria-label` (`copy-button.tsx:149-151,161-164`)
 * and a desktop notification (`session-actions-menu.tsx:482,486`). Touch has no
 * hover and this build has no notification centre, so the one slot on screen
 * carries the specific message rather than the bare word — `copyIdFailed`
 * verbatim (`i18n/en.ts:2166`).
 */
private const val COPY_ID_FAILED = "Could not copy session ID"

/**
 * The clip's own description, which Android 13+ shows in the system clipboard
 * notice. User-visible product copy, not a debug tag.
 */
private const val SESSION_ID_CLIP_LABEL = "Session ID"

/**
 * Desktop's fixed menu group order: open, identity, work, tab, danger
 * (`session-actions-menu.tsx:234,291,344,371,433`). Declaration order here is
 * the contract — [sessionActionsMenuPlan] sorts by it, so a later slice adds a
 * verb to its group and cannot reorder the menu by accident.
 */
enum class SessionActionsGroup {
    /** Where else this session can go. No touch equivalent yet — slot only. */
    Open,

    /** Name, mark and reference the session. */
    Identity,

    /** Derive or extract from the session. */
    Work,

    /** Verbs that act on a tab strip. Android has no tab strip — slot only. */
    Tab,

    /** Put it away or destroy it. Delete stays last and destructive-red. */
    Danger,
}

/**
 * What the Copy ID row is currently saying — Desktop's `CopyStatus`
 * (`copy-button.tsx:14`) with the same three states and the same transitions.
 */
enum class SessionIdCopyStatus {
    /** Offering to copy. */
    Idle,

    /** The clip was accepted. */
    Copied,

    /** The clipboard refused it. */
    Failed,
}

/**
 * One action row — Desktop's `ActionItemSpec` (`actions-menu.tsx:69-79`) minus
 * the web-only parts. Desktop keys an item by its label (`actions-menu.tsx:90`)
 * and so does this port, which is why [label] is the dispatch key below.
 */
data class SessionActionItem(
    val group: SessionActionsGroup,
    val icon: HermesIcon,
    val label: String,
    /**
     * Destructive-red. Delete alone carries it upstream — Archive sits in the
     * danger group without it (`session-actions-menu.tsx:435-461`).
     */
    val destructive: Boolean = false,
)

/** A rendered menu node: an action, or one of Desktop's group separators. */
sealed interface SessionMenuNode {
    data class Action(val item: SessionActionItem) : SessionMenuNode

    data object Separator : SessionMenuNode
}

/**
 * Lay actions out in Desktop's group order with a separator between adjacent
 * populated groups.
 *
 * Desktop's `renderItems` (`session-actions-menu.tsx:465-522`) guards the open
 * and tab separators on their group being non-empty and writes the identity and
 * work separators unconditionally — but identity, work and danger each always
 * hold at least one always-rendered item there, so upstream never paints a
 * leading, trailing or doubled rule. This rule reproduces that rendered output
 * for every Desktop configuration and stays honest while Android's groups are
 * still filling up.
 */
fun sessionActionsMenuPlan(items: List<SessionActionItem>): List<SessionMenuNode> = buildList {
    SessionActionsGroup.entries.forEach { group ->
        val inGroup = items.filter { it.group == group }
        if (inGroup.isEmpty()) return@forEach
        if (isNotEmpty()) add(SessionMenuNode.Separator)
        inGroup.forEach { add(SessionMenuNode.Action(it)) }
    }
}

/**
 * The verbs this build can actually perform for [sessionId].
 *
 * Deliberately short: a permanently disabled Rename would be the menu lying
 * about what the app can do. Rename (S14) and Delete (S15) append themselves
 * here, and [SessionActionsGroup] puts them in Desktop's slots.
 *
 * Desktop disables its whole menu for a session with no id
 * (`disabled={!sessionId}`, `session-actions-menu.tsx:471,481`); with nothing
 * left to disable this returns nothing, and [SessionActionsControl] renders no
 * control at all rather than a bordered empty popup.
 */
fun sessionActionItems(
    sessionId: String,
    copyStatus: SessionIdCopyStatus = SessionIdCopyStatus.Idle,
): List<SessionActionItem> {
    if (!hasSessionActions(sessionId)) return emptyList()
    return listOf(
        when (copyStatus) {
            SessionIdCopyStatus.Idle -> CopyId
            SessionIdCopyStatus.Copied -> CopyIdCopied
            SessionIdCopyStatus.Failed -> CopyIdFailed
        },
    )
}

/**
 * Whether this build can do anything at all with [sessionId] — the one rule
 * [sessionActionItems] applies, named so the control can ask it without
 * building a list it would only measure and throw away.
 */
internal fun hasSessionActions(sessionId: String): Boolean = sessionId.isNotBlank()

/**
 * The Copy ID row and its two settled forms. Declared once and matched by value
 * below rather than dispatching on the rendered string: product copy is
 * reviewed and edited (`docs/workflows/review-product-copy.md`), and a label
 * that a `when` branch no longer recognises would silently stop working.
 */
private val CopyId = SessionActionItem(SessionActionsGroup.Identity, HermesIcon.Copy, COPY_ID)

private val CopyIdCopied =
    SessionActionItem(SessionActionsGroup.Identity, HermesIcon.Check, COPY_ID_DONE)

/**
 * Desktop's failure state swaps the icon to an `X` and changes nothing else
 * (`copy-button.tsx:142`); its ink stays the menu item's own, because the only
 * class the row is given is `text-current` (`session-actions-menu.tsx:483`). So
 * this is deliberately **not** `destructive` — that variant is Delete's, and
 * reddening a transient failure here would make the two read alike.
 */
private val CopyIdFailed =
    SessionActionItem(SessionActionsGroup.Identity, HermesIcon.Close, COPY_ID_FAILED)

/**
 * The 48dp overflow control and the menu it opens.
 *
 * A tap is the only path in: long-press stays with the platform so it cannot
 * fight text selection, and Desktop's modifier chords
 * (`session-row-gesture.ts:27,33`) have no touch equivalent at all.
 */
@Composable
internal fun SessionActionsControl(
    sessionId: String,
    modifier: Modifier = Modifier,
    tint: Color = HermesTheme.tokens.textTertiary,
    writeClipboard: ClipboardWriter = rememberClipboardWriter(),
) {
    // A menu with nothing in it is chrome that lies about the app. This asks the
    // rule `sessionActionItems` itself applies, so the control and its contents
    // can never disagree about whether there is anything to open.
    if (!hasSessionActions(sessionId)) return

    var expanded by remember(sessionId) { mutableStateOf(false) }
    var copyStatus by remember(sessionId) { mutableStateOf(SessionIdCopyStatus.Idle) }
    // Bumped on every press so a repeat press restarts the reset below, exactly
    // as Desktop clears its pending timeout before setting a new one
    // (`copy-button.tsx:115-123,128-136`).
    var copyPress by remember(sessionId) { mutableIntStateOf(0) }

    // Desktop settles both the copied and the failed state back to idle after
    // `COPIED_RESET_MS` (`copy-button.tsx:120-123,133-136`); the constant this
    // app's other two clipboard controls already share is the same 1.5s.
    //
    // Guarded rather than launched-and-ignored: this composable runs once per
    // session row, and nearly every row is never tapped, so an idle row should
    // not be paying for a coroutine that has nothing to wait for.
    if (copyStatus != SessionIdCopyStatus.Idle) {
        LaunchedEffect(copyPress) {
            delay(COPY_CONFIRM_MILLIS)
            copyStatus = SessionIdCopyStatus.Idle
        }
    }

    Box(modifier) {
        HermesIconButton(
            icon = HermesIcon.KebabVertical,
            contentDescription = SESSION_ACTIONS_LABEL,
            onClick = { expanded = true },
            active = expanded,
            tint = tint,
        )
        SessionActionsMenu(
            expanded = expanded,
            // Built by the popup's own content lambda, so a collapsed row —
            // which is nearly every row, nearly always — allocates nothing.
            items = { sessionActionItems(sessionId, copyStatus) },
            onDismiss = {
                expanded = false
                copyStatus = SessionIdCopyStatus.Idle
            },
            onSelect = { item ->
                when (item) {
                    // Desktop's copy item keeps the menu open so its own
                    // confirmation is visible (`copy-button.tsx:94-97`).
                    CopyId, CopyIdCopied, CopyIdFailed -> {
                        copyPress++
                        copyStatus = if (writeClipboard.write(SESSION_ID_CLIP_LABEL, sessionId)) {
                            SessionIdCopyStatus.Copied
                        } else {
                            SessionIdCopyStatus.Failed
                        }
                    }

                    // S14's Rename and S15's Delete must arrive with a branch
                    // here. Falling through would render a live-looking row
                    // that does nothing, which is worse than not shipping it.
                    else -> error("unhandled session action: ${item.label}")
                }
            },
        )
    }
}

/**
 * The menu itself — a Compose `DropdownMenu` painted only from
 * `HermesTheme.tokens`, never Material's surface, elevation or type defaults.
 */
@Composable
internal fun SessionActionsMenu(
    expanded: Boolean,
    items: () -> List<SessionActionItem>,
    onDismiss: () -> Unit,
    onSelect: (SessionActionItem) -> Unit,
) {
    val tokens = HermesTheme.tokens
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        // Desktop's `sideOffset={6}` (`session-actions-menu.tsx:595`).
        offset = DpOffset(0.dp, 6.dp),
        modifier = Modifier
            .widthIn(min = SessionActionsMenuWidth)
            .border(1.dp, tokens.strokePrimary, SessionActionsMenuShape)
            .testTag(SESSION_ACTIONS_MENU_TAG),
        shape = SessionActionsMenuShape,
        containerColor = tokens.cardSurface,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        sessionActionsMenuPlan(items()).forEach { node ->
            when (node) {
                is SessionMenuNode.Separator -> Hairline()

                is SessionMenuNode.Action -> SessionActionRow(node.item) { onSelect(node.item) }
            }
        }
    }
}

@Composable
private fun SessionActionRow(item: SessionActionItem, onClick: () -> Unit) {
    val tokens = HermesTheme.tokens
    val ink = if (item.destructive) tokens.destructive else tokens.textSecondary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = HermesTheme.spacing.touchTarget)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        HermesIconGlyph(item.icon, color = ink, size = 13.sp)
        Text(text = item.label, style = HermesTheme.type.scaffold, color = ink)
    }
}

/** Desktop's `w-40` content, widened for the phone type scale. */
private val SessionActionsMenuWidth = 220.dp

private val SessionActionsMenuShape = RoundedCornerShape(6.dp)
