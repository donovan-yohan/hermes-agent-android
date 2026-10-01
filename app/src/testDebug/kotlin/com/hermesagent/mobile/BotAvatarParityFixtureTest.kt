package com.hermesagent.mobile

import com.hermesagent.mobile.data.gateway.*
import com.hermesagent.mobile.plugins.GatewayPluginHost
import com.hermesagent.mobile.plugins.bots.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
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
class BotAvatarParityFixtureTest {
    @Test fun everyRegisteredStateUsesRealProductionReadAndMutationPaths() = runTest {
        for (state in BOT_AVATAR_CAPTURE_STATES) {
            val rpc = BotAvatarFixtureRpc(state)
            val host = GatewayPluginHost(backgroundScope, MutableStateFlow<GatewayRpcClient?>(rpc), MutableStateFlow(0L), EndpointDispatchFence())
            runCurrent()
            val vm = BotsAvatarViewModel(host, backgroundScope, {})
            vm.open(BotManagementTarget("synthetic-avatar", 0L)); runCurrent()
            stageBotAvatar(state, vm); runCurrent()
            val value = vm.state.value
            assertEquals("profiles.get_asset", rpc.calls.first().first)
            val writes = rpc.calls.filter { it.first == "profiles.set_asset" }
            when (state) {
                "bot-avatar-loading" -> assertTrue(value.loading)
                "bot-avatar-loaded" -> assertNotNull(value.original?.bytes)
                "bot-avatar-picking" -> assertNotNull(value.picking)
                "bot-avatar-cancel" -> assertEquals("Image selection cancelled. Nothing was saved.", value.message)
                "bot-avatar-error" -> assertNull(value.original)
                "bot-avatar-changed" -> assertTrue(value.canSave)
                "bot-avatar-saved" -> { assertEquals("Avatar saved.", value.message); assertEquals(1, writes.size) }
                "bot-avatar-cleared" -> { assertEquals("Avatar removed.", value.message); assertNull(rpc.bytes); assertEquals(1, writes.size) }
            }
            if (state !in setOf("bot-avatar-saved", "bot-avatar-cleared")) assertTrue(writes.isEmpty())
            if (writes.isNotEmpty()) assertEquals("profiles.get_asset", rpc.calls.last().first)
            vm.close()
        }
    }
}
