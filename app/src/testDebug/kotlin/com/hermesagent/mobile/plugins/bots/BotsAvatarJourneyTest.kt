package com.hermesagent.mobile.plugins.bots

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.hermesagent.mobile.ui.theme.AppearanceSelection
import com.hermesagent.mobile.ui.theme.HermesTheme
import kotlinx.coroutines.*
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
class BotsAvatarJourneyTest {
    @get:Rule val compose = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    @After fun close() { scope.cancel() }
    @Test fun avatarControlsExposeButtonRolesAndDisabledState() {
        var state by mutableStateOf(BotAvatarState(
            ticket = BotAvatarTicket(BotManagementTarget("worker", 7), 1),
            original = BotAvatarRead(avatarPng()),
        ))
        compose.setContent { HermesTheme(AppearanceSelection()) { BotAvatarEditor(state, BotAvatarActions()) } }
        val button = SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.Role, androidx.compose.ui.semantics.Role.Button)
        for (label in listOf("Upload", "Remove image")) compose.onNodeWithText(label).assert(button).assertIsEnabled()
        compose.onNodeWithText("Save avatar").assert(button).assertIsNotEnabled()
        for (label in listOf("Bot", "Generate", "Pet")) {
            compose.onNodeWithContentDescription("$label. Work in progress.").assert(button).assertIsNotEnabled()
        }
        compose.runOnIdle { state = state.copy(busy = true) }
        for (label in listOf("Upload", "Remove image", "Save avatar")) compose.onNodeWithText(label).assert(button).assertIsNotEnabled()
    }
    @Test fun selectedContentUriNormalizesBeforeInlineSaveAndReadback() {
        var launchedCode = -1
        val registry = object : androidx.activity.result.ActivityResultRegistry() {
            override fun <I, O> onLaunch(requestCode: Int, contract: androidx.activity.result.contract.ActivityResultContract<I, O>, input: I,
                options: androidx.core.app.ActivityOptionsCompat?) { launchedCode = requestCode }
        }
        val owner = object : androidx.activity.result.ActivityResultRegistryOwner { override val activityResultRegistry = registry }
        val uri = android.net.Uri.parse("content://synthetic.avatar/selected")
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val source = com.hermesagent.mobile.fixtureAvatarPng()
        org.robolectric.Shadows.shadowOf(context.contentResolver).registerInputStream(uri, java.io.ByteArrayInputStream(source))
        var stored: ByteArray? = null
        val host = ModelTestHost().apply { answer = { method, params ->
            if (method == "profiles.set_asset") {
                stored = java.util.Base64.getDecoder().decode(params.getValue("data").jsonPrimitive.content.substringAfter(','))
                modelReply("""{"ok":true,"asset":"avatar","size":${stored!!.size}}""")
            } else stored?.let { com.hermesagent.mobile.plugins.PluginHostResult.Success(avatarReply(it)) } ?: modelReply("""{"found":false}""")
        } }
        val vm = BotsAvatarViewModel(host, scope, {})
        compose.runOnIdle { vm.open(BotManagementTarget("worker", 7)) }
        compose.setContent { CompositionLocalProvider(androidx.activity.compose.LocalActivityResultRegistryOwner provides owner) {
            HermesTheme(AppearanceSelection()) { val state by vm.state.collectAsState(); BotAvatarEditor(state, botAvatarActions(vm, state)) }
        } }
        compose.onNodeWithText("Upload").performClick()
        compose.runOnIdle { registry.dispatchResult(launchedCode, android.app.Activity.RESULT_OK, android.content.Intent().setData(uri)) }
        compose.waitUntil(5_000) { vm.state.value.canSave }
        assertNull(stored)
        compose.onNodeWithText("Save avatar").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Avatar saved.").assertIsDisplayed()
        val saved = requireNotNull(stored)
        val bitmap = android.graphics.BitmapFactory.decodeByteArray(saved, 0, saved.size)
        assertEquals(256, bitmap.width); assertEquals(256, bitmap.height)
        assertEquals(1, host.calls.count { it.first == "profiles.set_asset" })
        assertEquals("profiles.get_asset", host.calls.last().first)
    }
    @Test fun explicitRemovalWritesOnlyAfterSaveAndShowsReadbackResult() {
        var present = true
        val host = ModelTestHost().apply { answer = { method, _ ->
            if (method == "profiles.set_asset") { present = false; modelReply("""{"ok":true,"asset":"avatar","size":0,"removed":1}""") }
            else if (present) com.hermesagent.mobile.plugins.PluginHostResult.Success(avatarReply(avatarPng()))
            else modelReply("""{"found":false}""")
        } }
        val vm = BotsAvatarViewModel(host, scope, {})
        compose.runOnIdle { vm.open(BotManagementTarget("worker", 7)) }
        compose.setContent { HermesTheme(AppearanceSelection()) {
            val state by vm.state.collectAsState()
            BotAvatarEditor(state, botAvatarActions(vm, state))
        } }
        compose.onNodeWithText("Remove image").performClick()
        assertEquals(0, host.calls.count { it.first == "profiles.set_asset" })
        compose.onNodeWithText("Save avatar").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Avatar removed.").assertIsDisplayed()
        compose.onNodeWithContentDescription("Avatar fallback: worker").assertIsDisplayed()
        compose.onNodeWithContentDescription("Avatar preview").assertDoesNotExist()
        assertEquals(1, host.calls.count { it.first == "profiles.set_asset" })
        assertEquals("profiles.get_asset", host.calls.last().first)
    }
    @Test fun platformPickerCancellationUsesActualActivityResultContract() {
        var launchedCode = -1
        val registry = object : androidx.activity.result.ActivityResultRegistry() {
            override fun <I, O> onLaunch(requestCode: Int, contract: androidx.activity.result.contract.ActivityResultContract<I, O>, input: I,
                options: androidx.core.app.ActivityOptionsCompat?) { launchedCode = requestCode }
        }
        val owner = object : androidx.activity.result.ActivityResultRegistryOwner { override val activityResultRegistry = registry }
        val host = ModelTestHost().apply { answer = { _, _ -> modelReply("""{"found":false}""") } }
        val vm = BotsAvatarViewModel(host, scope, {})
        compose.runOnIdle { vm.open(BotManagementTarget("worker", 7)) }
        compose.setContent { CompositionLocalProvider(androidx.activity.compose.LocalActivityResultRegistryOwner provides owner) {
            HermesTheme(AppearanceSelection()) {
                val state by vm.state.collectAsState()
                BotAvatarEditor(state, botAvatarActions(vm, state))
            }
        } }
        compose.onNodeWithText("Upload").performClick()
        assertTrue(launchedCode >= 0)
        compose.runOnIdle { registry.dispatchResult(launchedCode, android.app.Activity.RESULT_CANCELED, null) }
        compose.onNodeWithText("Image selection cancelled. Nothing was saved.").assertIsDisplayed()
        assertEquals(0, host.calls.count { it.first == "profiles.set_asset" })
    }
    @Test fun uploadCancelAndExplicitRemovalUseRealViewModelAndReadback() {
        val host = ModelTestHost().apply { answer = { method, _ -> modelReply(if (method == "profiles.set_asset")
            """{"ok":true,"asset":"avatar","size":0,"removed":1}""" else """{"found":false}""") } }
        val vm = BotsAvatarViewModel(host, scope, {})
        var pick: BotAvatarPick? = null
        compose.runOnIdle { vm.open(BotManagementTarget("worker", 7)) }
        compose.setContent { HermesTheme(AppearanceSelection()) {
            val state by vm.state.collectAsState()
            BotAvatarEditor(state, botAvatarActions(vm, state), chooseImage = { pick = it })
        } }
        compose.onNodeWithText("Upload").assertIsEnabled().performClick()
        compose.onNodeWithText("Choosing image…").assertIsDisplayed()
        compose.runOnIdle { vm.finishPick(pick!!, null) }
        compose.onNodeWithText("Image selection cancelled. Nothing was saved.").assertIsDisplayed()
        assertEquals(0, host.calls.count { it.first == "profiles.set_asset" })
        compose.onNodeWithText("Upload").performClick()
        compose.runOnIdle { vm.finishPick(pick!!, avatarPng()) }
        compose.onNodeWithText("Save avatar").assertIsEnabled()
        compose.onNodeWithText("Remove image").performClick()
        compose.onNodeWithText("Save avatar").assertIsNotEnabled()
        assertEquals(0, host.calls.count { it.first == "profiles.set_asset" })
    }
}
