package com.hermesagent.mobile.ui.common

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowClipboardManager

/**
 * The one clipboard write in the app, and the failure it is not allowed to
 * turn into a crash.
 *
 * `setPrimaryClip` is a Binder call into `system_server`: it throws when the
 * clipboard service is unavailable, when a device policy blocks the clip, and
 * on the death of the remote side. Every caller here is a Compose `onClick`,
 * where an escaping exception takes the whole app down — so the contract is
 * that this function reports a refusal rather than raising one.
 */
@RunWith(RobolectricTestRunner::class)
// Robolectric defaults to compileSdk, and SDK 36 wants a JDK this build does
// not use. Every Robolectric suite in this repo pins the same level.
@Config(sdk = [34])
class ClipboardTest {

    @Test
    fun `an accepted clip is reported and lands under its own label`() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        assertTrue(copyToClipboard(context, LABEL, TEXT))

        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        assertEquals(TEXT, clipboard.primaryClip?.getItemAt(0)?.text?.toString())
        // The label is what Android 13+ shows in its own clipboard notice.
        assertEquals(LABEL, clipboard.primaryClip?.description?.label?.toString())
    }

    @Test
    @Config(shadows = [RefusingClipboardManager::class])
    fun `a write the clipboard service refuses is reported, not thrown`() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        assertFalse(copyToClipboard(context, LABEL, TEXT))

        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        assertNull(clipboard.primaryClip)
    }

    @Test
    fun `an unreachable clipboard service is reported, not thrown`() {
        // The other half of the same Binder hop: the lookup itself can fail
        // before there is anything to write to.
        val unreachable = object : ContextWrapper(ApplicationProvider.getApplicationContext()) {
            override fun getSystemService(name: String): Any? =
                if (name == Context.CLIPBOARD_SERVICE) {
                    throw SecurityException("clipboard service unavailable")
                } else {
                    super.getSystemService(name)
                }
        }

        assertFalse(copyToClipboard(unreachable, LABEL, TEXT))
    }

    /** A clipboard that throws the way a blocked or dead one does. */
    @Implements(ClipboardManager::class)
    class RefusingClipboardManager : ShadowClipboardManager() {
        @Implementation
        override fun setPrimaryClip(clip: ClipData) {
            throw SecurityException("clipboard write refused")
        }
    }

    private companion object {
        const val LABEL = "Session ID"
        const val TEXT = "s-clip-1"
    }
}
