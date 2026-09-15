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
