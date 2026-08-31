package com.hermesagent.mobile.data.gateway

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.util.Log
import androidx.browser.customtabs.CustomTabsClient
import androidx.browser.customtabs.CustomTabsIntent
import androidx.browser.customtabs.CustomTabsServiceConnection
import androidx.browser.customtabs.CustomTabsSession
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A live Custom Tabs service binding, and the session it minted.
 *
 * The binding is the point. A tab launched with a bare `ACTION_VIEW` leaves
 * this app cached with nothing raising its importance, and Android 12+ freezes
 * cached processes — the loopback callback is accepted by the kernel and then
 * never read. A bound service is an importance the freezer respects, so the
 * accept loop is still running when the browser redirects back.
 *
 * The session is read, never waited for. Requesting the bind is what buys the
 * protection; the session the provider mints afterwards only makes the tab load
 * a little faster, and blocking the person's tap on it for up to two seconds
 * traded the thing that matters for the thing that does not.
 */
internal class CustomTabsBinding(
    val packageName: String,
    private val minted: AtomicReference<CustomTabsSession?> = AtomicReference(null),
    private val release: () -> Unit,
) : AutoCloseable {
    /** Whatever the provider has handed back by now, which may be nothing. */
    val session: CustomTabsSession? get() = minted.get()


    private val released = AtomicBoolean(false)

    /** Idempotent: the flow closes it, and so does a launcher reused for a second sign-in. */
    override fun close() {
        if (released.compareAndSet(false, true)) runCatching { release() }
    }
}

/**
 * Where a sign-in was started, so finishing it in the browser finishes where
 * the person was.
 *
 * A sign-in is the one journey in this app that leaves it: the person is handed
 * to a browser and comes back through a fresh Intent, which by itself resumes
 * whatever surface was last on screen — the Gateways pane, even when the whole
 * reason they were there was a session they could not reach. Desktop has no
 * equivalent to port; its native sign-in returns into the same window it left.
 *
 * Deliberately two values and not a destination. This says where the journey
 * began; what that means for navigation belongs to the shell that owns the
 * destinations (`MainActivity`, `HermesApp`), and a data layer that named a
 * screen would be deciding it.
 */
enum class SignInOrigin {
    /** The Gateways pane itself: the person is already where the result shows. */
    Gateways,

    /** The sessions surface — the drawer, its switcher, or a failure raised there. */
    Sessions,
}

/**
 * The origin on a hand-back Intent. Namespaced, because this app's own
 * Activity is the only thing meant to read it.
 */
internal const val EXTRA_SIGN_IN_ORIGIN = "com.hermesagent.mobile.extra.SIGN_IN_ORIGIN"

/**
 * What an incoming Intent says about where its sign-in started, or null when it
 * says nothing.
 *
 * By enum *name*, and an unrecognised name is null rather than a guess: this
 * value only ever asks for navigation, so the safe reading of "I do not know
 * this" is the behaviour an unstamped hand-back already has — come forward,
 * change nothing.
 */
internal fun signInOriginFrom(intent: Intent?): SignInOrigin? {
    val named = intent?.getStringExtra(EXTRA_SIGN_IN_ORIGIN) ?: return null
    return SignInOrigin.entries.firstOrNull { it.name == named }
}

/** Everything the sign-in hand-off asks of the platform, in one injectable seam. */
internal interface SignInBrowserPlatform {
    /** The package of a browser that serves Custom Tabs, or null when none does. */
    fun customTabsProvider(): String?

    /**
     * Binds [packageName]'s Custom Tabs service. Null when the bind is refused.
     *
     * Returns as soon as the bind is *accepted*. It does not suspend waiting for
     * the service to connect: the accepted bind is already the importance that
     * keeps this process out of the freezer.
     */
    fun bindCustomTabs(packageName: String): CustomTabsBinding?

    fun startActivity(intent: Intent)
}

/**
 * Opens a native sign-in in a Custom Tab, holds that browser's service binding
 * for the whole flow, and brings the app back once a callback is accepted.
 *
 * Process-scoped on purpose. The sign-in outlives the screen that started it —
 * the person leaves for a browser, and an Activity that is destroyed while they
 * are gone must not take the flow with it — so this holds the application
 * context and never an Activity.
 */
internal class GatewaySignInBrowser(
    private val context: Context,
    /** The Activity to resume; wired by the process so this layer holds no UI class. */
    private val mainActivity: Class<*>,
    private val platform: SignInBrowserPlatform = AndroidSignInBrowserPlatform(context),
    private val log: GatewaySignInLog = GatewaySignInLog {},
    /**
     * Every platform call runs here, and it is the main thread on purpose.
     *
     * The sign-in itself is process-scoped and runs on IO, but `bindService`
     * and `startActivity` are the two calls this class exists to make, and the
     * shape already proven on a device makes both from the main thread. Moving
     * them onto an arbitrary background thread was an unforced change to the
     * one part of this flow that was known to work.
     */
    private val platformContext: CoroutineContext = Dispatchers.Main.immediate,
) : GatewayBrowserLauncher {
    private val warmed = AtomicReference<CustomTabsBinding?>(null)

    override suspend fun bindForSignIn(): AutoCloseable? = withContext(platformContext) {
        // A previous flow that ended without closing its handle would otherwise
        // leak a binding for the life of the process.
        warmed.getAndSet(null)?.close()
        val provider = platform.customTabsProvider() ?: return@withContext null
        val bound = platform.bindCustomTabs(provider) ?: return@withContext null
        warmed.set(bound)
        AutoCloseable {
            warmed.compareAndSet(bound, null)
            bound.close()
        }
    }

    override suspend fun open(url: String) = withContext(platformContext) {
        val intent = signInIntent(url)
        try {
            platform.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            // The warmed provider stopped being able to show a tab between the
            // bind and the launch. Any browser will do for the actual flow, so
            // this falls back rather than failing the sign-in.
            if (intent.`package` == null) throw GatewayAuthException(GatewaySignInCopy.NO_BROWSER)
            log.step(GatewaySignInStep.FellBackToBrowser)
            try {
                platform.startActivity(viewIntent(Uri.parse(url)))
            } catch (_: ActivityNotFoundException) {
                throw GatewayAuthException(GatewaySignInCopy.NO_BROWSER)
            }
        }
    }

    /**
     * Deliberately not swallowed here. The caller
     * ([NativeGatewayAuthenticator.signIn]) is the only place that knows the
     * sign-in is already persisted and that a refusal therefore costs nothing,
     * and it records the breadcrumb. Two guards for one failure meant the outer
     * one could never be tested.
     *
     * Note that the common refusal is not an exception at all: Android logs
     * "Background activity launch blocked!" and returns normally. Nothing here
     * can detect that, which is exactly why the sign-in must not depend on it.
     */
    override suspend fun returnToApp() {
        withContext(platformContext) { platform.startActivity(returnIntent()) }
    }

    /**
     * The same launcher, carrying where this sign-in started.
     *
     * A delegate rather than a second [GatewaySignInBrowser]: the warmed
     * Custom Tabs binding is per instance, and a copy would bind its own and
     * leave the process one binding richer than it can close. Only the
     * hand-back differs, and only in what its Intent says.
     */
    fun startedFrom(origin: SignInOrigin): GatewayBrowserLauncher {
        val opener = this
        return object : GatewayBrowserLauncher by opener {
            override suspend fun returnToApp() {
                withContext(platformContext) { platform.startActivity(returnIntent(origin)) }
            }
        }
    }

    /**
     * A Custom Tab into the warmed provider when there is one, and a plain
     * `ACTION_VIEW` when there is not — a device with no Custom Tabs provider
     * still signs in, it just has no protection against the freezer.
     */
    internal fun signInIntent(url: String): Intent {
        val target = Uri.parse(url)
        val bound = warmed.get() ?: return viewIntent(target)
        // This whole branch is an optimisation over the plain view intent, so a
        // library that cannot build its own intent degrades to the intent that
        // always works rather than ending the sign-in.
        return runCatching {
            CustomTabsIntent.Builder(bound.session)
                .setShowTitle(true)
                .build()
                .intent
                .setPackage(bound.packageName)
                .setData(target)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }.getOrElse { viewIntent(target) }
    }

    /**
     * Resumes the app the person left, rather than stacking a second copy of
     * it. `MainActivity` is `standard` and the task root
     * (`app/src/main/AndroidManifest.xml`), so `NEW_TASK` — required at all,
     * because this launcher holds the application context — finds the existing
     * task instead of creating one, `SINGLE_TOP` keeps the instance that is
     * already there, and `REORDER_TO_FRONT` brings that task forward from the
     * browser.
     *
     * [origin] is what turns "come forward" into "come back to where you
     * started" — read by `MainActivity` and consumed there, so a later
     * Activity recreate does not re-navigate a journey the person has already
     * finished.
     */
    internal fun returnIntent(origin: SignInOrigin? = null): Intent =
        Intent(context, mainActivity)
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT,
            )
            // Null is a launcher nobody told where the person started: it comes
            // forward and changes nothing, which is what this Intent did before
            // it could carry an origin at all.
            .apply { origin?.let { putExtra(EXTRA_SIGN_IN_ORIGIN, it.name) } }

    private fun viewIntent(target: Uri): Intent =
        Intent(Intent.ACTION_VIEW, target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

}

/**
 * The real platform.
 *
 * Every method here is called on the main thread, and callers must keep it that
 * way — [GatewaySignInBrowser.platformContext] is what guarantees it. `bindService`
 * and `startActivity` are the two calls this class exists to make, and the shape
 * proven on a device makes both from the main thread; moving them onto the
 * process-scoped IO thread is an unforced change to the one part of the sign-in
 * that was known to work, and #114 was spent failing to localize the result.
 * Nothing here may be "optimised" back onto an arbitrary thread.
 */
internal class AndroidSignInBrowserPlatform(
    private val context: Context,
) : SignInBrowserPlatform {
    override fun customTabsProvider(): String? =
        runCatching { CustomTabsClient.getPackageName(context, null) }.getOrNull()

    override fun bindCustomTabs(packageName: String): CustomTabsBinding? {
        val minted = AtomicReference<CustomTabsSession?>(null)
        val connection = object : CustomTabsServiceConnection() {
            override fun onCustomTabsServiceConnected(name: ComponentName, client: CustomTabsClient) {
                runCatching { client.warmup(0L) }
                minted.set(runCatching { client.newSession(null) }.getOrNull())
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                minted.set(null)
            }
        }
        val bound = runCatching {
            CustomTabsClient.bindCustomTabsService(context, packageName, connection)
        }.getOrDefault(false)
        // Nothing suspends between the bind succeeding and the handle that can
        // release it existing. A cancellation in that window used to strand the
        // binding for the life of the process, with no handle anywhere.
        if (!bound) return null
        return CustomTabsBinding(packageName, minted) {
            runCatching { context.unbindService(connection) }
        }
    }

    override fun startActivity(intent: Intent) {
        context.startActivity(intent)
    }
}

/**
 * Step names to logcat. Names only — see [GatewaySignInLog]. Wired by the
 * process; the data layer's own default is a no-op so JVM unit tests never
 * reach `android.util.Log`, which this project deliberately does not mock.
 */
internal object AndroidGatewaySignInLog : GatewaySignInLog {
    // The gateway package's existing tag (`GatewayRpc.kt`), so one logcat filter
    // covers a connection and the sign-in that opened it. WARN rather than INFO
    // because these exist to be read off a device after a failure, and a
    // priority filter that hides them makes the whole seam worthless.
    override fun step(step: GatewaySignInStep) {
        Log.w(GATEWAY_LOG_TAG, step.toString())
    }

    override fun failed(step: GatewaySignInStep, cause: Throwable) {
        // The whole chain, types only. The outermost name is usually this
        // codebase's own wrapper and says nothing; what it wrapped is the
        // answer. Messages are never logged — they carry hosts and paths.
        Log.w(GATEWAY_LOG_TAG, "$step (${throwableChain(cause)})")
    }
}

/**
 * Names the type of a connection failure that came from this app rather than
 * from the network. Type only, for the same reason.
 */
internal val androidGatewayAppFailureLog: (String) -> Unit = { chain ->
    Log.w(GATEWAY_LOG_TAG, "connect failed inside the app ($chain)")
}

/**
 * Starts and stops [SignInForegroundService] around one sign-in.
 *
 * On API 33+ without a `POST_NOTIFICATIONS` grant the service still runs and
 * its notification is simply not shown — which is the outcome that matters
 * here, because the uid's network state is what the service is for. This app
 * asks for that grant once, at the first live Gateway, and respects a refusal,
 * so a person who declined still gets a working sign-in.
 */
internal class AndroidGatewaySignInForeground(private val context: Context) : GatewaySignInForeground {
    override fun hold(): AutoCloseable? {
        val started = runCatching { SignInForegroundService.start(context) }.isSuccess
        if (!started) return null
        return AutoCloseable { runCatching { SignInForegroundService.stop(context) } }
    }
}

/**
 * Whether the platform thinks there is a working default network yet.
 *
 * Used once, before the single token-exchange retry, so the second attempt can
 * differ from the first instead of repeating it milliseconds later. Resolves
 * immediately when the network already validates, which is the common case —
 * this exists for the case where it does not.
 */
internal class AndroidGatewayNetworkGate(context: Context) : GatewayNetworkGate {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    override suspend fun awaitUsable(timeoutMillis: Long) {
        if (validatedNow()) return
        val usable = CompletableDeferred<Unit>()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                    usable.complete(Unit)
                }
            }
        }
        val watching = runCatching { connectivity.registerDefaultNetworkCallback(callback) }.isSuccess
        // Nothing to wait on is not a reason to stall the retry.
        if (!watching) return
        try {
            withTimeoutOrNull(timeoutMillis) { usable.await() }
        } finally {
            runCatching { connectivity.unregisterNetworkCallback(callback) }
        }
    }

    private fun validatedNow(): Boolean = runCatching {
        val active = connectivity.activeNetwork ?: return@runCatching false
        connectivity.getNetworkCapabilities(active)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
    }.getOrDefault(false)
}

/**
 * Names a connect-lifecycle event. Fixed phrases only, for the same reason.
 * This exists because a cancelled connect used to leave nothing behind at all.
 */
internal val androidGatewayConnectEventLog: (String) -> Unit = { event ->
    Log.w(GATEWAY_LOG_TAG, event)
}
