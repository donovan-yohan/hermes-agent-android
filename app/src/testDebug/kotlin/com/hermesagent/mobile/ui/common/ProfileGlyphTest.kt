package com.hermesagent.mobile.ui.common

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import com.hermesagent.mobile.data.profiles.HermesProfile
import com.hermesagent.mobile.ui.theme.AppearanceSelection
import com.hermesagent.mobile.ui.theme.HermesTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Which face a profile wears.
 *
 * `apps/desktop/src/components/ui/profile-glyph.tsx:21-41` @
 * `936b970e281d5d28e930c5698f36bc4ebb54c7ba`: the `home` icon is the default
 * profile's alone. Every other profile carries its initial — including one that
 * resolves to no identity colour, which tints against `--ui-text-quaternary`
 * rather than borrowing the default's face.
 *
 * Each case supplies a description, because a glyph with none is decorative and
 * deliberately clears its own subtree — the owning control speaks for it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProfileGlyphTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `the default profile wears the home face and carries no initial`() {
        show(HermesProfile(name = "default", isDefault = true))

        compose.onNodeWithContentDescription(MARK).assertIsDisplayed()
        assertEquals(0, compose.textNodes())
    }

    @Test
    fun `a named profile carries its initial`() {
        show(HermesProfile(name = "work"))

        compose.onNodeWithText("W").assertIsDisplayed()
    }

    @Test
    fun `a colourless non-default profile still carries its initial`() {
        // `resolveProfileColor` returns null for the key `default` whatever
        // `is_default` says (`lib/profile-color.ts:35-43`), so this is the one
        // reachable colourless row that is not the default profile. Before this
        // it borrowed the default's home face and lost its identity entirely.
        show(HermesProfile(name = "default", isDefault = false))

        compose.onNodeWithText("D").assertIsDisplayed()
        assertEquals(1, compose.textNodes())
    }

    @Test
    fun `a decorative glyph speaks for nothing`() {
        compose.setContent {
            HermesTheme(AppearanceSelection()) {
                ProfileGlyph(profile = HermesProfile(name = "work"))
            }
        }
        compose.waitForIdle()

        assertEquals(0, compose.textNodes())
    }

    @Test
    fun `the owning-profile tag speaks the canonical key`() {
        compose.setContent {
            HermesTheme(AppearanceSelection()) {
                ProfileTag(profile = HermesProfile(name = "work"))
            }
        }
        compose.waitForIdle()

        compose.onNodeWithContentDescription("Profile: work").assertIsDisplayed()
    }

    private fun show(profile: HermesProfile) {
        compose.setContent {
            HermesTheme(AppearanceSelection()) {
                ProfileGlyph(profile = profile, contentDescription = MARK)
            }
        }
        compose.waitForIdle()
    }

    private companion object {
        const val MARK = "Profile mark"
    }
}

/** Every node carrying text — the codicon face clears its own, so it counts none. */
private fun ComposeContentTestRule.textNodes(): Int =
    onAllNodesWithText("", substring = true).fetchSemanticsNodes().size
