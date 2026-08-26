package com.hermesagent.mobile.ui

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.hermesagent.mobile.data.relay.RELAY_UNAVAILABLE_ON_GATEWAY_MESSAGE
import com.hermesagent.mobile.data.relay.RelayAvailability
import com.hermesagent.mobile.data.relay.RelayAvailabilityState
import com.hermesagent.mobile.data.relay.RelayChannelsStatus
import com.hermesagent.mobile.data.relay.RelayLaneState
import com.hermesagent.mobile.data.relay.RelaySignInReason
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.hermesagent.mobile.ui.chat.ChatUiState
import com.hermesagent.mobile.ui.gateway.GatewaySettingsUiState
import com.hermesagent.mobile.ui.relay.CHANNEL_LIST_TAG
import com.hermesagent.mobile.ui.relay.NOTICE_TAG
import com.hermesagent.mobile.ui.relay.RelayChannelRow
import com.hermesagent.mobile.ui.relay.RelayScreen
import com.hermesagent.mobile.ui.relay.RelaySenderKind
import com.hermesagent.mobile.ui.relay.RelayTranscriptRow
import com.hermesagent.mobile.ui.relay.RelayUiState
import com.hermesagent.mobile.ui.relay.STALE_TAG
import com.hermesagent.mobile.ui.relay.TRANSCRIPT_TAG
import com.hermesagent.mobile.ui.relay.relayNotice
import com.hermesagent.mobile.ui.ssh.SshUiState
import com.hermesagent.mobile.ui.theme.AppearanceSelection
import com.hermesagent.mobile.ui.theme.HermesTheme
import com.hermesagent.mobile.ui.theme.HermesSpacing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Relay read path as a person walks it: entry point, channels, one
 * transcript, and back out — plus the states where none of that is honest.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RelayJourneyTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var backDispatcher: OnBackPressedDispatcher
    private var resumes = 0
    private var pauses = 0
    private var retries = 0

    @Test
    fun `settings opens Relay and the channel list renders what Relay returned`() {
        launch(channelsState())
        openRelay()

        compose.onNodeWithText("Relay channels").assertIsDisplayed()
        compose.onNodeWithTag(CHANNEL_LIST_TAG).assertIsDisplayed()
        compose.onNodeWithText("product").assertIsDisplayed()
        // Archived stays in place with an annotation, never hidden or re-sorted.
        compose.onNodeWithText("launch-notes · archived").assertIsDisplayed()
        compose.onNodeWithText("Ada: parity is green").assertIsDisplayed()
        compose.onNodeWithText("Channel · Public").assertIsDisplayed()
    }

    @Test
    fun `a channel row is one 48dp target with one spoken label`() {
        launch(channelsState())
        openRelay()

        compose.onNodeWithTag("Relay channel c1")
            .assertHeightIsAtLeast(HermesSpacing().touchTarget)
        compose.onNodeWithContentDescription(
            "product. Channel · Public. Ada: parity is green.",
        ).assertIsDisplayed()
    }

    @Test
    fun `selecting a channel opens its transcript and back returns to the list`() {
        launch(channelsState())
        openRelay()

        compose.onNodeWithTag("Relay channel c1").performClick()
        compose.onNodeWithTag(TRANSCRIPT_TAG).assertIsDisplayed()
        compose.onNodeWithText("Yes — the parity gate is green.").assertIsDisplayed()
        // The header becomes the channel, and its back affordance means the
        // pane you came from rather than the destination you came from.
        compose.onNodeWithText("product").assertIsDisplayed()

        compose.onNodeWithContentDescription("Back to channels").performClick()
        compose.onNodeWithTag(CHANNEL_LIST_TAG).assertIsDisplayed()

        // System back from the list leaves Relay entirely.
        backDispatcher.onBackPressed()
        compose.onNodeWithText("Settings").assertIsDisplayed()
    }

    @Test
    fun `system back inside a transcript returns to the channel list, not out of Relay`() {
        launch(channelsState())
        openRelay()
        compose.onNodeWithTag("Relay channel c1").performClick()
        compose.onNodeWithTag(TRANSCRIPT_TAG).assertIsDisplayed()

        backDispatcher.onBackPressed()
        compose.waitForIdle()

        compose.onNodeWithTag(CHANNEL_LIST_TAG).assertIsDisplayed()
    }

    @Test
    fun `polling starts when the surface appears and stops when it leaves`() {
        launch(channelsState())
        assertEquals(0, resumes)

        openRelay()
        assertEquals(1, resumes)
        assertEquals(0, pauses)

        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitForIdle()
        // Desktop's interval dies with its pane; this one dies with the screen.
        assertEquals(1, pauses)

        compose.onNodeWithTag(RELAY_ROW).performClick()
        compose.waitForIdle()
        assertEquals(2, resumes)
    }

    @Test
    fun `backgrounding the app pauses the poll and returning resumes it`() {
        val owner = TestOwner()
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                HermesTheme(AppearanceSelection()) {
                    RelayScreen(
                        state = channelsState(),
                        actions = RelayActions(
                            onResume = { resumes++ },
                            onPause = { pauses++ },
                        ),
                        onLeave = {},
                        onOpenGateways = {},
                    )
                }
            }
        }

        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        assertEquals(1, resumes)
        assertEquals(0, pauses)

        // Backgrounded: Desktop's pane is gone, and so is the interval.
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.STARTED }
        assertEquals(1, pauses)
        assertEquals(1, resumes)

        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        assertEquals(2, resumes)
    }

    @Test
    fun `a Gateway without the plugin says so where Relay would live`() {
        launch(
            channelsState().copy(
                unavailableOnGateway = true,
                notice = relayNotice(RelayAvailabilityState(RelayAvailability.Missing)),
            ),
        )
        compose.onNodeWithContentDescription("Open settings").performClick()

        compose.onNodeWithContentDescription(
            "Relay channels. $RELAY_UNAVAILABLE_ON_GATEWAY_MESSAGE",
        ).assertIsDisplayed()

        // The row is a statement, not a door, and it never became an error.
        compose.onNodeWithTag(RELAY_ROW).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Settings").assertIsDisplayed()
        assertEquals(0, resumes)
    }

    @Test
    fun `before any Gateway connection Relay says so instead of painting blank`() {
        // The controller holds no availability and no spinner until the first
        // Connected edge; the surface must still be readable in that window.
        launch(RelayUiState(notice = relayNotice(RelayAvailabilityState())))
        openRelay()

        compose.onNodeWithText("Connect to a Gateway to open Relay.").assertIsDisplayed()
        assertTrue(compose.onAllNodesWithTag(CHANNEL_LIST_TAG).fetchSemanticsNodes().isEmpty())

        compose.onNodeWithText("Open Gateways").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Gateways").assertIsDisplayed()
    }

    @Test
    fun `a state that can never produce a list shows no spinner under it`() {
        launch(
            RelayUiState(
                unavailableOnGateway = false,
                notice = relayNotice(RelayAvailabilityState(RelayAvailability.Incompatible)),
            ),
        )
        openRelay()

        compose.onNodeWithTag(NOTICE_TAG).assertIsDisplayed()
        // Nothing is being asked of Relay, so nothing may claim to be loading.
        assertTrue(compose.onAllNodesWithText("Loading channels…").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun `a lapsed credential offers the app's own sign-in path instead of a list`() {
        launch(
            channelsState().copy(
                notice = relayNotice(
                    RelayAvailabilityState(
                        RelayAvailability.SignInRequired(RelaySignInReason.SessionExpired),
                        signInAvailable = true,
                    ),
                ),
            ),
        )
        openRelay()

        compose.onNodeWithTag(NOTICE_TAG).assertIsDisplayed()
        compose.onNodeWithText("Open Gateways").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Gateways").assertIsDisplayed()
    }

    @Test
    fun `an offline lane keeps the rows on screen under a state, not an error`() {
        launch(
            channelsState().copy(
                notice = relayNotice(
                    RelayAvailabilityState(
                        RelayAvailability.Available(
                            RelayChannelsStatus(RelayLaneState.OFFLINE, null, null),
                        ),
                    ),
                ),
                stale = true,
            ),
        )
        openRelay()

        compose.onNodeWithText("Relay is offline").assertIsDisplayed()
        compose.onNodeWithTag(CHANNEL_LIST_TAG).assertIsDisplayed()
        compose.onNodeWithText("product").assertIsDisplayed()
        compose.onNodeWithTag(STALE_TAG).assertIsDisplayed()

        compose.onNodeWithText("Try again").performClick()
        assertEquals(1, retries)
    }

    @Test
    fun `a first load that failed offers a retry and does not claim stale data`() {
        launch(
            RelayUiState(
                relayAnswered = true,
                channelsLoaded = false,
                stale = true,
            ),
        )
        openRelay()

        compose.onNodeWithText("Channels could not be loaded").assertIsDisplayed()
        // There is no previous answer, so nothing may say one is being shown.
        assertTrue(compose.onAllNodesWithTag(STALE_TAG).fetchSemanticsNodes().isEmpty())

        compose.onNodeWithText("Retry channels").performClick()
        assertEquals(1, retries)
    }

    @Test
    fun `the first probe is the only spinner and it hides the list`() {
        launch(
            RelayUiState(
                connecting = true,
                notice = relayNotice(RelayAvailabilityState(availability = null, probing = true)),
            ),
        )
        openRelay()

        compose.onNodeWithText("Connecting to Relay").assertIsDisplayed()
        assertTrue(compose.onAllNodesWithTag(CHANNEL_LIST_TAG).fetchSemanticsNodes().isEmpty())
    }

    private fun openRelay() {
        compose.onNodeWithContentDescription("Open settings").performClick()
        compose.onNodeWithTag(RELAY_ROW).performClick()
        compose.waitForIdle()
    }

    private fun launch(initial: RelayUiState) {
        compose.setContent {
            val dispatcher = requireNotNull(
                LocalOnBackPressedDispatcherOwner.current,
            ).onBackPressedDispatcher
            SideEffect { backDispatcher = dispatcher }
            // Stands in for the ViewModel: selection is state the surface is
            // given, and it is never written anywhere.
            var state by remember { mutableStateOf(initial) }
            HermesApp(
                chatState = ChatUiState(),
                gatewayState = GatewaySettingsUiState(),
                sshState = SshUiState(),
                appearance = AppearanceSelection(),
                chatActions = ChatActions(),
                appearanceActions = AppearanceActions(),
                gatewayActions = GatewayActions(),
                sshActions = SshActions(),
                relayState = state,
                relayActions = RelayActions(
                    onSelectChannel = { id ->
                        state = state.copy(
                            selectedChannelId = id,
                            selectedChannelTitle = state.channels.first { it.id == id }.title,
                            transcript = TRANSCRIPT,
                            transcriptLoaded = true,
                        )
                    },
                    onClearSelection = {
                        state = state.copy(
                            selectedChannelId = null,
                            selectedChannelTitle = null,
                            transcript = emptyList(),
                            transcriptLoaded = false,
                        )
                    },
                    onRetry = { retries++ },
                    onResume = { resumes++ },
                    onPause = { pauses++ },
                ),
            )
        }
        compose.waitForIdle()
    }

    /** A lifecycle this test drives, standing in for the process foreground. */
    private class TestOwner : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }

    private companion object {
        const val RELAY_ROW = "settings-row-relay channels"

        val TRANSCRIPT = listOf(
            RelayTranscriptRow(
                id = "m1",
                attribution = "Ada",
                senderKind = RelaySenderKind.Human,
                text = "Did the parity gate pass?",
                timestamp = "09:12",
                status = "Delivered",
                truncated = false,
                description = "Ada. 09:12. Did the parity gate pass?. Delivered.",
            ),
            RelayTranscriptRow(
                id = "m2",
                attribution = "Hermes",
                senderKind = RelaySenderKind.Agent,
                text = "Yes — the parity gate is green.",
                timestamp = "09:14",
                status = "Delivered",
                truncated = false,
                description = "Hermes. 09:14. Yes — the parity gate is green. Delivered.",
            ),
        )

        fun channelsState() = RelayUiState(
            channels = listOf(
                RelayChannelRow(
                    id = "c1",
                    title = "product",
                    archived = false,
                    classification = "Channel · Public",
                    preview = "Ada: parity is green",
                    timestamp = "09:14",
                    description = "product. Channel · Public. Ada: parity is green.",
                ),
                RelayChannelRow(
                    id = "c2",
                    title = "launch-notes · archived",
                    archived = true,
                    classification = null,
                    preview = null,
                    timestamp = null,
                    description = "launch-notes. Archived.",
                ),
            ),
            channelsLoaded = true,
            relayAnswered = true,
        )
    }
}
