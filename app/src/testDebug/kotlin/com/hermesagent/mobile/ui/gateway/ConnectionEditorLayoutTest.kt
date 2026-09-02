package com.hermesagent.mobile.ui.gateway

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTopPositionInRootIsEqualTo
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import com.hermesagent.mobile.data.connections.ConnectionKind
import com.hermesagent.mobile.data.connections.SavedConnection
import com.hermesagent.mobile.data.gateway.RemoteGatewayProfile
import com.hermesagent.mobile.ui.ConnectionsActions
import com.hermesagent.mobile.ui.common.WIP_SPOKEN
import com.hermesagent.mobile.ui.theme.AppearanceSelection
import com.hermesagent.mobile.ui.theme.HermesSpacing
import com.hermesagent.mobile.ui.theme.HermesTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The registry's two button rows have to fit a phone, and in Desktop's order.
 *
 * Desktop puts the editor's actions in a `flex justify-end gap-2` — Cancel as a
 * ghost, then the filled Save at the right edge
 * (`apps/desktop/src/app/settings/connections-registry.tsx:947-954` @
 * `3ca096de5f8183cb2e0ec23673f294d5978656a3`) — and its foot in a
 * `flex items-center gap-2` holding an outline `Add connection` and, once there
 * is more than one instance, the update fan-out (`:957-988`). This shipped with
 * Save reversed onto the left at full width and Add drawn as a borderless link
 * (#85), neither of which a rendered assertion would have let through.
 *
 * The sibling of [ConnectionRowActionsLayoutTest], and native for the same
 * reason: these are measured geometry, and Robolectric's legacy graphics stub
 * every glyph to about a dp of advance, under which any row fits and this file
 * would pass while saying nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ConnectionEditorLayoutTest {

    @get:Rule
    val compose = createComposeRule()

    private val screenWidth = 411.dp

    /** The real tokens rather than retyped literals — see the sibling file. */
    private val spacing = HermesSpacing()

    /**
     * Held in state rather than passed to a second `setContent`: the rule takes
     * one composition per test, and the blank-name case has to watch one editor
     * gain a name rather than compare two screens.
     */
    private var state by mutableStateOf(ConnectionsUiState())

    private fun show(initial: ConnectionsUiState) {
        state = initial
        compose.setContent {
            HermesTheme(AppearanceSelection()) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = HermesTheme.spacing.pageInset),
                ) {
                    ConnectionsSection(state = state, actions = ConnectionsActions())
                }
            }
        }
        compose.waitForIdle()
    }

    private fun editing(label: String) = ConnectionsUiState(
        connections = listOf(connection("a", "Alpha")),
        activeId = "a",
        loaded = true,
        editor = ConnectionEditorState(id = "a", kind = ConnectionKind.Remote, label = label),
    )

    private fun connection(id: String, label: String) = SavedConnection(
        id = id,
        label = label,
        kind = ConnectionKind.Remote,
        remote = RemoteGatewayProfile(baseUrl = "https://$id.test"),
    )

    @Test
    fun `the editor puts Cancel before Save, right-aligned on one line`() {
        show(editing("Alpha"))

        val cancel = compose.onNodeWithText(ConnectionsCopy.CANCEL)
            .performScrollTo()
            .getUnclippedBoundsInRoot()
        val save = compose.onNodeWithText(ConnectionsCopy.SAVE)
            .performScrollTo()
            .getUnclippedBoundsInRoot()

        assertTrue("Cancel must come before Save, as it does upstream", cancel.left < save.left)
        compose.onNodeWithText(ConnectionsCopy.SAVE)
            .assertTopPositionInRootIsEqualTo(cancel.top)

        // `justify-end`: the pair sits at the right edge of the page rather
        // than a filled Save spanning the form.
        assertTrue(
            "the action row runs to ${save.right}, past the ${screenWidth - spacing.pageInset} page edge",
            save.right <= screenWidth - spacing.pageInset,
        )
        assertTrue(
            "Save should hug the right edge, not stretch across the editor",
            save.right - save.left < (screenWidth - spacing.pageInset * 2) / 2,
        )
    }

    @Test
    fun `Save is disarmed until the connection has a name`() {
        show(editing(""))
        compose.onNodeWithText(ConnectionsCopy.SAVE).performScrollTo().assertIsNotEnabled()

        state = editing("Alpha")
        compose.waitForIdle()
        compose.onNodeWithText(ConnectionsCopy.SAVE).performScrollTo().assertIsEnabled()
    }

    /**
     * The box, not the border.
     *
     * Whether a border is *drawn* is a pixel claim, and this lane cannot make
     * it: Robolectric never hands the test a window to read back, which is why
     * `ContextUsageSwatchInkTest` reads its ink off the composable rather than
     * off a bitmap. So this measures the thing a border needs — a padded box
     * one touch target tall, wider than the label it holds — and the drawn
     * stroke stays with the rendered parity report (`docs/parity/gateway-connections.md`).
     */
    @Test
    fun `Add connection fills a padded touch target rather than sitting inline`() {
        show(ConnectionsUiState(connections = listOf(connection("a", "Alpha")), activeId = "a", loaded = true))

        val box = compose.onNodeWithContentDescription(ConnectionsCopy.ADD_CONNECTION)
            .performScrollTo()
            .assertIsDisplayed()
            .assertHeightIsEqualTo(spacing.touchTarget)
            .getUnclippedBoundsInRoot()
        // The label is inside a padded box with the glyph ahead of it, rather
        // than being the whole target.
        val label = compose.onNodeWithText(ConnectionsCopy.ADD_CONNECTION, useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
        assertTrue("the label should sit inside the button, not start it", label.left > box.left)
        assertTrue("the button should extend past its label", box.right > label.right)
    }

    @Test
    fun `the fan-out sits beside Add connection on one line at phone width`() {
        show(
            ConnectionsUiState(
                connections = listOf(connection("a", "Alpha"), connection("b", "Bravo")),
                activeId = "a",
                loaded = true,
            ),
        )

        val add = compose.onNodeWithContentDescription(ConnectionsCopy.ADD_CONNECTION)
            .performScrollTo()
            .getUnclippedBoundsInRoot()
        val updateAll = compose.onNodeWithContentDescription("${ConnectionsCopy.UPDATE_ALL}. $WIP_SPOKEN")
            .performScrollTo()
            .getUnclippedBoundsInRoot()

        compose.onNodeWithContentDescription("${ConnectionsCopy.UPDATE_ALL}. $WIP_SPOKEN")
            .assertTopPositionInRootIsEqualTo(add.top)
        assertTrue("the fan-out follows Add connection", updateAll.left > add.left)
        assertTrue(
            "the add row runs to ${updateAll.right}, past the ${screenWidth - spacing.pageInset} page edge",
            updateAll.right <= screenWidth - spacing.pageInset,
        )
    }
}
