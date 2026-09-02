package com.hermesagent.mobile.data.gateway

/**
 * A `/skill` turn is persisted expanded — the whole skill body, addressed to
 * the model. Every surface renders the invocation the person typed
 * (`/work fix the leak`) instead, so no client can leak the body into a chat
 * bubble.
 *
 * The `session.history` RPC already projects it away
 * (`tui_gateway/server.py:9653-9661,9801-9808` @
 * `3ca096de5f8183cb2e0ec23673f294d5978656a3`). The REST transcript route hands
 * back the stored rows themselves (`hermes_cli/web_routers/sessions.py:672`),
 * so a client reading that contract needs the twin — which is exactly why
 * Desktop ships one (`apps/shared/src/skill-scaffold.ts:1-14`, consumed at
 * `apps/desktop/src/lib/chat-messages/hydration.ts:32`). This is that module,
 * ported; its markers mirror `agent/skill_commands.py` byte for byte.
 */

private const val INVOCATION_PREFIX = "[IMPORTANT: The user has invoked the "
private const val SINGLE_MARKER = "The full skill content is loaded below.]"
private const val SINGLE_INSTRUCTION =
    "The user has provided the following instruction alongside the skill invocation: "
private const val RUNTIME_NOTE = "\n\n[Runtime note:"
private const val BUNDLE_MARKER = " skill bundle,"
private const val BUNDLE_INSTRUCTION = "\nUser instruction: "
private const val BUNDLE_SKILL_BLOCK = "\n\n[Loaded as part of the "

/** The skill name is the first quoted span of the activation note. */
private val SKILL_NAME = Regex("^\\Q$INVOCATION_PREFIX\\E\"([^\"]*)\"")
private val SKILL_WHITESPACE = Regex("\\s+")

/** Text between [marker] and [end], or empty when the marker is absent. */
private fun between(text: String, marker: String, end: String, fromEnd: Boolean = false): String {
    val index = if (fromEnd) text.lastIndexOf(marker) else text.indexOf(marker)
    if (index < 0) return ""
    val tail = text.substring(index + marker.length)
    val stop = tail.indexOf(end)
    return (if (stop >= 0) tail.substring(0, stop) else tail).trim()
}

/**
 * The invocation a scaffolded turn came from (`/work fix the leak`), or null
 * when [text] is ordinary prose that should render as written
 * (`apps/shared/src/skill-scaffold.ts:46-69` @ `3ca096de`).
 */
internal fun skillInvocationText(text: String): String? {
    if (!text.startsWith(INVOCATION_PREFIX)) return null
    val name = SKILL_NAME.find(text)?.groupValues?.getOrNull(1)?.trim().orEmpty()
    if (name.isEmpty()) return null

    // Bundle headers already carry their typed "/a /b" keys; a single skill is
    // a bare name. The single-skill instruction trails the body (which may
    // quote the marker), so match it from the end.
    val label = if (name.startsWith("/")) name else "/$name"
    val instruction = when {
        BUNDLE_MARKER in text -> between(text, BUNDLE_INSTRUCTION, BUNDLE_SKILL_BLOCK)
        SINGLE_MARKER in text -> between(text, SINGLE_INSTRUCTION, RUNTIME_NOTE, fromEnd = true)
        else -> ""
    }
    return if (instruction.isEmpty()) label else "$label ${instruction.replace(SKILL_WHITESPACE, " ")}"
}
