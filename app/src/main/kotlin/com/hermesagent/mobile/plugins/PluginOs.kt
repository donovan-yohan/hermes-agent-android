package com.hermesagent.mobile.plugins

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.hermesagent.mobile.data.notifications.NotificationKind
import com.hermesagent.mobile.data.notifications.NotificationPost
import com.hermesagent.mobile.data.notifications.NotificationSurface

/**
 * Input for a native notification requested by a plugin.
 */
data class PluginNotificationInput(
    val title: String,
    val body: String,
    val activate: String? = null,
    val silent: Boolean = false,
)

/**
 * The curated OS door — every way a plugin reaches outside the app window.
 *
 * Direct Kotlin port of Desktop's `PluginOs`
 * (`apps/desktop/src/contrib/plugin.ts:20-56` @
 * `3ca096de5f8183cb2e0ec23673f294d5978656a3`).
 * Every member resolves a result instead of throwing when the capability
 * cannot apply, so callers branch on the return value.
 */
interface PluginOs {
    /** Native OS notification attributed to this plugin. */
    fun notify(input: PluginNotificationInput)

    /** Open a URL with the OS default handler. Resolves false when unable. */
    suspend fun openExternal(url: String): Boolean

    /** Write text to the system clipboard. Resolves false when unable. */
    suspend fun writeClipboard(text: String): Boolean

    /** Share text via Android system share sheet. Resolves false when unable. */
    suspend fun share(text: String, title: String? = null): Boolean
}

/**
 * Android implementation of [PluginOs].
 */
class AndroidPluginOs(
    private val context: Context,
    private val notifications: NotificationSurface,
    private val pluginId: String,
) : PluginOs {
    override fun notify(input: PluginNotificationInput) {
        notifications.post(
            NotificationPost(
                kind = NotificationKind.Plugin,
                durableSessionId = "plugin:$pluginId",
                title = input.title,
                body = input.body,
            )
        )
    }

    override suspend fun openExternal(url: String): Boolean = try {
        val uri = Uri.parse(url)
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        true
    } catch (_: Throwable) {
        false
    }

    override suspend fun writeClipboard(text: String): Boolean = try {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText(pluginId, text))
            true
        } else {
            false
        }
    } catch (_: Throwable) {
        false
    }

    override suspend fun share(text: String, title: String?): Boolean = try {
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            putExtra(Intent.EXTRA_TEXT, text)
            type = "text/plain"
        }
        val chooser = Intent.createChooser(sendIntent, title).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
        true
    } catch (_: Throwable) {
        false
    }
}
