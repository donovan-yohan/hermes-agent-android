package com.hermesagent.mobile.ui.gateway

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertTopPositionInRootIsEqualTo
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.dp
import com.hermesagent.mobile.data.connections.ConnectionKind
import com.hermesagent.mobile.data.connections.SavedConnection
import com.hermesagent.mobile.data.gateway.RemoteGatewayProfile
import com.hermesagent.mobile.ui.ConnectionsActions
import com.hermesagent.mobile.ui.common.WIP_PILL
import com.hermesagent.mobile.ui.common.WIP_SPOKEN
import com.hermesagent.mobile.ui.theme.AppearanceSelection
import com.hermesagent.mobile.ui.theme.HermesSpacing
import com.hermesagent.mobile.ui.theme.HermesTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The row action cluster has to fit a phone.
 *
 * [ConnectionsSection]'s cluster is a `FlowRow`, so nothing here can fail to
 * render — an oversized cluster silently becomes two 48dp lines per row, which
 * is how it shipped. `Test` and `Make primary` each carried a `Coming soon`
 * pill wider than the control it qualified, and that pushed the icon actions
 * onto a second line.
 *
 * These assertions are measured geometry, so they need real text metrics:
 * Robolectric's legacy graphics stub every glyph to about a dp of advance,
 * under which any cluster fits and this file would pass while saying nothing.
 * Hence [GraphicsMode.Mode.NATIVE], and hence a class of its own rather than
 * another case in `ConnectionsJourneyTest`.
 *
 * `w411dp` is the narrowest width this app carries evidence for
 * (`ProfileRailJourneyTest`), and the section is measured inside the page inset
 * the Gateways screen puts around it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ConnectionRowActionsLayoutTest {

    @get:Rule
    val compose = createComposeRule()

    private val screenWidth = 411.dp

    /**
     * The real tokens rather than retyped literals: `HermesTheme` provides a
     * default-constructed [HermesSpacing], so this is the same instance the
     * composition under test reads.
     */
    private val spacing = HermesSpacing()

    /**
     * The fullest cluster a row can carry. `Switch` is offered on inactive rows
     * only and `Remove` explains itself on the last row, so one saved row that
     * is not the active one puts every action on screen at once — and, unlike a
     * two-row fixture, gives each of them a description that appears exactly
     * once, since `Test` and `Make primary` are named for the action alone.
     */
    private val cluster
        get() = listOf(
            "${ConnectionsCopy.SWITCH_CONNECTION} Alpha",
            "${ConnectionsCopy.TEST_CONNECTION}. $WIP_SPOKEN",
            "${ConnectionsCopy.MAKE_PRIMARY}. $WIP_SPOKEN",
            "${ConnectionsCopy.EDIT_CONNECTION} Alpha",
            "${ConnectionsCopy.REMOVE_CONNECTION} Alpha. ${ConnectionsCopy.LAST_CONNECTION_HINT}",
        )

    @Before
    fun showRegistry() {
        val state = ConnectionsUiState(
            connections = listOf(
                SavedConnection(
                    id = "a",
                    label = "Alpha",
                    kind = ConnectionKind.Remote,
                    remote = RemoteGatewayProfile(baseUrl = "https://alpha.test"),
                ),
            ),
            // Saved but not dialled: the state that offers Switch on the row.
            activeId = null,
            loaded = true,
        )
        compose.setContent {
            HermesTheme(AppearanceSelection()) {
                Column(Modifier.fillMaxWidth().padding(horizontal = HermesTheme.spacing.pageInset)) {
                    ConnectionsSection(state = state, actions = ConnectionsActions())
                }
            }
        }
    }

    @Test
    fun `the fullest row keeps every action on one line at phone width`() {
        val top = compose.onNodeWithContentDescription(cluster.first())
            .getUnclippedBoundsInRoot()
            .top

        cluster.forEach { compose.onNodeWithContentDescription(it).assertTopPositionInRootIsEqualTo(top) }

        // Inside the page, not merely inside the FlowRow: an action that ran to
        // the screen edge would be one layout change away from wrapping again.
        val right = cluster.maxOf {
            compose.onNodeWithContentDescription(it).getUnclippedBoundsInRoot().right
        }
        assertTrue(
            "the cluster runs to $right, past the ${screenWidth - spacing.pageInset} page edge",
            right <= screenWidth - spacing.pageInset,
        )
    }

    @Test
    fun `marking an action WIP costs the row no height`() {
        // The pill rides inside the control's own touch target rather than
        // setting the row height itself, so an unbuilt action is exactly as
        // tall as a working one and the cluster is exactly one line tall.
        compose.onNodeWithContentDescription("${ConnectionsCopy.TEST_CONNECTION}. $WIP_SPOKEN")
            .assertHeightIsEqualTo(spacing.touchTarget)
        compose.onNodeWithContentDescription(cluster.first()).assertHeightIsEqualTo(spacing.touchTarget)
    }

    @Test
    fun `the marker is a compact pill, not a run of text`() {
        // One per unbuilt control on the pane: the row's `Test` and
        // `Make primary`, plus the launch-mode toggle at the foot of the
        // section. Unmerged, so these are the pill nodes themselves rather than
        // the actions they are merged into.
        val pills = compose.onAllNodesWithTag(WIP_PILL, useUnmergedTree = true)
        assertEquals(3, pills.fetchSemanticsNodes().size)

        repeat(3) { index ->
            val box = pills[index].getUnclippedBoundsInRoot()
            val width = box.right - box.left
            assertTrue("a WIP pill is $width wide, wide enough to be a sentence", width <= 40.dp)
        }
    }
}
