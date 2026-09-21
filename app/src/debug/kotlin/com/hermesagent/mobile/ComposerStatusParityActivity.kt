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
import com.hermesagent.mobile.ui.common.JoinedEdge
import com.hermesagent.mobile.ui.common.JoinedPaneLayout
import com.hermesagent.mobile.ui.common.JoinedStackRadius
import com.hermesagent.mobile.ui.theme.AppearanceSelection
import com.hermesagent.mobile.ui.theme.HermesTheme
import com.hermesagent.mobile.ui.theme.HermesThemeMode

/** Debug-only, synthetic status fixture. Intent extras are capture metadata, never user data. */
class ComposerStatusParityActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val state = ComposerStatusFixtureState.parse(intent.getStringExtra(EXTRA_STATE))
        val theme = if (intent.getStringExtra(EXTRA_THEME) == "light") HermesThemeMode.Light else HermesThemeMode.Dark
        setContent { HermesTheme(AppearanceSelection("mono", theme)) { ComposerStatusParityFixture(state) } }
    }

    companion object {
        const val EXTRA_STATE = "visual_parity_state"
        const val EXTRA_THEME = "visual_parity_theme"
    }
}

/** Every status-stack state in the capture catalog. Unknown or wrong-surface values fail before rendering. */
internal enum class ComposerStatusFixtureState(val wireValue: String, val goalState: ComposerGoalState) {
    FullStackTaskList("full-stack-task-list", ComposerGoalState.Active),
    GoalActive("goal-active", ComposerGoalState.Active),
    GoalWaiting("goal-waiting", ComposerGoalState.Waiting),
    GoalPaused("goal-paused", ComposerGoalState.Paused),
    GoalDone("goal-done", ComposerGoalState.Done),
    QueueParkedCollapsed("queue-parked-collapsed", ComposerGoalState.Active),
    BackgroundOpen("background-open", ComposerGoalState.Active),
    ;

    companion object {
        fun parse(value: String?): ComposerStatusFixtureState = entries.firstOrNull { it.wireValue == value }
            ?: throw IllegalArgumentException("unsupported ComposerStatus parity state: $value")
    }
}

@Composable
internal fun ComposerStatusParityFixture(state: ComposerStatusFixtureState) {
    val goal = ComposerGoalStatus("Synthetic capture goal", state.goalState, "Synthetic capture goal")
    val background = ComposerBackgroundProcess("preview", "Synthetic preview", ComposerBackgroundProcessState.Running)
    val status = when (state) {
        ComposerStatusFixtureState.FullStackTaskList -> ComposerStatusState(
            goal = goal,
            todos = listOf(
                ComposerTodoStatus("outline", "Outline the synthetic fixture", ComposerTodoState.Completed),
                ComposerTodoStatus("render", "Render the status stack", ComposerTodoState.InProgress),
                ComposerTodoStatus("record", "Record visual provenance", ComposerTodoState.Pending),
            ),
            subagents = listOf(ComposerSubagentStatus("helper", "Fixture helper", "render")),
            backgroundProcesses = listOf(background),
        )
        ComposerStatusFixtureState.QueueParkedCollapsed -> ComposerStatusState()
        ComposerStatusFixtureState.BackgroundOpen -> ComposerStatusState(backgroundProcesses = listOf(background))
        ComposerStatusFixtureState.GoalActive,
        ComposerStatusFixtureState.GoalWaiting,
        ComposerStatusFixtureState.GoalPaused,
        ComposerStatusFixtureState.GoalDone,
        -> ComposerStatusState(goal = goal)
    }
    val queue = listOf(
        QueuedPrompt("first", "Synthetic queued message", queuedAtMillis = 0L, delivery = QueuedPromptDelivery.Ready),
        QueuedPrompt("second", "Second synthetic queued message", queuedAtMillis = 1L, delivery = QueuedPromptDelivery.Ready),
    )
    Column(Modifier.fillMaxSize().background(HermesTheme.tokens.chatSurface).systemBarsPadding()) {
        Spacer(Modifier.weight(1f))
        ComposerStatusStack(
            activeSessionId = "visual-parity-session",
            status = status,
            // Queue starts collapsed in the real surface. The catalog retains that
            // initial accessibility state without tapping it open before capture.
            hasQueue = state == ComposerStatusFixtureState.QueueParkedCollapsed,
            queueContent = if (state == ComposerStatusFixtureState.QueueParkedCollapsed) {
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
            // Background starts collapsed in the real status stack. Its catalogued
            // Background tap opens it, then the helper retains/checks that state.
            // The strip is the run's top pane and the composer is its bottom, which
            // is the geometry the capture is judging.
            joinedLayout = JoinedPaneLayout(JoinedEdge.Start, JoinedStackRadius),
            modifier = Modifier.padding(start = HermesTheme.spacing.pageInset + 8.dp, top = 4.dp, end = HermesTheme.spacing.pageInset + 8.dp),
        )
        Composer(
            draft = "", onDraftChange = {}, onSend = {}, onStop = {}, isStreaming = false,
            canSend = false, connected = true, statusLine = "Synthetic capture connection",
            joinedLayout = JoinedPaneLayout(JoinedEdge.End, JoinedStackRadius),
        )
    }
}
