package com.hermesagent.mobile.data.ssh

/**
 * The only SSH capability Phase 1 owns.
 *
 * The seam is one method wide on purpose. The scope doc's next slice adds a
 * local port forward and a `hermes serve` bootstrap
 * (`docs/spikes/native-kotlin-ssh-client-scope.md` §5.2); those become
 * *siblings* of [probe] on a `SshTransport`, not a pre-built interface forest
 * nobody implements yet. See `docs/adr/0001-ssh-probe-to-tunnel.md`.
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
         * changes, and the output is a fixed 20 bytes.
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
            "This host answered and its key is trusted, but it refused Tailscale SSH. " +
                "Either the target is not running Tailscale SSH, or your tailnet SSH policy " +
                "does not allow this connection — sharing a tailnet only provides the route " +
                "and the name. Enable Tailscale SSH on the target and allow it in the policy, " +
                "or switch to Password or Private key. Nothing was sent."

        const val CONNECT_TIMEOUT_MILLIS: Int = 15_000
        const val OVERALL_TIMEOUT_MILLIS: Long = 30_000
    }
}
