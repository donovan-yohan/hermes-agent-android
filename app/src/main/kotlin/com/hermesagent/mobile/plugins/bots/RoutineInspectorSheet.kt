package com.hermesagent.mobile.plugins.bots

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.hermesagent.mobile.ui.common.TextButton
import com.hermesagent.mobile.ui.theme.HermesTheme

/** Phone adaptation of Desktop's read-only detail dialog; no mutation actions. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RoutineInspectorSheet(job: RoutineRow, nowMillis: Long, stale: Boolean, onClose: () -> Unit) {
    val tokens = HermesTheme.tokens
    ModalBottomSheet(
        onDismissRequest = onClose,
        // A partially expanded sheet places short, non-scrollable details below
        // the viewport; open fully so Instruction and Close are reachable.
        sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = tokens.cardSurface,
        contentColor = tokens.textPrimary,
    ) {
        Column(
            Modifier.fillMaxWidth().imePadding().navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = HermesTheme.spacing.pageInset, vertical = 8.dp)
                .testTag("Routine inspector"),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(routineDisplay(job.title), style = HermesTheme.type.screenTitle)
            Text("What this job runs, and when it runs next.", style = HermesTheme.type.caption, color = tokens.textTertiary)
            if (stale) Text(BotsRoutinesCopy.STALE_NOTICE, style = HermesTheme.type.caption, color = tokens.textTertiary)
            job.issue?.let { Text(routineDisplay(it, 1024), style = HermesTheme.type.body, color = tokens.textPrimary) }
            routineDetailRows(job, nowMillis).forEach { field ->
                Column {
                    Text(field.label, style = HermesTheme.type.caption, color = tokens.textTertiary)
                    Text(field.value, style = HermesTheme.type.body, color = tokens.textPrimary)
                }
            }
            job.instructionPreview?.let {
                Text("Instruction", style = HermesTheme.type.caption, color = tokens.textTertiary)
                Text(routineDisplay(it, 1024), style = HermesTheme.type.body, color = tokens.textPrimary)
            }
            TextButton(label = "Close", onClick = onClose)
        }
    }
}
