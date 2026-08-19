package com.hermesagent.mobile.data.demo

import com.hermesagent.mobile.data.session.AssistantTurn
import com.hermesagent.mobile.data.session.SessionCache
import com.hermesagent.mobile.data.session.SessionStatus
import com.hermesagent.mobile.data.session.SessionSummary
import com.hermesagent.mobile.data.session.ToolActivity
import com.hermesagent.mobile.data.session.ToolState
import com.hermesagent.mobile.data.session.UserTurn

/**
 * Deterministic demo content.
 *
 * Seeded relative to an injected `nowMillis` so the session list exercises
 * every calendar bucket and every status dot on any day, in any timezone, and
 * so a test can assert exact rows. Nothing here survives the gateway slice —
 * it is replaced by the first real `session.list`, not extended.
 */
object DemoSessions {

    private const val MINUTE = 60_000L
    private const val HOUR = 60 * MINUTE
    private const val DAY = 24 * HOUR

    fun seed(cache: SessionCache, nowMillis: Long) {
        val sessions = listOf(
            SessionSummary(
                id = "s-tunnel",
                title = "SSH tunnel bring-up",
                preview = "probe returns HERMES_ANDROID_SSH_OK — next is the local forward",
                lastActiveAtMillis = nowMillis - 6 * MINUTE,
                status = SessionStatus.Unread,
            ),
            SessionSummary(
                id = "s-theme",
                title = "Theme parity with Desktop",
                preview = "six presets, synthesised light for the dark-first five",
                lastActiveAtMillis = nowMillis - 40 * MINUTE,
                status = SessionStatus.Idle,
            ),
            SessionSummary(
                id = "s-approval",
                title = "Approval flow sketch",
                preview = "waiting on an answer before the turn continues",
                lastActiveAtMillis = nowMillis - 3 * HOUR,
                status = SessionStatus.NeedsInput,
            ),
            SessionSummary(
                id = "s-transcript",
                title = "Transcript block model",
                preview = "list item is a markdown block, not a message",
                lastActiveAtMillis = nowMillis - DAY - 2 * HOUR,
                status = SessionStatus.Background,
            ),
            SessionSummary(
                id = "s-spike",
                title = "Kotlin SSH library spike",
                preview = "sshj 0.40.0 vs Apache MINA — sshj wins on exec-stdin",
                lastActiveAtMillis = nowMillis - 4 * DAY,
                status = SessionStatus.Idle,
            ),
            SessionSummary(
                id = "s-archived",
                title = "Capacitor shell autopsy",
                preview = "archived: WebView shell, not a native client",
                lastActiveAtMillis = nowMillis - 40 * DAY,
                status = SessionStatus.Idle,
                archived = true,
            ),
        )
        cache.upsertSessions(sessions)

        cache.setTranscript(
            "s-tunnel",
            listOf(
                UserTurn(
                    id = "s-tunnel-u1",
                    text = "Can the app reuse my Termux SSH key?",
                    atMillis = nowMillis - 9 * MINUTE,
                ),
                AssistantTurn(
                    id = "s-tunnel-a1",
                    atMillis = nowMillis - 8 * MINUTE,
                    markdown = """
                        No. Termux and this app are separate Android packages with separate
                        sandboxes, so `~/.ssh/config`, the agent socket and the private keys
                        in Termux's home are simply *not readable* here.

                        What Termux proves is different, and still useful:

                        - the host is reachable from this phone's network
                        - `sshd` is up and accepts your account
                        - the credentials you use there are valid credentials

                        So the app asks for its own material: a password, or a key you import
                        through the system file picker.
                    """.trimIndent(),
                ),
                ToolActivity(
                    id = "s-tunnel-t1",
                    label = "probe hermes-box:22",
                    detail = "printf HERMES_ANDROID_SSH_OK",
                    state = ToolState.Done,
                    elapsedSeconds = 2,
                ),
            ),
        )

        cache.setTranscript(
            "s-theme",
            listOf(
                UserTurn(
                    id = "s-theme-u1",
                    text = "Show me how a new theme gets added.",
                    atMillis = nowMillis - 44 * MINUTE,
                ),
                AssistantTurn(
                    id = "s-theme-a1",
                    atMillis = nowMillis - 43 * MINUTE,
                    markdown = """
                        Append one entry to `BuiltinThemes.ALL`. Nothing else changes — the
                        picker enumerates the list and components read semantic tokens.

                        ```kotlin
                        val Solarized = HermesThemePreset(
                            name = "solarized",
                            label = "Solarized",
                            description = "Warm low-contrast",
                            colors = HermesPalette(/* … */),
                        )
                        ```

                        A preset with no `darkColors` is dark-first: its light half is
                        synthesised by the same arithmetic Desktop uses.
                    """.trimIndent(),
                ),
            ),
        )

        cache.setTranscript(
            "s-approval",
            listOf(
                UserTurn(
                    id = "s-approval-u1",
                    text = "Restart the gateway on the box.",
                    atMillis = nowMillis - 3 * HOUR,
                ),
                AssistantTurn(
                    id = "s-approval-a1",
                    atMillis = nowMillis - 3 * HOUR + MINUTE,
                    markdown = "That needs remote lifecycle control, which this slice does not have. " +
                        "It is the next vertical slice: local forward, then `hermes serve` bootstrap.",
                ),
            ),
        )
    }

    /** The session the app opens on: newest, and it has a transcript. */
    const val INITIAL_SESSION_ID: String = "s-tunnel"
}
