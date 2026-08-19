package com.hermesagent.mobile.data.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The destination field is now the only place host, port and username come
 * from, so its parser is load-bearing: a wrong answer here either dials the
 * wrong box or refuses a correct address.
 */
class SshDestinationTest {

    private fun parse(raw: String): SshDestination {
        val result = parseSshDestination(raw)
        return when (result) {
            is DestinationParse.Valid -> result.destination
            is DestinationParse.Invalid -> throw AssertionError("`$raw` was rejected: ${result.reason}")
        }
    }

    private fun reject(raw: String): String {
        val result = parseSshDestination(raw)
        return when (result) {
            is DestinationParse.Invalid -> result.reason
            is DestinationParse.Valid -> throw AssertionError("`$raw` was accepted as ${result.destination}")
        }
    }

    @Test
    fun `a bare user and host default to port 22`() {
        assertEquals(SshDestination("donovanyohan", "dev", 22), parse("donovanyohan@dev"))
        assertEquals(SshDestination("hermes", "hermes-box.local", 22), parse("hermes@hermes-box.local"))
    }

    @Test
    fun `an explicit port is taken from the destination`() {
        assertEquals(SshDestination("hermes", "hermes-box", 2222), parse("hermes@hermes-box:2222"))
        assertEquals(SshDestination("hermes", "hermes-box", 1), parse("hermes@hermes-box:1"))
        assertEquals(SshDestination("hermes", "hermes-box", 65535), parse("hermes@hermes-box:65535"))
    }

    @Test
    fun `an IPv6 address is read from brackets, with or without a port`() {
        assertEquals(SshDestination("hermes", "fd7a:115c::1", 2222), parse("hermes@[fd7a:115c::1]:2222"))
        assertEquals(SshDestination("hermes", "fd7a:115c::1", 22), parse("hermes@[fd7a:115c::1]"))
        assertEquals(SshDestination("hermes", "::1", 22), parse("hermes@[::1]"))
    }

    @Test
    fun `outer whitespace is trimmed, because a paste usually carries some`() {
        assertEquals(SshDestination("hermes", "dev", 22), parse("  hermes@dev\n"))
        assertEquals(SshDestination("hermes", "dev", 2222), parse("\thermes@dev:2222 "))
    }

    @Test
    fun `whitespace inside is refused rather than stripped`() {
        // Silently deleting it would turn a typo into a different, valid host.
        reject("her mes@dev")
        reject("hermes@he rmes-box")
        reject("hermes @dev")
    }

    @Test
    fun `a missing user or host is refused`() {
        reject("")
        reject("   ")
        reject("dev")
        reject("@dev")
        reject("hermes@")
        reject("hermes@:2222")
    }

    @Test
    fun `an unusable port is refused instead of falling back to 22`() {
        reject("hermes@dev:")
        reject("hermes@dev:0")
        reject("hermes@dev:65536")
        reject("hermes@dev:ssh")
        reject("hermes@dev:+22")
        reject("hermes@dev:-22")
        reject("hermes@dev:2 2")
    }

    @Test
    fun `a bare IPv6 address is refused as ambiguous`() {
        // `fd7a:115c::1:2222` is either a host with a port or an address; the
        // parser must not guess, and `ssh` does not either.
        val reason = reject("hermes@fd7a:115c::1:2222")
        assertTrue("the message must name brackets as the fix, got: $reason", reason.contains("brackets"))
        reject("hermes@fd7a:115c::1")
        reject("hermes@[fd7a:115c::1:2222")
        reject("hermes@[fd7a:115c::1]2222")
    }

    @Test
    fun `formatting leaves port 22 implicit and brackets IPv6`() {
        assertEquals("hermes@dev", SshDestination("hermes", "dev").format())
        assertEquals("hermes@dev:2222", SshDestination("hermes", "dev", 2222).format())
        assertEquals("hermes@[fd7a:115c::1]", SshDestination("hermes", "fd7a:115c::1").format())
        assertEquals("hermes@[fd7a:115c::1]:2222", SshDestination("hermes", "fd7a:115c::1", 2222).format())
    }

    @Test
    fun `formatting round-trips through the parser`() {
        val destinations = listOf(
            SshDestination("donovanyohan", "dev"),
            SshDestination("hermes", "hermes-box.local", 2222),
            SshDestination("hermes", "100.64.0.1", 22),
            SshDestination("hermes", "fd7a:115c::1"),
            SshDestination("hermes", "fd7a:115c::1", 2222),
        )
        for (destination in destinations) {
            assertEquals(destination, parse(destination.format()))
        }
    }

    @Test
    fun `parsing round-trips through the formatter`() {
        for (text in listOf("hermes@dev", "hermes@dev:2222", "hermes@[fd7a:115c::1]:2222")) {
            assertEquals(text, parse(text).format())
        }
        // Port 22 typed out loud is the one non-identity case, and it is a
        // normalisation, not a loss.
        assertEquals("hermes@dev", parse("hermes@dev:22").format())
    }
}
