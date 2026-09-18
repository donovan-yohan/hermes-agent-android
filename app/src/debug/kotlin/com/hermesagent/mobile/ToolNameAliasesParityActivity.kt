package com.hermesagent.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hermesagent.mobile.data.gateway.latestComposerTodosFromHistory
import com.hermesagent.mobile.data.session.ComposerStatusState
import com.hermesagent.mobile.data.session.TODO_TOOL_NAME
import com.hermesagent.mobile.data.session.ToolActivity
import com.hermesagent.mobile.data.session.ToolState
import com.hermesagent.mobile.data.session.TranscriptEntry
import com.hermesagent.mobile.ui.chat.ComposerStatusStack
import com.hermesagent.mobile.ui.chat.Transcript
import com.hermesagent.mobile.ui.theme.AppearanceSelection
import com.hermesagent.mobile.ui.theme.HermesTheme
import com.hermesagent.mobile.ui.theme.HermesThemeMode
import kotlinx.serialization.json.Json

/** Debug-only, synthetic task-tool-name fixture. Intent extras are capture metadata, never user data. */
class ToolNameAliasesParityActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val state = ToolNameAliasesFixtureState.parse(intent.getStringExtra(EXTRA_STATE))
        val theme = if (intent.getStringExtra(EXTRA_THEME) == "light") HermesThemeMode.Light else HermesThemeMode.Dark
        setContent { HermesTheme(AppearanceSelection("mono", theme)) { ToolNameAliasesParityFixture(state) } }
    }

    companion object {
        const val EXTRA_STATE = "visual_parity_state"
        const val EXTRA_THEME = "visual_parity_theme"
    }
}

/** Every task-tool-name state in the capture catalog. Unknown values fail before rendering. */
internal enum class ToolNameAliasesFixtureState(val wireValue: String) {
    /**
     * Both wire spellings as transcript rows, over one identical synthetic
     * payload, beneath the composer task panel derived from a stored
     * `todo_list` history through the production parser (#284).
     */
    TodoNameAliases("todo-name-aliases"),
    ;

    companion object {
        fun parse(value: String?): ToolNameAliasesFixtureState =
            entries.firstOrNull { it.wireValue == value }
                ?: throw IllegalArgumentException("unsupported tool-name-aliases parity state: $value")
    }
}

/**
 * One synthetic payload for both rows, so the only thing that differs between
 * them is the name the wire gave the tool: an explicit `count: 2` makes the
 * count label the display table's noun rather than an inference.
 */
internal const val ALIAS_FIXTURE_RESULT = """{"count":2}"""

/** The stored history the composer panel is derived from, as a Gateway wrote it. */
internal const val ALIAS_FIXTURE_HISTORY = """{"messages":[
    {"role":"assistant","content":[
        {"type":"tool-call","toolName":"$TODO_TOOL_NAME","args":{"todos":[
            {"id":"outline","content":"Outline the synthetic fixture","status":"completed"},
            {"id":"render","content":"Render the task panel","status":"in_progress"}
        ]}}
    ]}
]}"""

/** The two rows, differing only in the spelling the wire used. */
internal fun toolNameAliasFixtureRows(): List<ToolActivity> = listOf(
    ToolActivity(
        id = "visual-parity-todo-legacy",
        label = "todo",
        detail = "",
        state = ToolState.Done,
        elapsedSeconds = 1.0,
        toolName = "todo",
        argsText = null,
        resultText = ALIAS_FIXTURE_RESULT,
    ),
    ToolActivity(
        id = "visual-parity-todo-current",
        label = TODO_TOOL_NAME,
        detail = "",
        state = ToolState.Done,
        elapsedSeconds = 1.0,
        toolName = TODO_TOOL_NAME,
        argsText = null,
        resultText = ALIAS_FIXTURE_RESULT,
    ),
)

/** The composer task list a pinned Gateway's stored history yields, through the real parser. */
internal fun toolNameAliasFixtureTodos() =
    latestComposerTodosFromHistory(Json.parseToJsonElement(ALIAS_FIXTURE_HISTORY)).orEmpty()

@Composable
internal fun ToolNameAliasesParityFixture(state: ToolNameAliasesFixtureState) {
    // The rows are the real `Transcript` rows, and the panel is the real status
    // stack over the real parser's output: nothing here is a stand-in for
    // production UI. The state exists because a hoisted tool is never a
    // transcript row in a live turn, so the only way a capture can show what the
    // display metadata resolves the two spellings to is to mount the same row
    // shape deliberately.
    val entries = remember(state) { toolNameAliasFixtureRows() as List<TranscriptEntry> }
    val todos = remember(state) { toolNameAliasFixtureTodos() }
    Column(Modifier.fillMaxSize().background(HermesTheme.tokens.chatSurface).systemBarsPadding()) {
        Transcript(
            entries = entries,
            listState = rememberLazyListState(),
            modifier = Modifier.weight(1f),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp),
        )
        ComposerStatusStack(
            activeSessionId = "visual-parity-session",
            status = ComposerStatusState(todos = todos),
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = HermesTheme.spacing.pageInset, end = HermesTheme.spacing.pageInset),
        )
    }
}
