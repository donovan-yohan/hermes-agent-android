package com.hermesagent.mobile.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import com.hermesagent.mobile.data.gateway.ApprovalMode
import com.hermesagent.mobile.data.gateway.GatewayConnectionState
import com.hermesagent.mobile.data.gateway.GatewayConnectionStatus
import com.hermesagent.mobile.data.session.ContextMeterState
import com.hermesagent.mobile.data.session.SessionUsage
import com.hermesagent.mobile.ui.ChatActions
import com.hermesagent.mobile.ui.theme.AppearanceSelection
import com.hermesagent.mobile.ui.theme.BuiltinThemes
import com.hermesagent.mobile.ui.theme.HermesTheme
import com.hermesagent.mobile.ui.theme.HermesThemeMode
import kotlin.math.abs
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The chat chrome's top bar is one line of title over one line of status,
 * centred on the icon buttons beside it.
 *
 * It was not: every tappable part of the status line took the 48dp touch floor
 * as *layout* height, which made the two-line block 73dp against 48dp buttons
 * and left the title floating above the chrome's centre while the status line
 * sat below it. The floor is a pointer rule, so it now overflows the line
 * ([com.hermesagent.mobile.ui.common.touchTargetOverflow]) instead of setting
 * its height — which only holds if the band a thumb and TalkBack get is still
 * 48dp, so both halves are asserted here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class ChatTopBarAlignmentTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `the title and status block is centred on the icon buttons`() {
        launch()

        val title = compose.onNodeWithTag(CHAT_TITLE_TAG).getUnclippedBoundsInRoot()
        val status = compose.onNodeWithTag(CHAT_SUBTITLE_ROW_TAG).getUnclippedBoundsInRoot()
        val settings = compose.onNodeWithContentDescription("Open settings").getUnclippedBoundsInRoot()

        val blockCentre = (title.top + status.bottom) / 2
        val buttonCentre = (settings.top + settings.bottom) / 2
        assertTrue(
            "block centre $blockCentre should sit on the icon centre $buttonCentre",
            abs((blockCentre - buttonCentre).value) <= 2f,
        )
    }

    @Test
    fun `the status line follows the title by the one gap the column spaces them with`() {
        launch()

        val title = compose.onNodeWithTag(CHAT_TITLE_TAG).getUnclippedBoundsInRoot()
        val status = compose.onNodeWithTag(CHAT_SUBTITLE_ROW_TAG).getUnclippedBoundsInRoot()

        assertClose("status line follows the title", expected = 1.dp, actual = status.top - title.bottom)
        // One line of type, not a 48dp touch target wearing one.
        assertTrue("status line is one line tall, was ${status.height}", status.height < 48.dp)
    }

    /**
     * The bug itself, as an invariant: the same words cost the same height
     * whether or not they are a door. Before, becoming one bought the line the
     * whole 48dp floor and pushed the title off the chrome's centre.
     */
    @Test
    fun `making the status line a door costs the line no height`() {
        var door by mutableStateOf(false)
        compose.setContent {
            HermesTheme(AppearanceSelection(BuiltinThemes.DEFAULT_NAME, HermesThemeMode.Dark)) {
                ChatScreen(
                    state = chromeState(
                        status = if (door) {
                            GatewayConnectionStatus.NeedsAttention
                        } else {
                            GatewayConnectionStatus.Connected
                        },
                    ),
                    actions = ChatActions(),
                    onOpenSettings = {},
                    onOpenGateways = {},
                )
            }
        }
        compose.waitForIdle()
        val prose = compose.onNodeWithTag(CHAT_SUBTITLE_ROW_TAG).getUnclippedBoundsInRoot().height

        door = true
        compose.waitForIdle()
        val tappable = compose.onNodeWithTag(CHAT_SUBTITLE_ROW_TAG).getUnclippedBoundsInRoot().height

        assertClose("a status line that is a door is the same line", prose, tappable)
    }

    @Test
    fun `the status door, the meter and the chip keep a 48dp band`() {
        launch()

        assertTouchBand("the status door", CHAT_SUBTITLE_TAG)
        assertTouchBand("the context meter", CONTEXT_METER_TAG)
        assertTouchBand("the approval chip", APPROVAL_MODE_CHIP_TAG)
    }

    /**
     * The two figures are read at a glance and mean nothing truncated — a chip
     * reading `Sm` names no approval posture. The prose is what gives way, so
     * neither figure may be narrower on a 360dp phone than with room to spare.
     */
    @Test
    @Config(qualifiers = "w800dp-h891dp")
    fun `the figures keep their full width when the status line runs out of room`() {
        var width by mutableStateOf(700.dp)
        compose.setContent {
            HermesTheme(AppearanceSelection(BuiltinThemes.DEFAULT_NAME, HermesThemeMode.Dark)) {
                Box(Modifier.width(width)) {
                    ChatScreen(
                        state = chromeState(),
                        actions = ChatActions(),
                        onOpenSettings = {},
                        onOpenGateways = {},
                    )
                }
            }
        }
        compose.waitForIdle()

        val roomyMeter = compose.onNodeWithTag(CONTEXT_METER_TAG).getUnclippedBoundsInRoot().width
        val roomyChip = compose.onNodeWithTag(APPROVAL_MODE_CHIP_TAG).getUnclippedBoundsInRoot().width

        width = 360.dp
        compose.waitForIdle()

        assertClose(
            "the meter keeps its width",
            roomyMeter,
            compose.onNodeWithTag(CONTEXT_METER_TAG).getUnclippedBoundsInRoot().width,
        )
        assertClose(
            "the chip keeps its width",
            roomyChip,
            compose.onNodeWithTag(APPROVAL_MODE_CHIP_TAG).getUnclippedBoundsInRoot().width,
        )
        // And the line still holds all three: the prose ellipsises into
        // whatever is left rather than pushing a figure off the row.
        val row = compose.onNodeWithTag(CHAT_SUBTITLE_ROW_TAG).getUnclippedBoundsInRoot()
        val chip = compose.onNodeWithTag(APPROVAL_MODE_CHIP_TAG).getUnclippedBoundsInRoot()
        val subtitle = compose.onNodeWithTag(CHAT_SUBTITLE_TAG).getUnclippedBoundsInRoot()
        val meter = compose.onNodeWithTag(CONTEXT_METER_TAG).getUnclippedBoundsInRoot()
        assertTrue("the chip stays inside the row", chip.right.value <= row.right.value + 0.5f)
        // A squeezed line is still one line: the prose ellipsises rather than
        // wrapping the status row into a second one.
        assertTrue("the squeezed status line is one line, was ${row.height}", row.height < 48.dp)
        assertTrue("the prose gives way to the figures", subtitle.right.value <= meter.left.value + 0.5f)
    }

    private fun launch() {
        compose.setContent {
            HermesTheme(AppearanceSelection(BuiltinThemes.DEFAULT_NAME, HermesThemeMode.Dark)) {
                ChatScreen(
                    state = chromeState(),
                    actions = ChatActions(),
                    onOpenSettings = {},
                    onOpenGateways = {},
                )
            }
        }
        compose.waitForIdle()
    }

    /**
     * The chrome carrying everything the status line can hold at once: a
     * connection line that is a door, the context meter, and the approval chip.
     */
    private fun chromeState(
        status: GatewayConnectionStatus = GatewayConnectionStatus.NeedsAttention,
    ) = ChatUiState(
        connection = GatewayConnectionState(status),
        contextMeter = ContextMeterState(
            label = "36.4k/272k",
            detail = "[█░░░░░░░░░] 13%",
            usage = SessionUsage(
                contextUsed = 36_400,
                contextMax = 272_000,
                contextPercent = 13,
                total = 36_400,
            ),
            breakdown = null,
        ),
        approvalMode = ApprovalMode.Smart,
    )

    /**
     * What a thumb and TalkBack actually get: `touchBoundsInRoot` is the
     * pointer region of the node's own coordinator, which is the band
     * `touchTargetOverflow` measured rather than the height it reported.
     */
    private fun assertTouchBand(what: String, tag: String) {
        val node = compose.onNodeWithTag(tag).fetchSemanticsNode()
        val band = with(compose.density) { node.touchBoundsInRoot.height.toDp() }
        assertTrue("$what should keep a 48dp band, was $band", band.value >= 47.5f)
    }

    private fun assertClose(what: String, expected: Dp, actual: Dp, tolerance: Float = 1f) {
        assertTrue("$what: expected $expected, was $actual", abs((expected - actual).value) <= tolerance)
    }
}
