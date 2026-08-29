package com.hermesagent.mobile.ui.common

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.Window
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext

/**
 * One effect owns both halves of "this screen is a protected one".
 *
 * **Secure window.** `FLAG_SECURE` keeps the surface out of screenshots, screen
 * recordings, casts and the recent-apps preview for exactly as long as it is
 * composed. Scoped to a screen rather than set once on the Activity: it belongs
 * to surfaces holding a password, a passphrase, a session token or a host-key
 * decision, and a process-wide flag would also black out a chat transcript
 * nobody asked to protect.
 *
 * **Secret lifetime.** [onLeave] ends the screen's credential lifetime. It is in
 * this effect, and ahead of `clearFlags`, on purpose. ViewModels here are
 * Activity-scoped while a screen is one destination inside a single
 * composition, so navigating away destroys nothing by itself; putting the wipe
 * in a second `DisposableEffect` would work but would leave the order to
 * Compose's disposal sequence rather than stating it. Two statements, one after
 * the other, is the whole guarantee: nothing secret is still held once the
 * window has stopped being a secure one.
 *
 * **Nesting.** The Gateways surface is protected *and* contains the SSH form,
 * which is protected on its own account, so two of these can be live over one
 * window. `clearFlags` is not reference-counted by the platform — the inner
 * screen's disposal would unprotect the outer one that is still on screen — so
 * the count is kept here. Compose runs effects on the main thread and disposes
 * children before parents, which is what makes a plain map safe and makes the
 * outermost screen the one that clears the flag.
 *
 * [onLeave] is read through [rememberUpdatedState] so a recomposition with a new
 * lambda does not re-run the effect — re-running it would clear and re-add the
 * flag, and would fire a wipe while the screen is still on screen.
 */
@Composable
internal fun SecureScreenLifetime(onLeave: () -> Unit = {}) {
    val window = LocalContext.current.findActivityWindow()
    val leave by rememberUpdatedState(onLeave)

    DisposableEffect(window) {
        window?.let { secured ->
            if (secureScreens.merge(secured, 1, Int::plus) == 1) {
                secured.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
        onDispose {
            leave()
            window?.let { secured ->
                val held = (secureScreens[secured] ?: 0) - 1
                if (held > 0) {
                    secureScreens[secured] = held
                } else {
                    secureScreens.remove(secured)
                    secured.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
            }
        }
    }
}

/**
 * How many composed screens are asking this window to stay secure.
 *
 * Entries are removed as they reach zero, so a finished Activity's window is
 * not held here. Touched only from composition effects, which run on the main
 * thread.
 */
private val secureScreens = mutableMapOf<Window, Int>()

/** Null in a `@Preview` or any other host that is not an Activity. */
private tailrec fun Context.findActivityWindow(): Window? = when (this) {
    is Activity -> window
    is ContextWrapper -> baseContext.findActivityWindow()
    else -> null
}
