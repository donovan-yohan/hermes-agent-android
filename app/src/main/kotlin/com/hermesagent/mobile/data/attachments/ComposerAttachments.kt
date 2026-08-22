package com.hermesagent.mobile.data.attachments

import java.util.Base64

/** What kind of Gateway staging route an attachment takes. */
enum class AttachmentKind { Image, File }

/**
 * Where locally acquired attachment bytes came from. The URI string exists only
 * here while the activity-scoped grant is valid; it never reaches a wire DTO.
 */
sealed interface AttachmentSource {
    /** SAF picker or ACTION_SEND grant. [uriString] is a content:// URI. */
    data class Granted(val uriString: String) : AttachmentSource

    /** Bytes already in memory (clipboard image). Owned and wiped after read. */
    data class InMemory(val bytes: ByteArray) : AttachmentSource {
        override fun equals(other: Any?): Boolean = other is InMemory && bytes.contentEquals(other.bytes)
        override fun hashCode(): Int = bytes.contentHashCode()
        // Deliberately no toString of contents.
        override fun toString(): String = "AttachmentSource.InMemory(${bytes.size} bytes)"
    }
}

/** How far one locally acquired attachment has travelled toward the Gateway. */
sealed interface AttachmentStage {
    data object Reading : AttachmentStage
    /** Read and validated locally; ready to stage at submit time. */
    data class Ready(val byteCount: Int) : AttachmentStage
    data class Staging(val phaseLabel: String) : AttachmentStage
    /** The Gateway accepted the bytes; only this reference is prompt-safe. */
    data class Staged(val reference: StagedAttachmentReference) : AttachmentStage
    /** Local refusal (too large, unknown type, read failure). Retryable in place. */
    data class Refused(val safeMessage: String) : AttachmentStage
}

data class StagedAttachmentReference(
    /** Prompt-safe text, e.g. `@file:`name`` or an image mention supplied by the Gateway. */
    val refText: String,
    /** Server-side path for best-effort detach cleanup, when provided. */
    val gatewayPath: String? = null,
)

/**
 * One locally acquired attachment draft. Occurrence-scoped and memory-only;
 * instances never persist and never serialize a URI, path, or payload byte.
 */
data class ComposerAttachmentDraft(
    val occurrenceId: String,
    val durableSessionId: String,
    val displayName: String,
    val kind: AttachmentKind,
    val stage: AttachmentStage,
)

/** A payload crossing to the Gateway. Structurally incapable of carrying a path. */
sealed interface OutgoingAttachment {
    val displayName: String

    data class Image(override val displayName: String, val contentBase64: String) : OutgoingAttachment
    data class GenericFile(override val displayName: String, val dataUrl: String) : OutgoingAttachment
}

object AttachmentPolicy {
    const val MAX_BYTES_PER_ATTACHMENT = 8 * 1024 * 1024
    const val MAX_TOTAL_BYTES = 16 * 1024 * 1024
    const val MAX_ATTACHMENTS_PER_MESSAGE = 8
    const val MAX_NAME_LENGTH = 80

    fun sanitizeDisplayName(raw: String): String =
        raw.substringAfterLast('/').replace(Regex("\\s+"), " ").trim().take(MAX_NAME_LENGTH)
            .ifBlank { "attachment" }

    /** Sniff image magic; everything else routes through the generic file path. */
    fun classify(bytes: ByteArray): AttachmentKind =
        when {
            startsWith(bytes, byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)) -> AttachmentKind.Image
            startsWith(bytes, byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())) -> AttachmentKind.Image
            startsWith(bytes, byteArrayOf(0x47, 0x49, 0x46, 0x38)) -> AttachmentKind.Image
            startsWith(bytes, byteArrayOf(0x52, 0x49, 0x46, 0x46)) -> AttachmentKind.Image
            startsWith(bytes, byteArrayOf(0x42, 0x4D)) -> AttachmentKind.Image
            else -> AttachmentKind.File
        }

    private fun startsWith(bytes: ByteArray, prefix: ByteArray): Boolean =
        bytes.size >= prefix.size && prefix.indices.all { bytes[it] == prefix[it] }
}

/** Result of reading one local source. Payload bytes live only until staging. */
sealed interface AttachmentReadResult {
    data class Read(
        val bytes: ByteArray,
        val kind: AttachmentKind,
        val displayName: String,
        val claimedMime: String?,
    ) : AttachmentReadResult

    data class Refused(val safeMessage: String) : AttachmentReadResult
}

/**
 * Bounded reader for one attachment source. Reads at most cap+1 bytes so an
 * oversize source is refused without allocating its full size; refused payloads
 * are wiped before returning.
 */
object AttachmentReader {
    suspend fun read(
        openStream: (() -> java.io.InputStream?)?,
        rawDisplayName: String,
        claimedMime: String?,
        cap: Int = AttachmentPolicy.MAX_BYTES_PER_ATTACHMENT,
    ): AttachmentReadResult {
        if (openStream == null) {
            return AttachmentReadResult.Refused("That file could not be opened. Try picking it again.")
        }
        val buffer = java.io.ByteArrayOutputStream()
        return try {
            openStream().use { stream ->
                if (stream == null) {
                    return AttachmentReadResult.Refused("That file could not be opened. Try picking it again.")
                }
                val chunk = ByteArray(64 * 1024)
                var total = 0
                while (true) {
                    val n = stream.read(chunk)
                    if (n < 0) break
                    total += n
                    if (total > cap) {
                        java.util.Arrays.fill(chunk, 0)
                        buffer.reset()
                        return AttachmentReadResult.Refused(
                            "That file is larger than ${cap / (1024 * 1024)} MB. Pick a smaller file.",
                        )
                    }
                    buffer.write(chunk, 0, n)
                }
            }
            val bytes = buffer.toByteArray()
            if (bytes.isEmpty()) {
                return AttachmentReadResult.Refused("That file is empty.")
            }
            val kind = AttachmentPolicy.classify(bytes)
            AttachmentReadResult.Read(
                bytes = bytes,
                kind = kind,
                displayName = AttachmentPolicy.sanitizeDisplayName(rawDisplayName),
                claimedMime = claimedMime?.takeIf(String::isNotBlank),
            )
        } catch (_: SecurityException) {
            AttachmentReadResult.Refused("Access to that file was denied. Pick it again.")
        } catch (_: Exception) {
            AttachmentReadResult.Refused("That file could not be read. Try again.")
        }
    }
}

/** Base64 helpers used exactly once per stage request; nothing retained afterwards. */
object AttachmentEncoding {
    fun base64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    fun dataUrl(mime: String?, bytes: ByteArray): String {
        val safeMime = mime?.takeIf(String::isNotBlank) ?: "application/octet-stream"
        return "data:$safeMime;base64," + base64(bytes)
    }
}
