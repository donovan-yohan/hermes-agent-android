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
 * @ `3ca096de5f8183cb2e0ec23673f294d5978656a3`.
 *
 * This slice ships the container, not the verbs: every session row and the
 * chat header get one 48dp control opening one menu, and the *group order* is
 * fixed now so rename, delete, pin, archive and the rest land in Desktop's
 * slots later without moving anything that already renders.
 *
 * The full ledger — group order, codicon map, and every touch deviation — is
 * `docs/parity/session-actions-menu.md`.
 */

/** Desktop's spoken name for the control (`i18n/en.ts:2319` @ `3ca096de`). */
const val SESSION_ACTIONS_LABEL = "Session actions"

internal const val SESSION_ACTIONS_MENU_TAG = "Session actions menu"

/** `Copy ID` (`i18n/en.ts:2308` @ `3ca096de`). */
private const val COPY_ID = "Copy ID"

/** `Rename…` (`i18n/en.ts:2311` @ `3ca096de`). */
const val RENAME = "Rename…"

/** `Delete` (`i18n/en.ts:24` @ `3ca096de`). */
const val DELETE = "Delete"

/** `Pin` / `Unpin` (`i18n/en.ts:2303-2304` @ `3ca096de`). */
const val PIN = "Pin"
const val UNPIN = "Unpin"

/** `Mark as unread` / `Mark as read` (`i18n/en.ts:2305-2306` @ `3ca096de`). */
const val MARK_UNREAD = "Mark as unread"
const val MARK_READ = "Mark as read"

/** `Archive` (`i18n/en.ts:2312` @ `3ca096de`). */
const val ARCHIVE = "Archive"

/**
 * `Unarchive` (`i18n/en.ts:1156` @ `3ca096de`).
 *
 * Desktop's row menu never says this word: its restore lives on the Archived
 * Chats settings page (`app/settings/sessions-settings.tsx:148-154`), which is
 * a non-goal here. With that page absent the row's own menu is the only place a
 * restore can live, so the verb moves — and it moves with Desktop's own word
 * rather than a new one.
 */
const val UNARCHIVE = "Unarchive"

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
 * verbatim (`i18n/en.ts:2318` @ `3ca096de`).
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
 * Desktop disables its whole menu for a session with no id
 * (`disabled={!sessionId}`, `session-actions-menu.tsx:471,481`); with nothing
 * left to disable this returns nothing, and [SessionActionsControl] renders no
 * control at all rather than a bordered empty popup.
 */
fun sessionActionItems(
    sessionId: String,
    copyStatus: SessionIdCopyStatus = SessionIdCopyStatus.Idle,
    /** The backend's durable pin (`sessions.pinned`); it decides which word this row says. */
    pinned: Boolean = false,
    /** Either unread source: the durable watermark or this client's finished-turn dot. */
    unread: Boolean = false,
    /** The backend's durable soft-archive; an archived row offers the way back. */
    archived: Boolean = false,
): List<SessionActionItem> {
    if (!hasSessionActions(sessionId)) return emptyList()
    return listOf(
        Rename,
        if (pinned) Unpin else Pin,
        // One item, both unread sources, Desktop's own pairing: the label and
        // the glyph both name the *action*, so `Mark as read` carries the open
        // envelope (`session-actions-menu.tsx:310-333` @ `3ca096de`).
        if (unread) MarkRead else MarkUnread,
        when (copyStatus) {
            SessionIdCopyStatus.Idle -> CopyId
            SessionIdCopyStatus.Copied -> CopyIdCopied
            SessionIdCopyStatus.Failed -> CopyIdFailed
        },
        if (archived) Unarchive else Archive,
        Delete,
    )
}

/**
 * Whether this build can do anything at all with [sessionId] — the one rule
 * [sessionActionItems] applies, named so the control can ask it without
 * building a list it would only measure and throw away.
 */
internal fun hasSessionActions(sessionId: String): Boolean = sessionId.isNotBlank()

/**
 * `Rename…` in the identity group (`apps/desktop/src/app/chat/sidebar/session-actions-menu.tsx:288-296`
 * @ `3ca096de`).
 */
private val Rename = SessionActionItem(SessionActionsGroup.Identity, HermesIcon.Edit, RENAME)

/**
 * `Pin` / `Unpin` in the identity group, one slot below Rename
 * (`apps/desktop/src/app/chat/sidebar/session-actions-menu.tsx:297-305` @
 * `3ca096de`). One glyph for both states, as upstream: the label carries the
 * direction.
 */
private val Pin = SessionActionItem(SessionActionsGroup.Identity, HermesIcon.Pin, PIN)

private val Unpin = SessionActionItem(SessionActionsGroup.Identity, HermesIcon.Pin, UNPIN)

/**
 * The read-state row (`session-actions-menu.tsx:310-333` @ `3ca096de`).
 *
 * Codicon has no `mail-unread` glyph, which is why closed `mail` and open
 * `mail-read` are the pair upstream chose — and why inventing a third would
 * break the vocabulary rather than complete it.
 */
private val MarkRead = SessionActionItem(SessionActionsGroup.Identity, HermesIcon.MailRead, MARK_READ)

private val MarkUnread = SessionActionItem(SessionActionsGroup.Identity, HermesIcon.Mail, MARK_UNREAD)

/**
 * `Archive` in the danger group *above* Delete and deliberately not
 * destructive-red (`session-actions-menu.tsx:431-440,441-459` @ `3ca096de`):
 * putting a chat away and destroying it must not read alike.
 */
private val Archive = SessionActionItem(SessionActionsGroup.Danger, HermesIcon.Archive, ARCHIVE)

private val Unarchive = SessionActionItem(SessionActionsGroup.Danger, HermesIcon.Archive, UNARCHIVE)

/**
 * `Delete` in the danger group, destructive-styled (`apps/desktop/src/app/chat/sidebar/session-actions-menu.tsx:441-459`
 * @ `3ca096de`).
 */
private val Delete =
    SessionActionItem(SessionActionsGroup.Danger, HermesIcon.Trash, DELETE, destructive = true)

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
    sessionTitle: String = "",
    modifier: Modifier = Modifier,
    tint: Color = HermesTheme.tokens.textTertiary,
    writeClipboard: ClipboardWriter = rememberClipboardWriter(),
    /** The backend's durable pin for this row. */
    pinned: Boolean = false,
    /** Either unread source: the durable watermark or the finished-turn dot. */
    unread: Boolean = false,
    /** The backend's durable soft-archive for this row. */
    archived: Boolean = false,
    onRename: (suspend (String) -> Unit)? = null,
    onDelete: (suspend () -> Unit)? = null,
    /**
     * Pin, read-state and archive, all deliberately **not** `suspend`.
     *
     * Every one of these verbs takes the row off the list it was pressed on:
     * an archive leaves the live pool, a pin moves into the Pinned section. A
     * coroutine launched on this composable's own scope would be cancelled on
     * the next frame, mid-`PATCH` — the Gateway never told, and neither the
     * success nor the rollback branch reached. The write belongs to a scope
     * that outlives the row, so these hand it off and return.
     */
    onSetPinned: ((Boolean) -> Unit)? = null,
    onSetUnread: ((Boolean) -> Unit)? = null,
    onSetArchived: ((Boolean) -> Unit)? = null,
) {
    // A menu with nothing in it is chrome that lies about the app. This asks the
    // rule `sessionActionItems` itself applies, so the control and its contents
    // can never disagree about whether there is anything to open.
    if (!hasSessionActions(sessionId)) return

    var expanded by remember(sessionId) { mutableStateOf(false) }
    var renameOpen by remember(sessionId) { mutableStateOf(false) }
    var deleteOpen by remember(sessionId) { mutableStateOf(false) }
    var copyStatus by remember(sessionId) { mutableStateOf(SessionIdCopyStatus.Idle) }
    // Bumped on every press so a repeat press restarts the reset below, exactly
    // as Desktop clears its pending timeout before setting a new one
    // (`copy-button.tsx:115-123,128-136`).
    var copyPress by remember(sessionId) { mutableIntStateOf(0) }

    // Pin, archive and read-state are one PATCH each with no dialog in front of
    // them, so the press dismisses the menu and hands the write off. The
    // *outcome* is the caller's: these lambdas are optimistic-and-honest at the
    // repository, and the failure they raise is already reported on the chat's
    // own notice slot, so re-raising it here would say the same thing twice.
    fun mutate(action: ((Boolean) -> Unit)?, value: Boolean) {
        expanded = false
        action?.invoke(value)
    }

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
            items = { sessionActionItems(sessionId, copyStatus, pinned, unread, archived) },
            onDismiss = {
                expanded = false
                copyStatus = SessionIdCopyStatus.Idle
            },
            onSelect = { item ->
                when (item) {
                    Rename -> {
                        expanded = false
                        renameOpen = true
                    }

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

                    Pin -> mutate(onSetPinned, true)

                    Unpin -> mutate(onSetPinned, false)

                    MarkRead -> mutate(onSetUnread, false)

                    MarkUnread -> mutate(onSetUnread, true)

                    Archive -> mutate(onSetArchived, true)

                    Unarchive -> mutate(onSetArchived, false)

                    Delete -> {
                        expanded = false
                        deleteOpen = true
                    }

                    else -> error("unhandled session action: ${item.label}")
                }
            },
        )
    }

    if (renameOpen) {
        RenameSessionDialog(
            open = renameOpen,
            onDismiss = { renameOpen = false },
            sessionId = sessionId,
            currentTitle = sessionTitle,
            onConfirm = { newTitle ->
                onRename?.invoke(newTitle)
            },
        )
    }

    if (deleteOpen) {
        DeleteSessionDialog(
            open = deleteOpen,
            onDismiss = { deleteOpen = false },
            sessionId = sessionId,
            sessionTitle = sessionTitle,
            onConfirm = {
                onDelete?.invoke()
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
