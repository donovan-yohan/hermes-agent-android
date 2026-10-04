package com.hermesagent.mobile.data.gateway

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HashFilenameCompatibilityTest {
    @Test fun rowAndLegacyFilenameVectors() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = AndroidGatewayTokenStore(context)
        // Inspect naming only: no encryption, credentials or lifecycle changes.
        val method = AndroidGatewayTokenStore::class.java.getDeclaredMethod("slotFile", GatewaySecretSlot::class.java)
            .apply { isAccessible = true }
        assertEquals("1e274ebc350ec2b6bb33f9d73ed7bb0b1cfe791d35165d8462d4e161a4f8e8e6.bin",
            (method.invoke(store, GatewaySecretSlot("row", "https://gateway.invalid")) as File).name)
        assertEquals("b97a94207d0aabc8f986340b9d8f58d9e0f00f520d49ca7812f666e5f0f4dbf8.bin",
            (method.invoke(store, GatewaySecretSlot("", "https://gateway.invalid")) as File).name)
    }
}
