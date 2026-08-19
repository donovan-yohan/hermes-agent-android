package com.hermesagent.mobile.data.demo

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * What a turn emits. These are the events a gateway turn already produces on
 * the wire (`tui_gateway/ws.py` @ `f82f2dba`); Phase 1 fabricates them locally
 * so the transcript, the streaming cursor, the tool row and the stop button can
 * all be exercised — and unit-tested — before any transport exists.
 */
sealed interface TurnEvent {
    /** More assistant text. Deltas are appended, never replaced. */
    data class Delta(val text: String) : TurnEvent

    data class ToolStarted(val id: String, val label: String, val detail: String) : TurnEvent

    data class ToolFinished(val id: String, val detail: String, val failed: Boolean = false) : TurnEvent

    data object Completed : TurnEvent
}

/** Pacing, injected so tests can drive the engine on virtual time. */
data class TurnTiming(
    val firstDelayMillis: Long = 220,
    val deltaDelayMillis: Long = 45,
    val toolRunMillis: Long = 900,
)

/**
 * The offline stand-in for a Hermes turn.
 *
 * Deterministic on purpose: the reply is chosen by a pure function of the
 * prompt, so a test asserts a real transcript rather than a coin flip, and two
 * people demoing the app see the same thing. It is not a mock of the gateway
 * protocol — that seam does not exist yet, and building one now would be the
 * "fake architecture" the port workflow warns about.
 */
class DemoTurnEngine(private val timing: TurnTiming = TurnTiming()) {

    fun run(prompt: String): Flow<TurnEvent> = flow {
        val script = scriptFor(prompt)
        delay(timing.firstDelayMillis)

        for (segment in script.opening) {
            emit(TurnEvent.Delta(segment))
            delay(timing.deltaDelayMillis)
        }

        script.tool?.let { tool ->
            emit(TurnEvent.ToolStarted(id = tool.id, label = tool.label, detail = tool.detail))
            delay(timing.toolRunMillis)
            emit(TurnEvent.ToolFinished(id = tool.id, detail = tool.result))
        }

        for (segment in script.closing) {
            emit(TurnEvent.Delta(segment))
            delay(timing.deltaDelayMillis)
        }

        emit(TurnEvent.Completed)
    }

    private data class ToolScript(val id: String, val label: String, val detail: String, val result: String)

    private data class Script(
        val opening: List<String>,
        val tool: ToolScript?,
        val closing: List<String>,
    )

    private fun scriptFor(prompt: String): Script {
        val normalized = prompt.trim().lowercase()
        val wantsCode = normalized.contains("code") ||
            normalized.contains("kotlin") ||
            normalized.contains("show me")
        val wantsTool = wantsCode || normalized.contains("file") || normalized.contains("look")

        val opening = if (wantsCode) {
            chunk(
                "Reading the transcript renderer now. The Android list item is a markdown " +
                    "**block**, not a message, so only the live tail recomposes per token.\n\n",
            )
        } else {
            chunk(
                "This is a local demo turn — nothing left the device. It exists so the " +
                    "streaming cursor, the tool row and the stop button are real interactions " +
                    "before the gateway transport lands.\n\n",
            )
        }

        val tool = if (wantsTool) {
            ToolScript(
                id = "tool-read-transcript",
                label = "Read app/src/main/kotlin/.../ChatScreen.kt",
                detail = "lines 1-120",
                result = "120 lines",
            )
        } else {
            null
        }

        val closing = if (wantsCode) {
            chunk(
                "\nThe block model looks like this:\n\n" +
                    "```kotlin\n" +
                    "sealed interface MarkdownBlock {\n" +
                    "    data class Paragraph(val spans: List<InlineSpan>) : MarkdownBlock\n" +
                    "    data class CodeFence(val language: String?, val code: String, val closed: Boolean) : MarkdownBlock\n" +
                    "}\n" +
                    "```\n\n" +
                    "Ask for `sessions` to see the list grouping, or open Appearance to switch skin.",
            )
        } else {
            chunk(
                "\nWhat is real in this build:\n\n" +
                    "- the six Desktop themes and their light/dark resolution\n" +
                    "- the adaptive session/transcript layout\n" +
                    "- the SSH host-key policy and a live `probe` over sshj\n\n" +
                    "What is not: any Hermes gateway traffic. That is the next slice.",
            )
        }

        return Script(opening = opening, tool = tool, closing = closing)
    }

    /**
     * Split into word-sized deltas so the stream looks like a model writing,
     * not a paragraph appearing. Splitting on whitespace keeps the boundary
     * markdown-safe: a fence marker never arrives half-parsed.
     */
    private fun chunk(text: String): List<String> {
        val parts = mutableListOf<String>()
        val builder = StringBuilder()
        for (token in text.split(" ")) {
            builder.append(token).append(' ')
            if (builder.length >= 12) {
                parts += builder.toString()
                builder.clear()
            }
        }
        if (builder.isNotEmpty()) parts += builder.toString().trimEnd()
        return parts
    }
}
