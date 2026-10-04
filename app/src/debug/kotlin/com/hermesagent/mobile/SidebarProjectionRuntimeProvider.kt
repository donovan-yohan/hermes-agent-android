package com.hermesagent.mobile

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** Existing debug runtime-provider pattern; DUMP permission, synthetic state only. */
class SidebarProjectionRuntimeProvider : ContentProvider() {
    override fun onCreate() = true
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        require(method == "snapshot")
        val task = FutureTask {
            val fixture = checkNotNull(active) { "No active synthetic sidebar fixture" }
            val ui = fixture.viewModel.uiState.value
            buildJsonObject {
                put("fixture_id", "sidebar-projection-synthetic-v1")
                put("ready", fixture.ready)
                put("observed_draft_edits", fixture.observedDraftEdits)
                put("overview_reused", fixture.overviewReused)
                put("previews_reused", fixture.previewsReused)
                put("query", ui.query)
                put("selected_project", ui.selectedProject?.id.orEmpty())
                put("project_count", ui.projects.size)
                put("now_millis", ui.nowMillis)
                put("resolved_locale", java.util.Locale.getDefault().toLanguageTag())
                put("resolved_timezone", java.util.TimeZone.getDefault().id)
            }.toString()
        }
        Handler(Looper.getMainLooper()).post(task)
        return Bundle().apply {
            putString("snapshot", Base64.encodeToString(task.get(2, TimeUnit.SECONDS).toByteArray(Charsets.UTF_8), Base64.NO_WRAP))
        }
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
    companion object { internal var active: SidebarProjectionParityFixture? = null }
}
