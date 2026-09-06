package com.hermesagent.mobile.data.markdown

/**
 * Parse transcript directives (a whole paragraph of the form `::name{key="value"}`) as a narrow,
 * plugin-addressed contribution shape. Ported from `apps/desktop/src/lib/transcript-directives.ts:19-45 @ 3ca096de5f8183cb2e0ec23673f294d5978656a3`.
 */
data class ParsedTranscriptDirective(
    val name: String,
    val attrs: Map<String, String>,
    val source: String,
)

// The whole paragraph, nothing else on the line: `::name` or `::name{...}`.
// Length caps bound the attr scan on adversarial input.
private val DIRECTIVE_RE = Regex("^::([a-z][a-z0-9-]{0,63})(?:\\{([^{}]{0,1024})\\})?$")

// `key="value"` pairs; single quotes accepted for model sloppiness.
private val ATTR_RE = Regex("([a-z][\\w-]{0,63})=(?:\"([^\"]*)\"|'([^']*)')", setOf(RegexOption.IGNORE_CASE))

/**
 * Parse a paragraph as a transcript directive. Returns null unless the ENTIRE trimmed text is one
 * directive — prose containing `::` stays prose. Pure and synchronous — safe to call during render.
 */
fun parseTranscriptDirective(text: String): ParsedTranscriptDirective? {
    val trimmed = text.trim()

    // Cheap reject before the regex: directives are short single lines.
    if (!trimmed.startsWith("::") || trimmed.length > 1200 || trimmed.contains('\n')) {
        return null
    }

    val match = DIRECTIVE_RE.matchEntire(trimmed) ?: return null

    val name = match.groupValues[1]
    val attrsText = match.groupValues.getOrNull(2)?.takeIf { it.isNotEmpty() }

    val attrs = linkedMapOf<String, String>()
    if (attrsText != null) {
        for (pair in ATTR_RE.findAll(attrsText)) {
            val key = pair.groupValues[1].lowercase()
            val value = pair.groups[2]?.value ?: pair.groups[3]?.value ?: ""
            attrs[key] = value
        }
    }

    return ParsedTranscriptDirective(name = name, attrs = attrs, source = trimmed)
}

