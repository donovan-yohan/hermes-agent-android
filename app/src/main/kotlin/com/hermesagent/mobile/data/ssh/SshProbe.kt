package com.hermesagent.mobile.data.ssh

/**
 * Secondary SSH-only diagnostic.
 *
 * The live Gateway path uses [SshSessionOpener] and the same transport and
 * host-key policy. This method remains useful for separating SSH/auth trouble
 * from remote Gateway lifecycle trouble without becoming the primary action.
 */
interface SshProbe {
    /**
     * Connect, verify the host key, authenticate, run one bounded harmless
     * command, close.
     *
     * Implementations must:
     * - stop before authentication when the host key is unknown or changed;
     * - never persist or log [credential];
     * - pass every message they return through [redact];
     * - honour coroutine cancellation and their own timeout.
     */
    suspend fun probe(profile: HostProfile, credential: SshCredential): ProbeResult

    companion object {
        /**
         * The command the probe runs. Chosen to be harmless, bounded, and
         * present on any POSIX login shell: no file is written, no state
         * changes, and the output is a fixed 21 bytes — the length of
         * [EXPECTED_OUTPUT], which is what [MAX_OUTPUT_BYTES] leaves room for.
         */
        const val COMMAND: String = "printf HERMES_ANDROID_SSH_OK"

        const val EXPECTED_OUTPUT: String = "HERMES_ANDROID_SSH_OK"

        /** Bounded read so a hostile or broken server cannot stream forever. */
        const val MAX_OUTPUT_BYTES: Int = 4096

        /**
         * What the screen says when a host is reachable, its key is trusted,
         * and it still refuses SSH auth type `none`. Held here so the sshj
         * adapter and [FakeSshProbe] cannot drift apart on the one message
         * whose whole job is to separate "on the tailnet" from "Tailscale SSH
         * is enabled and the policy allows you".
         */
        const val TAILSCALE_SSH_REFUSED: String =
            "This trusted host refused Tailscale SSH. Enable Tailscale SSH on the target and " +
                "allow this connection in your tailnet policy, or choose Password or Private key. Nothing was sent."

        const val CONNECT_TIMEOUT_MILLIS: Int = 15_000
        const val OVERALL_TIMEOUT_MILLIS: Long = 30_000
    }
}
