package com.hermesagent.mobile.data.connections

import com.hermesagent.mobile.data.gateway.LocalGatewayProfile
import com.hermesagent.mobile.data.gateway.RemoteGatewayProfile
import com.hermesagent.mobile.data.ssh.AuthMethod
import com.hermesagent.mobile.data.ssh.HostProfile
import com.hermesagent.mobile.data.ssh.SshDestination
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules a registry row obeys, ported from Desktop's dedupe and display
 * helpers at `3ca096de5f8183cb2e0ec23673f294d5978656a3`
 * (`app/settings/connections-registry.tsx:89-168`,
 * `lib/connection-display.ts:3-75`) plus the one Android rule Desktop has no
 * equivalent for: host-key trust is scoped to a host and a port, per row.
 *
 * No value here is a real host, user, fingerprint or URL.
 */
class ConnectionRegistryTest {

    @Test
    fun `remote rows collide on the normalized gateway URL`() {
        val existing = remote("one", "Alpha", "https://Example.Test/hermes/")
        val candidate = remote("two", "Beta", "  https://example.test/hermes  ")

        assertEquals(existing, findDuplicateConnection(candidate, listOf(existing)))
        assertNull("a row never duplicates itself", findDuplicateConnection(existing, listOf(existing)))
    }

    @Test
    fun `an SSH row collides on user host port plus the remote profile`() {
        val existing = ssh("one", "Alpha", user = "demo-user", host = "demo-host", port = 22)
        val implicitPort = ssh("two", "Beta", user = "demo-user", host = "Demo-Host")
        val otherProfile = ssh("three", "Gamma", user = "demo-user", host = "demo-host", profile = "review")
        val otherPort = ssh("four", "Delta", user = "demo-user", host = "demo-host", port = 2222)

        assertEquals(existing, findDuplicateConnection(implicitPort, listOf(existing)))
        assertNull("a different remote profile is a different install", findDuplicateConnection(otherProfile, listOf(existing)))
        assertNull("a different port is a different sshd", findDuplicateConnection(otherPort, listOf(existing)))
    }

    @Test
    fun `an incomplete endpoint never claims a duplicate`() {
        val existing = remote("one", "Alpha", "https://example.test")

        assertNull(findDuplicateConnection(remote("two", "Beta", ""), listOf(existing)))
        assertNull(findDuplicateConnection(ssh("three", "Gamma", user = "", host = ""), listOf(existing)))
    }

    @Test
    fun `kinds never collide with each other`() {
        val remote = remote("one", "Alpha", "https://example.test")
        val ssh = ssh("two", "Beta", user = "demo-user", host = "example.test")

        assertNull(findDuplicateConnection(ssh, listOf(remote)))
        assertNull(findDuplicateConnection(remote, listOf(ssh)))
    }

    @Test
    fun `the SSH composite key makes the default port explicit`() {
        assertEquals("demo-user@demo-host:22", sshCompositeKey("Demo-User@Demo-Host"))
        assertEquals("demo-user@demo-host:22", sshCompositeKey(" demo-user@demo-host:22 "))
        assertEquals("demo-user@demo-host:2222", sshCompositeKey("demo-user@demo-host:2222"))
        assertEquals("", sshCompositeKey("   "))
    }

    @Test
    fun `the gateway URL key trims, drops trailing slashes and lowercases`() {
        assertEquals("https://example.test/hermes", normalizeGatewayUrl(" https://Example.Test/hermes/// "))
    }

    @Test
    fun `display order is by label, numerically aware, with the id breaking ties`() {
        val rows = listOf(
            remote("c", "gateway 10", "https://c.test"),
            remote("a", "Gateway 2", "https://a.test"),
            remote("b", "gateway 2", "https://b.test"),
        )

        assertEquals(
            listOf("a", "b", "c"),
            sortConnectionsForDisplay(rows).map(SavedConnection::id),
        )
    }

    @Test
    fun `a gateway on this device is anchored above every gateway that is not`() {
        val rows = listOf(
            remote("b", "Alpha", "https://b.test"),
            SavedConnection(
                id = "a",
                label = "Zulu",
                kind = ConnectionKind.Local,
                local = LocalGatewayProfile(baseUrl = "http://127.0.0.1:9119"),
            ),
        )

        assertEquals(
            "Desktop's local anchor: the row on this device leads, whatever it is called",
            listOf("a", "b"),
            sortConnectionsForDisplay(rows).map(SavedConnection::id),
        )
    }

    @Test
    fun `search matches every needle across the non-secret details`() {
        val row = ssh("one", "Homelab box", user = "demo-user", host = "demo-host", port = 2222)

        assertTrue(connectionMatchesQuery(row, ""))
        assertTrue(connectionMatchesQuery(row, "homelab demo-host"))
        assertTrue("the port is a detail people remember", connectionMatchesQuery(row, "2222"))
        assertTrue("kind labels are searchable as aliases", connectionMatchesQuery(row, "ssh", listOf("SSH")))
        assertFalse(connectionMatchesQuery(row, "homelab missing"))
    }

    @Test
    fun `search ignores accents rather than demanding them`() {
        val row = remote("one", "Café", "https://example.test")

        assertTrue(connectionMatchesQuery(row, "cafe"))
    }

    @Test
    fun `the endpoint summary is the one canonical non-secret string`() {
        assertEquals("demo-user@demo-host", ssh("one", "Alpha", user = "demo-user", host = "demo-host").endpoint)
        assertEquals(
            "demo-user@demo-host:2222",
            ssh("two", "Beta", user = "demo-user", host = "demo-host", port = 2222).endpoint,
        )
        assertEquals("https://example.test", remote("three", "Gamma", "https://example.test").endpoint)
        assertNull(remote("four", "Delta", "").endpoint)
    }

    @Test
    fun `changing a row's host or port drops that row's accepted fingerprint`() {
        val trusted = ssh("one", "Alpha", user = "demo-user", host = "demo-host").let {
            it.copy(host = it.host.copy(acceptedFingerprint = FINGERPRINT))
        }

        val renamedUser = trusted.copy(
            host = trusted.host.withDestination(SshDestination("other-user", "demo-host")),
        )
        val movedHost = trusted.copy(
            host = trusted.host.withDestination(SshDestination("demo-user", "other-host")),
        )
        val movedPort = trusted.copy(
            host = trusted.host.withDestination(SshDestination("demo-user", "demo-host", 2222)),
        )

        assertEquals("same box, same key", FINGERPRINT, renamedUser.host.acceptedFingerprint)
        assertNull("a different host has never been reviewed", movedHost.host.acceptedFingerprint)
        assertNull("a different port is a different sshd", movedPort.host.acceptedFingerprint)
    }

    @Test
    fun `trust and the secret slot are per row, never shared`() {
        val first = ssh("one", "Alpha", user = "demo-user", host = "demo-host")
            .let { it.copy(host = it.host.copy(acceptedFingerprint = FINGERPRINT)) }
        val second = ssh("two", "Beta", user = "demo-user", host = "other-host")

        assertNull(second.host.acceptedFingerprint)
        assertEquals(FINGERPRINT, first.host.acceptedFingerprint)
        assertEquals("one", remote("one", "Alpha", "https://example.test").remoteProfile.secretSlotId)
        assertEquals("two", remote("two", "Beta", "https://example.test").remoteProfile.secretSlotId)
    }

    @Test
    fun `the stored document round-trips every field a row carries`() {
        val rows = listOf(
            remote("one", "Alpha", "https://example.test/hermes").let {
                it.copy(remote = it.remote.copy(provider = "fixture-provider"))
            },
            ssh("two", "Beta", user = "demo-user", host = "demo-host", port = 2222, profile = "review").let {
                it.copy(
                    host = it.host.copy(
                        authMethod = AuthMethod.PrivateKey,
                        acceptedFingerprint = FINGERPRINT,
                    ),
                )
            },
        )

        assertEquals(rows, ConnectionRegistryCodec.decode(ConnectionRegistryCodec.encode(rows)))
    }

    @Test
    fun `a future document fails closed and one bad row does not take the others down`() {
        assertEquals(emptyList<SavedConnection>(), ConnectionRegistryCodec.decode("""{"version":"2","connections":[]}"""))
        assertEquals(emptyList<SavedConnection>(), ConnectionRegistryCodec.decode("not json"))
        assertEquals(emptyList<SavedConnection>(), ConnectionRegistryCodec.decode(null))

        val mixed = """{"version":"1","connections":[{"label":"no id"},{"id":"two","label":"Beta","kind":"Ssh"}]}"""
        assertEquals(listOf("two"), ConnectionRegistryCodec.decode(mixed).map(SavedConnection::id))
    }

    @Test
    fun `an unrecognised stored auth method falls back to Password, never to a keyless one`() {
        val stored = """{"version":"1","connections":[{"id":"one","label":"Alpha","kind":"Ssh","authMethod":"Future"}]}"""

        assertEquals(AuthMethod.Password, ConnectionRegistryCodec.decode(stored).single().host.authMethod)
    }

    @Test
    fun `a Local row round-trips through the one stored address field`() {
        val rows = listOf(local("one", "This phone", "http://127.0.0.1:9119"))

        val stored = ConnectionRegistryCodec.encode(rows)

        assertEquals(rows, ConnectionRegistryCodec.decode(stored))
        assertTrue(
            "the address is written to the one url field, not a second one",
            stored.contains("\"url\":\"http://127.0.0.1:9119\""),
        )
    }

    @Test
    fun `a Local row collides on the loopback address, however it was spelled`() {
        val existing = local("one", "This phone", "http://127.0.0.1:9119")
        val sameServer = local("two", "Also this phone", "http://localhost:9119")
        val otherPort = local("three", "The other one", "http://127.0.0.1:9200")

        assertEquals(existing, findDuplicateConnection(sameServer, listOf(existing)))
        assertNull(findDuplicateConnection(otherPort, listOf(existing)))
        assertNull("a row never duplicates itself", findDuplicateConnection(existing, listOf(existing)))
    }

    @Test
    fun `a Local row states its address and how it proves itself`() {
        val row = local("one", "This phone", "http://127.0.0.1:9119")

        assertEquals("127.0.0.1:9119", row.endpoint)
        assertEquals(SavedConnection.SESSION_TOKEN, row.authModeLabel)
        assertEquals("the slot follows the row, not the address", "one", row.localProfile.secretSlotId)
    }

    @Test
    fun `a build without the Local route reads a Local row as an unusable Remote one`() {
        // What an older build's decoder does with a kind it has never heard of:
        // it falls back to Remote and finds an address its own normalizer
        // refuses, so the row is inert rather than dialled wrongly.
        val stored = """{"version":"1","connections":[{"id":"one","label":"Phone","kind":"Cloud","url":"http://127.0.0.1:9119"}]}"""

        val row = ConnectionRegistryCodec.decode(stored).single()

        assertEquals(ConnectionKind.Remote, row.kind)
        assertFalse("an http address is not a usable Remote gateway", row.remote.isValid)
    }

    @Test
    fun `an unrecognised stored kind is a remote gateway, never a keyless SSH route`() {
        val stored = """{"version":"1","connections":[{"id":"one","label":"Alpha","kind":"Cloud"}]}"""

        assertEquals(ConnectionKind.Remote, ConnectionRegistryCodec.decode(stored).single().kind)
    }

    @Test
    fun `the active row falls back to the first when the marker names nothing`() {
        val rows = listOf(remote("one", "Alpha", "https://a.test"), remote("two", "Beta", "https://b.test"))

        assertEquals("two", ConnectionRegistry(rows, "two").active?.id)
        assertEquals("one", ConnectionRegistry(rows, "missing").active?.id)
        assertNull(ConnectionRegistry(emptyList(), "one").active)
    }

    @Test
    fun `a local row id is random and carries no endpoint identity`() {
        val first = newConnectionId()

        assertTrue(first.matches(Regex("[0-9a-f]{16}")))
        assertFalse(first == newConnectionId())
    }

    private companion object {
        /** Not a real fingerprint, and not from a real host. */
        const val FINGERPRINT = "SHA256:0pXQ0M2fEXAMPLEfingerprintDEMOonlyNOTreal01"

        fun remote(id: String, label: String, url: String) = SavedConnection(
            id = id,
            label = label,
            kind = ConnectionKind.Remote,
            remote = RemoteGatewayProfile(baseUrl = url),
        )

        fun local(id: String, label: String, url: String) = SavedConnection(
            id = id,
            label = label,
            kind = ConnectionKind.Local,
            local = LocalGatewayProfile(baseUrl = url),
        )

        fun ssh(
            id: String,
            label: String,
            user: String,
            host: String,
            port: Int = SshDestination.DEFAULT_PORT,
            profile: String = "",
        ) = SavedConnection(
            id = id,
            label = label,
            kind = ConnectionKind.Ssh,
            host = HostProfile(host = host, port = port, username = user, remoteHermesProfile = profile),
        )
    }
}
