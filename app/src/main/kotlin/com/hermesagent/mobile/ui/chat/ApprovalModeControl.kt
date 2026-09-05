package com.hermesagent.mobile.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.hermesagent.mobile.data.gateway.ApprovalMode
import com.hermesagent.mobile.ui.common.Hairline
import com.hermesagent.mobile.ui.common.MenuSectionLabel
import com.hermesagent.mobile.ui.common.ZapGlyph
import com.hermesagent.mobile.ui.common.touchTargetOverflow
import com.hermesagent.mobile.ui.theme.HermesTheme

/**
 * The approval-mode control, ported from Desktop's statusbar item
 * (`apps/desktop/src/app/shell/approval-mode-menu.tsx:21-76` and
 * `apps/desktop/src/app/shell/hooks/use-statusbar-items.tsx:270,568-572`) @
 * `3ca096de5f8183cb2e0ec23673f294d5978656a3`.
 *
 * Android has no Electron footer, so the item is a chip in the chat top bar's
 * subtitle row beside the Context meter — the placement `docs/parity/context-usage.md`
 * already settled for a statusbar-sourced control. Everything else is Desktop's:
 * the bolt, the mode word, the `off` state's filled-and-highlighted treatment,
 * the menu title, and three radio rows in Desktop's own order carrying its
 * labels and descriptions verbatim.
 *
 * The full ledger is `docs/parity/approval-mode.md`.
 */

/** `Approval mode` (`i18n/en.ts:2898` @ `3ca096de`). */
const val APPROVAL_MODE_TITLE = "Approval mode"

/** `Manual` (`i18n/en.ts:2900` @ `3ca096de`). */
private const val MANUAL = "Manual"

/** `Ask before actions that require approval` (`i18n/en.ts:2901` @ `3ca096de`). */
private const val MANUAL_DESCRIPTION = "Ask before actions that require approval"

/** `Smart` (`i18n/en.ts:2902` @ `3ca096de`). */
private const val SMART = "Smart"

/** `Automatically assess actions and ask when needed` (`i18n/en.ts:2903` @ `3ca096de`). */
private const val SMART_DESCRIPTION = "Automatically assess actions and ask when needed"

/** `Off` (`i18n/en.ts:2904` @ `3ca096de`). */
private const val OFF = "Off"

/** `Run without approval prompts` (`i18n/en.ts:2905` @ `3ca096de`). */
private const val OFF_DESCRIPTION = "Run without approval prompts"

internal const val APPROVAL_MODE_CHIP_TAG = "approval-mode-chip"
internal const val APPROVAL_MODE_MENU_TAG = "approval-mode-menu"
internal const val APPROVAL_MODE_MENU_HEADER_TAG = "approval-mode-menu-header"

/** `Approval mode: ${mode}` (`i18n/en.ts:2899` @ `3ca096de`). */
fun approvalModeSpokenName(mode: ApprovalMode): String = "$APPROVAL_MODE_TITLE: ${approvalModeLabel(mode)}"

fun approvalModeLabel(mode: ApprovalMode): String = when (mode) {
    ApprovalMode.Manual -> MANUAL
    ApprovalMode.Smart -> SMART
    ApprovalMode.Off -> OFF
}

fun approvalModeDescription(mode: ApprovalMode): String = when (mode) {
    ApprovalMode.Manual -> MANUAL_DESCRIPTION
    ApprovalMode.Smart -> SMART_DESCRIPTION
    ApprovalMode.Off -> OFF_DESCRIPTION
}

@Composable
fun ApprovalModeChip(
    mode: ApprovalMode,
    onSelect: (ApprovalMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = HermesTheme.tokens
    var expanded by remember { mutableStateOf(false) }
    // `off` is the only state Desktop highlights: it swaps the outline bolt for
    // the filled one, drops the 70% opacity the other modes carry, and gives
    // the item the hovered chrome background (`approval-mode-menu.tsx:46-47`).
    val off = mode == ApprovalMode.Off
    val ink = if (off) tokens.textPrimary else tokens.textSecondary

    Box(modifier) {
        Row(
            modifier = Modifier
                // The touch floor overflows the status line rather than
                // heightening it, and the chip's own chrome stays the size of
                // the word inside it — a 48dp `off` highlight would be a pill
                // taller than the title above it.
                .touchTargetOverflow(HermesTheme.spacing.touchTarget)
                .testTag(APPROVAL_MODE_CHIP_TAG)
                .clickable(
                    role = Role.Button,
                    onClick = { expanded = true },
                    // `clickable` merges the row's children, so a blanket
                    // description here would replace the mode word a sighted
                    // reader sees. The name rides the click action instead,
                    // which is exactly what Desktop's `title` is (`:73`).
                    onClickLabel = approvalModeSpokenName(mode),
                )
                .wrapContentHeight(Alignment.CenterVertically)
                .clip(ChipShape)
                .background(if (off) tokens.widgetSurface else Color.Transparent)
                .padding(horizontal = 4.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ZapGlyph(color = if (off) ink else tokens.textTertiary, filled = off)
            Text(
                text = approvalModeLabel(mode),
                style = HermesTheme.type.caption,
                color = ink,
                maxLines = 1,
            )
        }
        ApprovalModeMenu(
            expanded = expanded,
            selected = mode,
            onDismiss = { expanded = false },
            onSelect = {
                expanded = false
                onSelect(it)
            },
        )
    }
}

/**
 * Desktop's `DropdownMenuRadioGroup` under a `DropdownMenuLabel` and a
 * separator (`approval-mode-menu.tsx:52-71`), as the same `DropdownMenu`
 * primitive the session actions menu already uses — painted from
 * `HermesTheme.tokens`, never Material's own surface or elevation.
 */
@Composable
internal fun ApprovalModeMenu(
    expanded: Boolean,
    selected: ApprovalMode,
    onDismiss: () -> Unit,
    onSelect: (ApprovalMode) -> Unit,
) {
    val tokens = HermesTheme.tokens
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        offset = DpOffset(0.dp, 6.dp),
        modifier = Modifier
            .widthIn(min = ApprovalMenuWidth)
            .border(1.dp, tokens.strokePrimary, ChipShape)
            .testTag(APPROVAL_MODE_MENU_TAG),
        shape = ChipShape,
        containerColor = tokens.cardSurface,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        // Desktop's `DropdownMenuLabel` (`dropdown-menu.tsx:183-198`) is
        // left-aligned at the same inset as its rows' words, because Desktop's
        // selected mark is trailing (`:176`). This app's mark leads the row, so
        // the heading follows the words rather than the box — and is set in the
        // app's own uppercase panel-label treatment, the one every other
        // heading over a list here already wears.
        MenuSectionLabel(
            text = APPROVAL_MODE_TITLE,
            modifier = Modifier.testTag(APPROVAL_MODE_MENU_HEADER_TAG),
            inset = MenuLabelColumnInset,
        )
        Hairline()
        ApprovalMode.MENU_ORDER.forEach { mode ->
            ApprovalModeRow(mode = mode, chosen = mode == selected) { onSelect(mode) }
        }
    }
}

@Composable
private fun ApprovalModeRow(mode: ApprovalMode, chosen: Boolean, onClick: () -> Unit) {
    val tokens = HermesTheme.tokens
    val label = approvalModeLabel(mode)
    val description = approvalModeDescription(mode)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = HermesTheme.spacing.touchTarget)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .semantics {
                contentDescription = "$label. $description"
                selected = chosen
            }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier
                .padding(top = 2.dp)
                .size(RadioSize)
                .border(1.dp, if (chosen) tokens.accent else tokens.strokeSecondary, RadioShape)
                .background(if (chosen) tokens.accent else tokens.cardSurface, RadioShape),
        )
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(text = label, style = HermesTheme.type.scaffold, color = tokens.textPrimary)
            Text(
                text = description,
                style = HermesTheme.type.scaffoldMeta,
                color = tokens.textTertiary,
            )
        }
    }
}

/**
 * Where a row's words start: the row's own inset, plus the leading selected
 * mark and the gap after it. The heading sits on the same column.
 */
private val MenuLabelColumnInset = 12.dp + 8.dp + 10.dp

/** Desktop's `w-72` menu, at the phone type scale. */
private val ApprovalMenuWidth = 280.dp

private val ChipShape = RoundedCornerShape(6.dp)
private val RadioShape = RoundedCornerShape(4.dp)
private val RadioSize = 8.dp
