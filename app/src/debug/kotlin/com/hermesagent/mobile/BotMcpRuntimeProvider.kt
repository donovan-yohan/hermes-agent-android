package com.hermesagent.mobile

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** Debug source set only. No app repositories, files, credentials or network access. */
class BotMcpRuntimeProvider : ContentProvider() {
    override fun onCreate() = true
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        require(method == "snapshot")
        val task = FutureTask {
            checkNotNull(snapshot) { "No active synthetic MCP fixture" }.invoke().toString()
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
    companion object { internal var snapshot: (() -> JsonObject)? = null }
}
