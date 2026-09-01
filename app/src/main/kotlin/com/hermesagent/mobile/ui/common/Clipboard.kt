package com.hermesagent.mobile.ui.common

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * How long a clipboard control shows its own confirmation before going quiet.
 *
 * Desktop's `COPIED_RESET_MS` (`apps/desktop/src/components/ui/copy-button.tsx:15`
 * @ `936b970e281d5d28e930c5698f36bc4ebb54c7ba`) to the millisecond, and the same
 * 1.5s every clipboard control in this app already used. It lives here, beside
 * the write itself, because three surfaces now share both.
 */
internal const val COPY_CONFIRM_MILLIS = 1_500L

/**
 * Put [text] on the clipboard under [label]. True when the clip was accepted.
 *
 * One place for the three-line service lookup every copy control was spelling
 * out for itself. [label] is the clip's own description, which Android 13+
 * shows in the system clipboard notice — so it is user-visible product copy,
 * not a debug tag.
 *
 * The write is guarded because it is a Binder call into another process:
 * `setPrimaryClip` throws when the system clipboard is unavailable or refuses
 * the app, and an exception raised inside a Compose `onClick` takes the whole
 * app down. Callers own their confirmation: this app's grammar is that the
 * control swaps its own icon and label rather than raising a second notice over
 * the platform's, and a caller that ignores the result is left exactly where it
 * was before this returned a value — optimistic, but no longer fatal.
 */
internal fun copyToClipboard(context: Context, label: String, text: String): Boolean = runCatching {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
}.isSuccess

/**
 * How a control reaches the clipboard, and whether the clip was accepted.
 *
 * A parameter rather than a bare call, so the refusal path is reachable from a
 * test without shadowing a framework class. It lives here rather than beside
 * the one control that currently takes it: the seam is about the shared write,
 * not about any one surface, and the next control that wants it should find it
 * here rather than declare a second one.
 */
internal fun interface ClipboardWriter {
    fun write(label: String, text: String): Boolean
}

/** The real clipboard. Every production caller takes this. */
@Composable
internal fun rememberClipboardWriter(): ClipboardWriter {
    val context = LocalContext.current
    return remember(context) {
        ClipboardWriter { label, text -> copyToClipboard(context, label, text) }
    }
}
