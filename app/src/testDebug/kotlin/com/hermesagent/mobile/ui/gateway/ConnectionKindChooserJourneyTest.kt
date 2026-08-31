package com.hermesagent.mobile.ui.gateway

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.hermesagent.mobile.data.connections.ConnectionKind
import com.hermesagent.mobile.ui.ConnectionsActions
import com.hermesagent.mobile.ui.common.WIP_PILL
import com.hermesagent.mobile.ui.common.WIP_SPOKEN
import com.hermesagent.mobile.ui.theme.AppearanceSelection
import com.hermesagent.mobile.ui.theme.HermesTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The registry's kind chooser, rendered
 * (`apps/desktop/src/app/settings/connections-registry.tsx:648-671` @
 * `936b970e281d5d28e930c5698f36bc4ebb54c7ba`).
 *
 * Desktop draws this one as a plain button grid, not as the mode cards above
 * it, and offers all four kinds on create. Kept in its own file so the kind
 * chooser's evidence does not sit in the middle of the registry's.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class ConnectionKindChooserJourneyTest {

    @get:Rule
    val compose = createComposeRule()

    private fun editor(kind: ConnectionKind = ConnectionKind.Remote, onEditKind: (ConnectionKind) -> Unit = {}) {
        compose.setContent {
            HermesTheme(AppearanceSelection()) {
                Column {
                    ConnectionsSection(
                        state = ConnectionsUiState(
                            loaded = true,
                            editor = ConnectionEditorState(kind = kind, label = "New"),
                        ),
                        actions = ConnectionsActions(onEditKind = onEditKind),
                    )
                }
            }
        }
    }

    @Test
    fun `all four of Desktop's kinds are offered on create`() {
        editor()

        // The order itself is gated without a frame in `ConnectionsSectionTest`;
        // what needs a frame is that all four actually reach the screen.
        assertEquals(
            listOf("Local", "Hermes Cloud", "Remote gateway", "SSH"),
            CONNECTION_KIND_CHOICES.map { it.label },
        )
        CONNECTION_KIND_CHOICES.forEach { compose.onNodeWithText(it.label).assertIsDisplayed() }
    }

    @Test
    fun `the editor's kind is the selected button`() {
        editor(kind = ConnectionKind.Ssh)

        compose.onNodeWithText(ConnectionsCopy.KIND_SSH).assertIsSelected()
        compose.onNodeWithText(ConnectionsCopy.KIND_LOCAL).assertIsNotSelected()
    }

    @Test
    fun `choosing a kind reports it`() {
        var chosen: ConnectionKind? = null
        editor(onEditKind = { chosen = it })

        compose.onNodeWithText(ConnectionsCopy.KIND_LOCAL).performClick()
        compose.waitForIdle()

        assertEquals(ConnectionKind.Local, chosen)
    }

    @Test
    fun `Hermes Cloud is offered with a coming soon pill and refuses the tap`() {
        var chosen: ConnectionKind? = null
        editor(onEditKind = { chosen = it })

        val cloud = compose.onNodeWithText(ConnectionsCopy.KIND_CLOUD)
        cloud.assertIsDisplayed()
        cloud.assertIsNotEnabled()
        compose.onNodeWithTag(WIP_PILL, useUnmergedTree = true).assertIsDisplayed()
        // The kind's own name has to survive the merge — see the mode cards.
        compose.onNodeWithContentDescription(
            "${ConnectionsCopy.KIND_CLOUD}. $WIP_SPOKEN",
        ).assertExists()

        cloud.performClick()
        compose.waitForIdle()

        assertEquals(null, chosen)
    }

    /**
     * Desktop disables Local while its one managed local entry exists
     * (`connections-registry.tsx:654`) and explains it with `localAddHint`
     * (`en.ts:757`). That rule is Desktop's registry's, not this one's: here
     * Local rows collide on the normalized loopback address, so two Termux
     * servers on two ports are two Gateways and Local stays offered. The hint
     * is deliberately absent because it would describe a rule this app does
     * not have.
     */
    @Test
    fun `Local stays offered, because this registry has no one-Local rule`() {
        editor()

        compose.onNodeWithText(ConnectionsCopy.KIND_LOCAL).assertIsDisplayed()
        compose.onNodeWithContentDescription(
            "Local is unavailable: the managed local connection already exists (there is only ever one).",
        ).assertDoesNotExist()
    }
}
