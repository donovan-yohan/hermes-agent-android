package com.hermesagent.mobile.ui.sessions

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.hermesagent.mobile.ui.theme.AppearanceSelection
import com.hermesagent.mobile.ui.theme.HermesTheme
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

/** Real Compose launcher and ComponentActivity registry, with synthetic payload only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class SessionExportLifecycleTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Before fun reset() {
        ExportLifecycleActivity.bytes = "synthetic-transcript".toByteArray()
        ExportLifecycleActivity.current = true
        ExportLifecycleActivity.session.value = "session-a"
        ExportLifecycleActivity.prepares = 0
        ShadowToast.reset()
        RefusingExportProvider.opens = 0
        org.robolectric.shadows.ShadowContentResolver.registerProviderInternal("synthetic.export", RefusingExportProvider())
    }
    private fun launchPicker(activity: ExportLifecycleActivity): Int {
        compose.onNodeWithContentDescription(SESSION_ACTIONS_LABEL).performClick()
        compose.onNodeWithText(EXPORT).performClick()
        compose.waitForIdle()
        return requireNotNull(shadowOf(activity).nextStartedActivityForResult).requestCode
    }
    private fun deliver(activity: ExportLifecycleActivity, code: Int, result: Int = Activity.RESULT_OK) {
        activity.activityResultRegistry.dispatchResult(code, result,
            Intent().setData(android.net.Uri.parse("content://synthetic.export/destination")))
        compose.waitForIdle()
    }
    @Test fun selectedDestinationWritesExactBytesThroughActualLauncher() {
        val controller = Robolectric.buildActivity(ExportLifecycleActivity::class.java).setup().visible()
        try {
            val expected = ExportLifecycleActivity.bytes.copyOf()
            val output = java.io.ByteArrayOutputStream()
            shadowOf(controller.get().contentResolver).registerOutputStream(
                android.net.Uri.parse("content://synthetic.export/destination"), output)
            val code = launchPicker(controller.get())
            deliver(controller.get(), code)
            compose.waitUntil(5_000) { ExportLifecycleActivity.bytes.all { it == 0.toByte() } }
            compose.waitForIdle()
            assertArrayEquals(expected, output.toByteArray())
            assertEquals("Session exported", ShadowToast.getTextOfLatestToast())
        } finally { controller.pause().stop().destroy() }
    }
    @Test fun recreationAbandonsBytesAndReportsFailureOnDelayedSave() {
        val controller = Robolectric.buildActivity(ExportLifecycleActivity::class.java).setup().visible()
        try {
            val code = launchPicker(controller.get())
            val saved = Bundle()
            controller.saveInstanceState(saved)
            assertFalse(saved.toString().contains("synthetic-transcript"))
            controller.recreate()
            compose.waitForIdle()
            assertTrue(ExportLifecycleActivity.bytes.all { it == 0.toByte() })
            deliver(controller.get(), code)
            assertEquals("Could not export session", ShadowToast.getTextOfLatestToast())
            assertEquals(0, RefusingExportProvider.opens)
            assertEquals(1, ExportLifecycleActivity.prepares)
        } finally { controller.pause().stop().destroy() }
    }
    @Test fun staleEndpointResultIsRefusedBeforeDestinationIsOpened() {
        val controller = Robolectric.buildActivity(ExportLifecycleActivity::class.java).setup().visible()
        try {
            val code = launchPicker(controller.get())
            ExportLifecycleActivity.current = false
            deliver(controller.get(), code)
            assertTrue(ExportLifecycleActivity.bytes.all { it == 0.toByte() })
            assertEquals("Could not export session", ShadowToast.getTextOfLatestToast())
            assertEquals(0, RefusingExportProvider.opens)
        } finally { controller.pause().stop().destroy() }
    }
    @Test fun abandonedSessionPickerCannotConsumeNewOperation() {
        val controller = Robolectric.buildActivity(ExportLifecycleActivity::class.java).setup().visible()
        try {
            val code = launchPicker(controller.get())
            compose.runOnIdle { ExportLifecycleActivity.session.value = "session-b" }
            compose.waitForIdle()
            compose.onNodeWithContentDescription(SESSION_ACTIONS_LABEL).performClick()
            compose.onNodeWithText(EXPORT).performClick()
            compose.waitForIdle()
            assertEquals("outstanding picker must drain before another launch", 1, ExportLifecycleActivity.prepares)
            deliver(controller.get(), code)
            assertEquals("Could not export session", ShadowToast.getTextOfLatestToast())
            assertEquals(0, RefusingExportProvider.opens)
            ExportLifecycleActivity.bytes = "new synthetic transcript".toByteArray()
            val next = launchPicker(controller.get())
            assertEquals(2, ExportLifecycleActivity.prepares)
            deliver(controller.get(), next, Activity.RESULT_CANCELED)
            assertTrue(ExportLifecycleActivity.bytes.all { it == 0.toByte() })
        } finally { controller.pause().stop().destroy() }
    }
}

class RefusingExportProvider : android.content.ContentProvider() {
    override fun onCreate() = true
    override fun getType(uri: android.net.Uri) = "application/json"
    override fun query(uri: android.net.Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): android.database.Cursor? = null
    override fun insert(uri: android.net.Uri, values: android.content.ContentValues?): android.net.Uri? = null
    override fun delete(uri: android.net.Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: android.net.Uri, values: android.content.ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun openFile(uri: android.net.Uri, mode: String): android.os.ParcelFileDescriptor {
        opens++
        throw java.io.FileNotFoundException("Synthetic destination must not be opened")
    }
    companion object { var opens = 0 }
}

class ExportLifecycleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            HermesTheme(AppearanceSelection()) {
                SessionActionsControl(sessionId = session.value, onExport = {
                    prepares++
                    PreparedSessionExport(bytes) { current }
                })
            }
        }
    }
    companion object {
        var bytes = byteArrayOf()
        var current = true
        var prepares = 0
        val session = mutableStateOf("session-a")
    }
}
