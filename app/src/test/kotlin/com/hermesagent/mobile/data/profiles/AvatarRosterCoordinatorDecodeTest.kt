package com.hermesagent.mobile.data.profiles

import android.graphics.Bitmap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AvatarRosterCoordinatorDecodeTest {
    @Test fun staleDecodeCannotPublishAfterNewAcceptedFalse() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val h = AvatarRosterHarness(this, AvatarDecoder {
            entered.complete(Unit)
            withContext(NonCancellable) { release.await() }
            Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        })
        val ref = h.coreRef()
        h.rpc.answer = { if (it == "profiles.list") avatarRosterWire(false) else avatarWire(byteArrayOf(137.toByte(),80,78,71,13,10,26,10)) }
        val binding = h.coordinator.subscribe(ref)!!
        runCurrent()
        assertTrue(entered.isCompleted)
        val cleared = h.botsRef()
        release.complete(Unit)
        runCurrent()
        assertSame(ProfileAvatar.Unavailable, binding.current())
        assertSame(ProfileAvatar.Missing, h.coordinator.subscribe(cleared)!!.current())
        assertNull(h.coordinator.subscribe(ref))
        h.close()
    }
}
