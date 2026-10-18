package com.hermesagent.mobile.data.markdown

import java.net.IDN
import java.net.URI
import java.net.URISyntaxException
import org.nibor.autolink.LinkExtractor
import org.nibor.autolink.LinkType

/** UTF-16 offsets in the final visible text, never in markdown source. */
data class TranscriptLink(val start: Int, val end: Int, val destination: String)

private val URL_EXTRACTOR = LinkExtractor.builder().linkTypes(setOf(LinkType.URL)).build()

/** Validate without rewriting ports, escapes, query strings or fragments. */
fun safeTranscriptUrl(destination: String): String? {
    if (destination.any { Character.isISOControl(it) }) return null
    return try {
        val uri = URI(destination)
        if (!uri.isAbsolute || uri.scheme.lowercase() !in setOf("http", "https")) return null
        val authority = uri.rawAuthority?.takeIf(String::isNotEmpty) ?: return null
        // java.net.URI does not expose a host for a valid Unicode DNS name.
        // Validate its IDNA form only; hand the original URI to Android.
        val server = if (uri.host != null) uri.parseServerAuthority() else {
            val hostPort = authority.substringAfterLast('@')
            val host = hostPort.substringBefore(':')
            val ascii = IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES)
            URI("${uri.scheme}://${authority.substringBeforeLast('@', "").let { if (it.isEmpty()) "" else "$it@" }}$ascii${hostPort.removePrefix(host)}")
                .parseServerAuthority()
        }
        if (server.host.isNullOrEmpty() || server.port !in -1..65535) null else destination
    } catch (_: URISyntaxException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }
}

/**
 * Extract after flattening: a URL may cross formatting runs. Explicit labels
 * own their entire visible range even when their destination is refused, so
 * a URL-looking label can never silently open a different bare destination.
 * Image fallback text also stays literal, unless owned by an explicit link.
 */
fun List<InlineSpan>.transcriptLinks(): List<TranscriptLink> {
    val visible = StringBuilder()
    val explicit = mutableListOf<TranscriptLink>()
    val labels = mutableListOf<IntRange>()
    fun visit(spans: List<InlineSpan>) {
        for (span in spans) when (span) {
            is InlineSpan.Strong -> visit(span.children)
            is InlineSpan.Emphasis -> visit(span.children)
            is InlineSpan.Link, is InlineSpan.ImageAlt -> {
                val start = visible.length
                // CommonMark disallows nested links; the outer label owns it.
                visible.append(span.text)
                val end = visible.length
                if (end > start) {
                    labels += start until end
                    if (span is InlineSpan.Link) {
                        safeTranscriptUrl(span.destination)?.let { explicit += TranscriptLink(start, end, it) }
                    }
                }
            }
            else -> visible.append(span.text)
        }
    }
    visit(this)
    val bare = URL_EXTRACTOR.extractLinks(visible).mapNotNull { range ->
        val start = range.beginIndex
        val end = range.endIndex
        if (labels.any { start <= it.last && end > it.first }) null else {
            safeTranscriptUrl(visible.substring(start, end))?.let { TranscriptLink(start, end, it) }
        }
    }
    return (explicit + bare).sortedBy(TranscriptLink::start)
}
