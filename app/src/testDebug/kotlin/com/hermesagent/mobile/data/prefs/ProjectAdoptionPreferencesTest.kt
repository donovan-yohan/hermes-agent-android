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
class ProjectAdoptionPreferencesTest {
    @Test fun durableGuardBytesContainNoAppearanceAndRejectMalformedOrStaleAuthorization() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scope = ProjectAdoptionScope("synthetic-codec-row/address", "default")
        val attempt = ProjectAdoptionAttempt("local-attempt", "auto", "/workspace/repository", "Repository")
        assertEquals("""[{"attempt":"local-attempt","auto":"auto","path":"/workspace/repository","label":"Repository"}]""", encodeProjectAdoptions(listOf(attempt)))
        assertEquals(listOf(attempt), decodeProjectAdoptions(encodeProjectAdoptions(listOf(attempt))))
        for (raw in listOf("{}", "null", "[{}]", "[{\"attempt\":null}]")) assertTrue(runCatching { decodeProjectAdoptions(raw) }.isFailure)
        val store = HermesPreferences(context)
        assertTrue(store.compareProjectAdoptions(scope, emptyList(), listOf(attempt)))
        assertFalse(store.compareProjectAdoptions(scope, listOf(attempt), emptyList()) { false })
        assertEquals(listOf(attempt), store.readProjectAdoptions(scope))
        assertTrue(store.compareProjectAdoptions(scope, listOf(attempt), emptyList()))
    }

    @Test fun preparedGuardSurvivesRestartAndCompareAndSetNeverClearsAnotherAttempt() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scope = ProjectAdoptionScope("synthetic-row/address-A", null)
        val first = ProjectAdoptionAttempt("first", "auto", "/workspace/repository", "Repository")
        val store = HermesPreferences(context) as? ProjectAdoptionStore
        assertNotNull("Production preferences must persist the adoption guard", store)
        store!!
        assertTrue(store.compareProjectAdoptions(scope, emptyList(), listOf(first)))
        val restarted = HermesPreferences(context) as ProjectAdoptionStore
        assertEquals(listOf(first), restarted.readProjectAdoptions(scope))
        for (other in listOf(scope.copy(profile = "default"), scope.copy(endpoint = "other-row/address-A"), scope.copy(endpoint = "synthetic-row/address-B"))) {
            assertTrue(restarted.readProjectAdoptions(other).isEmpty())
        }
        val second = first.copy(attemptId = "second")
        assertTrue(restarted.compareProjectAdoptions(scope, listOf(first), listOf(second)))
        assertFalse(store.compareProjectAdoptions(scope, listOf(first), emptyList()))
        assertEquals(listOf(second), store.readProjectAdoptions(scope))
        assertTrue(store.compareProjectAdoptions(scope, listOf(second), emptyList()))
        assertEquals(emptyList<ProjectAdoptionAttempt>(), restarted.readProjectAdoptions(scope))
    }
}
