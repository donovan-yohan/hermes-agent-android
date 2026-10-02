package com.hermesagent.mobile.plugins.bots

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.hermesagent.mobile.ui.theme.AppearanceSelection
import com.hermesagent.mobile.ui.theme.HermesTheme
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class BotsSkillsJourneyTest {
    @get:Rule val compose = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    @After fun close() { scope.cancel() }
    @Test fun `skills autosave leaves an unsaved avatar draft untouched`() {
        val skillsHost = SkillsTestHost()
        val host = object : com.hermesagent.mobile.plugins.PluginHost by skillsHost {
            override suspend fun requestAtEndpoint(expectedGeneration: Long, method: String,
                params: kotlinx.serialization.json.JsonObject): com.hermesagent.mobile.plugins.PluginHostResult {
                check(method == "profiles.get_asset")
                return com.hermesagent.mobile.plugins.PluginHostResult.Success(
                    kotlinx.serialization.json.Json.parseToJsonElement("""{"found":false}"""))
            }
        }
        val storage = object : com.hermesagent.mobile.plugins.PluginStorage {
            override suspend fun get(key: String, fallback: String?) = fallback
            override suspend fun set(key: String, value: String) {}
            override suspend fun remove(key: String) {}
        }
        val management = BotsManagementViewModel(host, storage, scope, {})
        compose.setContent { HermesTheme(AppearanceSelection()) { Column {} } }
        compose.runOnIdle {
            management.avatar.open(BotManagementTarget("worker", 7L))
            management.skills.open(BotManagementTarget("worker", 7L))
        }
        compose.waitForIdle()
        lateinit var draft: BotAvatarState
        compose.runOnIdle {
            val pick = management.avatar.beginPick(management.avatar.state.value)!!
            val png = java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAACAAAAAgCAIAAAD8GO2jAAAAKklEQVR4nO3NQQEAAATAQCTUvwwl+N0C7DJ64rN6vQMAAAAAAAAAAIDDFmtiAY8TwohVAAAAAElFTkSuQmCC")
            management.avatar.finishPick(pick, png)
            draft = management.avatar.state.value
            assertTrue(draft.canSave)
            management.skills.toggle(management.skills.state.value, "research")
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals("Skill saved.", management.skills.state.value.message)
            assertSame(draft, management.avatar.state.value)
        }
    }
    @Test fun `registered editor autosave preserves identity model and toolsets drafts`() {
        val skillsHost = SkillsTestHost()
        val calls = mutableListOf<String>()
        val host = object : com.hermesagent.mobile.plugins.PluginHost by skillsHost {
            override suspend fun request(method: String, params: kotlinx.serialization.json.JsonObject) = requestAtEndpoint(7L, method, params)
            override suspend fun requestAtEndpoint(expectedGeneration: Long, method: String,
                params: kotlinx.serialization.json.JsonObject): com.hermesagent.mobile.plugins.PluginHostResult {
                calls += method
                val json = when (method) {
                    "profiles.list" -> """{"profiles":[{"name":"worker","display_name":"Worker","ui_meta":{}}]}"""
                    "model.options" -> """{"providers":[]}"""
                    "profiles.describe" -> """{"name":"worker","description":"","soul":"","model":{"provider":"synthetic","default":"synthetic"},"toolsets_pinned":false,"toolsets":[{"name":"web","enabled":true,"description":"Search"},{"name":"terminal","enabled":false,"description":"Commands"}]}"""
                    else -> error("Unexpected $method")
                }
                return com.hermesagent.mobile.plugins.PluginHostResult.Success(kotlinx.serialization.json.Json.parseToJsonElement(json))
            }
        }
        val registry = com.hermesagent.mobile.plugins.ContributionRegistry()
        BotsPlugin(scope = scope).register(com.hermesagent.mobile.plugins.createPluginContext(
            pluginId = "bots", registry = registry, host = host,
            rest = object : com.hermesagent.mobile.plugins.PluginRest {
                override suspend fun execute(pluginId: String, path: String, options: com.hermesagent.mobile.plugins.PluginRestOptions) =
                    com.hermesagent.mobile.plugins.PluginRestResult.Success(200, "{}".toByteArray())
            }, socket = object : com.hermesagent.mobile.plugins.PluginSocket {
                override fun connect(pluginId: String, path: String, onMessage: (String) -> Unit): () -> Unit = {}
            }, storage = object : com.hermesagent.mobile.plugins.PluginStorage {
                override suspend fun get(key: String, fallback: String?) = fallback
                override suspend fun set(key: String, value: String) {}
                override suspend fun remove(key: String) {}
            }, os = object : com.hermesagent.mobile.plugins.PluginOs {
                override fun notify(input: com.hermesagent.mobile.plugins.PluginNotificationInput) {}
                override suspend fun openExternal(url: String) = false
                override suspend fun writeClipboard(text: String) = false
                override suspend fun share(text: String, title: String?) = false
            }))
        val route = registry.getArea(com.hermesagent.mobile.plugins.PluginAreas.ROUTES_AREA).single { it.id.endsWith(":route") }
        compose.setContent { HermesTheme(AppearanceSelection()) { route.render!!.invoke() } }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Actions for Worker").performClick()
        compose.onNodeWithText("Edit…").performClick(); compose.waitForIdle()
        compose.onNode(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsActions.Expand))
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.Expand)
        compose.onNodeWithContentDescription("Display name").performScrollTo().performTextReplacement("Unsaved identity")
        compose.onNodeWithContentDescription("Model ID").performScrollTo().performTextReplacement("unsaved-model")
        compose.onNodeWithContentDescription("Toolset terminal").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Skill research").performScrollTo().performClick(); compose.waitForIdle()
        compose.onNodeWithText("Skill saved.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Display name").performScrollTo().assertTextEquals("Unsaved identity")
        compose.onNodeWithContentDescription("Model ID").performScrollTo().assertTextEquals("unsaved-model")
        compose.onNodeWithContentDescription("Toolset terminal").performScrollTo().assertIsOn()
        compose.onNodeWithText("Save toolsets").assertIsEnabled()
        assertFalse(calls.any { it == "profiles.configure" || it == "profiles.set_asset" })
        compose.onNodeWithText("Cancel").performScrollTo().performClick(); compose.waitForIdle()
        compose.onNodeWithContentDescription("Actions for Worker").performClick()
        compose.onNodeWithText("Edit…").performClick(); compose.waitForIdle()
        compose.onNode(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsActions.Expand))
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.Expand)
        compose.onNodeWithContentDescription("Skill research").performScrollTo().assertIsOn()
        assertEquals(1, skillsHost.writes.size)
    }
    @Test fun `switch autosaves disables while pending and cancel reopen cannot rollback`() {
        val release = CompletableDeferred<Unit>()
        val host = SkillsTestHost().apply { afterWrite = { release.await() } }
        val vm = BotsSkillsViewModel(host, scope)
        compose.setContent {
            val state by vm.state.collectAsState()
            HermesTheme(AppearanceSelection()) { Column {
                BotSkillsEditor(state, BotSkillsActions(onToggle = { vm.toggle(state, it) }, onRefresh = { vm.refresh(state) }))
            } }
        }
        compose.runOnIdle { vm.open(BotManagementTarget("worker", 7L)) }; compose.waitForIdle()
        compose.onNodeWithContentDescription("Skill research").assertIsOff().performClick()
        compose.onNodeWithContentDescription("Skill research").assertIsNotEnabled()
        compose.onNodeWithText("Save skills").assertDoesNotExist()
        compose.onNodeWithText("Changes save immediately. Cancel does not undo skill changes.").assertIsDisplayed()
        compose.runOnIdle { vm.close(); vm.open(BotManagementTarget("worker", 7L)) }; compose.waitForIdle()
        compose.onNodeWithContentDescription("Skill research").assertIsNotEnabled()
        compose.runOnIdle { release.complete(Unit) }; compose.waitForIdle()
        compose.onNodeWithContentDescription("Skill research").assertIsOn().assertIsEnabled()
        assertEquals(1, host.writes.size)
        compose.onNodeWithText("Browse and install skills").assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithText("Skill details and editing").assertIsNotEnabled()
        compose.onNodeWithText("Bulk actions and archive").assertIsNotEnabled()
    }
}
