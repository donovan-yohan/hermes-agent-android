package com.hermesagent.mobile.data.ssh

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.InputStream

/**
 * What reading a picked key document produced.
 *
 * [Read] is not "a key" — it is bytes inside the cap, which still have to
 * decode and still have to look like a PEM private key. Deciding that is
 * [looksLikePrivateKey]'s job, and it happens after this.
 */
internal sealed interface KeyDocument {

    /** Bytes within [MAX_KEY_BYTES], plus the provider's own name for them. */
    class Read(val bytes: ByteArray, val displayName: String) : KeyDocument

    data class Refused(val problem: KeyImportProblem) : KeyDocument
}

/**
 * Main-thread epoch gate for asynchronous document reads.
 *
 * A picker result can outlive the Gateways screen while its DocumentsProvider
 * is still reading on IO. Invalidating the gate makes that result stale; when
 * it eventually returns, [claim] wipes the bytes instead of handing a key back
 * to a screen whose secret lifetime already ended.
 */
internal class KeyImportGate {
    private var generation = 0L

    fun begin(): Long = ++generation

    fun invalidate() {
        generation++
    }

    fun claim(token: Long, document: KeyDocument): KeyDocument? {
        if (token == generation) return document
        if (document is KeyDocument.Read) document.bytes.fill(0)
        return null
    }
}

/**
 * Reads a picked document off the caller's thread.
 *
 * The Storage Access Framework hands its result to an Activity callback on the
 * **main** thread, and both halves of reading one are IPC to another process:
 * `openInputStream` on a DocumentsProvider that has to fetch the file first
 * (Drive, OneDrive, anything on a network) can hold that thread for seconds,
 * which is the ANR-eligible case. A local file returns fast, which is exactly
 * why a device pass does not surface it.
 *
 * The two provider calls are parameters rather than a `ContentResolver` so this
 * is testable on a plain JVM: the contract worth gating is that the work
 * happens on [io], that the read is bounded, and that an oversized document is
 * refused with its bytes already wiped rather than truncated into a key that
 * can never parse.
 */
internal suspend fun readKeyDocument(
    io: CoroutineDispatcher,
    openStream: () -> InputStream?,
    displayName: () -> String,
): KeyDocument = withContext(io) {
    // One byte past the cap, so an oversized file is refused rather than
    // silently truncated.
    val bytes = runCatching { openStream()?.use { it.readBounded(MAX_KEY_BYTES + 1) } }.getOrNull()
        ?: return@withContext KeyDocument.Refused(KeyImportProblem.Unreadable)

    if (bytes.size > MAX_KEY_BYTES) {
        bytes.fill(0)
        return@withContext KeyDocument.Refused(KeyImportProblem.TooLarge)
    }

    KeyDocument.Read(bytes, runCatching(displayName).getOrDefault(""))
}
