package com.hermesagent.mobile.ui

import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.hermesagent.mobile.data.connections.ConnectionKind
import com.hermesagent.mobile.data.connections.SavedConnection
import com.hermesagent.mobile.data.prefs.HermesPreferences
import com.hermesagent.mobile.data.themes.BackendSkinCache
import com.hermesagent.mobile.data.themes.BackendSkinSync
import com.hermesagent.mobile.data.themes.GatewayThemeRepository
import com.hermesagent.mobile.ui.theme.HermesTheme
import com.hermesagent.mobile.ui.theme.HermesTokens
import com.hermesagent.mobile.ui.theme.paletteFor
import com.hermesagent.mobile.ui.theme.rendersDark
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.rules.TemporaryFolder
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Cross-layer restart proof; not a process-death or live Gateway acceptance test. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackendSkinRestartJourneyTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `persisted backend skin restores offline and paints instead of falling back`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val preferences = HermesPreferences(context)
        val row = SavedConnection("restart-skin", "Skin restart", ConnectionKind.Remote)
        val directory = temporary.newFolder()
        val payload = Json.parseToJsonElement(
            """{"name":"restart-skin","colors":{"background":"#123456","ui_text":"#ffffff"}}""",
        ) as JsonObject
        try {
            preferences.saveConnection(row)
            preferences.setActiveConnection(row.id)
            val scope = preferences.activeScope.first()
            val live = GatewayThemeRepository(http = { null })
            val sync = BackendSkinSync(BackendSkinCache(directory), live, { preferences.activeScope.first() }, { 0L })
            assertTrue(sync.ingestAndApply(payload, true, 0L, scope) { target ->
                preferences.setBackendSkinTheme(target, row.id, scope)
            })

            // Recreate cache reader, repository, sync and preference facade. No HTTP service exists.
            val restartedPreferences = HermesPreferences(context)
            val boot = GatewayThemeRepository(http = { null })
            val restarted = BackendSkinSync(
                BackendSkinCache(directory), boot, { restartedPreferences.activeScope.first() }, { 0L },
            )
            restarted.restore()
            val selection = restartedPreferences.appearance.first()
            assertEquals("restart-skin", selection.themeName)
            assertEquals(listOf("restart-skin"), boot.state.value.themes.map { it.name })
            var painted: Color? = null
            compose.setContent {
                val themes by boot.state.collectAsState()
                HermesTheme(selection, customThemes = themes.themes.map { it.preset }) {
                    val background = HermesTheme.tokens.chatSurface
                    SideEffect { painted = background }
                }
            }
            // Chat chrome is a semantic mix, not the skin's raw background seed.
            val palette = boot.state.value.themes.single().preset.paletteFor(false)
            assertEquals(Color(0xFF123456), palette.background)
            val expected = HermesTokens.from(palette, rendersDark(palette.background, false)).chatSurface
            compose.runOnIdle { assertEquals(expected, painted) }

            // A reconnect seed and its repeated change must not undo a later manual choice.
            assertNull(restarted.ingest(payload, false, 0L, scope))
            assertEquals("restart-skin", restarted.ingest(payload, true, 0L, scope))
            boot.acknowledgeBackendSkinApply("restart-skin", 0L)
            assertTrue(restartedPreferences.setConnectionTheme("mono", row.id))
            assertNull(restarted.ingest(payload, false, 0L, scope))
            assertNull(restarted.ingest(payload, true, 0L, scope))
            assertEquals("mono", restartedPreferences.appearance.first().themeName)
        } finally {
            preferences.removeConnection(row.id)
        }
    }
}
