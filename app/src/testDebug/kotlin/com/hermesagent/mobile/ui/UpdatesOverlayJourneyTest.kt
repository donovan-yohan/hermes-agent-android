package com.hermesagent.mobile.ui

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.hermesagent.mobile.data.gateway.GatewayConnectionState
import com.hermesagent.mobile.data.gateway.GatewayConnectionStatus
import com.hermesagent.mobile.data.gateway.GatewayStatusSummary
import com.hermesagent.mobile.data.updates.GatewayUpdateStage
import com.hermesagent.mobile.data.updates.GatewayUpdateState
import com.hermesagent.mobile.data.updates.GatewayUpdateStatusKey
import com.hermesagent.mobile.data.updates.buildCommitChangelog
import com.hermesagent.mobile.ui.chat.ChatUiState
import com.hermesagent.mobile.ui.gateway.GatewaySettingsUiState
import com.hermesagent.mobile.ui.relay.RelayUiState
import com.hermesagent.mobile.ui.ssh.SshUiState
import com.hermesagent.mobile.ui.system.UPDATES_APPLY_TAG
import com.hermesagent.mobile.ui.system.UPDATES_CLOSE_TAG
import com.hermesagent.mobile.ui.system.UPDATES_LATER_TAG
import com.hermesagent.mobile.ui.system.UPDATES_LOG_TAG
import com.hermesagent.mobile.ui.system.UPDATES_STATUS_TAG
import com.hermesagent.mobile.ui.system.UPDATES_TRY_AGAIN_TAG
import com.hermesagent.mobile.ui.system.SystemActions
import com.hermesagent.mobile.ui.system.SystemCopy
import com.hermesagent.mobile.ui.system.SystemUiState
import com.hermesagent.mobile.ui.system.UpdateCheckState
import com.hermesagent.mobile.ui.theme.AppearanceSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Every branch of the updates sheet renders its own verbatim Desktop copy, and
 * an apply in flight owns the sheet.
 *
 * The copy assertions are the point: this is the surface where inventing a
 * sentence would be easiest and worst, because the person reading it is
 * deciding whether to change software on a machine they cannot see.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UpdatesOverlayJourneyTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var backDispatcher: OnBackPressedDispatcher
    private var closes = 0

    @Test
    fun `a check in flight says it is looking`() {
        launch(SystemUiState(status = STATUS, sheetOpen = true, checking = true, check = null))

        compose.onNodeWithText(SystemCopy.CHECKING).assertIsDisplayed()
    }

    @Test
    fun `a check that never completed offers Try again`() {
        launch(SystemUiState(status = STATUS, sheetOpen = true, checking = false, check = null))

        compose.onNodeWithText(SystemCopy.CHECK_FAILED_TITLE).assertIsDisplayed()
        compose.onNodeWithTag(UPDATES_TRY_AGAIN_TAG).assertIsDisplayed()
        compose.onNodeWithText(SystemCopy.TRY_AGAIN).assertIsDisplayed()
    }

    @Test
    fun `a host that cannot update itself says so in the host's own words`() {
        launch(
            SystemUiState(
                status = STATUS,
                sheetOpen = true,
                check = check(supported = false, message = "Hermes is managed by Termux APT."),
            ),
        )

        compose.onNodeWithText(SystemCopy.NOT_AVAILABLE_TITLE).assertIsDisplayed()
        compose.onNodeWithText("Hermes is managed by Termux APT.").assertIsDisplayed()
    }

    @Test
    fun `a host that cannot update itself and says nothing falls back to Desktop's sentence`() {
        launch(SystemUiState(status = STATUS, sheetOpen = true, check = check(supported = false)))

        compose.onNodeWithText(SystemCopy.UNSUPPORTED_MESSAGE).assertIsDisplayed()
    }

    @Test
    fun `a failed check offers the connection sentence and Try again`() {
        launch(SystemUiState(status = STATUS, sheetOpen = true, check = check(failed = true)))

        compose.onNodeWithText(SystemCopy.CHECK_FAILED_TITLE).assertIsDisplayed()
        compose.onNodeWithText(SystemCopy.CONNECTION_RETRY).assertIsDisplayed()
        compose.onNodeWithTag(UPDATES_TRY_AGAIN_TAG).assertIsDisplayed()
    }

    @Test
    fun `an up-to-date backend is the all-set sheet`() {
        launch(
            SystemUiState(
                status = STATUS,
                sheetOpen = true,
                check = check(updateAvailable = false, behind = 0, commits = emptyList()),
            ),
        )

        compose.onNodeWithText(SystemCopy.ALL_SET_TITLE).assertIsDisplayed()
        compose.onNodeWithText(SystemCopy.LATEST_BODY_BACKEND).assertIsDisplayed()
    }

    @Test
    fun `an available update shows its grouped changelog, both buttons and the overflow line`() {
        launch(
            SystemUiState(
                status = STATUS,
                sheetOpen = true,
                check = check(
                    updateAvailable = true,
                    behind = 9,
                    commits = listOf("feat: a new thing", "fix: an old thing"),
                ),
            ),
        )

        compose.onNodeWithText(SystemCopy.AVAILABLE_TITLE_BACKEND).assertIsDisplayed()
        compose.onNodeWithText(SystemCopy.AVAILABLE_BODY_BACKEND).assertIsDisplayed()
        // Desktop's own group labels, uppercased by the section label.
        compose.onNodeWithText("WHAT'S NEW").assertIsDisplayed()
        compose.onNodeWithText("FIXED").assertIsDisplayed()
        compose.onNodeWithText("A new thing").assertIsDisplayed()
        compose.onNodeWithText("An old thing").assertIsDisplayed()
        // Update now above Maybe later, Desktop's order (`updates-overlay.tsx:269-276`).
        compose.onNodeWithTag(UPDATES_APPLY_TAG).assertIsDisplayed()
        compose.onNodeWithTag(UPDATES_LATER_TAG).assertIsDisplayed()
        compose.onNodeWithText(SystemCopy.UPDATE_NOW).assertIsDisplayed()
        compose.onNodeWithText(SystemCopy.MAYBE_LATER).assertIsDisplayed()
        compose.onNodeWithText("+ 7 more changes included.").assertIsDisplayed()
    }

    @Test
    fun `an install type with no release notes says that instead of filler`() {
        launch(
            SystemUiState(
                status = STATUS,
                sheetOpen = true,
                check = check(updateAvailable = true, behind = 1, commits = emptyList()),
            ),
        )

        compose.onNodeWithText(SystemCopy.AVAILABLE_BODY_NO_CHANGELOG).assertIsDisplayed()
    }

    @Test
    fun `an apply in flight shows the stage, the body, the latest line and the log tail`() {
        launch(
            SystemUiState(
                status = STATUS,
                sheetOpen = true,
                check = check(updateAvailable = true, behind = 1, commits = listOf("feat: a thing")),
                apply = GatewayUpdateState(
                    applying = true,
                    stage = GatewayUpdateStage.Pull,
                    status = GatewayUpdateStatusKey.Pulling,
                    log = listOf("one", "two", "three"),
                ),
            ),
        )

        compose.onNodeWithText(SystemCopy.stageTitle(GatewayUpdateStage.Pull)).assertIsDisplayed()
        compose.onNodeWithText(SystemCopy.APPLYING_BODY_BACKEND).assertIsDisplayed()
        compose.onNodeWithTag(UPDATES_STATUS_TAG).assertIsDisplayed()
        compose.onNodeWithTag(UPDATES_LOG_TAG).assertIsDisplayed()
        // Desktop's Electron-only footer must not appear on a phone.
        assertEquals(0, compose.countWithText("This window will close while the update runs"))
    }

    @Test
    fun `an apply in flight offers nothing that closes the sheet`() {
        launch(
            SystemUiState(
                status = STATUS,
                sheetOpen = true,
                apply = GatewayUpdateState(
                    applying = true,
                    stage = GatewayUpdateStage.Restart,
                    status = GatewayUpdateStatusKey.Restarting,
                ),
            ),
        )

        compose.onNodeWithText(SystemCopy.stageTitle(GatewayUpdateStage.Restart)).assertIsDisplayed()
        // Desktop hides the close affordance while applying
        // (`updates-overlay.tsx:112`), so none of the three ways out is drawn.
        assertEquals(0, compose.countWithText(SystemCopy.MAYBE_LATER))
        assertEquals(0, compose.countWithText(SystemCopy.DONE))
        assertEquals(0, compose.countWithText(SystemCopy.TRY_AGAIN))
        // Nothing on screen asked to close it, either.
        assertEquals(0, closes)
    }

    /**
     * The gesture half of the same rule.
     *
     * The sheet is a dialog with its own window, so on a device the back
     * gesture reaches its `BackHandler` and never the shell's. A Robolectric
     * `createComposeRule` has no second window to press back in, so what is
     * checkable here is the guard the shell owns: an apply in flight refuses
     * the dismiss regardless of who asks. `SystemViewModelTest` pins the other
     * half — `closeUpdates()` is a no-op while `applyLocked`.
     */
    @Test
    fun `leaving the panel does not close the apply's report`() {
        launch(
            SystemUiState(
                status = STATUS,
                sheetOpen = true,
                apply = GatewayUpdateState(
                    applying = true,
                    stage = GatewayUpdateStage.Restart,
                    status = GatewayUpdateStatusKey.Restarting,
                ),
            ),
        )

        backDispatcher.onBackPressed()
        compose.waitForIdle()

        assertEquals("nothing may quietly abandon the report of a running apply", 0, closes)
    }

    @Test
    fun `a refusal shows the host's message and command with a way out`() {
        launch(
            SystemUiState(
                status = STATUS,
                sheetOpen = true,
                apply = GatewayUpdateState(
                    applying = false,
                    stage = GatewayUpdateStage.Manual,
                    status = GatewayUpdateStatusKey.NotAvailable,
                    message = "Hermes is managed by Termux APT.",
                    command = "pkg upgrade hermes-agent",
                ),
            ),
        )

        compose.onNodeWithText(SystemCopy.stageTitle(GatewayUpdateStage.Manual)).assertIsDisplayed()
        compose.onNodeWithText("Hermes is managed by Termux APT.").assertIsDisplayed()
        compose.onNodeWithText("pkg upgrade hermes-agent").assertIsDisplayed()

        compose.onNodeWithTag(UPDATES_CLOSE_TAG).performClick()
        assertTrue(closes > 0)
    }

    @Test
    fun `a backend that never came back says so rather than claiming failure`() {
        launch(
            SystemUiState(
                status = STATUS,
                sheetOpen = true,
                apply = GatewayUpdateState(
                    applying = false,
                    stage = GatewayUpdateStage.Error,
                    status = GatewayUpdateStatusKey.NoReturn,
                ),
            ),
        )

        // Desktop's `errorTitle`, not the `error` stage label — the stage
        // labels belong to a running apply (`updates-overlay.tsx:533-555`).
        compose.onNodeWithText(SystemCopy.ERROR_TITLE).assertIsDisplayed()
        compose.onNodeWithText(SystemCopy.NO_RETURN).assertIsDisplayed()
        // Try again above Not now, Desktop's own order and its own two labels.
        compose.onNodeWithTag(UPDATES_APPLY_TAG).assertIsDisplayed()
        compose.onNodeWithText(SystemCopy.TRY_AGAIN).assertIsDisplayed()
        compose.onNodeWithTag(UPDATES_CLOSE_TAG).assertIsDisplayed()
        compose.onNodeWithText(SystemCopy.NOT_NOW).assertIsDisplayed()
    }

    @Test
    fun `a check that reports a positive behind count still offers the update`() {
        // Desktop's own test is the flag *or* the count (`:69-70`).
        launch(
            SystemUiState(
                status = STATUS,
                sheetOpen = true,
                check = check(updateAvailable = false, behind = 3, commits = listOf("feat: a thing")),
            ),
        )

        compose.onNodeWithText(SystemCopy.AVAILABLE_TITLE_BACKEND).assertIsDisplayed()
        assertEquals(0, compose.countWithText(SystemCopy.ALL_SET_TITLE))
    }

    @Test
    fun `a failed apply says exactly that`() {
        launch(
            SystemUiState(
                status = STATUS,
                sheetOpen = true,
                apply = GatewayUpdateState(
                    applying = false,
                    stage = GatewayUpdateStage.Error,
                    status = GatewayUpdateStatusKey.Failed,
                ),
            ),
        )

        compose.onNodeWithText(SystemCopy.applyStatus(GatewayUpdateStatusKey.Failed)).assertIsDisplayed()
    }

    @Test
    fun `an applying view shows the latest log line where the status line would be`() {
        // Desktop overloads one field for both (`store/updates.ts:571-590`),
        // so the newest log line replaces the status sentence rather than
        // stacking under it.
        launch(
            SystemUiState(
                status = STATUS,
                sheetOpen = true,
                apply = GatewayUpdateState(
                    applying = true,
                    stage = GatewayUpdateStage.Pull,
                    status = GatewayUpdateStatusKey.Pulling,
                    log = listOf("Resolving deltas", "Checking out files"),
                ),
            ),
        )

        compose.onNodeWithText("Checking out files").assertIsDisplayed()
        assertEquals(0, compose.countWithText(SystemCopy.applyStatus(GatewayUpdateStatusKey.Pulling)))
    }

    @Test
    fun `a finished apply says so and closes on the person's own tap`() {
        launch(
            SystemUiState(
                status = STATUS,
                sheetOpen = true,
                apply = GatewayUpdateState(applying = false, stage = GatewayUpdateStage.Done),
            ),
        )

        compose.onNodeWithText(SystemCopy.stageTitle(GatewayUpdateStage.Done)).assertIsDisplayed()
        compose.onNodeWithTag(UPDATES_CLOSE_TAG).performClick()
        assertTrue(closes > 0)
    }

    private fun check(
        supported: Boolean = true,
        message: String? = null,
        updateAvailable: Boolean = true,
        behind: Int = 1,
        commits: List<String> = listOf("feat: a thing"),
        failed: Boolean = false,
    ): UpdateCheckState {
        val groups = buildCommitChangelog(commits)
        val shown = groups.sumOf { it.items.size }
        return UpdateCheckState(
            supported = supported,
            message = message,
            updateAvailable = updateAvailable,
            behind = behind,
            changelog = groups,
            moreChanges = (behind - shown).coerceAtLeast(0),
            failed = failed,
        )
    }

    private fun launch(state: SystemUiState) {
        compose.setContent {
            val dispatcher = requireNotNull(
                LocalOnBackPressedDispatcherOwner.current,
            ).onBackPressedDispatcher
            SideEffect { backDispatcher = dispatcher }
            HermesApp(
                chatState = ChatUiState(),
                gatewayState = GatewaySettingsUiState(
                    connection = GatewayConnectionState(GatewayConnectionStatus.Connected),
                ),
                sshState = SshUiState(),
                appearance = AppearanceSelection(),
                chatActions = ChatActions(),
                appearanceActions = AppearanceActions(),
                gatewayActions = GatewayActions(),
                sshActions = SshActions(),
                relayState = RelayUiState(),
                relayActions = RelayActions(),
                systemState = state,
                systemActions = SystemActions(onCloseUpdates = { closes += 1 }),
            )
        }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Open settings").performClick()
        compose.onNodeWithTag(SYSTEM_ROW).performClick()
        compose.waitForIdle()
    }

    private companion object {
        const val SYSTEM_ROW = "settings-row-system panel"

        val STATUS = GatewayStatusSummary(
            version = "0.5.1",
            activeSessions = 0L,
            gatewayRunning = true,
            canUpdateHermes = true,
        )
    }
}

private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.countWithText(text: String): Int =
    onAllNodes(androidx.compose.ui.test.hasText(text, substring = true)).fetchSemanticsNodes().size
