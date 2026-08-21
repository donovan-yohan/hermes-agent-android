package com.hermesagent.mobile.ui.chat.composer

import com.hermesagent.mobile.data.composer.ComposerReference
import java.net.URI

/**
 * The composer sends plain canonical text in this slice.  Keep the quoting
 * policy in one place so an Add-sheet URL, a picked completion and typed text
 * serialize identically.  This mirrors Desktop's `quoteRefValue`: references
 * are always fenced, while ordinary emoji and slash commands are plain text.
 */
internal fun composerReferenceText(kind: String, value: String): String = when (kind) {
    "file" -> ComposerReference.File(value).wireText
    "folder" -> ComposerReference.Folder(value).wireText
    "url" -> ComposerReference.Url(value).wireText
    "session" -> ComposerReference.Session(value).wireText
    "git" -> ComposerReference.Git(value).wireText
    else -> "@$kind:$value"
}

internal fun composerUrlReferenceText(url: String): String = ComposerReference.Url(url).wireText

/** Explicit HTTP(S) only: a filename or `example.com` must remain ordinary prose. */
internal fun validComposerUrl(value: String): Boolean = runCatching {
    val parsed = URI(value.trim())
    val isHttp = parsed.scheme.equals("http", ignoreCase = true) ||
        parsed.scheme.equals("https", ignoreCase = true)
    isHttp && !parsed.host.isNullOrBlank()
}.getOrDefault(false)

/**
 * Promote only a bare, remote-workspace-shaped `@path/` token.  Android local
 * paths/content URIs never enter this function, so it cannot manufacture a
 * remote reference from device-only state.
 */
internal fun canonicalizeTypedComposerPath(token: String): String? {
    if (!token.startsWith('@') || ':' in token || '/' !in token || token.startsWith("@/")) return null
    val path = token.removePrefix("@").trimEnd('/')
    if (path.isBlank() || path.contains("://") || path.contains("@")) return null
    return composerReferenceText(if (token.endsWith('/')) "folder" else "file", path)
}

/** Preserves the sentence punctuation that terminates a typed/pasted URL. */
internal fun canonicalizeComposerUrls(text: String): String {
    val url = Regex("""https?://[^\s<>\[\]{}"'`]+""", RegexOption.IGNORE_CASE)
    return url.replace(text) { match ->
        val raw = match.value
        val before = text.substring(0, match.range.first)
        // Existing canonical directives own their fenced value; rewriting it
        // again would produce `@url:`@url:...`` after the next space.
        if (before.endsWith("@url:`") || before.endsWith("@url:\"") || before.endsWith("@url:'")) return@replace raw
        var core = raw.replace(Regex("[,.;:!?]+$"), "")
        while (core.endsWith(')') && core.count { it == ')' } > core.count { it == '(' }) {
            core = core.dropLast(1)
        }
        if (!validComposerUrl(core)) raw else composerUrlReferenceText(core) + raw.drop(core.length)
    }
}

/** Commit URL/path canonical text only at a word boundary, never during IME preedit. */
internal fun canonicalizeComposerTextOnSpace(text: String): String {
    if (text.isEmpty() || !text.last().isWhitespace()) return text
    val urls = canonicalizeComposerUrls(text)
    val beforeSpace = urls.dropLast(1)
    val tokenStart = beforeSpace.indexOfLast { it.isWhitespace() } + 1
    val token = beforeSpace.substring(tokenStart)
    val core = token.replace(Regex("[,.;!?]+$"), "")
    val replacement = canonicalizeTypedComposerPath(core) ?: return urls
    return beforeSpace.substring(0, tokenStart) + replacement + token.drop(core.length) + urls.last()
}

/** Replace the active completion range without disturbing text on either side. */
internal fun replaceComposerRange(text: String, start: Int, end: Int, replacement: String): String {
    val safeStart = start.coerceIn(0, text.length)
    val safeEnd = end.coerceIn(safeStart, text.length)
    return text.substring(0, safeStart) + replacement + text.substring(safeEnd)
}
