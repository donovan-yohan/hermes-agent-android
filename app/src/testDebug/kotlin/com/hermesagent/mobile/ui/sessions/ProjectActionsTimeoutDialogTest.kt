package com.hermesagent.mobile.ui.sessions

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.inspector.WindowInspector
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import com.hermesagent.mobile.data.connections.StorageEndpoint
import com.hermesagent.mobile.data.gateway.*
import com.hermesagent.mobile.data.prefs.*
import com.hermesagent.mobile.data.session.*
import com.hermesagent.mobile.ui.chat.*
import com.hermesagent.mobile.ui.theme.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Mounted production dialog from real wire/repository/VM; native draw, not device/IME proof. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w412dp-h915dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProjectActionsTimeoutDialogTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun darkRenameDeadlineRendersSafeFeedbackWithNoRetry() = feedback(HermesThemeMode.Dark)
    @Test fun lightRenameDeadlineRendersSafeFeedbackWithNoRetry() = feedback(HermesThemeMode.Light)

    private fun feedback(mode: HermesThemeMode) = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val mutations = mutableListOf<JsonObject>()
        lateinit var rpc: CorrelatedGatewayRpc
        val wire = object : GatewayRpcWire {
            override fun close() = Unit
            override fun send(text: String): Boolean {
                val frame = Json.parseToJsonElement(text).jsonObject
                if (frame["method"] == JsonPrimitive("projects.tree")) {
                    rpc.receive("""{"jsonrpc":"2.0","id":${frame["id"]},"result":{"projects":[{"id":"saved","label":"Synthetic project","path":"/workspace/project","isAuto":false}]}}""")
                } else if (frame["method"] == JsonPrimitive("projects.update")) mutations += frame
                return true
            }
        }
        rpc = CorrelatedGatewayRpc(wire, { 100 }, dispatcher)
        val endpoint = MutableStateFlow<StorageEndpoint?>(StorageEndpoint("synthetic-row/address", 0))
        val cache = SessionCache()
        val repo = LiveGatewaySessionRepository(cache, MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)),
            MutableStateFlow<GatewayRpcClient?>(rpc), backgroundScope, projectStorageEndpoint = endpoint) { 0 }
        val model = ChatViewModel(cache, repo, TransientSidebarViewStore(SidebarGrouping.Project), storageEndpoint = endpoint,
            clock = { 0 }, bucketLabel = SessionBucketLabel { "Synthetic" })
        var mounted = true
        try {
            backgroundScope.launch { model.uiState.collect {} }; runCurrent()
            repo.refreshProjects(); runCurrent()
            model.openProjectActions(cache.state.value.projects.projects.getValue("saved"), { mounted }); runCurrent()
            val token = model.uiState.value.projectManagement.dialog!!.token
            val actions = ProjectActions(dismiss = model::dismissProjectActions, page = model::projectActionPage, execute = model::executeProjectAction)
            compose.setContent {
                val state by model.uiState.collectAsState()
                DisposableEffect(Unit) { onDispose { mounted = false } }
                HermesTheme(AppearanceSelection("mono", mode)) {
                    state.projectManagement.dialog?.let { ProjectActionsDialog(it, null, actions) }
                }
            }
            compose.onNodeWithText("Rename…").performClick(); runCurrent()
            compose.onNodeWithContentDescription("Name").performTextReplacement("Renamed project")
            compose.onNodeWithText("Save").performClick(); runCurrent()
            assertEquals(1, mutations.size)
            assertEquals(Json.parseToJsonElement("""{"id":"saved","name":"Renamed project","profile":"default"}"""), mutations.single()["params"])
            compose.onNodeWithText("Saving…").assertIsDisplayed()
            compose.onNodeWithText("Save").assertIsNotEnabled()
            advanceTimeBy(101); runCurrent()
            compose.onNodeWithText("Project change was not confirmed. Reopen Sessions to check it.").assertIsDisplayed()
            compose.onNodeWithText("Saving…").assertDoesNotExist()
            compose.onNodeWithText("Save").assertDoesNotExist()
            compose.onNodeWithText("Cancel").assertDoesNotExist()
            compose.onNodeWithText("Done").assertIsEnabled().assertHeightIsAtLeast(androidx.compose.ui.unit.Dp(48f))
            capture(mode.name.lowercase())
            actions.execute(token, ProjectOperation.Rename("Renamed project"))
            actions.page(token, ProjectActionPage.Delete); runCurrent()
            assertEquals(1, mutations.size)
            compose.onNodeWithText("Project change was not confirmed. Reopen Sessions to check it.").assertIsDisplayed()
            compose.onNodeWithText("Done").performClick(); runCurrent()
            assertNull(model.uiState.value.projectManagement.dialog)
            assertEquals(1, mutations.size)
        } finally { rpc.close(); Dispatchers.resetMain() }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val directory = System.getenv("PROJECT_ACTIONS_TIMEOUT_CAPTURE_DIR")?.let(::File) ?: return
        directory.mkdirs()
        compose.runOnIdle {
            val decor = WindowInspector.getGlobalWindowViews().last { it.width > 0 && it.height > 0 }
            val bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
            decor.draw(Canvas(bitmap))
            File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
