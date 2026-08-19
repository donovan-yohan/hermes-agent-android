package com.hermesagent.mobile.data.ssh

import net.schmizz.sshj.common.SecurityUtils
import javax.crypto.KeyAgreement
import java.security.KeyPairGenerator
import java.security.Provider
import java.security.Security

/**
 * Whether this process has a JCE provider that can carry an SSH handshake.
 *
 * A status rather than a boolean because the failure has to reach the screen as
 * something a person can act on, and it must never carry a host name, a
 * credential, or a stack trace.
 */
sealed interface CryptoProviderStatus {
    data class Ready(val providerName: String) : CryptoProviderStatus

    data class Unavailable(val reason: String) : CryptoProviderStatus
}

/**
 * Makes sshj's algorithm lookups resolve to the BouncyCastle this app bundles.
 *
 * ## The bug this exists for
 *
 * On a Pixel 10 Pro (Android 17 / API 37) the real probe failed before host-key
 * review with `no such algorithm: X25519 for provider BC`. That is not a
 * network or a policy problem; it is a provider-name collision, and the chain is
 * exact:
 *
 * 1. sshj's `SecurityUtils.registerSecurityProvider` instantiates
 *    `org.bouncycastle.jce.provider.BouncyCastleProvider` — the full 1.85.2 one
 *    this app ships — and then calls `Security.addProvider(it)`.
 * 2. Android already ships a *stripped, deprecated* platform provider that owns
 *    the name `BC`, so `addProvider` returns `-1` and the bundled provider is
 *    never installed.
 * 3. sshj smoke-tests MD5 and DH against the *instance* it built, which passes,
 *    and then records the provider by **name**: `securityProvider = "BC"`.
 * 4. Every later lookup goes through that name. `Curve25519DH` asks for
 *    `KeyPairGenerator/KeyAgreement/KeyFactory "X25519"`, the platform `BC`
 *    has none of them, and curve25519-sha256 — the first KEX sshj proposes and
 *    the one OpenSSH and Tailscale SSH prefer — dies before authentication.
 *
 * The same collision also hides Ed25519, ECDSA, RSA-SHA2 and HmacSHA256 behind
 * the stale name, so it is the whole handshake, not one algorithm.
 * `SshSecurityProviderTest` reproduces step 2 through 4 offline.
 *
 * ## The repair
 *
 * The bundled provider is registered under a name **nobody else owns**, and
 * sshj is pinned to that name. Nothing that already exists is removed, replaced
 * or reordered: Android's platform `BC` keeps its name, its position and every
 * caller that depends on it, and JCA's default resolution order is unchanged
 * because the new provider is appended last. Setting sshj's provider name also
 * short-circuits its own registration heuristic, so the collision above cannot
 * run at all.
 *
 * The alternative — removing the platform `BC` and installing the bundled one
 * in its place — was measured to work too, but it changes what `BC` means for
 * every other caller in the process. That is a bigger blast radius for no extra
 * capability, so it is not what ships.
 *
 * ## Why this is safe on API 26+
 *
 * - It adds a provider; it never removes or reorders one. The only process-wide
 *   effect is that an algorithm *no installed provider offers* can now be found
 *   by an unqualified `getInstance`, and this app makes no such call.
 * - `Provider(String, double, String)` and `Provider.putAll` are API 1. The
 *   copied entries are BouncyCastle's own algorithm→class-name strings, and
 *   they resolve through this class's own class loader, which is the app's —
 *   the same loader that holds the BouncyCastle classes.
 * - It is installed at most once, under a lock, and re-verified before each
 *   use: every algorithm the handshake needs must be present, and a real
 *   X25519 agreement must complete. A provider that fails either check is
 *   removed again and [ensureReady] reports [CryptoProviderStatus.Unavailable],
 *   so the probe fails closed with a typed, non-secret message instead of
 *   half-configuring the transport.
 *
 * Device proof is Ebi's Pixel rerun; everything above is asserted offline.
 */
object SshSecurityProvider {

    /**
     * Deliberately not `BC`. Taking that name would mean evicting Android's
     * provider, which is the blast radius this design avoids.
     */
    const val PROVIDER_NAME: String = "HermesBouncyCastle"

    private const val BUNDLED_PROVIDER = "org.bouncycastle.jce.provider.BouncyCastleProvider"

    /**
     * What the handshake cannot proceed without.
     *
     * Ciphers and MAC preferences are absent on purpose: sshj's `DefaultConfig`
     * self-tests every cipher when it is constructed and drops the ones the
     * runtime cannot provide, so a missing cipher narrows the proposal instead
     * of breaking it. A missing key exchange or host-key algorithm does break
     * it, which is what this list covers.
     */
    private val REQUIRED: List<Requirement> = listOf(
        // curve25519-sha256: the KEX that failed on the device.
        Requirement("KeyPairGenerator", "X25519"),
        Requirement("KeyAgreement", "X25519"),
        Requirement("KeyFactory", "X25519"),
        // ssh-ed25519 host keys — what OpenSSH and Tailscale SSH present.
        Requirement("Signature", "Ed25519"),
        Requirement("KeyFactory", "Ed25519"),
        // ecdsa-sha2-nistp* and rsa-sha2-* host keys.
        Requirement("KeyFactory", "ECDSA"),
        Requirement("Signature", "SHA256withECDSA"),
        Requirement("KeyFactory", "RSA"),
        Requirement("Signature", "SHA256withRSA"),
        // Exchange hash, and the digest sshj itself smoke-tests.
        Requirement("MessageDigest", "SHA-256"),
        Requirement("MessageDigest", "MD5"),
        Requirement("Mac", "HmacSHA256"),
    )

    @Volatile
    private var unavailable: CryptoProviderStatus.Unavailable? = null

    /**
     * Installs the provider once and pins sshj whenever the provider verifies.
     *
     * Call it before constructing anything from sshj: `DefaultConfig` decides
     * which ciphers it can offer while it is being built, so a provider
     * installed afterwards is a provider that arrived too late.
     */
    fun ensureReady(): CryptoProviderStatus = synchronized(this) {
        unavailable ?: install(PROVIDER_NAME, ::bundledProvider).also { result ->
            unavailable = result as? CryptoProviderStatus.Unavailable
        }
    }

    /**
     * The body of [ensureReady], parameterised so a test can drive it with a
     * deliberately incomplete provider and prove the fail-closed path without
     * poisoning its cached failure.
     */
    internal fun install(name: String, candidate: () -> Provider?): CryptoProviderStatus {
        var installedHere = false
        return try {
            if (Security.getProvider(name) != null) {
                // A provider list and sshj's provider name are both mutable.
                // Re-verify and re-pin rather than trusting either one.
                return readyAndPin(name) ?: unavailable(name, "$name is installed but incomplete")
            }

            val provider = candidate() ?: return unavailable(name, "the bundled provider could not be created")
            val renamed = RenamedProvider(name, provider)
            val missing = REQUIRED.filter { renamed.getService(it.type, it.algorithm) == null }
            if (missing.isNotEmpty()) {
                // Nothing was registered, so there is nothing to undo.
                return unavailable(name, "missing ${missing.joinToString(", ")}")
            }

            if (Security.addProvider(renamed) < 0) {
                return unavailable(name, "the name $name is already taken")
            }
            installedHere = true

            readyAndPin(name)?.also { installedHere = false } ?: run {
                Security.removeProvider(name)
                installedHere = false
                unavailable(name, "$name did not work once installed")
            }
        } catch (_: Exception) {
            if (installedHere) runCatching { Security.removeProvider(name) }
            unavailable(name, "the bundled provider could not be installed")
        } catch (_: LinkageError) {
            if (installedHere) runCatching { Security.removeProvider(name) }
            unavailable(name, "the bundled provider could not be installed")
        }
    }

    /** Drops the cached failure. Tests only; there is no runtime caller. */
    internal fun resetForTest() = synchronized(this) { unavailable = null }

    private fun bundledProvider(): Provider? = runCatching {
        Class.forName(BUNDLED_PROVIDER).getDeclaredConstructor().newInstance() as Provider
    }.getOrNull()

    /**
     * Ready only if every required algorithm resolves *by name* and a real
     * X25519 agreement completes. The live agreement is the point: a provider
     * can list a service whose implementation class does not load, and the
     * device failure this whole file exists for was a lookup that only failed
     * once something asked for it.
     */
    private fun verified(name: String): CryptoProviderStatus.Ready? {
        val provider = Security.getProvider(name) ?: return null
        if (REQUIRED.any { provider.getService(it.type, it.algorithm) == null }) return null

        val agreed = runCatching {
            val local = KeyPairGenerator.getInstance("X25519", name).generateKeyPair()
            val peer = KeyPairGenerator.getInstance("X25519", name).generateKeyPair()
            KeyAgreement.getInstance("X25519", name).run {
                init(local.private)
                doPhase(peer.public, true)
                generateSecret()
            }
        }.getOrNull()

        return if (agreed != null && agreed.isNotEmpty()) CryptoProviderStatus.Ready(name) else null
    }

    /** Pins sshj only after the named provider has just been verified. */
    private fun readyAndPin(name: String): CryptoProviderStatus.Ready? =
        verified(name)?.also { SecurityUtils.setSecurityProvider(name) }

    /** Non-secret by construction: algorithm names and a provider name only. */
    private fun unavailable(name: String, detail: String): CryptoProviderStatus.Unavailable =
        CryptoProviderStatus.Unavailable(
            "This device cannot supply the cryptography an SSH handshake needs " +
                "($detail). Nothing was sent. Provider: $name.",
        )

    private data class Requirement(val type: String, val algorithm: String) {
        override fun toString(): String = "$type.$algorithm"
    }

    /**
     * The bundled provider's algorithm table under a name of our own.
     *
     * `putAll` copies BouncyCastle's `type.algorithm -> class name` properties,
     * which is the whole of how it registers; the copies load through this
     * class's loader, which is the one that holds those classes.
     */
    @Suppress("DEPRECATION")
    private class RenamedProvider(name: String, delegate: Provider) : Provider(
        name,
        1.0,
        "Hermes: bundled BouncyCastle under a name Android does not already own",
    ) {
        init {
            putAll(delegate)
        }
    }
}
