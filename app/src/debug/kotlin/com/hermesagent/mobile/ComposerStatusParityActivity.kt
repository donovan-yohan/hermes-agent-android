package com.hermesagent.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hermesagent.mobile.data.composer.QueuedPrompt
import com.hermesagent.mobile.data.composer.QueuedPromptDelivery
import com.hermesagent.mobile.data.session.ComposerBackgroundProcess
import com.hermesagent.mobile.data.session.ComposerBackgroundProcessState
import com.hermesagent.mobile.data.session.ComposerGoalState
import com.hermesagent.mobile.data.session.ComposerGoalStatus
import com.hermesagent.mobile.data.session.ComposerStatusState
import com.hermesagent.mobile.data.session.ComposerSubagentStatus
import com.hermesagent.mobile.data.session.ComposerTodoState
import com.hermesagent.mobile.data.session.ComposerTodoStatus
import com.hermesagent.mobile.ui.chat.Composer
import com.hermesagent.mobile.ui.chat.ComposerQueueSection
import com.hermesagent.mobile.ui.chat.ComposerStatusStack
import com.hermesagent.mobile.ui.theme.AppearanceSelection
import com.hermesagent.mobile.ui.theme.HermesTheme
import com.hermesagent.mobile.ui.theme.HermesThemeMode

/** Debug-only, synthetic status fixture. Intent extras are capture metadata, never user data. */
class ComposerStatusParityActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val state = intent.getStringExtra(EXTRA_STATE) ?: "full-stack-task-list"
        val theme = if (intent.getStringExtra(EXTRA_THEME) == "light") HermesThemeMode.Light else HermesThemeMode.Dark
        setContent { HermesTheme(AppearanceSelection("mono", theme)) { ComposerStatusParityFixture(state) } }
    }

    companion object {
        const val EXTRA_STATE = "visual_parity_state"
        const val EXTRA_THEME = "visual_parity_theme"
    }
}

@Composable
private fun ComposerStatusParityFixture(state: String) {
    val goalState = mapOf(
        "goal-active" to ComposerGoalState.Active,
        "goal-waiting" to ComposerGoalState.Waiting,
        "goal-paused" to ComposerGoalState.Paused,
        "goal-done" to ComposerGoalState.Done,
    )[state] ?: ComposerGoalState.Active
    val status = ComposerStatusState(
        goal = ComposerGoalStatus("Synthetic capture goal", goalState, "Synthetic capture goal"),
        todos = listOf(
            ComposerTodoStatus("outline", "Outline the synthetic fixture", ComposerTodoState.Completed),
            ComposerTodoStatus("render", "Render the status stack", ComposerTodoState.InProgress),
            ComposerTodoStatus("record", "Record visual provenance", ComposerTodoState.Pending),
        ),
        subagents = listOf(ComposerSubagentStatus("helper", "Fixture helper", "render")),
        backgroundProcesses = listOf(
            ComposerBackgroundProcess("preview", "Synthetic preview", ComposerBackgroundProcessState.Running),
        ),
    )
    val queue = listOf(
        QueuedPrompt("first", "Synthetic queued message", queuedAtMillis = 0L, delivery = QueuedPromptDelivery.Ready),
        QueuedPrompt("second", "Second synthetic queued message", queuedAtMillis = 1L, delivery = QueuedPromptDelivery.Ready),
    )
    Column(
        Modifier.fillMaxSize().background(HermesTheme.tokens.chatSurface).systemBarsPadding(),
    ) {
        Spacer(Modifier.weight(1f))
        ComposerStatusStack(
            activeSessionId = "visual-parity-session",
            status = status,
            hasQueue = state == "queue-parked-collapsed",
            queueContent = if (state == "queue-parked-collapsed") {
                {
                    ComposerQueueSection(
                        durableSessionId = "visual-parity-session",
                        entries = queue,
                        parked = true,
                        editingEntryId = null,
                        editingText = "",
                        onEdit = {}, onEditTextChange = {}, onSaveEdit = {}, onCancelEdit = {},
                        onDelete = {}, onSendNext = {}, onRedirectNow = {}, onResume = {}, onMarkReadyAfterReview = {},
                    )
                }
            } else null,
            fusedToComposer = true,
            modifier = Modifier.padding(start = HermesTheme.spacing.pageInset + 8.dp, top = 4.dp, end = HermesTheme.spacing.pageInset + 8.dp),
        )
        Composer(
            draft = "", onDraftChange = {}, onSend = {}, onStop = {}, isStreaming = false,
            canSend = false, connected = true, statusLine = "Synthetic capture connection", fusedStatusAbove = true,
        )
    }
}
