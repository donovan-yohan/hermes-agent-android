package com.hermesagent.mobile.data.profiles

import android.graphics.Bitmap
import com.hermesagent.mobile.data.gateway.EndpointDispatchFence
import com.hermesagent.mobile.data.gateway.EndpointDispatchingGatewayRpcClient
import com.hermesagent.mobile.data.gateway.GatewayEvent
import com.hermesagent.mobile.data.gateway.GatewayRpcClient
import com.hermesagent.mobile.data.gateway.GatewayRpcException
import com.hermesagent.mobile.plugins.GatewayPluginHost
import com.hermesagent.mobile.plugins.bots.BotsPluginRepository
import com.hermesagent.mobile.plugins.bots.BotsRosterLoad
import com.hermesagent.mobile.plugins.bots.BotsRosterPhase
import com.hermesagent.mobile.plugins.bots.BotsViewModel
import com.hermesagent.mobile.plugins.bots.parseBotsRoster
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

internal fun avatarRosterWire(flag: Boolean = true, name: String = " Fixture-Alpha ") = buildJsonObject {
    put("profiles", buildJsonArray { add(buildJsonObject { put("name", name); put("has_avatar", flag); put("display_name", "Fixture label") }) })
}

internal class AvatarRosterRpc : EndpointDispatchingGatewayRpcClient {
    override val events: Flow<GatewayEvent> = emptyFlow()
    val calls = mutableListOf<Pair<String, JsonObject>>()
    var roster: JsonElement = avatarRosterWire()
    var answer: suspend (String) -> JsonElement = { method ->
        if (method == "profiles.list") roster else buildJsonObject { put("found", false) }
    }
    override suspend fun request(method: String, params: JsonObject): JsonElement = error("ordinary RPC fallback")
    override suspend fun requestAtEndpointDispatch(method: String, params: JsonObject, dispatch: (() -> Boolean) -> Boolean): JsonElement {
        if (!dispatch { calls += method to params; true }) throw GatewayRpcException("Synthetic stale lease")
        return answer(method)
    }
    override fun close() = Unit
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AvatarRosterCoordinatorTest {
    private inner class Harness(scope: TestScope, decoder: AvatarDecoder = AvatarDecoder { null }) {
        val rpc = AvatarRosterRpc()
        val clients = MutableStateFlow<GatewayRpcClient?>(rpc)
        val endpoint = MutableStateFlow(0L)
        val fence = EndpointDispatchFence()
        val pluginScope = CoroutineScope(SupervisorJob(scope.backgroundScope.coroutineContext[kotlinx.coroutines.Job]) + StandardTestDispatcher(scope.testScheduler))
        val appHost = GatewayPluginHost(scope.backgroundScope, clients, endpoint, fence)
        val pluginHost = GatewayPluginHost(pluginScope, clients, endpoint, fence)
        val avatars = ProfileAvatarRepository(scope.backgroundScope, decoder, StandardTestDispatcher(scope.testScheduler), { scope.testScheduler.currentTime })
        val coordinator = AvatarRosterCoordinator(appHost, avatars)
        val coreProducer = coordinator.open(AvatarRosterCoordinator.Kind.Core)
        val botsProducer = coordinator.open(AvatarRosterCoordinator.Kind.Bots, pluginHost)
        val core = GatewayProfileRepository({ error("live slot fallback") }, avatarProducer = coreProducer)
        val bots = BotsPluginRepository(pluginHost, botsProducer)
        suspend fun coreRef(): ProfileAvatarRef {
            assertTrue(core.refreshProfiles())
            return checkNotNull(core.roster.value.profiles.single().avatarRef)
        }
        suspend fun botsRef(): ProfileAvatarRef = checkNotNull((bots.loadRoster() as BotsRosterLoad.Loaded).rows.single().avatarRef)
        fun assetCalls() = rpc.calls.filter { it.first == "profiles.get_asset" }
        fun close() { botsProducer.close(); pluginScope.cancel(); coordinator.close() }
    }

    @Test fun realRepositoriesPreserveRoutingNamesAndCaptureLiteralAssetName() = runTest {
        val h = Harness(this)
        val coreRef = h.coreRef()
        val coreRow = h.core.roster.value.profiles.single()
        val botsRef = h.botsRef()
        assertEquals("Fixture-Alpha", coreRow.name)
        assertEquals("Fixture label", coreRow.label)
        assertEquals(" Fixture-Alpha ", coreRef.identity.rawName)
        val core = checkNotNull(h.coordinator.subscribe(coreRef))
        val bot = checkNotNull(h.coordinator.subscribe(botsRef))
        runCurrent()
        assertSame(ProfileAvatar.Missing, core.current())
        assertSame(ProfileAvatar.Missing, bot.current())
        assertEquals(1, h.assetCalls().size)
        assertEquals(" Fixture-Alpha ", h.assetCalls().single().second["name"]!!.jsonPrimitive.content)
        assertEquals("avatar", h.assetCalls().single().second["asset"]!!.jsonPrimitive.content)
        assertEquals(listOf("false", "true"), h.rpc.calls.filter { it.first == "profiles.list" }.map { it.second["include_sessions"].toString() })
        h.close()
    }

    @Test fun rawRosterParsingAndProcessDeathReconstructionCannotMintPermissions() = runTest {
        val h = Harness(this)
        assertNull(parseProfileList(avatarRosterWire())!!.single().avatarRef)
        assertNull(parseBotsRoster(avatarRosterWire())!!.single().avatarRef)
        assertNull(HermesProfile("Fixture-Alpha", hasAvatar = true).avatarRef)
        assertNull(h.coordinator.subscribe(null))
        val old = h.coreRef()
        val replacement = AvatarRosterCoordinator(h.appHost, ProfileAvatarRepository(backgroundScope, AvatarDecoder { null }, StandardTestDispatcher(testScheduler)))
        replacement.open(AvatarRosterCoordinator.Kind.Core)
        assertNull(replacement.subscribe(old))
        assertFalse(java.io.Serializable::class.java.isAssignableFrom(ProfileAvatarRef::class.java))
        assertFalse(android.os.Parcelable::class.java.isAssignableFrom(ProfileAvatarRef::class.java))
        replacement.close(); h.close()
    }

    @Test fun falseAndRemovalRevokeAllOldTrueRowsBeforeNewSubscriptions() = runTest {
        val h = Harness(this)
        val oldCore = h.coreRef()
        val oldBots = h.botsRef()
        val binding = checkNotNull(h.coordinator.subscribe(oldCore))
        runCurrent()
        h.rpc.roster = avatarRosterWire(false)
        val cleared = h.botsRef()
        assertSame(ProfileAvatar.Unavailable, binding.current())
        assertNull(h.coordinator.subscribe(oldCore))
        assertNull(h.coordinator.subscribe(oldBots))
        assertSame(ProfileAvatar.Missing, h.coordinator.subscribe(cleared)!!.current())
        assertEquals(1, h.assetCalls().size)
        h.rpc.roster = avatarRosterWire(true)
        val replacement = h.botsRef()
        assertNull(h.coordinator.subscribe(oldCore)) // false -> true never revives the old true permit.
        assertNull(h.coordinator.subscribe(cleared))
        assertNotNull(h.coordinator.subscribe(replacement))
        runCurrent()
        assertEquals(2, h.assetCalls().size)
        h.rpc.roster = buildJsonObject { put("profiles", buildJsonArray {}) }
        assertTrue(h.core.refreshProfiles())
        assertNull(h.coordinator.subscribe(replacement))
        h.close()
    }

    @Test fun olderCoreRosterStillPublishesAfterNewerBotsFalseOrRemoval() = runTest {
        for (remove in listOf(false, true)) {
            val h = Harness(this)
            val oldCore = h.coreRef()
            val oldBots = h.botsRef()
            val held = CompletableDeferred<JsonElement>()
            h.rpc.answer = { held.await() }
            val older = async { h.core.refreshProfiles() }
            runCurrent()
            val latest = if (remove) buildJsonObject { put("profiles", buildJsonArray {}) } else avatarRosterWire(false)
            h.rpc.answer = { latest }
            val newer = h.bots.loadRoster() as BotsRosterLoad.Loaded
            held.complete(avatarRosterWire(true))
            assertTrue(older.await())
            assertTrue(h.core.roster.value.loaded)
            assertEquals("Fixture-Alpha", h.core.roster.value.profiles.single().name)
            assertNull(h.core.roster.value.profiles.single().avatarRef)
            assertNull(h.coordinator.subscribe(oldCore))
            assertNull(h.coordinator.subscribe(oldBots))
            newer.rows.firstOrNull()?.avatarRef?.let {
                assertSame(ProfileAvatar.Missing, h.coordinator.subscribe(it)!!.current())
            }
            assertEquals(0, h.assetCalls().size)
            h.close()
        }
    }

    @Test fun olderBotsRosterStillPublishesAfterNewerCoreFalseOrRemoval() = runTest {
        for (remove in listOf(false, true)) {
            val h = Harness(this)
            val oldCore = h.coreRef()
            val oldBots = h.botsRef()
            val vm = BotsViewModel(h.bots, backgroundScope, connected = MutableStateFlow(false), endpointGeneration = h.endpoint)
            val held = CompletableDeferred<JsonElement>()
            h.rpc.answer = { held.await() }
            val older = async { vm.refreshNow() }
            runCurrent()
            h.rpc.answer = { if (remove) buildJsonObject { put("profiles", buildJsonArray {}) } else avatarRosterWire(false) }
            assertTrue(h.core.refreshProfiles())
            held.complete(avatarRosterWire(true))
            older.await()
            assertEquals(BotsRosterPhase.Ready, vm.uiState.value.phase)
            val row = vm.uiState.value.sections.single().rows.single()
            assertEquals("Fixture-Alpha", row.name)
            assertNull(row.avatarRef)
            assertNull(vm.uiState.value.safeMessage)
            assertNull(h.coordinator.subscribe(oldCore))
            assertNull(h.coordinator.subscribe(oldBots))
            h.core.roster.value.profiles.firstOrNull()?.avatarRef?.let {
                assertSame(ProfileAvatar.Missing, h.coordinator.subscribe(it)!!.current())
            }
            assertEquals(0, h.assetCalls().size)
            h.close()
        }
    }

    @Test fun initialCoreRosterCannotBeStarvedByNewerBotsDecoration() = runTest {
        val h = Harness(this)
        val held = CompletableDeferred<JsonElement>()
        h.rpc.answer = { held.await() }
        val older = async { h.core.refreshProfiles() }
        runCurrent()
        h.rpc.answer = { avatarRosterWire(false) }
        val current = h.botsRef()
        held.complete(avatarRosterWire(true))
        assertTrue(older.await())
        assertTrue(h.core.roster.value.loaded)
        assertEquals("Fixture-Alpha", h.core.roster.value.profiles.single().name)
        assertNull(h.core.roster.value.profiles.single().avatarRef)
        assertSame(ProfileAvatar.Missing, h.coordinator.subscribe(current)!!.current())
        assertEquals(0, h.assetCalls().size)
        h.close()
    }

    @Test fun oldEndpointReplyStillCannotPublishPrimaryRoster() = runTest {
        val h = Harness(this)
        val held = CompletableDeferred<JsonElement>()
        h.rpc.answer = { held.await() }
        val older = async { h.core.refreshProfiles() }
        runCurrent()
        h.fence.invalidate(); h.clients.value = AvatarRosterRpc(); h.endpoint.value++
        held.complete(avatarRosterWire())
        assertFalse(older.await())
        assertFalse(h.core.roster.value.loaded)
        assertTrue(h.core.roster.value.profiles.isEmpty())
        h.close()
    }

    @Test fun identicalAvatarEnabledRosterPreservesStateRowsAndCurrentPermits() = runTest {
        val h = Harness(this)
        val ref = h.coreRef()
        val state = h.core.roster.value
        val row = state.profiles.single()
        val binding = h.coordinator.subscribe(ref)!!
        runCurrent()
        repeat(3) {
            assertTrue(h.core.refreshProfiles())
            assertSame(state, h.core.roster.value)
            assertSame(state.profiles, h.core.roster.value.profiles)
            assertSame(row, h.core.roster.value.profiles.single())
            assertSame(ref, h.core.roster.value.profiles.single().avatarRef)
            runCurrent()
            assertSame(ProfileAvatar.Missing, binding.current())
        }
        // Another producer's identical metadata does not replace this producer's permit.
        val botRef = h.botsRef()
        assertSame(ref, h.coreRef())
        assertSame(state, h.core.roster.value)
        assertSame(botRef, h.botsRef())
        h.close()
    }

    @Test fun stablePermitsNeverReviveAfterFlagsRevocationOrReconnect() = runTest {
        val h = Harness(this)
        val old = h.coreRef()
        val originalState = h.core.roster.value
        h.rpc.roster = avatarRosterWire(false)
        val cleared = h.coreRef()
        assertNotSame(originalState, h.core.roster.value)
        assertNotSame(old, cleared)
        val clearedState = h.core.roster.value
        assertSame(cleared, h.coreRef())
        assertSame(clearedState, h.core.roster.value)
        h.rpc.roster = avatarRosterWire(true)
        val newTrue = h.coreRef()
        assertNotSame(old, newTrue)
        assertNull(h.coordinator.subscribe(old))
        assertNull(h.coordinator.subscribe(cleared))
        h.clients.value = AvatarRosterRpc()
        val newLeg = h.coreRef()
        assertNotSame(newTrue, newLeg)
        assertNull(h.coordinator.subscribe(newTrue))
        assertNotNull(h.coordinator.subscribe(newLeg))
        h.close()
    }

    @Test fun olderIdenticalCoreReplyMayReuseStillAuthorizedCurrentPermit() = runTest {
        val h = Harness(this)
        val original = h.coreRef()
        val state = h.core.roster.value
        val held = CompletableDeferred<JsonElement>()
        h.rpc.answer = { held.await() }
        val older = async { h.core.refreshProfiles() }
        runCurrent()
        h.rpc.answer = { avatarRosterWire() }
        h.botsRef()
        held.complete(avatarRosterWire())
        assertTrue(older.await())
        assertSame(state, h.core.roster.value)
        assertSame(original, h.core.roster.value.profiles.single().avatarRef)
        assertNotNull(h.coordinator.subscribe(original))
        h.close()
    }

    @Test fun onlyCurrentReadAndCurrentActivationMayAccept() = runTest {
        val h = Harness(this)
        val oldRead = checkNotNull(h.coreProducer.begin())
        val newer = checkNotNull(h.coreProducer.begin())
        assertNull(h.coreProducer.accept(oldRead, avatarRosterWire()))
        assertNotNull(h.coreProducer.accept(newer, avatarRosterWire()))
        val oldRef = h.coreRef()
        val replacement = h.coordinator.open(AvatarRosterCoordinator.Kind.Core)
        assertNull(h.coreProducer.begin())
        assertNull(h.coordinator.subscribe(oldRef))
        h.coreProducer.close() // Old disposer cannot close the replacement slot.
        assertNotNull(replacement.begin())
        h.close()
    }

    @Test fun changedCacheEpochPreventsPermissionAcceptanceAndKeepsOldRosterText() = runTest {
        val h = Harness(this)
        val old = h.coreRef()
        val held = CompletableDeferred<JsonElement>()
        h.rpc.answer = { held.await() }
        val load = async { h.core.refreshProfiles() }
        runCurrent()
        h.core.connectionChanged(GatewayProfileConnectionState.Changed)
        held.complete(avatarRosterWire())
        assertFalse(load.await())
        assertTrue(h.core.roster.value.loaded)
        assertEquals("Fixture-Alpha", h.core.roster.value.profiles.single().name)
        assertNull(h.coordinator.subscribe(old))
        h.close()
    }

    @Test fun ownerLossWithoutAnyAvatarEventIsReadOnlyAndOldRowsNeverRetarget() = runTest {
        val h = Harness(this)
        val ref = h.coreRef()
        val binding = checkNotNull(h.coordinator.subscribe(ref))
        runCurrent()
        val revision = h.coordinator.revision.value
        val next = AvatarRosterRpc()
        h.clients.value = next // No observer run, avatar event, or lifecycle sync.
        repeat(3) { assertSame(ProfileAvatar.Unavailable, binding.current()) }
        assertEquals(revision, h.coordinator.revision.value) // Draw reads must not mutate ownership/cache.
        assertTrue(next.calls.isEmpty())
        assertNull(h.coordinator.subscribe(ref))
        assertTrue(next.calls.isEmpty())
        val fresh = h.coreRef()
        assertNotNull(h.coordinator.subscribe(fresh))
        runCurrent()
        assertEquals(1, next.calls.count { it.first == "profiles.get_asset" })
        h.close()
    }

    @Test fun pairCaptureAndAcceptanceRefuseEitherHostsLostLeg() = runTest {
        val h = Harness(this)
        val separateClients = MutableStateFlow<GatewayRpcClient?>(h.rpc)
        val separateHost = GatewayPluginHost(backgroundScope, separateClients, h.endpoint, h.fence)
        val producer = h.coordinator.open(AvatarRosterCoordinator.Kind.Bots, separateHost)
        val ticket = checkNotNull(producer.begin())
        separateClients.value = AvatarRosterRpc()
        assertNull(producer.accept(ticket, avatarRosterWire()))
        val ticket2 = checkNotNull(producer.begin())
        h.clients.value = AvatarRosterRpc()
        assertNull(producer.accept(ticket2, avatarRosterWire()))
        separateClients.value = null
        assertNull(producer.begin())
        h.close()
    }

    @Test fun boundedMetadataSubscribersAndDuplicateOrNonStringNamesFailClosed() = runTest {
        val h = Harness(this)
        h.rpc.roster = buildJsonObject { put("profiles", buildJsonArray {
            repeat(100) { add(buildJsonObject { put("name", "fixture-$it"); put("has_avatar", true) }) }
        }) }
        assertTrue(h.core.refreshProfiles())
        val rows = h.core.roster.value.profiles
        assertEquals(100, rows.size)
        assertEquals(64, rows.count { it.avatarRef != null })
        val ref = checkNotNull(rows.first().avatarRef)
        val subs = List(256) { checkNotNull(h.coordinator.subscribe(ref)) }
        assertNull(h.coordinator.subscribe(ref))
        subs.first().close()
        assertNotNull(h.coordinator.subscribe(ref))
        h.rpc.roster = Json.parseToJsonElement("""{"profiles":[{"name":"same","has_avatar":true},{"name":"same","has_avatar":false},{"name":17,"has_avatar":true}]}""")
        assertTrue(h.core.refreshProfiles())
        assertTrue(h.core.roster.value.profiles.all { it.avatarRef == null })
        assertNull(h.coordinator.subscribe(ref))
        h.close()
    }

    @Test fun closingBotsWaiterPreservesCoreAssetWhileDisablingPluginCancelsRoster() = runTest {
        val h = Harness(this)
        val coreRef = h.coreRef()
        val botsRef = h.botsRef()
        val answer = CompletableDeferred<JsonElement>()
        h.rpc.answer = { answer.await() }
        val core = h.coordinator.subscribe(coreRef)!!
        val bot = h.coordinator.subscribe(botsRef)!!
        runCurrent()
        val roster = async { h.bots.loadRoster() }
        runCurrent()
        h.botsProducer.close()
        h.pluginScope.cancel()
        runCurrent()
        assertTrue(roster.isCancelled)
        assertSame(ProfileAvatar.Unavailable, bot.current())
        answer.complete(buildJsonObject { put("found", false) })
        runCurrent()
        assertSame(ProfileAvatar.Missing, core.current())
        assertEquals(1, h.assetCalls().size)
        h.close()
    }

    @Test fun lastWaiterDisposalCancelsActualHostExchange() = runTest {
        val h = Harness(this)
        val ref = h.coreRef()
        var cancelled = false
        h.rpc.answer = { try { awaitCancellation() } finally { cancelled = true } }
        val binding = h.coordinator.subscribe(ref)!!
        runCurrent()
        binding.close()
        runCurrent()
        assertTrue(cancelled)
        h.close()
    }

    @Test fun staleDecodeCannotPublishAfterNewAcceptedFalse() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val h = Harness(this, AvatarDecoder {
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

    @Test fun sameFlagRefreshInvalidatesMissingAndErrorsDoNotChangeRosterPhase() = runTest {
        val h = Harness(this)
        val vm = BotsViewModel(h.bots, backgroundScope, connected = MutableStateFlow(false), endpointGeneration = h.endpoint)
        vm.refreshNow()
        val ref = checkNotNull(vm.uiState.value.sections.single().rows.single().avatarRef)
        val sub = h.coordinator.subscribe(ref)!!
        runCurrent()
        assertSame(ProfileAvatar.Missing, sub.current())
        h.rpc.answer = { if (it == "profiles.list") avatarRosterWire() else throw GatewayRpcException("Synthetic refusal") }
        vm.surfaceResumed()
        runCurrent()
        val fresh = checkNotNull(vm.uiState.value.sections.single().rows.single().avatarRef)
        val failed = h.coordinator.subscribe(fresh)!!
        runCurrent()
        assertSame(ProfileAvatar.Unavailable, failed.current())
        assertEquals(BotsRosterPhase.Ready, vm.uiState.value.phase)
        assertTrue(vm.uiState.value.attentionByKey.isEmpty())
        assertNull(vm.uiState.value.safeMessage)
        repeat(10) { h.coordinator.subscribe(fresh)?.close() }
        runCurrent()
        assertEquals(2, h.assetCalls().size)
        advanceTimeBy(15_000)
        h.coordinator.subscribe(fresh)
        runCurrent()
        assertEquals(3, h.assetCalls().size)
        h.close()
    }

    @Test fun endpointResetAndCloseRevokeReceiptsWithoutRevivingOnNextLeg() = runTest {
        val h = Harness(this)
        val ref = h.coreRef()
        val binding = h.coordinator.subscribe(ref)!!
        runCurrent()
        h.fence.invalidate(); h.clients.value = null; h.endpoint.value++
        h.coordinator.reset()
        assertSame(ProfileAvatar.Unavailable, binding.current())
        h.clients.value = AvatarRosterRpc()
        assertNull(h.coordinator.subscribe(ref))
        h.coordinator.close()
        assertNull(h.coordinator.subscribe(ref))
        assertNull(h.coreProducer.begin())
        h.close()
    }
}
