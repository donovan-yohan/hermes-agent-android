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
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Platform destination; only transcript bytes cross this boundary, never a phone path. */
@Composable
internal fun rememberSessionExport(prepare: (suspend () -> ByteArray)?): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<ByteArray?>(null) }
    var busy by remember { mutableStateOf(false) }
    fun failed() {
        Toast.makeText(context, "Could not export session", Toast.LENGTH_LONG).show()
    }
    DisposableEffect(Unit) { onDispose { pending?.fill(0); pending = null } }
    val destination = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val bytes = pending
        pending = null
        if (uri == null || bytes == null) {
            bytes?.fill(0)
            busy = false
        } else scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    writeSessionExport(bytes) { context.contentResolver.openOutputStream(uri, "wt") }
                }
                Toast.makeText(context, "Session exported", Toast.LENGTH_SHORT).show()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                failed()
            } finally {
                bytes.fill(0)
                busy = false
            }
        }
    }
    return {
        if (!busy && prepare != null) {
            busy = true
            scope.launch {
                try {
                    pending = prepare()
                    destination.launch("session.json")
                } catch (cancelled: CancellationException) {
                    pending?.fill(0); pending = null; busy = false
                    throw cancelled
                } catch (_: Exception) {
                    pending?.fill(0); pending = null; busy = false
                    failed()
                }
            }
        }
    }
}

internal fun writeSessionExport(bytes: ByteArray, open: () -> java.io.OutputStream?) {
    val output = open() ?: error("Document unavailable")
    output.use { it.write(bytes) }
}
