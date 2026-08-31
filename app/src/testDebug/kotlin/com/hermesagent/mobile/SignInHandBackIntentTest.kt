package com.hermesagent.mobile

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.hermesagent.mobile.data.gateway.EXTRA_SIGN_IN_ORIGIN
import com.hermesagent.mobile.data.gateway.SignInOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The hand-back through the real Activity: `onNewIntent` consumes the origin,
 * so an Activity recreate cannot replay a journey the person already finished.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SignInHandBackIntentTest {

    @Test
    fun `a sessions hand-back is consumed, so a recreate does not replay it`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val handBack = Intent(
            ApplicationProvider.getApplicationContext(),
            MainActivity::class.java,
        ).putExtra(EXTRA_SIGN_IN_ORIGIN, SignInOrigin.Sessions.name)

        controller.newIntent(handBack)

        assertNull(
            "consumed, so the next thing to read this Intent sees no journey",
            handBack.getStringExtra(EXTRA_SIGN_IN_ORIGIN),
        )
        assertEquals(handBack, controller.get().intent)
    }
}
