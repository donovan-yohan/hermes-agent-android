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
import com.hermesagent.mobile.data.session.ComposerStatusState
import com.hermesagent.mobile.data.session.ComposerTodoState
import com.hermesagent.mobile.data.session.ComposerTodoStatus
import com.hermesagent.mobile.ui.chat.CodingContext
import com.hermesagent.mobile.ui.chat.CodingPullRequest
import com.hermesagent.mobile.ui.chat.CodingStatusRow
import com.hermesagent.mobile.ui.chat.Composer
import com.hermesagent.mobile.ui.chat.ComposerStatusStack
import com.hermesagent.mobile.ui.theme.AppearanceSelection
import com.hermesagent.mobile.ui.theme.HermesTheme
import com.hermesagent.mobile.ui.theme.HermesThemeMode

/** Debug-only sanitized fixture used by the Desktop-to-mobile visual gate. */
class ComposerStatusParityActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            HermesTheme(AppearanceSelection("mono", HermesThemeMode.Dark)) {
                ComposerStatusParityFixture()
            }
        }
    }
}

@Composable
private fun ComposerStatusParityFixture() {
    val coding = CodingContext.Available(
        branch = "feat/markdown-rendering",
        worktreePath = "/home/alice/Documents/Programs/hermes-mobile",
        additions = 83,
        deletions = 37,
        pullRequest = CodingPullRequest(
            number = 23,
            url = "https://github.com/acme/hermes-mobile/pull/23",
            state = "open",
            draft = false,
        ),
    )
    val tasks = listOf(
        ComposerTodoStatus("trace", "Trace the Desktop composer status contract", ComposerTodoState.Completed),
        ComposerTodoStatus("transport", "Use the authenticated Gateway git transport", ComposerTodoState.Completed),
        ComposerTodoStatus("tests", "Cover task parsing and repository status", ComposerTodoState.Completed),
        ComposerTodoStatus("surface", "Implement branch links and local diff counts", ComposerTodoState.InProgress),
        ComposerTodoStatus("visual", "Compare Desktop and Android screenshots", ComposerTodoState.Pending),
        ComposerTodoStatus("verify", "Run the final Android verification gate", ComposerTodoState.Pending),
    )

    Column(
        Modifier
            .fillMaxSize()
            .background(HermesTheme.tokens.chatSurface)
            .systemBarsPadding(),
    ) {
        Spacer(Modifier.weight(1f))
        ComposerStatusStack(
            activeSessionId = "visual-parity-session",
            status = ComposerStatusState(todos = tasks),
            fusedToComposer = true,
            modifier = Modifier.padding(
                start = HermesTheme.spacing.pageInset + 8.dp,
                top = 4.dp,
                end = HermesTheme.spacing.pageInset + 8.dp,
            ),
        )
        Composer(
            draft = "",
            onDraftChange = {},
            onSend = {},
            onStop = {},
            isStreaming = false,
            canSend = false,
            connected = true,
            statusLine = "Connected to Gateway",
            codingHeader = {
                CodingStatusRow(
                    context = coding,
                    onOpenReview = {},
                    openExternal = {},
                    copyPath = {},
                )
            },
            fusedStatusAbove = true,
        )
    }
}
