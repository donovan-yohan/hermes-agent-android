package com.hermesagent.mobile.data.themes

import com.hermesagent.mobile.data.gateway.GatewayHttp
import com.hermesagent.mobile.data.gateway.GatewayHttpRequest
import com.hermesagent.mobile.data.gateway.GatewayHttpResult
import com.hermesagent.mobile.data.gateway.RecordingGatewayHttp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayThemeRepositoryTest {
    @Test fun `maps response classes and resets endpoint state`() = runTest {
        val generation = longArrayOf(0)
        val repo = GatewayThemeRepository(http = { RecordingGatewayHttp(GatewayHttpResult.Success(200, ok().toByteArray())) }, endpointGeneration = { generation[0] })
        repo.refresh()
        assertEquals(GatewayThemesStatus.Ready, repo.state.value.status)
        assertEquals(1, repo.state.value.themes.size)
        assertEquals("custom", repo.state.value.activeOnGateway)
        repo.resetForEndpointSwitch()
        assertEquals(GatewayThemesStatus.Idle, repo.state.value.status)
        assertTrue(repo.state.value.themes.isEmpty())
        assertEquals(null, repo.state.value.activeOnGateway)

        listOf(401 to GatewayThemesStatus.SignInRequired, 403 to GatewayThemesStatus.SignInRequired, 404 to GatewayThemesStatus.Unsupported, 500 to GatewayThemesStatus.Unreachable, 0 to GatewayThemesStatus.Unreachable).forEach { (code, status) ->
            GatewayThemeRepository(http = { RecordingGatewayHttp(GatewayHttpResult.Rejected(code, "safe")) }).also { it.refresh(); assertEquals(status, it.state.value.status) }
        }
        GatewayThemeRepository(http = { RecordingGatewayHttp(GatewayHttpResult.Success(200, "bad".toByteArray())) }).also {
            it.refresh(); assertEquals(GatewayThemesStatus.Unusable, it.state.value.status)
        }
    }

    @Test fun `drops a stale answer after generation changes`() = runTest {
        var generation = 0L
        val gate = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val repo = GatewayThemeRepository(http = { object : GatewayHttp {
            override suspend fun execute(request: GatewayHttpRequest): GatewayHttpResult { started.complete(Unit); gate.await(); return GatewayHttpResult.Success(200, ok().toByteArray()) }
        } }, endpointGeneration = { generation })
        val pending = async { repo.refresh() }
        started.await()
        val beforeBump = repo.state.value
        generation++
        gate.complete(Unit)
        pending.await()
        assertEquals(beforeBump, repo.state.value)
    }

    @Test fun `socket skins survive dashboard failure but clear at endpoint switch`() = runTest {
        val repo = GatewayThemeRepository(http = { RecordingGatewayHttp(GatewayHttpResult.Rejected(404, "")) })
        val skin = parseBackendSkin(kotlinx.serialization.json.Json.parseToJsonElement(
            """{"name":"socket-skin","colors":{"background":"#123","ui_text":"#fff"}}""",
        ) as kotlinx.serialization.json.JsonObject)!!
        repo.ingestBackendSkin(skin, apply = false)
        repo.refresh()
        assertEquals(GatewayThemesStatus.Unsupported, repo.state.value.status)
        assertEquals(listOf("socket-skin"), repo.state.value.themes.map { it.name })
        assertTrue(repo.isBackendSkin("socket-skin"))
        repo.resetForEndpointSwitch()
        assertTrue(repo.state.value.themes.isEmpty())
        assertTrue(!repo.isBackendSkin("socket-skin"))
    }

    @Test fun `reset invalidates an in flight refresh even without a generation bump`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val repo = GatewayThemeRepository(http = { object : GatewayHttp {
            override suspend fun execute(request: GatewayHttpRequest): GatewayHttpResult {
                started.complete(Unit)
                gate.await()
                return GatewayHttpResult.Success(200, ok().toByteArray())
            }
        } })
        val refresh = async { repo.refresh() }
        started.await()
        repo.resetForEndpointSwitch()
        gate.complete(Unit)
        refresh.await()
        assertEquals(GatewayThemesState(), repo.state.value)
    }

    @Test fun `refresh preserves socket publication made while HTTP is suspended`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val repo = GatewayThemeRepository(http = { object : GatewayHttp {
            override suspend fun execute(request: GatewayHttpRequest): GatewayHttpResult {
                started.complete(Unit)
                gate.await()
                return GatewayHttpResult.Success(200, ok().toByteArray())
            }
        } })
        val refresh = async { repo.refresh() }
        started.await()
        val skin = parseBackendSkin(kotlinx.serialization.json.Json.parseToJsonElement(
            """{"name":"socket-skin","colors":{"background":"#123","ui_text":"#fff"}}""",
        ) as kotlinx.serialization.json.JsonObject)!!
        repo.ingestBackendSkin(skin, false, 0L)
        gate.complete(Unit)
        refresh.await()
        assertEquals(setOf("custom", "socket-skin"), repo.state.value.themes.map { it.name }.toSet())
    }

    @Test fun `old skin publication cannot restore definitions or apply state after reset`() {
        var generation = 0L
        val repo = GatewayThemeRepository(http = { null }, endpointGeneration = { generation })
        val skin = parseBackendSkin(kotlinx.serialization.json.Json.parseToJsonElement(
            """{"name":"socket-skin","colors":{"background":"#123","ui_text":"#fff"}}""",
        ) as kotlinx.serialization.json.JsonObject)!!
        generation++
        repo.resetForEndpointSwitch()
        assertEquals(false, repo.ingestBackendSkin(skin, true, 0L))
        assertEquals(false, repo.requestBackendSkinApply("mono", true, 0L))
        assertEquals(GatewayThemesState(), repo.state.value)
        assertEquals(false, repo.isBackendSkin("socket-skin"))
    }

    @Test fun `repeat skin changes and reconnect seeds preserve manual appearance choices`() {
        val repo = GatewayThemeRepository(http = { null })
        val skin = parseBackendSkin(kotlinx.serialization.json.Json.parseToJsonElement(
            """{"name":"socket-skin","colors":{"background":"#123","ui_text":"#fff"}}""",
        ) as kotlinx.serialization.json.JsonObject)!!
        assertEquals(false, repo.ingestBackendSkin(skin, apply = false))
        assertEquals(true, repo.ingestBackendSkin(skin, apply = true))
        // The caller can now choose a different local theme. Neither a replay
        repo.acknowledgeBackendSkinApply(skin.name, 0L)
        // nor the reconnect seed may request that the local choice be replaced.
        assertEquals(false, repo.ingestBackendSkin(skin, apply = true))
        assertEquals(false, repo.ingestBackendSkin(skin, apply = false))
        assertEquals(false, repo.ingestBackendSkin(skin, apply = true))
        val other = skin.copy(name = "another-skin")
        assertEquals(false, repo.ingestBackendSkin(other, apply = false))
        assertEquals(true, repo.ingestBackendSkin(other, apply = true))
        assertEquals(true, repo.ingestBackendSkin(skin, apply = true))
        repo.resetForEndpointSwitch()
        assertEquals(true, repo.ingestBackendSkin(skin, apply = true))
    }

    @Test fun `queued select keeps its captured transport`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val refreshStarted = CompletableDeferred<Unit>()
        var calls = 0
        val old = object : GatewayHttp {
            val paths = mutableListOf<String>()
            override suspend fun execute(request: GatewayHttpRequest): GatewayHttpResult {
                paths += request.path
                if (request.path.endsWith("themes") && ++calls > 1) { refreshStarted.complete(Unit); gate.await() }
                return GatewayHttpResult.Success(200, if (request.method == "PUT") """{"ok":true,"theme":"custom"}""".toByteArray() else ok().toByteArray())
            }
        }
        val newer = ScriptedHttp(GatewayHttpResult.Success(200, ok().toByteArray()))
        var current: GatewayHttp? = old
        var expectSelectCapture = false
        val selectCaptured = CompletableDeferred<Unit>()
        val repo = GatewayThemeRepository(http = {
            if (expectSelectCapture) selectCaptured.complete(Unit)
            current
        })
        repo.refresh()
        val refresh = async { repo.refresh() }
        refreshStarted.await()
        expectSelectCapture = true
        val selection = async { repo.select("custom") }
        selectCaptured.await()
        current = newer
        gate.complete(Unit)
        refresh.await()
        assertEquals(GatewayThemesStatus.Ready, selection.await())
        assertTrue(old.paths.contains("api/dashboard/theme"))
    }

    @Test fun `select requires a loaded name and exact host confirmation`() = runTest {
        val transport = RecordingGatewayHttp(
            GatewayHttpResult.Success(200, ok().toByteArray()),
            GatewayHttpResult.Success(200, """{"ok":true,"theme":"other"}""".toByteArray()),
        )
        val repo = GatewayThemeRepository(http = { transport })
        repo.refresh()
        assertEquals(GatewayThemesStatus.Unreachable, repo.select("missing"))
        assertEquals(1, transport.requests.size)
        assertEquals(GatewayThemesStatus.Unusable, repo.select("custom"))
        assertEquals(2, transport.requests.size)
    }

    private class ScriptedHttp(private val result: GatewayHttpResult) : GatewayHttp {
        override suspend fun execute(request: GatewayHttpRequest) = result
    }

    private fun ok() = """{"themes":[{"name":"custom","label":"Custom","description":"","definition":{"name":"custom","palette":{"background":"#112233","midground":"#445566","foreground":"#778899"}}}],"active":"custom"}"""
}
