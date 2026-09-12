package com.hermesagent.mobile.ui.gateway

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Dp
import com.hermesagent.mobile.data.gateway.GatewayConnectionMode
import com.hermesagent.mobile.ui.GatewayActions
import com.hermesagent.mobile.ui.SshActions
import com.hermesagent.mobile.ui.common.WIP_PILL
import com.hermesagent.mobile.ui.common.WIP_SPOKEN
import com.hermesagent.mobile.ui.ssh.SshUiState
import com.hermesagent.mobile.ui.theme.AppearanceSelection
import com.hermesagent.mobile.ui.theme.HermesTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Desktop's **Connection mode** cards, rendered
 * (`apps/desktop/src/app/settings/gateway-settings.tsx:1044-1084` @
 * `3ca096de5f8183cb2e0ec23673f294d5978656a3`).
 *
 * [GatewayScreenTest] gates the data — order, words, totality — without a
 * frame. This gates what the frame does with it: which card is lit, that the
 * unsupported one is visible and refuses the tap, that the hover tooltip's
 * touch replacement actually reveals Desktop's sentence, and that the grid
 * steps 1 → 2 → 4 at the widths the mapping claims.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class ConnectionModeCardsJourneyTest {

    @get:Rule
    val compose = createComposeRule()

    private fun screen(
        mode: GatewayConnectionMode = GatewayConnectionMode.Remote,
        onModeChange: (GatewayConnectionMode) -> Unit = {},
    ) {
        compose.setContent {
            HermesTheme(AppearanceSelection()) {
                GatewayScreen(
                    state = GatewaySettingsUiState(loaded = true, mode = mode),
                    gatewayActions = GatewayActions(onModeChange = onModeChange),
                    sshState = SshUiState(),
                    sshActions = SshActions(),
                )
            }
        }
    }

    @Test
    fun `the heading and all four cards are on screen, in Desktop's order`() {
        screen()

        compose.onNodeWithText(GatewayModeCopy.MODE_TITLE).assertIsDisplayed()

        val tops = GATEWAY_MODE_CARDS.map { card ->
            compose.onNodeWithText(card.title).getBoundsInRoot().top
        }
        // One column at 411dp, so "in order" is literally "down the page".
        tops.zipWithNext { above, below ->
            assertTrue("cards are out of Desktop's order: $tops", above < below)
        }
    }

    @Test
    fun `each card carries Desktop's description`() {
        screen()

        GATEWAY_MODE_CARDS.forEach { card ->
            compose.onNodeWithText(card.description).performScrollTo().assertIsDisplayed()
        }
    }

    @Test
    fun `the active route is the checked card and the others are not`() {
        screen(mode = GatewayConnectionMode.Ssh)

        compose.onNodeWithText(GatewayModeCopy.SSH_TITLE).performScrollTo().assertIsSelected()
        compose.onNodeWithText(GatewayModeCopy.REMOTE_TITLE).performScrollTo().assertIsNotSelected()
        compose.onNodeWithText(GatewayModeCopy.LOCAL_TITLE).performScrollTo().assertIsNotSelected()
    }

    @Test
    fun `tapping a card changes the route`() {
        var chosen: GatewayConnectionMode? = null
        screen(onModeChange = { chosen = it })

        compose.onNodeWithText(GatewayModeCopy.LOCAL_TITLE).performScrollTo().performClick()
        compose.waitForIdle()

        assertEquals(GatewayConnectionMode.Local, chosen)
    }

    @Test
    fun `Hermes Cloud is shown with a coming soon pill and refuses the tap`() {
        var chosen: GatewayConnectionMode? = null
        screen(onModeChange = { chosen = it })

        val cloud = compose.onNodeWithText(GatewayModeCopy.CLOUD_TITLE).performScrollTo()
        cloud.assertIsDisplayed()
        cloud.assertIsNotEnabled()
        compose.onNodeWithTag(WIP_PILL, useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        // The whole announcement, not just the marker: the card merges its
        // descendants, so a marker that spoke for itself would replace this
        // card's name rather than follow it, and asserting on the marker alone
        // would not notice. Seen as an initialism, said as the words.
        compose.onNodeWithContentDescription(
            "${GatewayModeCopy.CLOUD_TITLE}. ${GatewayModeCopy.CLOUD_DESC}. $WIP_SPOKEN",
        ).assertExists()

        cloud.performClick()
        compose.waitForIdle()

        // Desktop renders this mode, so removing it would teach a different
        // surface. It is here, it is legible, and it cannot be selected.
        assertEquals(null, chosen)
    }

    @Test
    fun `the hint glyph reveals Desktop's tooltip sentence, and hides it again`() {
        screen()

        compose.onNodeWithText(GatewayModeCopy.REMOTE_AUTH_HINT).assertDoesNotExist()

        val reveal = compose.onNodeWithContentDescription("About ${GatewayModeCopy.REMOTE_TITLE}")
        reveal.performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText(GatewayModeCopy.REMOTE_AUTH_HINT).performScrollTo().assertIsDisplayed()

        reveal.performClick()
        compose.waitForIdle()
        compose.onNodeWithText(GatewayModeCopy.REMOTE_AUTH_HINT).assertDoesNotExist()
    }

    @Test
    fun `a card with no Desktop hint has no revealer`() {
        screen()

        compose.onNodeWithContentDescription("About ${GatewayModeCopy.LOCAL_TITLE}").assertDoesNotExist()
        compose.onNodeWithContentDescription("About ${GatewayModeCopy.CLOUD_TITLE}").assertDoesNotExist()
    }

    /** `grid-cols-1` below Desktop's `sm:` step. */
    @Test
    fun `a phone gets one column`() {
        screen()
        assertEquals(4, distinctRows())
    }

    /** `sm:grid-cols-2`, mapped to Android's compact/medium boundary. */
    @Test
    @Config(sdk = [34], qualifiers = "w600dp-h900dp")
    fun `600dp gets two columns`() {
        screen()
        assertEquals(2, distinctRows())
    }

    /** `min-[72rem]:grid-cols-4`, mapped to this app's wide breakpoint. */
    @Test
    @Config(sdk = [34], qualifiers = "w840dp-h900dp")
    fun `a wide window gets four columns`() {
        screen()
        assertEquals(1, distinctRows())
    }

    /**
     * How many rows the four cards occupy, read off their rendered tops. Four
     * cards in one column is four rows; in two columns, two; in four, one.
     */
    private fun distinctRows(): Int {
        // Measured without scrolling on purpose: the page is a plain Column, so
        // every card is composed and positioned whether or not it is in view,
        // and scrolling between two measurements would move the cards already
        // measured relative to the ones not yet.
        val tops: List<Dp> = GATEWAY_MODE_CARDS.map { card ->
            compose.onNodeWithText(card.title).getBoundsInRoot().top
        }
        return tops.distinct().size
    }
}
