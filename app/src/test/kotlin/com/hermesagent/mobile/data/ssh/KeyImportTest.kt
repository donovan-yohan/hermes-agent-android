package com.hermesagent.mobile.data.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.CharBuffer

/**
 * What the key picker is allowed to accept, and what it is allowed to show.
 *
 * Both halves come from another app: the bytes are the user's file, and the
 * name is whatever the content provider says it is. Neither is trusted here.
 *
 * The awkward-looking characters are written as escapes on purpose — an
 * invisible character in a test about invisible characters is a test nobody can
 * review.
 */
class KeyImportTest {

    /** Header and footer only. No key material exists anywhere in this repo. */
    private val body = "b3BlbnNzaC1rZXktdjEAAAAABG5vbmU"

    @Test
    fun `a PEM private key is accepted, whatever the algorithm says`() {
        for (label in listOf("OPENSSH ", "RSA ", "EC ", "ENCRYPTED ", "")) {
            val pem = "-----BEGIN ${label}PRIVATE KEY-----\n$body\n-----END ${label}PRIVATE KEY-----\n"
            assertTrue("`$label` should be a private key", looksLikePrivateKey(pem))
        }
    }

    @Test
    fun `surrounding blank lines are fine`() {
        val pem = "\n\n-----BEGIN OPENSSH PRIVATE KEY-----\n$body\n-----END OPENSSH PRIVATE KEY-----\n\n"

        assertTrue(looksLikePrivateKey(pem))
    }

    @Test
    fun `a document that merely mentions a private key is refused`() {
        // The check this replaces was `contains("PRIVATE KEY")`, which said yes
        // to every one of these and then showed "Key loaded" on screen.
        val junk = listOf(
            "Paste your PRIVATE KEY here and press enter.",
            "# How to make a PRIVATE KEY with ssh-keygen",
            "-----BEGIN OPENSSH PRIVATE KEY-----",
            "-----END OPENSSH PRIVATE KEY-----",
            "PRIVATE KEY",
            "",
            "   ",
        )

        for (text in junk) {
            assertFalse("`$text` is not a key", looksLikePrivateKey(text))
        }
    }

    @Test
    fun `a key with anything appended is refused`() {
        val pem = "-----BEGIN OPENSSH PRIVATE KEY-----\n$body\n-----END OPENSSH PRIVATE KEY-----\n" +
            "and then something else entirely"

        assertFalse("the whole document has to be the key", looksLikePrivateKey(pem))
    }

    @Test
    fun `a PEM with mismatched delimiters is refused`() {
        val pem = "-----BEGIN OPENSSH PRIVATE KEY-----\n$body\n-----END RSA PRIVATE KEY-----\n"

        assertFalse("the footer must name the key type the header opened", looksLikePrivateKey(pem))
    }

    @Test
    fun `concatenated PEM documents are refused`() {
        val one = "-----BEGIN OPENSSH PRIVATE KEY-----\n$body\n-----END OPENSSH PRIVATE KEY-----\n"

        assertFalse("an import is exactly one key document", looksLikePrivateKey(one + one))
    }

    @Test
    fun `a public key is not a private key`() {
        assertFalse(looksLikePrivateKey("ssh-ed25519 AAAAC3NzaC1lZDI1NTE5 you@example"))
    }

    @Test
    fun `a char array is checked without minting a string copy of the key`() {
        val pem = "-----BEGIN OPENSSH PRIVATE KEY-----\n$body\n-----END OPENSSH PRIVATE KEY-----\n"

        assertTrue(looksLikePrivateKey(CharBuffer.wrap(pem.toCharArray())))
    }

    @Test
    fun `an ordinary file name survives intact`() {
        assertEquals("id_ed25519", sanitizeKeyDisplayName("id_ed25519"))
        assertEquals("hermes key.pem", sanitizeKeyDisplayName("hermes key.pem"))
    }

    @Test
    fun `a name that tries to look like another file is defused`() {
        // U+202E reverses everything after it, so this renders as `key exe.png`.
        val spoofed = "key\u202egnp.exe"

        val safe = sanitizeKeyDisplayName(spoofed)

        assertFalse("no bidirectional override may reach the screen", safe.contains('\u202e'))
        assertEquals("keygnp.exe", safe)
    }

    @Test
    fun `control characters and separators are removed`() {
        assertEquals("a bell is not a character", "keyfile", sanitizeKeyDisplayName("key\u0007file"))
        assertEquals("a name is one row", "a b", sanitizeKeyDisplayName("a\nb"))
        assertEquals("with one space between words", "a b", sanitizeKeyDisplayName("a\t \r\n b"))
        assertEquals("a name cannot be a path", "....etcpasswd", sanitizeKeyDisplayName("../../etc/passwd"))
        assertEquals("nor hide a zero-width mark", "abc", sanitizeKeyDisplayName("a\u200bb\u200ec"))
    }

    @Test
    fun `a name is capped rather than allowed to push a row off screen`() {
        val safe = sanitizeKeyDisplayName("k".repeat(5_000))

        assertEquals(64, safe.length)
    }

    @Test
    fun `a name that is nothing at all falls back to something readable`() {
        assertEquals("imported key", sanitizeKeyDisplayName(null))
        assertEquals("imported key", sanitizeKeyDisplayName(""))
        assertEquals("imported key", sanitizeKeyDisplayName("   "))
        assertEquals("imported key", sanitizeKeyDisplayName(" \u202e "))
    }

    @Test
    fun `each refusal says something different`() {
        val messages = KeyImportProblem.entries.map(KeyImportProblem::message)

        assertEquals(KeyImportProblem.entries.size, messages.toSet().size)
        assertTrue("every one has to be a sentence", messages.all { it.length > 30 })
    }
}
