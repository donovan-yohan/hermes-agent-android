package com.hermesagent.mobile.ui.sessions

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SessionExportContractTest {
    @Test fun `document destination requests JSON create without a filesystem path`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val intent = ActivityResultContracts.CreateDocument("application/json").createIntent(context, "session.json")
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, intent.action)
        assertEquals("application/json", intent.type)
        assertEquals("session.json", intent.getStringExtra(Intent.EXTRA_TITLE))
        assertNull(intent.data)
    }
    @Test fun `cancel has no output destination`() {
        assertNull(ActivityResultContracts.CreateDocument("application/json").parseResult(Activity.RESULT_CANCELED, Intent()))
    }
}
