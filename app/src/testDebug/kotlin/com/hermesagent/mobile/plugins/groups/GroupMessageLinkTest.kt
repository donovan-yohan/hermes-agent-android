package com.hermesagent.mobile.plugins.groups

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextLayoutResult
import com.hermesagent.mobile.data.markdown.parseTranscriptDirective
import com.hermesagent.mobile.data.ssh.redact
import com.hermesagent.mobile.ui.common.WIP_SPOKEN
import com.hermesagent.mobile.ui.saveNativeCapture
import com.hermesagent.mobile.ui.theme.AppearanceSelection
import com.hermesagent.mobile.ui.theme.HermesTheme
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Production GroupsScreen text layout and coordinate taps, without network requests. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GroupMessageLinkTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val opened = mutableListOf<String>()
    private val handler = object : UriHandler {
        override fun openUri(uri: String) { opened += uri }
    }
    private val room = HostedGroup("room", "Planning", listOf(GroupMember("ops", "ops", "Ops", false)), 1, false, "fixture")
    private val state = mutableStateOf(GroupsUiState())

    @Test
    fun memberLabeledBareInlineAndFencedHttpLinksOpenExactTargets() {
        val bare = "http://example.org/run?tab=one%20two#latest"
        val inline = "https://example.org/inline"
        val fenced = "http://example.org/fenced"
        mount(listOf(hydrateMessage("message.member", "Try [**open** the *artifact*]($DESTINATION).\n\nBare $bare.\n\nInline `$inline`\n\n```sh\ncurl $fenced\n```")))
        compose.runOnIdle { saveNativeCapture(compose.activity, "group-message-links.png") }

        assertLink("Try open the artifact.", "open the artifact", DESTINATION)
        assertLink("Bare $bare.", bare, bare)
        assertLink("Inline $inline", inline, inline)
        assertLink("curl $fenced", fenced, fenced)
        touch("Try open the artifact.", "artifact")
        touch("Bare $bare.", "example.org")
        touch("Inline $inline", "example.org")
        touch("curl $fenced", "example.org")
        assertEquals(listOf(DESTINATION, bare, inline, fenced), opened)
    }

    @Test
    fun unsafeDestinationsAndUrlLookingRefusedLabelsStayInert() {
        mount(listOf(hydrateMessage("message.member",
            "[https://example.org/](javascript:bad) [file](file:///sdcard/test) [data](data:text/plain,test) [ftp](ftp://example.org/file) and ordinary words")))
        val visible = "https://example.org/ file data ftp and ordinary words"
        assertNoLinks(visible)
        for (target in listOf("example.org", "file", "data", "ftp", "ordinary")) touch(visible, target)
        assertTrue(opened.isEmpty())
    }

    @Test
    fun realUserMessageIsClickableWhileRoomControlsAndComposerRemainReadOnly() {
        val event = hydrateMessage("message.user", "[user artifact]($DESTINATION)")
        assertEquals("message.user", event.kind)
        assertNull(event.memberId)
        mount(listOf(event))
        compose.onNodeWithText("You").assertIsDisplayed()
        assertLink("user artifact", "user artifact", DESTINATION)
        touch("user artifact", "artifact")
        assertEquals(listOf(DESTINATION), opened)
        for (label in listOf(
            "Group settings for Planning", "Manage group members", "Disband Planning", "Stop",
            "Reply in thread", "Attach files — every responding bot sees them", "New Thread",
        )) compose.onNodeWithContentDescription("$label. $WIP_SPOKEN").assertIsNotEnabled()
        compose.onNodeWithText("Say something — every bot in this group hears the room.").assertIsDisplayed()
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
    }

    @Test
    fun unsupportedDirectiveRemainsVisibleWithoutPluginRendering() {
        val directive = "::widget{title=\"Not enabled\"}"
        assertNotNull(parseTranscriptDirective(directive))
        mount(listOf(hydrateMessage("message.member", directive)))
        assertNoLinks(directive)
        touch(directive, "widget")
        assertTrue(opened.isEmpty())
    }

    @Test
    fun htmlShapedMessageKeepsLiteralTextAndClickableVisibleUrl() {
        val destination = "https://example.org/fallback"
        val source = "<section>Keep $destination visible</section>"
        mount(listOf(hydrateMessage("message.member", source)))
        assertLink(source, destination, destination)
        touch(source, "example.org")
        assertEquals(listOf(destination), opened)
    }

    @Test
    fun mixedMarkdownMessageKeepsBeforeHtmlNoteAndAfterWithExactUrlTap() {
        val note = "<section>HTML note $DESTINATION visible</section>"
        mount(listOf(hydrateMessage("message.member", "Before **note**.\n\n$note\n\nAfter *note*.")))
        assertNoLinks("Before note.")
        assertNoLinks("After note.")
        assertLink(note, DESTINATION, DESTINATION)
        touch(note, "example.com")
        assertEquals(listOf(DESTINATION), opened)
    }

    @Test
    fun htmlHrefIsLiteralAndUnsafeSchemesNeverOpen() {
        val source = "Before <a href=\"javascript:bad\">unsafe</a> after"
        mount(listOf(hydrateMessage("message.member", source)))
        assertNoLinks(source)
        touch(source, "unsafe")
        touch(source, "javascript")
        assertTrue(opened.isEmpty())
    }

    @Test
    fun redactionPrecedesMarkdownAndPreservesLiteralMarkersWithoutLeakingTokens() {
        val source = "Before password=synthetic-material and [artifact]($DESTINATION).\n\n" +
            "<section>Note X-Hermes-Session-Token: synthetic-token </section>"
        mount(listOf(hydrateMessage("message.member", source)))
        val visible = "Before password=<redacted> and artifact."
        assertLink(visible, "artifact", DESTINATION)
        assertNoLinks("<section>Note X-Hermes-Session-Token: <redacted> </section>")
        for (token in listOf("synthetic-material", "synthetic-token")) {
            compose.onAllNodesWithText(token, substring = true, useUnmergedTree = true).assertCountEquals(0)
        }
        touch(visible, "artifact")
        assertEquals(listOf(DESTINATION), opened)
    }

    @Test
    fun renameStatusAndUnknownMessageKindsStayLiteralWithoutLinks() {
        val rename = hydrateRename("[Renamed]($DESTINATION)")
        val status = "Status [activity](https://example.org/status)"
        val unsupported = "Unknown [assistant](https://example.org/assistant)"
        mount(listOf(
            rename,
            GroupEvent(2, "room.activity", null, status),
            GroupEvent(3, "message.assistant", "ops", unsupported),
        ))
        for (text in listOf(rename.text, status, unsupported)) {
            assertNoLinks(text)
            touch(text, "https")
        }
        assertTrue(opened.isEmpty())
    }

    @Test
    fun changingMessageTextReparsesTheRenderedLink() {
        mount(listOf(hydrateMessage("message.member", "[old artifact]($DESTINATION)")))
        assertLink("old artifact", "old artifact", DESTINATION)
        val replacement = "https://example.org/replacement?q=%25#new"
        compose.runOnIdle {
            state.value = state.value.copy(transcript = GroupTranscript(GroupState(room),
                listOf(hydrateMessage("message.member", "[new artifact]($replacement)")), 1))
        }
        compose.onNodeWithText("old artifact", useUnmergedTree = true).assertDoesNotExist()
        assertLink("new artifact", "new artifact", replacement)
        touch("new artifact", "artifact")
        assertEquals(listOf(replacement), opened)
    }

    private fun mount(events: List<GroupEvent>) {
        state.value = GroupsUiState(phase = GroupsPhase.Ready, selected = room.id,
            transcript = GroupTranscript(GroupState(room), events, events.last().seq))
        compose.setContent {
            HermesTheme(AppearanceSelection()) {
                CompositionLocalProvider(LocalUriHandler provides handler) { GroupsScreen(state.value, {}) }
            }
        }
        compose.waitForIdle()
    }

    private fun hydrateMessage(kind: String, text: String): GroupEvent {
        val member = kind == "message.member"
        val coordinates = if (member) """,
            "discussion_event_id":"discussion-1","turn_id":"turn-1","member_id":"ops",
            "task_id":"task-1","member_index":0,"round_index":0
        """ else ""
        return hydrate(kind, if (member) "member" else "user", """
            {"thread_id":"thread-1","text":${JsonPrimitive(text)}$coordinates}
        """).also {
            assertEquals(kind, it.kind)
            assertEquals(redact(text), it.text)
        }
    }

    private fun hydrateRename(name: String) = hydrate("room.renamed", "user", """{"name":${JsonPrimitive(name)}}""")

    private fun hydrate(kind: String, actorKind: String, payload: String) = parseGroupLog(Json.parseToJsonElement("""
        {"authority":{"gateway_id":"fixture","epoch":1},"cursor":1,"latest_seq":1,"has_more":false,
         "events":[{"room_id":"room","seq":1,"event_id":"event-1","authority_epoch":1,
          "created_at":1,"idempotent":false,"actor":{"id":"actor-1","kind":"$actorKind"},
          "kind":"$kind","payload":$payload}]}
    """.trimIndent()), "room", 0).events.single()

    private fun assertLink(visible: String, label: String, destination: String) {
        compose.onNodeWithText(visible, useUnmergedTree = true).assertIsDisplayed()
        val text = layout(visible).layoutInput.text
        val annotation = text.getLinkAnnotations(0, text.length).single()
        assertEquals(destination, (annotation.item as LinkAnnotation.Url).url)
        assertEquals(label, text.text.substring(annotation.start, annotation.end))
    }

    private fun assertNoLinks(visible: String) {
        compose.onNodeWithText(visible, useUnmergedTree = true).assertIsDisplayed()
        val text = layout(visible).layoutInput.text
        assertTrue(text.getLinkAnnotations(0, text.length).isEmpty())
    }

    private fun touch(visible: String, target: String) {
        val textLayout = layout(visible)
        val offset = textLayout.layoutInput.text.text.indexOf(target)
        assertTrue("'$target' must be visible", offset >= 0)
        compose.onNodeWithText(visible, useUnmergedTree = true).performTouchInput {
            click(textLayout.getBoundingBox(offset).center)
        }
        compose.waitForIdle()
    }

    private fun layout(visible: String): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        val action = compose.onNodeWithText(visible, useUnmergedTree = true)
            .fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action
        assertTrue("production Text must expose its actual layout", action != null && action(layouts))
        return layouts.single()
    }

    private companion object {
        const val DESTINATION = "https://example.com:8782/activity?tab=run%20log#latest"
    }
}
