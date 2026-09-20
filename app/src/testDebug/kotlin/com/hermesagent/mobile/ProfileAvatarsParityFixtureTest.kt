package com.hermesagent.mobile

import com.hermesagent.mobile.data.gateway.EndpointDispatchFence
import com.hermesagent.mobile.data.gateway.GatewayRpcClient
import com.hermesagent.mobile.data.profiles.AvatarRosterCoordinator
import com.hermesagent.mobile.data.profiles.GatewayProfileRepository
import com.hermesagent.mobile.data.profiles.ProfileAvatar
import com.hermesagent.mobile.data.profiles.ProfileAvatarRepository
import com.hermesagent.mobile.plugins.GatewayPluginHost
import com.hermesagent.mobile.plugins.bots.BotsPluginRepository
import com.hermesagent.mobile.plugins.bots.BotsRosterLoad
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ProfileAvatarsParityFixtureTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val clients = MutableStateFlow<GatewayRpcClient?>(null)
    private val endpoint = MutableStateFlow(0L)
    private val host = GatewayPluginHost(scope, clients, endpoint, EndpointDispatchFence())

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun `fallback state reaches a real found false asset response`() = runBlocking {
        val fixture = AvatarFixtureRpc(ProfileAvatarsFixtureState.ProfileFallback)
        clients.value = fixture
        val coordinator = AvatarRosterCoordinator(host, ProfileAvatarRepository(scope))
        val producer = coordinator.open(AvatarRosterCoordinator.Kind.Core)
        val profiles = GatewayProfileRepository({ error("captured fixture path") }, avatarProducer = producer)

        assertTrue(profiles.refreshProfiles())
        val profile = profiles.roster.value.profiles.single()
        assertTrue(profile.hasAvatar)
        val binding = checkNotNull(coordinator.bind(checkNotNull(profile.avatarRef)))
        assertSame(ProfileAvatar.Missing, withTimeout(5_000) { binding.updates.first { it is ProfileAvatar.Missing } })
        assertEquals(listOf("profiles.list", "profiles.get_asset"), fixture.calls)
        coordinator.close()
    }

    @Test
    fun `loading state advertises avatar and holds the real asset request`() = runBlocking {
        val fixture = AvatarFixtureRpc(ProfileAvatarsFixtureState.ProfileLoading)
        clients.value = fixture
        val coordinator = AvatarRosterCoordinator(host, ProfileAvatarRepository(scope))
        val producer = coordinator.open(AvatarRosterCoordinator.Kind.Core)
        val profiles = GatewayProfileRepository({ error("captured fixture path") }, avatarProducer = producer)

        assertTrue(profiles.refreshProfiles())
        val profile = profiles.roster.value.profiles.single()
        assertTrue(profile.hasAvatar)
        val binding = checkNotNull(coordinator.bind(checkNotNull(profile.avatarRef)))
        withTimeout(5_000) { fixture.loadingAssetStarted.await() }
        assertEquals(listOf("profiles.list", "profiles.get_asset"), fixture.calls)
        assertTrue(binding.current() is ProfileAvatar.Loading)
        coordinator.close()
    }

    @Test
    fun `unavailable state advertises avatar and maps a real refusal to fallback`() = runBlocking {
        val fixture = AvatarFixtureRpc(ProfileAvatarsFixtureState.ProfileUnavailable)
        clients.value = fixture
        val coordinator = AvatarRosterCoordinator(host, ProfileAvatarRepository(scope))
        val producer = coordinator.open(AvatarRosterCoordinator.Kind.Core)
        val profiles = GatewayProfileRepository({ error("captured fixture path") }, avatarProducer = producer)

        assertTrue(profiles.refreshProfiles())
        val profile = profiles.roster.value.profiles.single()
        assertTrue(profile.hasAvatar)
        val binding = checkNotNull(coordinator.bind(checkNotNull(profile.avatarRef)))
        assertSame(ProfileAvatar.Unavailable, withTimeout(5_000) { binding.updates.first { it is ProfileAvatar.Unavailable } })
        assertEquals(listOf("profiles.list", "profiles.get_asset"), fixture.calls)
        coordinator.close()
    }

    @Test
    fun `bots fallback distinguishes absent avatar and makes no asset request`() = runBlocking {
        val fixture = AvatarFixtureRpc(ProfileAvatarsFixtureState.BotsFallback)
        clients.value = fixture
        val coordinator = AvatarRosterCoordinator(host, ProfileAvatarRepository(scope))
        val producer = coordinator.open(AvatarRosterCoordinator.Kind.Bots, host)
        val bots = BotsPluginRepository(host, producer)

        val loaded = bots.loadRoster() as BotsRosterLoad.Loaded
        val row = loaded.rows.single()
        assertTrue(!row.hasAvatar)
        val ref = checkNotNull(row.avatarRef)
        assertSame(ProfileAvatar.Missing, coordinator.bind(ref)!!.current())
        assertEquals(listOf("profiles.list"), fixture.calls)
        coordinator.close()
    }
}
