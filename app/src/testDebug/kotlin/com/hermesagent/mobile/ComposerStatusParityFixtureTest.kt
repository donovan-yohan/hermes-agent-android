package com.hermesagent.mobile

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.junit4.createComposeRule
import com.hermesagent.mobile.ui.theme.AppearanceSelection
import com.hermesagent.mobile.ui.theme.HermesTheme
import com.hermesagent.mobile.ui.theme.HermesThemeMode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ComposerStatusParityFixtureTest {
    @get:Rule val compose = createComposeRule()

    private fun setFixture(state: ComposerStatusFixtureState) {
        compose.setContent {
            HermesTheme(AppearanceSelection("mono", HermesThemeMode.Dark)) {
                ComposerStatusParityFixture(state)
            }
        }
    }

    @Test
    fun queueCaptureStateMountsOnlyTheCollapsedParkedQueue() {
        setFixture(ComposerStatusFixtureState.QueueParkedCollapsed)

        compose.onNodeWithContentDescription("Queue, 2 messages, parked, expand").assertIsDisplayed()
        compose.onAllNodes(hasContentDescription("Goal active, expand")).assertCountEquals(0)
        compose.onAllNodes(hasContentDescription("Tasks 1/3, collapse")).assertCountEquals(0)
        compose.onAllNodes(hasContentDescription("Background, 1, expand")).assertCountEquals(0)
    }

    @Test
    fun backgroundCaptureStateMountsOnlyTheRequestedDisclosure() {
        setFixture(ComposerStatusFixtureState.BackgroundOpen)

        compose.onNodeWithContentDescription("Background, 1, expand").performClick()
        compose.onNodeWithContentDescription("Background, 1, collapse").assertIsDisplayed()
        compose.onAllNodes(hasContentDescription("Goal active, expand")).assertCountEquals(0)
        compose.onAllNodes(hasContentDescription("Tasks 1/3, collapse")).assertCountEquals(0)
    }
}