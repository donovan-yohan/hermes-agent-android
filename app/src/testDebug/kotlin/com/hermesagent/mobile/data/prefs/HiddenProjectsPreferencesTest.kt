package com.hermesagent.mobile.data.prefs

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HiddenProjectsPreferencesTest {
    @Test fun savedIdsSurviveNewPreferenceInstanceWithoutCrossingRowAddressOrNullableProfile() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = HermesPreferences(context)
        val scope = HiddenProjectsScope("synthetic-row/address-A", null)
        store.writeHiddenProjects(scope, setOf("/workspace/repository", "auto-two"))
        val restarted = HermesPreferences(context)
        assertEquals(setOf("/workspace/repository", "auto-two"), restarted.readHiddenProjects(scope))
        listOf(scope.copy(profile = "default"), scope.copy(profile = "research"), scope.copy(endpoint = "synthetic-row/address-B"),
            scope.copy(endpoint = "other-row/address-A")).forEach { assertTrue(restarted.readHiddenProjects(it).isEmpty()) }
        restarted.writeHiddenProjects(scope, emptySet())
        assertTrue(HermesPreferences(context).readHiddenProjects(scope).isEmpty())
    }
}
