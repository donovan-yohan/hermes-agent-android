package com.hermesagent.mobile

import android.app.Application
import com.hermesagent.mobile.data.demo.DemoSessions
import com.hermesagent.mobile.data.prefs.HermesPreferences
import com.hermesagent.mobile.data.session.SessionCache

/**
 * Process-scoped state, and the reason it is not on the Activity.
 *
 * The session cache outlives any one Activity: a retained `ViewModel` survives
 * a configuration change while the Activity does not, so an Activity-owned
 * cache would leave the ViewModel writing into an orphaned copy — and the demo
 * seed would re-run on every recreation, quietly undoing an archive or a
 * rename. Both live here instead, created once per process.
 *
 * When the gateway lands, the transport and its connection scope join this
 * object, and the seed call goes away.
 */
class HermesApplication : Application() {

    val cache: SessionCache by lazy {
        SessionCache().also { DemoSessions.seed(it, System.currentTimeMillis()) }
    }

    val preferences: HermesPreferences by lazy { HermesPreferences(this) }
}
