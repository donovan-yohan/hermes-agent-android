package com.hermesagent.mobile.ui.chat

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextLayoutResult
import com.hermesagent.mobile.data.gateway.parseHistory
import com.hermesagent.mobile.data.gateway.projectRestTranscriptRows
import com.hermesagent.mobile.data.markdown.MarkdownBlock
import com.hermesagent.mobile.data.markdown.parseMarkdown
import com.hermesagent.mobile.data.markdown.parseTranscriptDirective
import com.hermesagent.mobile.data.markdown.spansText
import com.hermesagent.mobile.data.markdown.transcriptLinks
import com.hermesagent.mobile.data.session.AssistantTurn
import com.hermesagent.mobile.data.session.SessionSummary
import com.hermesagent.mobile.data.ssh.redact
import com.hermesagent.mobile.plugins.bots.displayPreview
import com.hermesagent.mobile.plugins.groups.GroupMember
import com.hermesagent.mobile.plugins.groups.GroupState
import com.hermesagent.mobile.plugins.groups.GroupTranscript
import com.hermesagent.mobile.plugins.groups.GroupsPhase
import com.hermesagent.mobile.plugins.groups.GroupsScreen
import com.hermesagent.mobile.plugins.groups.GroupsUiState
import com.hermesagent.mobile.plugins.groups.HostedGroup
import com.hermesagent.mobile.plugins.groups.parseGroupLog
import com.hermesagent.mobile.ui.ChatActions
import com.hermesagent.mobile.ui.saveNativeCapture
import com.hermesagent.mobile.ui.theme.AppearanceSelection
import com.hermesagent.mobile.ui.theme.HermesTheme
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Diagnostic coverage of the exact reported APK paragraph; no Gateway or browser requests. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp", shadows = [ShadowSelectionMagnifier::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ApkArtifactLinkDiagnosticTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val opened = mutableListOf<String>()
    private val handler = object : UriHandler {
        override fun openUri(uri: String) { opened += uri }
    }

    @Test
    fun exactSourceSurvivesRedactionRpcAndRestProjection() {
        assertEquals(SOURCE, redact(SOURCE))
        for (turn in listOf(hydrate(false), hydrate(true))) {
            assertEquals(SOURCE, turn.markdown)
            val spans = (parseMarkdown(turn.markdown).single() as MarkdownBlock.Paragraph).spans
            assertEquals(LABEL, spans.spansText())
            assertNull(parseTranscriptDirective(spans.spansText()))
            val link = spans.transcriptLinks().single()
            assertEquals(DESTINATION, link.destination)
            assertEquals(LABEL, spans.spansText().substring(link.start, link.end))
        }
    }

    @Test
    fun protocolLookalikesDoNotChangeTheExactMarkdownLink() {
        assertEquals(LABEL, displayPreview(SOURCE))
        val mediaSpans = (parseMarkdown("MEDIA:$DESTINATION").single() as MarkdownBlock.Paragraph).spans
        assertEquals("MEDIA:$DESTINATION", mediaSpans.spansText())
        val imageSpans = (parseMarkdown("!$SOURCE").single() as MarkdownBlock.Paragraph).spans
        assertEquals(LABEL, imageSpans.spansText())
        assertTrue(imageSpans.transcriptLinks().isEmpty())
    }

    @Test
    fun exactSourceNormalChatHasClickableTarget() = checkChat(botChat = false)

    @Test
    fun exactSourceCanonicalBotChatHasClickableTarget() = checkChat(botChat = true)

    @Test
    fun exactSourceHostedGroupMemberMessageHasClickableTarget() {
        val room = HostedGroup("room", "Planning", listOf(GroupMember("ops", "ops", "Ops", false)), 1, false, "fixture")
        val state = GroupsUiState(
            phase = GroupsPhase.Ready,
            selected = room.id,
            transcript = GroupTranscript(GroupState(room), hydrateGroupMember(), 1),
        )
        compose.setContent {
            HermesTheme(AppearanceSelection()) {
                CompositionLocalProvider(LocalUriHandler provides handler) { GroupsScreen(state, {}) }
            }
        }
        val literal = compose.onAllNodesWithText(SOURCE, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        compose.runOnIdle {
            saveNativeCapture(compose.activity, if (literal) "apk-link-group-baseline.png" else "apk-link-group-current.png")
        }
        assertTrue("Group message must render the link label, not literal Markdown: $SOURCE", !literal)
        compose.onNodeWithText(LABEL, useUnmergedTree = true).assertIsDisplayed()
        val text = layout(LABEL).layoutInput.text
        val annotation = text.getLinkAnnotations(0, text.length).single()
        assertEquals(DESTINATION, (annotation.item as LinkAnnotation.Url).url)
        assertEquals(LABEL, text.text.substring(annotation.start, annotation.end))
        touch(LABEL, "download")
        assertEquals(listOf(DESTINATION), opened)
    }

    private fun hydrateGroupMember() = parseGroupLog(Json.parseToJsonElement("""
        {
          "authority":{"gateway_id":"fixture","epoch":1},
          "cursor":1,"latest_seq":1,"has_more":false,
          "events":[{
            "room_id":"room","seq":1,"event_id":"message-1","authority_epoch":1,
            "created_at":1,"idempotent":false,"actor":{"id":"ops","kind":"member"},
            "kind":"message.member","payload":{
              "thread_id":"thread-1","discussion_event_id":"discussion-1","turn_id":"turn-1",
              "member_id":"ops","task_id":"task-1","member_index":0,"round_index":0,
              "text":${JsonPrimitive(SOURCE)}
            }
          }]
        }
    """.trimIndent()), "room", 0).events.also { events ->
        assertEquals("message.member", events.single().kind)
        assertEquals("ops", events.single().memberId)
        assertEquals(SOURCE, events.single().text)
    }

    private fun checkChat(botChat: Boolean) {
        val state = ChatUiState(
            activeSession = SessionSummary("diagnostic", "APK link", "", 1L),
            transcript = listOf(hydrate(false)),
            botChat = botChat,
        )
        compose.setContent {
            HermesTheme(AppearanceSelection()) {
                CompositionLocalProvider(LocalUriHandler provides handler) {
                    ChatScreen(state, ChatActions(), onOpenSettings = {})
                }
            }
        }
        compose.onNodeWithText(LABEL, useUnmergedTree = true).assertIsDisplayed()
        compose.runOnIdle { saveNativeCapture(compose.activity, if (botChat) "apk-link-bot-current.png" else "apk-link-normal-current.png") }
        val text = layout(LABEL).layoutInput.text
        val annotation = text.getLinkAnnotations(0, text.length).single()
        assertEquals(DESTINATION, (annotation.item as LinkAnnotation.Url).url)
        assertEquals(LABEL, text.text.substring(annotation.start, annotation.end))
        touch(LABEL, "download")
        assertEquals(listOf(DESTINATION), opened)
    }

    private fun hydrate(rest: Boolean): AssistantTurn {
        val row = buildJsonObject {
            put("role", JsonPrimitive("assistant"))
            put(if (rest) "content" else "text", JsonPrimitive(SOURCE))
            put(if (rest) "id" else "row_id", JsonPrimitive(1))
        }
        val rows = if (rest) projectRestTranscriptRows(listOf(row)) else listOf(row)
        val history = buildJsonObject { put("messages", JsonArray(rows)) }
        return parseHistory(history, "diagnostic", 1L).filterIsInstance<AssistantTurn>().single()
    }

    private fun touch(visible: String, target: String) {
        val textLayout = layout(visible)
        val offset = textLayout.layoutInput.text.text.indexOf(target)
        assertTrue(offset >= 0)
        compose.onNodeWithText(visible, useUnmergedTree = true).performTouchInput {
            click(textLayout.getBoundingBox(offset).center)
        }
        compose.waitForIdle()
    }

    private fun layout(visible: String): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        val action = compose.onNodeWithText(visible, useUnmergedTree = true)
            .fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action
        assertTrue(action != null && action(layouts))
        return layouts.single()
    }

    private companion object {
        const val DESTINATION = "https://github.com/donovan-yohan/hermes-agent-android/actions/runs/39120359182/artifacts/12134162983"
        const val LABEL = "download the latest apk"
        const val SOURCE = "[download the latest apk](https://github.com/donovan-yohan/hermes-agent-android/actions/runs/39120359182/artifacts/12134162983)"
    }
}
