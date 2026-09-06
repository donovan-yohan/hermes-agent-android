package com.hermesagent.mobile.ui

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.hermesagent.mobile.data.gateway.GatewayConnectionState
import com.hermesagent.mobile.data.gateway.SignInOrigin
import com.hermesagent.mobile.plugins.Contribution
import com.hermesagent.mobile.plugins.ContributionRegistry
import com.hermesagent.mobile.plugins.PluginAreas
import com.hermesagent.mobile.plugins.relay.CHANNEL_LIST_TAG
import com.hermesagent.mobile.plugins.relay.COMPOSER_FIELD_TAG
import com.hermesagent.mobile.plugins.relay.NOTICE_TAG
import com.hermesagent.mobile.plugins.relay.RELAY_UNAVAILABLE_ON_GATEWAY_MESSAGE
import com.hermesagent.mobile.plugins.relay.RelayActions
import com.hermesagent.mobile.plugins.relay.RelayAvailability
import com.hermesagent.mobile.plugins.relay.RelayAvailabilityController
import com.hermesagent.mobile.plugins.relay.RelayAvailabilityState
import com.hermesagent.mobile.plugins.relay.RelayChannel
import com.hermesagent.mobile.plugins.relay.RelayChannelRow
import com.hermesagent.mobile.plugins.relay.RelayChannelsStatus
import com.hermesagent.mobile.plugins.relay.RelayComposerUiState
import com.hermesagent.mobile.plugins.relay.RelayCredentialRefresher
import com.hermesagent.mobile.plugins.relay.RelayLaneState
import com.hermesagent.mobile.plugins.relay.RelayMessage
import com.hermesagent.mobile.plugins.relay.RelayMessageFormat
import com.hermesagent.mobile.plugins.relay.RelayScreen
import com.hermesagent.mobile.plugins.relay.RelaySenderKind
import com.hermesagent.mobile.plugins.relay.RelaySignInReason
import com.hermesagent.mobile.plugins.relay.RelayTimeLabels
import com.hermesagent.mobile.plugins.relay.RelayTranscriptRow
import com.hermesagent.mobile.plugins.relay.RelayUiState
import com.hermesagent.mobile.plugins.relay.SEND_TAG
import com.hermesagent.mobile.plugins.relay.STALE_TAG
import com.hermesagent.mobile.plugins.relay.TRANSCRIPT_TAG
import com.hermesagent.mobile.plugins.relay.TRANSPORT_DOWN_MESSAGE
import com.hermesagent.mobile.plugins.relay.relayChannelRows
import com.hermesagent.mobile.plugins.relay.relayNotice
import com.hermesagent.mobile.plugins.relay.relayTranscriptRows
import com.hermesagent.mobile.ui.AppearanceActions
import com.hermesagent.mobile.ui.ChatActions
import com.hermesagent.mobile.ui.GatewayActions
import com.hermesagent.mobile.ui.HermesApp
import com.hermesagent.mobile.ui.HermesDestination
import com.hermesagent.mobile.ui.LocalPluginNavigation
import com.hermesagent.mobile.ui.SshActions
import com.hermesagent.mobile.ui.chat.ChatUiState
import com.hermesagent.mobile.ui.common.WIP_SPOKEN
import com.hermesagent.mobile.ui.gateway.GatewaySettingsUiState
import com.hermesagent.mobile.ui.settings.SettingsRow
import com.hermesagent.mobile.ui.ssh.SshUiState
import com.hermesagent.mobile.ui.theme.AppearanceSelection
import com.hermesagent.mobile.ui.theme.HermesSpacing
import com.hermesagent.mobile.ui.theme.HermesTheme
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
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
    private var screenState by mutableStateOf(RelayUiState())
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
    fun `a fresh install with no Gateway offers the Gateways screen, not a dead retry`() {
        launch(RelayUiState(notice = relayNotice(settledAvailability(gatewaySaved = false))))
        openRelay()

        compose.onNodeWithText("Connect to a Gateway to open Relay.").assertIsDisplayed()
        // The state #80 found on the device: a reconnect sentence about a
        // Gateway that was never configured, over a retry with nothing to ask.
        assertTrue(displayed(TRANSPORT_DOWN_MESSAGE).isEmpty())
        assertTrue(displayed("Try again").isEmpty())
        assertTrue(compose.onAllNodesWithTag(CHANNEL_LIST_TAG).fetchSemanticsNodes().isEmpty())

        compose.onNodeWithText("Open Gateways").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Gateways").assertIsDisplayed()
    }

    @Test
    fun `a saved Gateway that is down keeps the reconnect sentence and its retry`() {
        launch(RelayUiState(notice = relayNotice(settledAvailability(gatewaySaved = true))))
        openRelay()

        compose.onNodeWithText(TRANSPORT_DOWN_MESSAGE).assertIsDisplayed()
        // There is a Gateway and it is already the right one, so the next step
        // is asking it again rather than going back to set one up.
        assertTrue(displayed("Open Gateways").isEmpty())

        compose.onNodeWithText("Try again").performClick()
        assertEquals(1, retries)
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
    fun `a cold start on an offline lane shows the state, never a spinner`() =
        assertColdStartIsSilent(RelayLaneState.OFFLINE, "Relay is offline")

    @Test
    fun `a cold start on an unauthorized lane shows the state, never a spinner`() =
        assertColdStartIsSilent(RelayLaneState.AUTH_REQUIRED, "Authorization required")

    @Test
    fun `a cold start on an errored lane shows the state, never a spinner`() =
        assertColdStartIsSilent(RelayLaneState.ERROR, "Relay needs attention")

    /**
     * Desktop's `auth_required` banner carries a title, a body and an
     * `Authorize Relay` button (`desktop/plugin.js:384-388,438-446` @
     * `563a8c8`, the SHA `docs/parity/relay-channels-surface.md` pins the
     * plugin at). This app renders the first two and cannot perform the third,
     * so the button ships visible, dimmed and marked rather than absent (#101);
     * #38 owns the write path that lights it.
     */
    @Test
    fun `an unauthorized lane offers Desktop's authorize action, marked and inert`() {
        launchScreen(
            RelayUiState(
                relayAnswered = true,
                relayReady = false,
                notice = relayNotice(
                    RelayAvailabilityState(
                        RelayAvailability.Available(
                            RelayChannelsStatus(RelayLaneState.AUTH_REQUIRED, LANE_DETAIL, guidance = null),
                        ),
                    ),
                ),
            ),
        )

        val spoken = "Authorize Relay. $WIP_SPOKEN"
        // The whole name, once: the finder matches the description list with
        // `any { }`, so a control that also named itself would announce twice
        // and still be found here.
        compose.onNodeWithContentDescription(spoken)
            .assertIsDisplayed()
            .assertIsNotEnabled()
            .assertContentDescriptionEquals(spoken)
        // Retry is the wrong next step for a grant the host has to make.
        assertTrue(displayed("Try again").isEmpty())
    }

    /**
     * Relay answered, so `relayAnswered` is true — but the ViewModel polls only
     * a ready lane, so nothing has been asked and nothing ever will be until
     * the lane changes. A "Loading channels…" pane here is a spinner with no
     * request behind it, and it never resolves.
     */
    private fun assertColdStartIsSilent(laneState: RelayLaneState, headline: String) {
        launch(
            RelayUiState(
                relayAnswered = true,
                relayReady = false,
                notice = relayNotice(
                    RelayAvailabilityState(
                        RelayAvailability.Available(
                            RelayChannelsStatus(laneState, LANE_DETAIL, guidance = null),
                        ),
                    ),
                ),
            ),
        )
        openRelay()

        compose.onNodeWithTag(NOTICE_TAG).assertIsDisplayed()
        compose.onNodeWithText(headline).assertIsDisplayed()
        // Relay's own words sit beside this app's sentence, never instead of it.
        compose.onNodeWithText(LANE_DETAIL).assertIsDisplayed()

        assertTrue(
            compose.onAllNodesWithText("Loading channels…").fetchSemanticsNodes().isEmpty(),
        )
        assertTrue(compose.onAllNodesWithTag(CHANNEL_LIST_TAG).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun `a duplicate row id from the wire renders instead of crashing the list`() {
        // A repeated id is a hub contract breach, but a keyed LazyColumn turns
        // it into an IllegalArgumentException, so the projections drop it.
        val times = RelayTimeLabels(ZoneId.of("UTC"), Locale.UK, NOW)
        val channels = relayChannelRows(
            listOf(wireChannel("dup", "product"), wireChannel("dup", "product again")),
            Locale.UK,
            times,
        )
        val transcript = relayTranscriptRows(
            listOf(wireMessage("dup", seq = 1), wireMessage("dup", seq = 2)),
            Locale.UK,
            times,
        )
        assertEquals(1, channels.size)
        assertEquals(1, transcript.size)

        launchScreen(
            RelayUiState(
                channels = channels,
                channelsLoaded = true,
                relayAnswered = true,
                relayReady = true,
            ),
        )
        compose.onNodeWithTag(CHANNEL_LIST_TAG).assertIsDisplayed()
        compose.onNodeWithText("product").assertIsDisplayed()

        screenState = transcriptState(transcript)
        compose.waitForIdle()
        compose.onNodeWithTag(TRANSCRIPT_TAG).assertIsDisplayed()
        compose.onNodeWithText("message 1").assertIsDisplayed()
    }

    // ── Tail follow ────────────────────────────────────────────────────────
    // The rule is ChatScreen's, adopted here because a three-second poll that
    // yanks a reader to the bottom is worse than a transcript that waits.

    @Test
    fun `opening a channel lands on the newest message, not the top of the window`() {
        launchScreen(transcriptState(longTranscript(40)))

        compose.onNodeWithText("Message 40 of the window.").assertIsDisplayed()
    }

    @Test
    fun `a poll that returns more follows a reader who is still at the tail`() {
        launchScreen(transcriptState(longTranscript(40)))
        compose.onNodeWithText("Message 40 of the window.").assertIsDisplayed()

        screenState = transcriptState(longTranscript(60))
        compose.waitForIdle()

        compose.onNodeWithText("Message 60 of the window.").assertIsDisplayed()
    }

    @Test
    fun `a poll that returns more does not yank a reader who scrolled back`() {
        launchScreen(transcriptState(longTranscript(40)))
        compose.onNodeWithText("Message 40 of the window.").assertIsDisplayed()

        // A real backward gesture is what disarms following — a programmatic
        // jump is not the reader deciding to read something further up.
        scrollBack()
        assertTrue(displayed("Message 40 of the window.").isEmpty())

        screenState = transcriptState(longTranscript(60))
        compose.waitForIdle()

        // Three seconds later Relay returned twenty more rows. The reader did
        // not move: neither the old tail nor the new one is on screen.
        assertTrue(displayed("Message 40 of the window.").isEmpty())
        assertTrue(displayed("Message 60 of the window.").isEmpty())

        // Reaching the bottom again re-arms following, so the next window does
        // land on screen. Scrolling up is deliberate; so is coming back.
        compose.onNodeWithTag(TRANSCRIPT_TAG).performScrollToIndex(59)
        compose.waitForIdle()

        screenState = transcriptState(longTranscript(80))
        compose.waitForIdle()
        compose.onNodeWithText("Message 80 of the window.").assertIsDisplayed()
    }

    @Test
    fun `switching channels opens the new transcript at its own newest message`() {
        launchScreen(transcriptState(longTranscript(40)))
        scrollBack()
        assertTrue(displayed("Message 40 of the window.").isEmpty())

        // A different channel is not a reading position to preserve.
        screenState = transcriptState(longTranscript(30)).copy(
            selectedChannelId = "c2",
            selectedChannelTitle = "launch-notes",
        )
        compose.waitForIdle()

        compose.onNodeWithText("Message 30 of the window.").assertIsDisplayed()
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
                relayReady = true,
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

    @Test
    fun `the composer belongs to a transcript and never to the channel list`() {
        launch(channelsState())
        openRelay()

        // Nothing to write to yet: a channel list is a place to pick, not to type.
        assertTrue(compose.onAllNodesWithTag(COMPOSER_FIELD_TAG).fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodesWithTag(SEND_TAG).fetchSemanticsNodes().isEmpty())

        compose.onNodeWithTag("Relay channel c1").performClick()
        compose.onNodeWithTag(COMPOSER_FIELD_TAG).assertIsDisplayed()
        compose.onNodeWithTag(SEND_TAG).assertHeightIsAtLeast(HermesSpacing().touchTarget)

        // Back out and it is gone again with the transcript it belonged to.
        compose.onNodeWithContentDescription("Back to channels").performClick()
        assertTrue(compose.onAllNodesWithTag(SEND_TAG).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun `a state that can never produce a channel shows no composer to type into`() {
        launch(
            RelayUiState(
                notice = relayNotice(
                    RelayAvailabilityState(
                        RelayAvailability.SignInRequired(RelaySignInReason.NoCredential),
                        signInAvailable = true,
                    ),
                ),
                selectedChannelId = "c1",
                selectedChannelTitle = "product",
                composer = RelayComposerUiState(editable = true),
            ),
        )
        openRelay()

        // The notice sends the person to Gateways; a composer under it would be
        // offering to send through a connection that does not exist.
        assertTrue(compose.onAllNodesWithTag(COMPOSER_FIELD_TAG).fetchSemanticsNodes().isEmpty())
    }

    /**
     * The state the real controller settles on for a Gateway that is not
     * connected, rather than one this test typed out.
     *
     * #80 reached a device because every notice journey started from a
     * hand-built [RelayAvailabilityState]: the copy was right and the mapping
     * that produces it was not. Driving the controller is what binds these two
     * journeys to the code the phone runs.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun settledAvailability(gatewaySaved: Boolean): RelayAvailabilityState {
        lateinit var settled: RelayAvailabilityState
        runTest {
            val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
            val controller = RelayAvailabilityController(
                scope = scope,
                probe = { error("a Gateway that is not connected is never probed") },
                // The status a cold start actually seeds, saved or not.
                connection = MutableStateFlow(GatewayConnectionState()),
                configured = MutableStateFlow(gatewaySaved),
                credentials = object : RelayCredentialRefresher {
                    override suspend fun refreshOnce() = false
                    override suspend fun signInAvailable() = false
                },
            )
            try {
                advanceUntilIdle()
                // What opening the surface does, through `surfaceResumed`.
                controller.refresh()
                advanceUntilIdle()
                settled = controller.state.value
            } finally {
                scope.cancel()
            }
        }
        return settled
    }

    /** Read something further up, the way a person does it. */
    private fun scrollBack() {
        compose.onNodeWithTag(TRANSCRIPT_TAG).performTouchInput { swipeDown() }
        compose.waitForIdle()
    }

    /**
     * A lazy list composes only what is on screen, so "not in the tree" is how
     * "not on screen" is asserted for a row the reader has scrolled away from.
     */
    private fun displayed(text: String) =
        compose.onAllNodesWithText(text).fetchSemanticsNodes()

    /** The Relay surface on its own, for the journeys that push state at it. */
    private fun launchScreen(initial: RelayUiState) {
        screenState = initial
        compose.setContent {
            HermesTheme(AppearanceSelection()) {
                RelayScreen(
                    state = screenState,
                    actions = RelayActions(),
                    onLeave = {},
                    onOpenGateways = {},
                )
            }
        }
        compose.waitForIdle()
    }

    private fun transcriptState(rows: List<RelayTranscriptRow>) = RelayUiState(
        selectedChannelId = "c1",
        selectedChannelTitle = "product",
        transcript = rows,
        transcriptLoaded = true,
        relayAnswered = true,
        relayReady = true,
    )

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
            val registry = remember {
                ContributionRegistry().apply {
                    registerMany(
                        listOf(
                            Contribution(
                                id = "hermes-plugin-relay:route",
                                area = PluginAreas.ROUTES_AREA,
                                source = "plugin:hermes-plugin-relay",
                                title = "Relay channels",
                                render = {
                                    val nav = LocalPluginNavigation.current
                                    RelayScreen(
                                        state = state,
                                        actions = RelayActions(
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
                                        onLeave = nav.onBack,
                                        onOpenGateways = { nav.onOpenGateways(SignInOrigin.Gateways) },
                                    )
                                },
                            ),
                            Contribution(
                                id = "hermes-plugin-relay:sidebar-nav",
                                area = PluginAreas.SIDEBAR_NAV_AREA,
                                source = "plugin:hermes-plugin-relay",
                                title = "Relay channels",
                                order = 300,
                                render = {
                                    val nav = LocalPluginNavigation.current
                                    val relayAvailable = !state.unavailableOnGateway
                                    SettingsRow(
                                        label = "Relay channels",
                                        description = if (relayAvailable) {
                                            "Channels, transcripts, and messaging live in their own workspace."
                                        } else {
                                            RELAY_UNAVAILABLE_ON_GATEWAY_MESSAGE
                                        },
                                        traversalIndex = 3f,
                                        enabled = relayAvailable,
                                        onClick = { nav.onNavigate("hermes-plugin-relay:route") },
                                    )
                                },
                            ),
                        ),
                    )
                }
            }
            HermesApp(
                chatState = ChatUiState(),
                gatewayState = GatewaySettingsUiState(),
                sshState = SshUiState(),
                appearance = AppearanceSelection(),
                chatActions = ChatActions(),
                appearanceActions = AppearanceActions(),
                gatewayActions = GatewayActions(),
                sshActions = SshActions(),
                pluginRegistry = registry,
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

        /** A lane sentence Relay wrote, with nothing in it to redact. */
        const val LANE_DETAIL = "The hub closed the link."

        /** 2026-08-26T12:00:00Z. Fixed, because these rows carry timestamps. */
        const val NOW = 1_787_745_600_000L

        /** Long enough to overflow a phone viewport several times over. */
        fun longTranscript(count: Int) = (1..count).map { index ->
            RelayTranscriptRow(
                id = "m$index",
                attribution = "Ada",
                senderKind = RelaySenderKind.Human,
                text = "Message $index of the window.",
                timestamp = "09:14",
                status = "Delivered",
                truncated = false,
                description = "Ada. 09:14. Message $index of the window. Delivered.",
            )
        }

        fun wireChannel(id: String, title: String) = RelayChannel(
            id = id,
            title = title,
            kind = null,
            visibility = null,
            archived = false,
            latestSeq = null,
            messageCount = null,
            threadCount = null,
            lastMessage = null,
        )

        fun wireMessage(id: String, seq: Long) = RelayMessage(
            id = id,
            channelId = "c1",
            seq = seq,
            kind = "message",
            status = "delivered",
            senderKind = "human",
            senderId = "s-$id",
            senderDisplayName = null,
            text = "message $seq",
            format = RelayMessageFormat.TEXT,
            threadId = null,
            parentMessageId = null,
            createdAt = "2026-08-26T09:14:00Z",
            updatedAt = "2026-08-26T09:14:00Z",
            truncated = null,
            clientMessageId = null,
        )

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
            relayReady = true,
        )
    }
}
