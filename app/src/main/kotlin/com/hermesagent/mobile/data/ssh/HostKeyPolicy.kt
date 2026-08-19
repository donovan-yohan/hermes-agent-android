package com.hermesagent.mobile.data.ssh

import java.security.MessageDigest
import java.util.Base64

/**
 * Trust-on-first-use policy for host keys.
 *
 * Desktop delegates this to OpenSSH (`StrictHostKeyChecking=accept-new`) and
 * detects a change by regexing stderr
 * (`apps/desktop/electron/ssh-connection.ts:337-342` @ `f82f2dba`). Android has
 * no OpenSSH and no stderr, so the policy becomes real code — and gets to be
 * stricter than Desktop's: a change is a hard stop with no click-through.
 *
 * The rules, in order:
 * 1. A stored fingerprint that matches ⇒ [HostKeyVerdict.Trusted].
 * 2. A stored fingerprint that does not match ⇒ [HostKeyVerdict.Changed].
 *    Never a prompt. Never "accept anyway".
 * 3. No stored fingerprint ⇒ [HostKeyVerdict.FirstUse]: the connection stops
 *    before authentication and the user must accept the fingerprint explicitly.
 *
 * There is deliberately no accept-all path anywhere in this file or its
 * callers; `HostKeyPolicyTest` asserts each rule.
 */
sealed interface HostKeyVerdict {
    data object Trusted : HostKeyVerdict
    data class FirstUse(val presented: String) : HostKeyVerdict
    data class Changed(val expected: String, val presented: String) : HostKeyVerdict
}

fun evaluateHostKey(storedFingerprint: String?, presentedFingerprint: String): HostKeyVerdict = when {
    storedFingerprint.isNullOrBlank() -> HostKeyVerdict.FirstUse(presentedFingerprint)
    storedFingerprint == presentedFingerprint -> HostKeyVerdict.Trusted
    else -> HostKeyVerdict.Changed(storedFingerprint, presentedFingerprint)
}

/**
 * `SHA256:<base64-no-padding>` — the exact format `ssh-keygen -lf` prints, so
 * a user can compare what the phone shows against what the server says without
 * translating between two notations.
 */
fun sha256Fingerprint(publicKeyBlob: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(publicKeyBlob)
    return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest)
}
