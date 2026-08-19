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

        const val CONNECT_TIMEOUT_MILLIS: Int = 15_000
        const val OVERALL_TIMEOUT_MILLIS: Long = 30_000
    }
}
