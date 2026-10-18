package com.hermesagent.mobile.ui.chat

import android.content.ActivityNotFoundException
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import com.hermesagent.mobile.data.markdown.InlineSpan
import com.hermesagent.mobile.data.markdown.safeTranscriptUrl
import com.hermesagent.mobile.data.markdown.transcriptLinks
import com.hermesagent.mobile.ui.theme.HermesTheme

/** One Text retains its normal wrapping, selection and per-character link hits. */
@Composable
internal fun List<InlineSpan>.transcriptAnnotated(): AnnotatedString {
    val tokens = HermesTheme.tokens
    val mono = HermesTheme.type.code.fontFamily
    val uriHandler = LocalUriHandler.current
    val links = transcriptLinks()
    val linkStyles = TextLinkStyles(
        style = SpanStyle(color = tokens.accent, textDecoration = TextDecoration.Underline),
    )
    return buildAnnotatedString {
        fun appendSpans(spans: List<InlineSpan>) {
            for (span in spans) when (span) {
                is InlineSpan.Plain, is InlineSpan.ImageAlt -> append(span.text)
                is InlineSpan.Strong -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { appendSpans(span.children) }
                is InlineSpan.Emphasis -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { appendSpans(span.children) }
                is InlineSpan.Link -> appendSpans(span.children)
                is InlineSpan.Code -> withStyle(
                    SpanStyle(
                        fontFamily = mono,
                        background = tokens.inlineCodeBackground,
                        color = tokens.inlineCodeForeground,
                    ),
                ) { append(span.text) }
            }
        }
        appendSpans(this@transcriptAnnotated)
        for (link in links) {
            addLink(
                LinkAnnotation.Url(link.destination, linkStyles) { uriHandler.openTranscriptUrl(link.destination) },
                link.start,
                link.end,
            )
        }
    }
}

/** A missing browser or a platform refusal must not take the transcript down. */
private fun UriHandler.openTranscriptUrl(destination: String) {
    val safe = safeTranscriptUrl(destination) ?: return
    try {
        // Android intent filters match schemes case-sensitively. Preserve the
        // authored annotation and every other URI component at the hand-off.
        openUri(Uri.parse(safe).normalizeScheme().toString())
    } catch (_: ActivityNotFoundException) {
        // No installed activity can open this URL.
    } catch (_: IllegalArgumentException) {
        // AndroidUriHandler wraps an unavailable activity in this exception.
    } catch (_: SecurityException) {
        // The platform or an installed handler refused the hand-off.
    }
}
