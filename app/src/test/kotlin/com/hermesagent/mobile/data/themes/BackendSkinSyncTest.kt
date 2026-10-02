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
        assertEquals("saved-skin", sync.ingest(payload, apply = true, expectedGeneration = 0L, expectedScope = scope))
        live.acknowledgeBackendSkinApply("saved-skin", 0L)
        assertNull(sync.ingest(payload, apply = true, expectedGeneration = 0L, expectedScope = scope))

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

    @Test fun `default seed preserves custom selection and explicit reset applies once`() = runTest {
        val cache = BackendSkinCache(temporary.newFolder())
        val scope = ComposerControlsScope("connection-a", "default")
        val repository = GatewayThemeRepository(http = { null })
        val sync = BackendSkinSync(cache, repository, { scope }, { 0L })
        val default = Json.parseToJsonElement("""{"name":"default"}""") as JsonObject
        var selected = "saved-skin"
        val persist: suspend (String) -> Boolean = { selected = it; true }
        assertEquals(false, sync.ingestAndApply(default, false, 0L, scope, persist))
        assertEquals("saved-skin", selected)
        assertTrue(repository.state.value.themes.isEmpty())
        assertTrue(cache.read(scope.connectionIdentity, scope.profileIdentity).isEmpty())
        assertTrue(sync.ingestAndApply(default, true, 0L, scope, persist))
        assertEquals("nous", selected)
        assertEquals("default", repository.state.value.activeOnGateway)
        selected = "mono" // A manual choice must survive duplicate events and reconnect seeds.
        assertEquals(false, sync.ingestAndApply(default, false, 0L, scope, persist))
        assertEquals(false, sync.ingestAndApply(default, true, 0L, scope, persist))
        assertEquals("mono", selected)
        val nous = Json.parseToJsonElement("""{"name":"nous"}""") as JsonObject
        assertTrue(sync.ingestAndApply(nous, true, 0L, scope, persist))
        selected = "mono"
        // Desktop guards by the announced name, not the resolved palette name.
        assertTrue(sync.ingestAndApply(default, true, 0L, scope, persist))
        assertEquals("nous", selected)
    }

    @Test fun `legacy Classic cache is filtered on restore without losing custom or scope isolation`() = runTest {
        val directory = temporary.newFolder()
        val cache = BackendSkinCache(directory)
        var scope = ComposerControlsScope("connection-a", "profile-a")
        assertTrue(cache.write(scope.connectionIdentity, scope.profileIdentity, listOf(payload)))
        val classic = Json.parseToJsonElement("""{"name":"default","colors":{"background":"#123"}}""")
        // Raw on-disk legacy data: do not use today's sanitizing writer to simulate an old cache.
        directory.listFiles()!!.single().writeText(kotlinx.serialization.json.JsonArray(listOf(classic, payload)).toString())
        val repository = GatewayThemeRepository(http = { null })
        val sync = BackendSkinSync(cache, repository, { scope }, { 0L })
        sync.restore()
        assertEquals(listOf("saved-skin"), repository.state.value.themes.map { it.name })
        assertEquals(listOf(payload), cache.read(scope.connectionIdentity, scope.profileIdentity))
        assertNull(repository.state.value.activeOnGateway)
        scope = scope.copy(profileIdentity = "profile-b")
        sync.restore()
        assertTrue(repository.state.value.themes.isEmpty())
        scope = ComposerControlsScope("connection-b", "profile-a")
        sync.restore()
        assertTrue(repository.state.value.themes.isEmpty())
        scope = ComposerControlsScope("connection-a", "profile-a")
        sync.restore()
        assertEquals(listOf("saved-skin"), repository.state.value.themes.map { it.name })
        assertNull(repository.state.value.activeOnGateway)
    }

    @Test fun `builtins and default apply without replacing palettes or needing colors`() = runTest {
        val cache = BackendSkinCache(temporary.newFolder())
        val scope = ComposerControlsScope("connection-a", "default")
        val repository = GatewayThemeRepository(http = { null })
        val sync = BackendSkinSync(cache, repository, { scope }, { 0L })
        val builtin = Json.parseToJsonElement("""{"name":"mono"}""") as JsonObject
        val default = Json.parseToJsonElement("""{"name":"default"}""") as JsonObject
        assertNull(sync.ingest(builtin, apply = false, expectedGeneration = 0L, expectedScope = scope))
        assertEquals("mono", sync.ingest(builtin, apply = true, expectedGeneration = 0L, expectedScope = scope))
        repository.acknowledgeBackendSkinApply("mono", 0L)
        assertNull(sync.ingest(builtin, apply = false, expectedGeneration = 0L, expectedScope = scope))
        assertNull(sync.ingest(builtin, apply = true, expectedGeneration = 0L, expectedScope = scope))
        assertEquals("nous", sync.ingest(default, apply = true, expectedGeneration = 0L, expectedScope = scope))
        assertTrue(repository.state.value.themes.isEmpty())
        assertTrue(cache.read(scope.connectionIdentity, scope.profileIdentity).isEmpty())
        assertEquals("saved-skin", sync.ingest(payload, apply = true, expectedGeneration = 0L, expectedScope = scope))
        assertEquals("mono", sync.ingest(builtin, apply = true, expectedGeneration = 0L, expectedScope = scope))
    }

    @Test fun `default reset retries failed writes and rejects stale ownership`() = runTest {
        var scope = ComposerControlsScope("connection-a", "profile-a")
        var generation = 0L
        val cache = BackendSkinCache(temporary.newFolder())
        val repository = GatewayThemeRepository(http = { null }, endpointGeneration = { generation })
        val sync = BackendSkinSync(cache, repository, { scope }, { generation })
        val default = JsonObject(payload + ("name" to kotlinx.serialization.json.JsonPrimitive(" default ")))
        val origin = scope
        var selected = "saved-skin"
        assertEquals(false, sync.ingestAndApply(default, true, 0L, origin) { false })
        assertTrue(sync.ingestAndApply(default, true, 0L, origin) { selected = it; true })
        assertEquals("nous", selected)
        assertTrue(repository.state.value.themes.isEmpty())
        assertTrue(cache.read(origin.connectionIdentity, origin.profileIdentity).isEmpty())
        scope = scope.copy(profileIdentity = "profile-b")
        assertEquals(false, sync.ingestAndApply(default, true, 0L, origin) { error("stale profile") })
        generation++
        assertEquals(false, sync.ingestAndApply(default, true, 0L, scope) { error("stale generation") })
    }

    @Test fun `buffered skin from another profile is rejected at the same generation`() = runTest {
        val cache = BackendSkinCache(temporary.newFolder())
        val origin = ComposerControlsScope("connection-a", "profile-a")
        val current = origin.copy(profileIdentity = "profile-b")
        val repository = GatewayThemeRepository(http = { null })
        val sync = BackendSkinSync(cache, repository, { current }, { 0L })
        assertNull(sync.ingest(payload, true, 0L, origin))
        assertTrue(repository.state.value.themes.isEmpty())
        assertTrue(cache.read(current.connectionIdentity, current.profileIdentity).isEmpty())
        assertTrue(cache.read(origin.connectionIdentity, origin.profileIdentity).isEmpty())
    }

    @Test fun `stale origin is neither registered nor cached`() = runTest {
        val cache = BackendSkinCache(temporary.newFolder())
        val scope = ComposerControlsScope("connection-b", "default")
        val repository = GatewayThemeRepository(http = { null })
        val sync = BackendSkinSync(cache, repository, { scope }, { 2L })
        assertNull(sync.ingest(payload, apply = true, expectedGeneration = 1L, expectedScope = scope))
        assertTrue(repository.state.value.themes.isEmpty())
        assertTrue(cache.read(scope.connectionIdentity, scope.profileIdentity).isEmpty())
    }

    @Test fun `failed preference writes remain retryable and only success acknowledges activation`() = runTest {
        val scope = ComposerControlsScope("connection-a", "default")
        val repo = GatewayThemeRepository(http = { null })
        val sync = BackendSkinSync(BackendSkinCache(temporary.newFolder()), repo, { scope }, { 0L })
        assertEquals(false, sync.ingestAndApply(payload, true, 0L, scope) { throw java.io.IOException("fixture") })
        assertEquals(false, sync.ingestAndApply(payload, true, 0L, scope) { false })
        var writes = 0
        assertTrue(sync.ingestAndApply(payload, true, 0L, scope) { writes++; true })
        assertEquals(false, sync.ingestAndApply(payload, true, 0L, scope) { writes++; true })
        assertEquals(1, writes)
    }

    @Test fun `failed cache write neither publishes nor persists and later recovers`() = runTest {
        val directory = temporary.newFile()
        val scope = ComposerControlsScope("connection-a", "default")
        val repo = GatewayThemeRepository(http = { null })
        val sync = BackendSkinSync(BackendSkinCache(directory), repo, { scope }, { 0L })
        var writes = 0
        assertEquals(false, sync.ingestAndApply(payload, true, 0L, scope) { writes++; true })
        assertEquals(0, writes)
        assertTrue(repo.state.value.themes.isEmpty())
        assertTrue(directory.delete())
        assertTrue(directory.mkdir())
        assertTrue(sync.ingestAndApply(payload, true, 0L, scope) { writes++; true })
        assertEquals(1, writes)
    }

    @Test fun `cache capacity retains newest definition and does not poison subsequent writes`() = runTest {
        val cache = BackendSkinCache(temporary.newFolder())
        val scope = ComposerControlsScope("connection-a", "default")
        fun skin(name: String) = JsonObject(payload + ("name" to kotlinx.serialization.json.JsonPrimitive(name)))
        assertTrue(cache.write(scope.connectionIdentity, scope.profileIdentity,
            (0 until BackendSkinCache.MAX_SKINS).map { skin("skin-$it") }))
        val sync = BackendSkinSync(cache, GatewayThemeRepository(http = { null }), { scope }, { 0L })
        assertTrue(sync.ingestAndApply(payload, true, 0L, scope) { true })
        assertTrue(sync.ingestAndApply(skin("next-skin"), true, 0L, scope) { true })
        val restored = cache.read(scope.connectionIdentity, scope.profileIdentity)
        assertEquals(BackendSkinCache.MAX_SKINS, restored.size)
        assertEquals("next-skin", (restored.last()["name"] as kotlinx.serialization.json.JsonPrimitive).content)
    }

    @Test fun `cancellation propagates and does not acknowledge an unfinished apply`() = runTest {
        val scope = ComposerControlsScope("connection-a", "default")
        val sync = BackendSkinSync(BackendSkinCache(temporary.newFolder()), GatewayThemeRepository(http = { null }), { scope }, { 0L })
        var cancelled = false
        try {
            sync.ingestAndApply(payload, true, 0L, scope) { throw kotlinx.coroutines.CancellationException("fixture") }
        } catch (_: kotlinx.coroutines.CancellationException) {
            cancelled = true
        }
        assertTrue(cancelled)
        assertTrue(sync.ingestAndApply(payload, true, 0L, scope) { true })
    }
}
