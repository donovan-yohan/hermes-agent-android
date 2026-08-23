package com.hermesagent.mobile.data.attachments

/**
 * The gateway persists attached images as `@image:<path>` directive lines at
 * the end of a user turn's text (`tui_gateway/server.py` @ `f82f2dba`,
 * `_build_persist_message_with_image_refs`). Renderers lift those lines out of
 * the body and draw thumbnails instead — the same contract Desktop's
 * `extractImageRefs` (`apps/desktop/src/lib/embedded-images.ts` @ `f82f2dba`)
 * implements. A path containing whitespace arrives quoted with `` ` ``, `"` or
 * `'` (`agent/context_references.py` `format_reference_value` @ `f82f2dba`).
 */
object ImageRefLines {
    private val IMAGE_REF_LINE = Regex("""^@image:[^\n]*\n?""", setOf(RegexOption.MULTILINE))
    private val QUOTED_VALUE = Regex("""^([`"'])(.*)\1$""")
    private val SCREENSHOT_PLACEHOLDER_LINE = Regex("""^\[screenshot]\n?""", setOf(RegexOption.MULTILINE))

    /** Splits a user turn's text into its body and the trailing image ref lines. */
    fun split(text: String): Pair<String, List<String>> {
        val refs = IMAGE_REF_LINE.findAll(text).map { it.value.trim() }.toList()
        if (refs.isEmpty()) return text to emptyList()
        val cleaned = IMAGE_REF_LINE.replace(text, "")
            .let { SCREENSHOT_PLACEHOLDER_LINE.replace(it, "") }
            .trim()
        return cleaned to refs
    }

    /**
     * The raw gateway path a ref line points at, unquoted. Returns null when the
     * line is not a well-formed `@image:` directive, so a malformed or hostile
     * line can only degrade to a chip, never to a fetch.
     */
    fun pathOf(refLine: String): String? {
        if (!refLine.startsWith("@image:")) return null
        val value = refLine.removePrefix("@image:").trim()
        if (value.isEmpty()) return null
        QUOTED_VALUE.matchEntire(value)?.let { return it.groupValues[2] }
        return value
    }

    /**
     * Formats a gateway path as the `@image:` line the gateway persists
     * (`agent/context_references.py` `format_reference_value` @ `f82f2dba`):
     * unquoted when clean, else wrapped in the first quote char the path does
     * not contain. Used for the optimistic row so it reads back identical to
     * the authoritative row the gateway will write.
     */
    fun formatRef(path: String): String {
        val needsQuoting = path.any { it.isWhitespace() || it in "()[]{}<>\"'`" }
        if (!needsQuoting) return "@image:$path"
        for (quote in charArrayOf('`', '"', '\'')) {
            if (quote !in path) return "@image:$quote$path$quote"
        }
        return "@image:$path"
    }
}
