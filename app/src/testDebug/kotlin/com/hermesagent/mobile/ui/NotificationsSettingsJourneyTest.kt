package com.hermesagent.mobile.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.hermesagent.mobile.data.notifications.NotificationKind
import com.hermesagent.mobile.data.notifications.SETTABLE_KINDS
import com.hermesagent.mobile.ui.settings.NOTIFICATIONS_BLOCKED_TAG
import com.hermesagent.mobile.ui.settings.NOTIFICATIONS_MASTER_TAG
import com.hermesagent.mobile.ui.settings.NOTIFICATIONS_PREVIEW_TAG
import com.hermesagent.mobile.ui.settings.NOTIFICATIONS_TEST_TAG
import com.hermesagent.mobile.ui.settings.NotificationsActions
import com.hermesagent.mobile.ui.settings.NotificationsCopy
import com.hermesagent.mobile.ui.settings.NotificationsScreen
import com.hermesagent.mobile.ui.settings.NotificationsUiState
import com.hermesagent.mobile.ui.settings.notificationKindTag
import com.hermesagent.mobile.ui.theme.HermesTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The notifications settings screen as a reader meets it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class NotificationsSettingsJourneyTest {

    @get:Rule
    val compose = createComposeRule()

    private val kindChanges = mutableListOf<Pair<NotificationKind, Boolean>>()
    private val enabledChanges = mutableListOf<Boolean>()
    private val previewChanges = mutableListOf<Boolean>()
    private var systemSettingsOpened = 0
    private var testsSent = 0

    private fun launch(state: NotificationsUiState = NotificationsUiState(kinds = allOn())) {
        compose.setContent {
            HermesTheme {
                NotificationsScreen(
                    state = state,
                    actions = NotificationsActions(
                        onEnabledChange = { enabledChanges += it },
                        onKindChange = { kind, on -> kindChanges += kind to on },
                        onPreviewChange = { previewChanges += it },
                        onOpenSystemSettings = { systemSettingsOpened++ },
                        onSendTest = { testsSent++ },
                    ),
                )
            }
        }
    }

    private fun allOn() = NotificationKind.entries.associateWith { true }

    @Test
    fun listsEveryKindThisAppCanActuallyRaise() {
        launch()

        // The screen scrolls: six kinds, a privacy section and a test button
        // do not fit a 411x891 dp phone, so each row is scrolled to rather than
        // asserted where it happens to have landed.
        for (kind in SETTABLE_KINDS) {
            compose.onNodeWithTag(notificationKindTag(kind)).performScrollTo().assertIsDisplayed()
        }
        assertEquals(6, SETTABLE_KINDS.size)
    }

    /**
     * The three Desktop-only kinds are classified `non-goal` in the ledger, and
     * the repo rule is that a non-goal is omitted rather than marked: a `WIP`
     * chip claims a row is coming, and these are not.
     */
    @Test
    fun omitsTheKindsWithNoMobileSource() {
        launch()

        for (kind in listOf(NotificationKind.BackgroundDone, NotificationKind.Credits, NotificationKind.Plugin)) {
            compose.onNodeWithTag(notificationKindTag(kind)).assertDoesNotExist()
        }
    }

    @Test
    fun theTwoMobileOnlyKindsAreOffered() {
        launch()

        compose.onNodeWithTag(notificationKindTag(NotificationKind.ConnectionLost))
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(notificationKindTag(NotificationKind.StillWaiting))
            .performScrollTo().assertIsDisplayed()
        assertTrue(NotificationKind.TurnError in SETTABLE_KINDS)
    }

    @Test
    fun togglingAKindReportsThatKind() {
        launch()

        compose.onNodeWithTag(notificationKindTag(NotificationKind.TurnError)).performScrollTo().performClick()

        assertEquals(listOf(NotificationKind.TurnError to false), kindChanges)
    }

    /**
     * `NotificationSettings.allows` reads the master first, so a per-kind row
     * under a master that is off is a control with no effect. Dimming it says
     * which switch to reach for instead of accepting a tap that changes nothing
     * a person can observe.
     */
    @Test
    fun theMasterSwitchDimsEveryKindBeneathIt() {
        launch(NotificationsUiState(enabled = false, kinds = allOn()))

        compose.onNodeWithTag(NOTIFICATIONS_MASTER_TAG).assertIsEnabled().assertIsOff()
        compose.onNodeWithTag(notificationKindTag(NotificationKind.Approval)).assertIsNotEnabled()
        compose.onNodeWithTag(NOTIFICATIONS_PREVIEW_TAG).performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun aRevokedSystemGrantSaysSoAndOffersTheWayOut() {
        launch(NotificationsUiState(kinds = allOn(), systemAllowed = false))

        compose.onNodeWithTag(NOTIFICATIONS_BLOCKED_TAG).assertIsDisplayed()
        // Nothing on this screen can overrule the OS, so nothing pretends to.
        compose.onNodeWithTag(NOTIFICATIONS_MASTER_TAG).assertIsNotEnabled()
        compose.onNodeWithTag(NOTIFICATIONS_TEST_TAG).performScrollTo().assertIsNotEnabled()

        compose.onNodeWithText(NotificationsCopy.BLOCKED_ACTION).performScrollTo().performClick()
        assertEquals(1, systemSettingsOpened)
    }

    @Test
    fun previewDefaultsOnAndReportsWhenTurnedOff() {
        launch()

        compose.onNodeWithTag(NOTIFICATIONS_PREVIEW_TAG).performScrollTo().assertIsOn()
        compose.onNodeWithTag(NOTIFICATIONS_PREVIEW_TAG).performClick()

        assertEquals(listOf(false), previewChanges)
    }

    /**
     * The one control on the screen that answers a question no preference here
     * can: whether the delivery path works at all.
     */
    @Test
    fun theTestNotificationSendsAndSaysWhereToLookIfNothingArrives() {
        launch()

        compose.onNodeWithTag(NOTIFICATIONS_TEST_TAG).performScrollTo().performClick()

        assertEquals(1, testsSent)
        compose.onNodeWithText(NotificationsCopy.TEST_SENT).performScrollTo().assertIsDisplayed()
    }
}
