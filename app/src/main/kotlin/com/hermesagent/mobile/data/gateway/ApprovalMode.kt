package com.hermesagent.mobile.data.gateway

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * How often the host asks before it acts.
 *
 * Three states, not a boolean: Desktop's own type is
 * `ApprovalMode = 'manual' | 'off' | 'smart'`
 * (`apps/desktop/src/store/approval-mode.ts:3` @
 * `3ca096de5f8183cb2e0ec23673f294d5978656a3`), and the backend validates the
 * same three (`hermes_cli/approval_mode.py:16`,
 * `tui_gateway/server.py:14586-14591`).
 */
enum class ApprovalMode(val wireValue: String) {
    Manual("manual"),
    Smart("smart"),
    Off("off"),
    ;

    companion object {
        /**
         * The three modes in the order Desktop's menu renders them
         * (`approval-mode-menu.tsx:62` @ `3ca096de`). Declaration order here is
         * already that order; naming it stops the menu reordering by accident.
         */
        val MENU_ORDER: List<ApprovalMode> = listOf(Manual, Smart, Off)

        /**
         * Anything unrecognised is [Manual] — the safe end of the scale, and
         * what both sides of the wire already do: Desktop's
         * `normalizeApprovalMode` (`store/approval-mode.ts:23-29`), the
         * Gateway's `_load_approval_mode` (`tui_gateway/server.py:5971`) and
         * the canonical resolver (`tools/approval.py:3405-3432`) all fall back
         * to `manual` rather than to the permissive end.
         */
        fun fromWire(raw: String?): ApprovalMode {
            val normalized = raw?.trim()?.lowercase().orEmpty()
            return entries.firstOrNull { it.wireValue == normalized } ?: Manual
        }
    }
}

/**
 * What this connection knows about the host's approval configuration.
 *
 * @param mode null until the first authoritative answer. Desktop shows `smart`
 *   before its own `config.get` returns (`store/approval-mode.ts:32`); this app
 *   shows nothing, because a control that names a security posture must not
 *   name one it is guessing.
 * @param bypassActive `session.info.yolo`: the effective approval bypass, which
 *   the Gateway ORs from three sources — the frozen process `--yolo`, the
 *   per-session flag and `approvals.mode == "off"`
 *   (`tui_gateway/server.py:7616-7631` @ `3ca096de`). Parsed because the field
 *   is on the contract and the first two sources are invisible from [mode]
 *   alone; nothing renders it, because Desktop shows it on a separate YOLO
 *   status item this app does not port (`docs/parity/approval-mode.md`).
 */
data class ApprovalModeState(
    val mode: ApprovalMode? = null,
    val bypassActive: Boolean = false,
)

/** The result of one `config.set approvals.mode`. */
sealed interface ApprovalModeOutcome {
    data object Applied : ApprovalModeOutcome

    /** The host refused or the write never landed; the control has rolled back. */
    data class Rejected(val safeMessage: String) : ApprovalModeOutcome
}

/** The one config key both `config.get` and `config.set` answer to. */
internal const val APPROVALS_MODE_KEY = "approvals.mode"

/**
 * What a rejected write says. The mode on screen has already rolled back to the
 * last confirmed one, so the sentence reports that and offers the retry.
 */
internal const val APPROVAL_MODE_REJECTED = "The approval mode was not changed. Try again."

private val NO_APPROVAL_MODE: StateFlow<ApprovalModeState> = MutableStateFlow(ApprovalModeState())

/** A repository with no approval-mode leg answers "not known", never a guess. */
internal fun noApprovalMode(): StateFlow<ApprovalModeState> = NO_APPROVAL_MODE
