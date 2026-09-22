package com.hermesagent.mobile.data.themes

import com.hermesagent.mobile.data.prefs.ComposerControlsScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BackendSkinSyncTest {
    @get:Rule val temporary = TemporaryFolder()
    private val payload = Json.parseToJsonElement(
        """{"name":"saved-skin","colors":{"background":"#123","ui_text":"#fff"}}""",
    ) as JsonObject

    @Test fun `live skin becomes a boot definition without repainting or leaking profiles`() = runTest {
        val cache = BackendSkinCache(temporary.newFolder())
        var scope = ComposerControlsScope("connection-a", "default")
        val live = GatewayThemeRepository(http = { null })
        val sync = BackendSkinSync(cache, live, { scope }, { 0L })
        assertEquals("saved-skin", sync.ingest(payload, apply = true, expectedGeneration = 0L))
        assertNull(sync.ingest(payload, apply = true, expectedGeneration = 0L))

        val boot = GatewayThemeRepository(http = { null })
        val restarted = BackendSkinSync(cache, boot, { scope }, { 0L })
        restarted.restore()
        assertEquals(listOf("saved-skin"), boot.state.value.themes.map { it.name })
        assertNull(boot.state.value.activeOnGateway)
        scope = scope.copy(profileIdentity = "other")
        restarted.restore()
        assertTrue(boot.state.value.themes.isEmpty())
        scope = scope.copy(profileIdentity = "default")
        restarted.restore()
        assertEquals(listOf("saved-skin"), boot.state.value.themes.map { it.name })
    }

    @Test fun `stale origin is neither registered nor cached`() = runTest {
        val cache = BackendSkinCache(temporary.newFolder())
        val scope = ComposerControlsScope("connection-b", "default")
        val repository = GatewayThemeRepository(http = { null })
        val sync = BackendSkinSync(cache, repository, { scope }, { 2L })
        assertNull(sync.ingest(payload, apply = true, expectedGeneration = 1L))
        assertTrue(repository.state.value.themes.isEmpty())
        assertTrue(cache.read(scope.connectionIdentity, scope.profileIdentity).isEmpty())
    }
}
