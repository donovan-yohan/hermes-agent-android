package com.hermesagent.mobile.ui.common

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context

/**
 * Put [text] on the clipboard under [label].
 *
 * One place for the three-line service lookup every copy control was spelling
 * out for itself. [label] is the clip's own description, which Android 13+
 * shows in the system clipboard notice — so it is user-visible product copy,
 * not a debug tag.
 *
 * Callers own their confirmation: this app's grammar is that the control swaps
 * its own icon and label rather than raising a second notice over the
 * platform's.
 */
fun copyToClipboard(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
}
