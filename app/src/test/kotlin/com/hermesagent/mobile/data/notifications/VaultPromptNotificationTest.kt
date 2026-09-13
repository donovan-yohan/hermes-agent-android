package com.hermesagent.mobile.data.notifications

import com.hermesagent.mobile.data.gateway.PendingInputKey
import com.hermesagent.mobile.data.gateway.PendingInputKind
import com.hermesagent.mobile.data.gateway.PendingInputRequest
import com.hermesagent.mobile.data.gateway.VaultCodePending
import com.hermesagent.mobile.data.gateway.VaultSaveLoginPending
import com.hermesagent.mobile.data.gateway.VaultUnlockPending
import com.hermesagent.mobile.data.session.SessionCacheState
import com.hermesagent.mobile.data.session.SessionSummary
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a vault prompt is allowed to put in the shade.
 *
 * Desktop's three vault handlers raise the same `input` kind as clarify, sudo
 * and secret do (`apps/desktop/src/app/session/hooks/use-message-stream/
 * gateway-event/input-requests.ts:384-389`, `:410-415`, `:436-441` @
 * `564aef2946c436500a5e80ee117b66b789b3f99a`), and that half is ported. The
 * body is not: Desktop puts the prompt title there — the site being signed
 * into, the password manager being unlocked — and on a phone that is a lock
 * screen anyone holding it can read. #99's rule for this shade already bars a
 * command, tool output, a sudo prompt and a secret name.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VaultPromptNotificationTest {
    @Test
    fun `every vault prompt rides the input kind rather than a kind of its own`() = runTest {
        val world = World(this)
        world.sessions.value = SessionCacheState(sessions = mapOf("s1" to summary("s1", "Sign-in work")))
        world.start()
        world.leaveQuietWindow()

        world.pendingInputs.value = unlock("s1") + saveLogin("s2") + code("s3")
        runCurrent()

        assertEquals(
            listOf(
                NotificationKind.Input to "s1",
                NotificationKind.Input to "s2",
                NotificationKind.Input to "s3",
            ),
            world.surface.posted().sortedBy { it.second },
        )
    }

    @Test
    fun `the shade is told a conversation is waiting, never which site or manager`() = runTest {
        val world = World(this)
        world.sessions.value = SessionCacheState(sessions = mapOf("s1" to summary("s1", "Sign-in work")))
        world.start()
        world.leaveQuietWindow()

        world.pendingInputs.value = unlock("s1")
        runCurrent()

        val post = world.surface.posts.single()
        assertEquals(NotificationCopy.INPUT_TITLE, post.title)
        assertEquals("Sign-in work", post.body)
        // Named explicitly so a future edit that "improves" the body by adding
        // the prompt's own text fails here rather than on somebody's lock screen.
        assertTrue(world.surface.posts.none { it.body.contains("1Password") })
        // No shade buttons either: only an approval carries an answerable
        // target, and a master password is not answerable from a notification.
        assertEquals(null, post.approval)
    }

    @Test
    fun `a vault prompt for the conversation on screen stays silent`() = runTest {
        val world = World(this)
        world.presence.applicationForegroundChanged(true)
        world.presence.visibleSessionChanged("s1")
        world.start()
        world.leaveQuietWindow()

        world.pendingInputs.value = code("s1")
        runCurrent()

        assertTrue(world.surface.posts.isEmpty())
    }

    private fun key(durableSessionId: String, kind: PendingInputKind) =
        PendingInputKey(1L, "runtime-$durableSessionId", "req-$durableSessionId", kind)

    private fun unlock(durableSessionId: String): Map<PendingInputKey, PendingInputRequest> {
        val key = key(durableSessionId, PendingInputKind.VaultUnlock)
        return mapOf(
            key to VaultUnlockPending(
                key = key,
                durableSessionId = durableSessionId,
                runtimeSessionId = key.runtimeSessionId,
                backend = "1password",
                displayName = "1Password",
            ),
        )
    }

    private fun saveLogin(durableSessionId: String): Map<PendingInputKey, PendingInputRequest> {
        val key = key(durableSessionId, PendingInputKind.VaultSaveLogin)
        return mapOf(
            key to VaultSaveLoginPending(
                key = key,
                durableSessionId = durableSessionId,
                runtimeSessionId = key.runtimeSessionId,
                origin = "origin",
                site = "site",
            ),
        )
    }

    private fun code(durableSessionId: String): Map<PendingInputKey, PendingInputRequest> {
        val key = key(durableSessionId, PendingInputKind.VaultCode)
        return mapOf(
            key to VaultCodePending(
                key = key,
                durableSessionId = durableSessionId,
                runtimeSessionId = key.runtimeSessionId,
                site = "site",
                hint = "",
            ),
        )
    }

    private fun summary(id: String, title: String) = SessionSummary(
        id = id,
        title = title,
        preview = "",
        lastActiveAtMillis = 0L,
    )

    /** The notifier with nothing real behind it, on virtual time. */
    private class World(private val test: TestScope) {
        val pendingInputs = MutableStateFlow<Map<PendingInputKey, PendingInputRequest>>(emptyMap())
        val sessions = MutableStateFlow(SessionCacheState())
        val presence = NotificationPresence()
        val surface = RecordingNotificationSurface()

        fun start() {
            SessionNotifier(
                pendingInputs = pendingInputs,
                turnOutcomes = MutableSharedFlow(),
                sessions = sessions,
                socketOpens = MutableSharedFlow(),
                presence = presence,
                settingsFlow = MutableStateFlow(NotificationSettings()),
                surface = surface,
                clock = { test.testScheduler.currentTime },
            ).start(test.backgroundScope)
            test.testScheduler.runCurrent()
        }

        /** Past the quiet window that construction itself opens. */
        fun leaveQuietWindow() {
            test.testScheduler.advanceTimeBy(4_001)
            test.testScheduler.runCurrent()
        }
    }
}
