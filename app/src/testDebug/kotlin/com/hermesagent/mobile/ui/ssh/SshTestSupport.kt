package com.hermesagent.mobile.ui.ssh

import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import com.hermesagent.mobile.data.ssh.HostProfile
import com.hermesagent.mobile.data.ssh.HostProfileStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * What the SSH Compose journeys share.
 *
 * The store double is the reason [HostProfileStore] is an interface at all: it
 * lets the ViewModel run without DataStore. Counting rather than asserting a
 * single node is deliberate too — "this copy is on screen once" and "this row
 * is not on screen at all" are both claims these journeys make, and
 * `onNodeWith…` cannot express the second.
 */
internal class InMemoryHostProfileStore : HostProfileStore {
    val saved = MutableStateFlow(HostProfile())
    override val hostProfile: Flow<HostProfile> = saved
    override suspend fun saveHostProfile(profile: HostProfile) {
        saved.value = profile
    }
}

internal fun ComposeContentTestRule.countWithText(text: String, substring: Boolean = false): Int =
    onAllNodes(hasText(text, substring = substring)).fetchSemanticsNodes().size

internal fun ComposeContentTestRule.countWithContentDescription(description: String): Int =
    onAllNodes(hasContentDescription(description)).fetchSemanticsNodes().size
