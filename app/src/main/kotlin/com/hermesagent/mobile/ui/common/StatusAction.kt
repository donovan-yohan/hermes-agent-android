package com.hermesagent.mobile.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.hermesagent.mobile.ui.theme.HermesTheme

/**
 * Where a status line goes when it is naming a problem some other surface
 * fixes, and what that surface is called.
 *
 * [spokenDestination] is the accessible half: a line that says "sign in before
 * reconnecting" tells an eye what is wrong but not where to go, so the spoken
 * name of the destination is appended to it. Carried with the callback rather
 * than hardcoded by the control, so a status line does not have to know which
 * surface this particular state points at.
 */
data class StatusAction(
    val spokenDestination: String,
    val onClick: () -> Unit,
)

/**
 * Makes a status line the door it is already describing, or leaves it as prose.
 *
 * One home for the three rules a tappable status owes: a touch-target floor, a
 * button role, and a spoken name that carries both the line and where it goes.
 * Two surfaces render such a line — the chat header subtitle and the composer
 * status — and an accessibility fix to the spoken form has to reach both.
 */
@Composable
fun Modifier.statusAction(line: String, action: StatusAction?): Modifier =
    if (action == null) {
        this
    } else {
        heightIn(min = HermesTheme.spacing.touchTarget)
            .clickable(role = Role.Button, onClick = action.onClick)
            .semantics { contentDescription = "$line. ${action.spokenDestination}" }
    }
