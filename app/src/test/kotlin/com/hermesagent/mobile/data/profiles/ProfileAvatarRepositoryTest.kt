package com.hermesagent.mobile.data.profiles

import android.graphics.Bitmap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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
class ProfileAvatarRepositoryTest {
    private val missing = buildJsonObject { put("found", false) }
    private val raster = avatarWire(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10))

    private inner class Source(endpoint: Any = Any()) : CapturedAvatarSource(endpoint) {
        var current = true
        val calls = mutableListOf<Pair<String, JsonObject>>()
        var answer: suspend () -> JsonElement = { missing }
        override fun isCurrent() = current
        override suspend fun requestCaptured(method: String, params: JsonObject): JsonElement {
            calls += method to params
            return answer()
        }
    }

    private inner class Harness(scope: TestScope, decode: AvatarDecoder? = null) {
        val source = Source()
        var decodes = 0
        val repo = ProfileAvatarRepository(scope.backgroundScope, decode ?: AvatarDecoder {
            decodes++
            Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        }, StandardTestDispatcher(scope.testScheduler), { scope.testScheduler.currentTime })
        init { repo.activate(source) }
        fun sub(name: String = "fixture-alpha", hasAvatar: Boolean = true) = repo.subscribe(source, name, hasAvatar)
    }

    @Test fun flagGateExactRawNameAndHardCodedReadOnlyRequest() = runTest {
        val h = Harness(this)
        assertSame(ProfileAvatar.Missing, h.sub(hasAvatar = false).current())
        runCurrent()
        assertEquals(0, h.source.calls.size)
        h.sub(" Fixture-Alpha ")
        h.sub("fixture-alpha")
        h.sub("default")
        runCurrent()
        assertEquals(3, h.source.calls.size)
        assertEquals(listOf(" Fixture-Alpha ", "fixture-alpha", "default"), h.source.calls.map { (it.second["name"] as JsonPrimitive).content })
        h.source.calls.forEach { (method, params) ->
            assertEquals("profiles.get_asset", method)
            assertEquals(setOf("name", "asset"), params.keys)
            assertEquals(JsonPrimitive("avatar"), params["asset"])
        }
        h.repo.close()
    }

    @Test fun dedupeOneWaiterLeavesOtherLivesThenCacheHit() = runTest {
        val h = Harness(this)
        val reply = CompletableDeferred<JsonElement>()
        h.source.answer = { reply.await() }
        val first = h.sub()
        val second = h.sub()
        runCurrent()
        first.close()
        reply.complete(raster)
        runCurrent()
        assertTrue(second.current() is ProfileAvatar.Ready)
        assertEquals(1, h.source.calls.size)
        assertEquals(1, h.decodes)
        second.close()
        val third = h.sub()
        runCurrent()
        assertTrue(third.current() is ProfileAvatar.Ready)
        assertEquals(1, h.source.calls.size)
        h.repo.close()
    }

    @Test fun lastWaiterCancellationCleansUpWithoutPoisoningRetry() = runTest {
        val h = Harness(this)
        var cancelled = false
        h.source.answer = { try { awaitCancellation() } finally { cancelled = true } }
        val first = h.sub()
        runCurrent()
        first.close()
        runCurrent()
        assertTrue(cancelled)
        h.source.answer = { missing }
        val second = h.sub()
        runCurrent()
        assertSame(ProfileAvatar.Missing, second.current())
        assertEquals(2, h.source.calls.size)
        h.repo.close()
    }

    @Test fun falseClearsExistingImageAndRejectsLateReplyThenTrueRetries() = runTest {
        val h = Harness(this)
        h.source.answer = { raster }
        val sub = h.sub()
        runCurrent()
        assertTrue(sub.current() is ProfileAvatar.Ready)
        val late = CompletableDeferred<JsonElement>()
        h.source.answer = { withContext(NonCancellable) { late.await() } }
        h.repo.refresh(h.source)
        runCurrent()
        h.sub(hasAvatar = false)
        assertSame(ProfileAvatar.Missing, sub.current())
        late.complete(raster)
        runCurrent()
        assertSame(ProfileAvatar.Missing, sub.current())
        h.source.answer = { missing }
        h.sub()
        runCurrent()
        assertEquals(3, h.source.calls.size)
        h.repo.close()
    }

    @Test fun predecessorFinallyCannotRemoveReplacementTask() = runTest {
        val h = Harness(this)
        val old = CompletableDeferred<JsonElement>()
        val replacement = CompletableDeferred<JsonElement>()
        h.source.answer = { withContext(NonCancellable) { old.await() } }
        h.sub()
        runCurrent()
        h.repo.refresh(h.source)
        h.source.answer = { replacement.await() }
        runCurrent()
        old.complete(missing)
        runCurrent()
        val second = h.sub()
        runCurrent()
        assertEquals(2, h.source.calls.size)
        replacement.complete(raster)
        runCurrent()
        assertTrue(second.current() is ProfileAvatar.Ready)
        h.repo.close()
    }

    @Test fun endpointSwitchFencesQueuedDispatchedAndOldRows() = runTest {
        val h = Harness(this)
        val old = CompletableDeferred<JsonElement>()
        h.source.answer = { withContext(NonCancellable) { old.await() } }
        val first = h.sub("fixture-one")
        h.sub("fixture-two")
        h.sub("fixture-queued")
        runCurrent()
        assertEquals(2, h.source.calls.size)
        val next = Source()
        h.repo.activate(next)
        assertSame(ProfileAvatar.Unavailable, first.current())
        val staleRow = h.sub()
        val fresh = h.repo.subscribe(next, "fixture-one", true)
        old.complete(raster)
        runCurrent()
        assertSame(ProfileAvatar.Unavailable, staleRow.current())
        assertSame(ProfileAvatar.Missing, fresh.current())
        assertEquals(2, h.source.calls.size)
        assertEquals(1, next.calls.size)
        assertEquals(0, h.decodes)
        h.repo.close()
    }

    @Test fun livenessReadFencesCollectorLagAndSameEndpointReconnect() = runTest {
        val h = Harness(this)
        h.source.answer = { raster }
        val old = h.sub()
        runCurrent()
        h.source.current = false
        assertSame(ProfileAvatar.Unavailable, old.current())
        assertSame(ProfileAvatar.Unavailable, h.sub().current())
        val nextLeg = Source(h.source.endpoint)
        h.repo.activate(nextLeg)
        val fresh = h.repo.subscribe(nextLeg, "fixture-alpha", true)
        runCurrent()
        assertSame(ProfileAvatar.Missing, fresh.current())
        assertEquals(1, nextLeg.calls.size)
        h.repo.close()
    }

    @Test fun ownerChangeDuringDecodeRejectsPublication() = runTest {
        val gate = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val h = Harness(this, AvatarDecoder {
            entered.complete(Unit)
            withContext(NonCancellable) { gate.await() }
            Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        })
        h.source.answer = { raster }
        val old = h.sub()
        runCurrent()
        assertTrue(entered.isCompleted)
        val next = Source(h.source.endpoint)
        h.repo.activate(next)
        gate.complete(Unit)
        runCurrent()
        assertSame(ProfileAvatar.Unavailable, old.current())
        val fresh = h.repo.subscribe(next, "fixture-alpha", true)
        runCurrent()
        assertSame(ProfileAvatar.Missing, fresh.current())
        h.repo.close()
    }

    @Test fun ttlRefreshesUnchangedFlagAndMissWhileOffscreenExpiryIsLazy() = runTest {
        val h = Harness(this)
        val sub = h.sub()
        runCurrent()
        assertSame(ProfileAvatar.Missing, sub.current())
        advanceTimeBy(59_999)
        runCurrent()
        assertEquals(1, h.source.calls.size)
        h.source.answer = { raster }
        advanceTimeBy(1)
        runCurrent()
        assertTrue(sub.current() is ProfileAvatar.Ready)
        h.repo.refresh(h.source)
        runCurrent()
        assertEquals(3, h.source.calls.size)
        sub.close()
        advanceTimeBy(120_000)
        runCurrent()
        assertEquals(3, h.source.calls.size)
        h.source.answer = { missing }
        val resumed = h.sub()
        runCurrent()
        assertSame(ProfileAvatar.Missing, resumed.current())
        assertEquals(4, h.source.calls.size)
        h.repo.close()
    }

    @Test fun failuresAreSuppressedNotNegativeCachedAndExplicitRefreshBypasses() = runTest {
        val h = Harness(this)
        h.source.answer = { throw IllegalStateException("synthetic refusal") }
        val sub = h.sub()
        runCurrent()
        assertSame(ProfileAvatar.Unavailable, sub.current())
        repeat(20) { h.sub().close() }
        runCurrent()
        assertEquals(1, h.source.calls.size)
        advanceTimeBy(15_000)
        h.sub().close()
        // Keep this new subscriber alive long enough to dispatch.
        val retry = h.sub()
        runCurrent()
        assertEquals(2, h.source.calls.size)
        h.source.answer = { missing }
        h.repo.refresh(h.source)
        runCurrent()
        assertSame(ProfileAvatar.Missing, retry.current())
        assertEquals(3, h.source.calls.size)
        h.repo.close()
    }

    @Test fun malformedAndDecoderFailureNeverBecomeMissing() = runTest {
        val h = Harness(this, AvatarDecoder { null })
        h.source.answer = { buildJsonObject { put("found", "false") } }
        val sub = h.sub()
        runCurrent()
        assertSame(ProfileAvatar.Unavailable, sub.current())
        h.source.answer = { raster }
        h.repo.refresh(h.source)
        runCurrent()
        assertSame(ProfileAvatar.Unavailable, sub.current())
        h.repo.close()
    }

    @Test fun activeWorkPendingKeysAndSubscribersHaveHardBounds() = runTest {
        val h = Harness(this)
        h.source.answer = { awaitCancellation() }
        val subs = (0 until 64).map { h.sub("fixture-$it") }
        val overflow = h.sub("fixture-overflow")
        runCurrent()
        assertEquals(2, h.source.calls.size)
        assertSame(ProfileAvatar.Unavailable, overflow.current())
        val duplicates = (0 until 192).map { h.sub("fixture-0") }
        assertSame(ProfileAvatar.Unavailable, h.sub("fixture-0").current())
        // False still clears/cancels the image under saturated admission.
        h.sub("fixture-0", false)
        assertSame(ProfileAvatar.Missing, subs[0].current())
        duplicates.forEach { it.close() }
        subs.forEach { it.close() }
        runCurrent()
        h.repo.close()
    }

    @Test fun negativeCacheCountsTowardLruEntryLimit() = runTest {
        val h = Harness(this)
        repeat(65) {
            val sub = h.sub("fixture-$it")
            runCurrent()
            assertSame(ProfileAvatar.Missing, sub.current())
            sub.close()
        }
        h.sub("fixture-64").close()
        runCurrent()
        assertEquals(65, h.source.calls.size)
        h.sub("fixture-0")
        runCurrent()
        assertEquals(66, h.source.calls.size)
        h.repo.close()
    }

    @Test fun bitmapAllocationBudgetEvictsWithoutRecyclingVisibleReferences() = runTest {
        val h = Harness(this)
        h.source.answer = { raster }
        val first = h.sub("fixture-0")
        runCurrent()
        val held = (first.current() as ProfileAvatar.Ready).bitmap
        repeat(32) { h.sub("fixture-${it + 1}"); runCurrent() }
        assertSame(ProfileAvatar.Unavailable, first.current())
        assertFalse(held.isRecycled)
        assertEquals(33, h.decodes)
        h.repo.close()
    }

    @Test fun transportDeadlineIsSuppressedButParentCancellationIsNotAnError() = runTest {
        val h = Harness(this)
        h.source.answer = { kotlinx.coroutines.withTimeout(60_000) { awaitCancellation() } }
        val sub = h.sub()
        runCurrent()
        advanceTimeBy(60_000)
        runCurrent()
        assertSame(ProfileAvatar.Unavailable, sub.current())
        repeat(10) { h.sub().close() }
        runCurrent()
        assertEquals(1, h.source.calls.size)
        h.source.answer = { missing }
        h.repo.refresh(h.source)
        runCurrent()
        assertSame(ProfileAvatar.Missing, sub.current())
        h.repo.close()
    }

    @Test fun cancellationBeforeDispatchAndDisposedScopeCannotStartWork() = runTest {
        val h = Harness(this)
        val abandoned = h.sub()
        abandoned.close()
        val replacement = h.sub()
        runCurrent()
        assertSame(ProfileAvatar.Missing, replacement.current())
        assertEquals(1, h.source.calls.size)
        backgroundScope.coroutineContext[kotlinx.coroutines.Job]!!.cancel()
        assertSame(ProfileAvatar.Unavailable, replacement.current())
        assertSame(ProfileAvatar.Unavailable, h.sub("fixture-new").current())
        runCurrent()
        assertEquals(1, h.source.calls.size)
        h.repo.close()
    }

    @Test fun livenessChangeImmediatelyBeforePublishRejectsDecodedResult() = runTest {
        lateinit var h: Harness
        h = Harness(this, AvatarDecoder {
            h.source.current = false
            Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        })
        h.source.answer = { raster }
        val sub = h.sub()
        runCurrent()
        assertFalse(sub.updates.value is ProfileAvatar.Ready)
        assertSame(ProfileAvatar.Unavailable, sub.current())
        h.repo.close()
    }

    @Test fun explicitKeyRefreshReplacesImageWithoutRefreshingOtherNames() = runTest {
        val h = Harness(this)
        h.source.answer = { raster }
        val first = h.sub("fixture-first")
        h.sub("fixture-other")
        runCurrent()
        val image = (first.current() as ProfileAvatar.Ready).bitmap
        h.repo.refresh(h.source, "fixture-first")
        runCurrent()
        assertNotSame(image, (first.current() as ProfileAvatar.Ready).bitmap)
        assertEquals(3, h.source.calls.size)
        h.source.answer = { missing }
        h.repo.refresh(h.source, "fixture-first")
        runCurrent()
        assertSame(ProfileAvatar.Missing, first.current())
        h.repo.close()
    }

    @Test fun slowReadIsNotTruncatedAtTenSecondsAndCloseRefusesWork() = runTest {
        val h = Harness(this)
        val gate = CompletableDeferred<JsonElement>()
        h.source.answer = { gate.await() }
        val sub = h.sub()
        runCurrent()
        advanceTimeBy(59_000)
        gate.complete(missing)
        runCurrent()
        assertSame(ProfileAvatar.Missing, sub.current())
        h.repo.close()
        assertSame(ProfileAvatar.Unavailable, sub.current())
        assertSame(ProfileAvatar.Unavailable, h.sub().current())
        assertEquals(1, h.source.calls.size)
    }
}
