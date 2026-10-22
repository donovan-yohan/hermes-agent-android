package com.hermesagent.mobile.ui.sessions

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** In-memory only; validity is checked again before opening a delayed picker destination. */
class PreparedSessionExport(val bytes: ByteArray, val isCurrent: () -> Boolean)

/** Platform destination; saved state contains only the opaque operation id, never transcript bytes. */
@Composable
internal fun rememberSessionExport(sessionId: String, prepare: (suspend () -> PreparedSessionExport)?): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentSession by rememberUpdatedState(sessionId)
    var operation by rememberSaveable { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<PreparedSessionExport?>(null) }
    var pendingOperation by remember { mutableStateOf<String?>(null) }
    var preparing by remember { mutableStateOf(false) }
    fun failed() {
        Toast.makeText(context, "Could not export session", Toast.LENGTH_LONG).show()
    }
    // Changing rows or recreating the Activity abandons the in-memory payload. Keep the
    // outstanding picker id until its callback drains: an old result cannot consume a new export.
    DisposableEffect(sessionId) { onDispose { pending?.bytes?.fill(0); pending = null } }
    val destination = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val payload = pending
        val token = operation
        if (uri == null) {
            payload?.bytes?.fill(0); pending = null; pendingOperation = null; operation = null
        } else if (payload == null || token == null || token != pendingOperation || !payload.isCurrent()) {
            payload?.bytes?.fill(0); pending = null; pendingOperation = null; operation = null
            // A recreated Activity cannot recover private bytes; report failure rather than a silent empty save.
            failed()
        } else {
            val owner = currentSession
            // Completion handlers also run if this job is cancelled BEFORE its body starts.
            launchSessionExportWrite(scope, payload.bytes) {
                try {
                    withContext(Dispatchers.IO) {
                        check(currentSession == owner && pendingOperation == token && pending === payload && payload.isCurrent())
                        writeSessionExport(payload.bytes) { context.contentResolver.openOutputStream(uri, "wt") }
                    }
                    Toast.makeText(context, "Session exported", Toast.LENGTH_SHORT).show()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    failed()
                }
            }.invokeOnCompletion {
                pending = null; pendingOperation = null; operation = null
            }
        }
    }
    return {
        if (operation == null && !preparing && prepare != null) {
            preparing = true
            val owner = sessionId
            val token = java.util.UUID.randomUUID().toString()
            scope.launch {
                var prepared: PreparedSessionExport? = null
                try {
                    prepared = prepare()
                    if (currentSession != owner || !prepared.isCurrent()) {
                        prepared.bytes.fill(0)
                        failed()
                    } else {
                        pending = prepared
                        pendingOperation = token
                        operation = token
                        destination.launch("session.json")
                    }
                } catch (cancelled: CancellationException) {
                    prepared?.bytes?.fill(0); pending = null; pendingOperation = null; operation = null
                    throw cancelled
                } catch (_: Exception) {
                    prepared?.bytes?.fill(0); pending = null; pendingOperation = null; operation = null
                    failed()
                } finally { preparing = false }
            }.invokeOnCompletion { preparing = false }
        }
    }
}

/** Register cleanup outside the body: a cancelled-before-start coroutine never enters finally. */
internal fun launchSessionExportWrite(scope: kotlinx.coroutines.CoroutineScope, bytes: ByteArray,
    write: suspend () -> Unit): kotlinx.coroutines.Job = scope.launch { write() }.also { job ->
    job.invokeOnCompletion { bytes.fill(0) }
}

internal fun writeSessionExport(bytes: ByteArray, open: () -> java.io.OutputStream?) {
    val output = open() ?: error("Document unavailable")
    output.use { it.write(bytes) }
}
