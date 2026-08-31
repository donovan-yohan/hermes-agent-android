package com.hermesagent.mobile.data.gateway

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.hermesagent.mobile.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The sign-in hand-off's platform half: what Intent actually leaves this app,
 * and whether the browser binding that keeps the process runnable is taken and
 * given back. Robolectric, because an `Intent`'s shape is the assertion.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GatewaySignInBrowserTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `a warmed provider gets the sign-in tab, and the binding outlives the launch`() = runBlocking {
        val platform = RecordingPlatform(provider = BROWSER_PACKAGE)
        val browser = GatewaySignInBrowser(context, MainActivity::class.java, platform)

        val binding = browser.bindForSignIn()
        browser.open(AUTHORIZE_URL)

        assertEquals(listOf(BROWSER_PACKAGE), platform.bound)
        val launched = platform.started.single()
        assertEquals(Intent.ACTION_VIEW, launched.action)
        assertEquals(BROWSER_PACKAGE, launched.`package`)
        assertEquals(AUTHORIZE_URL, launched.dataString)
        assertTrue(launched.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        // Still bound while the tab is up: that binding is the whole reason the
        // callback listener is not frozen out.
        assertEquals(0, platform.unbinds)

        binding?.close()
        assertEquals(1, platform.unbinds)
        // Idempotent, so a second close from a retry does not unbind twice.
        binding?.close()
        assertEquals(1, platform.unbinds)
    }

    @Test
    fun `a device with no custom tabs provider still signs in, with a plain view intent`() = runBlocking {
        val platform = RecordingPlatform(provider = null)
        val browser = GatewaySignInBrowser(context, MainActivity::class.java, platform)

        val binding = browser.bindForSignIn()
        browser.open(AUTHORIZE_URL)

        assertNull("nothing to bind, so nothing to hold", binding)
        val launched = platform.started.single()
        assertEquals(Intent.ACTION_VIEW, launched.action)
        assertNull(launched.`package`)
        assertEquals(AUTHORIZE_URL, launched.dataString)
        assertTrue(launched.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test
    fun `a provider that refuses the bind still signs in, unprotected and knowingly`() = runBlocking {
        val platform = RecordingPlatform(provider = BROWSER_PACKAGE, refuseBind = true)
        val browser = GatewaySignInBrowser(context, MainActivity::class.java, platform)

        val binding = browser.bindForSignIn()
        browser.open(AUTHORIZE_URL)

        assertNull("a refused bind is not a handle to close", binding)
        // No binding means no warmed provider to aim at, so the tab is a plain
        // view intent. The sign-in still runs; it is only unprotected against
        // the freezer, which is the same honest degradation as no provider.
        val launched = platform.started.single()
        assertNull(launched.`package`)
        assertEquals(AUTHORIZE_URL, launched.dataString)
    }

    @Test
    fun `a provider that cannot show the tab falls back to the default browser`() = runBlocking {
        val platform = RecordingPlatform(provider = BROWSER_PACKAGE, refusePackaged = true)
        val browser = GatewaySignInBrowser(context, MainActivity::class.java, platform)

        browser.bindForSignIn()
        browser.open(AUTHORIZE_URL)

        assertEquals(2, platform.started.size)
        assertEquals(BROWSER_PACKAGE, platform.started.first().`package`)
        assertNull("the fallback must not insist on the provider", platform.started.last().`package`)
        assertEquals(AUTHORIZE_URL, platform.started.last().dataString)
    }

    @Test
    fun `a device with no browser at all is told so, not left silent`() = runBlocking {
        val platform = RecordingPlatform(provider = null, refuseAll = true)
        val browser = GatewaySignInBrowser(context, MainActivity::class.java, platform)

        val failure = runCatching { browser.open(AUTHORIZE_URL) }.exceptionOrNull()

        assertTrue(failure is GatewayAuthException)
        assertEquals(GatewaySignInCopy.NO_BROWSER, failure?.message)
    }

    @Test
    fun `an accepted callback resumes the app the person left`() = runBlocking {
        val platform = RecordingPlatform(provider = null)
        val browser = GatewaySignInBrowser(context, MainActivity::class.java, platform)

        browser.returnToApp()

        val resumed = platform.started.single()
        assertEquals(MainActivity::class.java.name, resumed.component?.className)
        assertTrue(resumed.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        // The instance the person left, brought forward — not a second one.
        assertTrue(resumed.flags and Intent.FLAG_ACTIVITY_SINGLE_TOP != 0)
        assertTrue(resumed.flags and Intent.FLAG_ACTIVITY_REORDER_TO_FRONT != 0)
    }

    @Test
    fun `every platform call is made from the main thread`() = runBlocking {
        val platform = RecordingPlatform(provider = BROWSER_PACKAGE)
        val browser = GatewaySignInBrowser(context, MainActivity::class.java, platform)

        browser.bindForSignIn()
        browser.open(AUTHORIZE_URL)
        browser.returnToApp()

        // Resolve the provider, bind it, launch the tab, come back: four calls,
        // and the sign-in that makes them runs on IO.
        assertEquals(4, platform.loopers.size)
        assertTrue(platform.loopers.all { it === Looper.getMainLooper() })
    }

    /**
     * The gate above is only worth having if it can fail, and a test that runs
     * on the main thread anyway would pass whether or not the hop exists.
     */
    @Test
    fun `the main-thread gate has teeth`() = runBlocking {
        val platform = RecordingPlatform(provider = BROWSER_PACKAGE, requireMainThread = false)
        val offMain = GatewaySignInBrowser(
            context,
            MainActivity::class.java,
            platform,
            platformContext = Dispatchers.IO,
        )

        offMain.open(AUTHORIZE_URL)

        assertNotSame(Looper.getMainLooper(), platform.loopers.single())
    }

    private class RecordingPlatform(
        private val provider: String?,
        private val refusePackaged: Boolean = false,
        private val refuseAll: Boolean = false,
        /** The provider exists but will not let this app bind its service. */
        private val refuseBind: Boolean = false,
        /** Off only for the test that proves the main-thread gate can fail. */
        private val requireMainThread: Boolean = true,
    ) : SignInBrowserPlatform {
        val started = mutableListOf<Intent>()
        val bound = mutableListOf<String>()
        val loopers = mutableListOf<Looper?>()
        var unbinds = 0
            private set

        /**
         * `bindService` and `startActivity` must reach the platform on the main
         * thread — see [AndroidSignInBrowserPlatform]. Asserting it here means
         * every test in this class gates it, so a future hop back onto the
         * process-scoped IO thread fails in Robolectric instead of on a phone.
         */
        private fun observeThread() {
            loopers += Looper.myLooper()
            if (requireMainThread) {
                assertSame(
                    "platform calls must be made from the main thread",
                    Looper.getMainLooper(),
                    Looper.myLooper(),
                )
            }
        }

        override fun customTabsProvider(): String? {
            observeThread()
            return provider
        }

        override fun bindCustomTabs(packageName: String): CustomTabsBinding? {
            observeThread()
            bound += packageName
            if (refuseBind) return null
            return CustomTabsBinding(packageName) { unbinds += 1 }
        }

        override fun startActivity(intent: Intent) {
            observeThread()
            started += intent
            if (refuseAll || (refusePackaged && intent.`package` != null)) {
                throw ActivityNotFoundException("no activity for ${intent.action}")
            }
        }
    }

    private companion object {
        const val BROWSER_PACKAGE = "com.example.browser"
        const val AUTHORIZE_URL =
            "https://gateway.example/hermes/auth/native/authorize?state=fixture"
    }
}
