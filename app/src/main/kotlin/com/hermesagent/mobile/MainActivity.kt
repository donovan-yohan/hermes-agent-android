package com.hermesagent.mobile

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.hermesagent.mobile.data.demo.DemoSessions
import com.hermesagent.mobile.data.ssh.KeyImportProblem
import com.hermesagent.mobile.data.ssh.MAX_KEY_BYTES
import com.hermesagent.mobile.data.ssh.readBounded
import com.hermesagent.mobile.ui.AppearanceActions
import com.hermesagent.mobile.ui.ChatActions
import com.hermesagent.mobile.ui.HermesApp
import com.hermesagent.mobile.ui.SshActions
import com.hermesagent.mobile.ui.chat.ChatViewModel
import com.hermesagent.mobile.ui.ssh.SshViewModel
import com.hermesagent.mobile.ui.theme.AppearanceSelection
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

/**
 * The single activity, and the single wiring site.
 *
 * Phase 1's object graph is a cache, a preferences store and two ViewModels —
 * small enough that a DI framework would be indirection with no payoff. The
 * process-scoped half lives in [HermesApplication]; this class only binds it to
 * the UI. When a gateway client and a tunnel service join, this is the place
 * that grows, and the decision gets revisited with real weight behind it.
 */
class MainActivity : ComponentActivity() {

    private val app get() = application as HermesApplication
    private val preferences get() = app.preferences

    private val chatViewModel: ChatViewModel by viewModels {
        ChatViewModel.factory(app.cache, DemoSessions.INITIAL_SESSION_ID)
    }
    private val sshViewModel: SshViewModel by viewModels { SshViewModel.factory(preferences) }

    /**
     * Storage Access Framework: the user picks a key file, we read it once and
     * keep it in memory. No persisted URI permission is taken — a key the app
     * can re-read after a restart is a key the app effectively stores.
     */
    private val pickKey = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(::readImportedKey)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val chatState by chatViewModel.uiState.collectAsStateWithLifecycle()
            val sshState by sshViewModel.uiState.collectAsStateWithLifecycle()
            val appearance by preferences.appearance.collectAsStateWithLifecycle(AppearanceSelection())

            HermesApp(
                chatState = chatState,
                sshState = sshState,
                appearance = appearance,
                chatActions = ChatActions(
                    onQueryChange = chatViewModel::setQuery,
                    onDraftChange = chatViewModel::setDraft,
                    onSelectSession = chatViewModel::selectSession,
                    onCreateSession = { chatViewModel.createSession() },
                    onArchiveToggle = chatViewModel::setArchived,
                    onRenameSession = chatViewModel::renameSession,
                    onSend = chatViewModel::submit,
                    onStop = chatViewModel::stop,
                    onToggleArchived = { chatViewModel.setShowArchived(!chatState.showArchived) },
                ),
                appearanceActions = AppearanceActions(
                    onSelectTheme = { name -> lifecycleScope.launch { preferences.setTheme(name) } },
                    onSelectMode = { mode -> lifecycleScope.launch { preferences.setMode(mode) } },
                ),
                sshActions = SshActions(
                    onDestinationChange = sshViewModel::setDestination,
                    onAuthMethodChange = sshViewModel::setAuthMethod,
                    onPasswordChange = sshViewModel::setPassword,
                    onPassphraseChange = sshViewModel::setKeyPassphrase,
                    onImportKey = { pickKey.launch(KEY_MIME_TYPES) },
                    onForgetKey = sshViewModel::forgetPrivateKey,
                    onProbe = sshViewModel::runProbe,
                    onCancelProbe = sshViewModel::cancelProbe,
                    onAcceptHostKey = sshViewModel::acceptPendingHostKey,
                    onDismissHostKey = sshViewModel::dismissPendingHostKey,
                    onForgetHostKey = sshViewModel::forgetAcceptedHostKey,
                ),
            )
        }
    }

    /**
     * Reads the picked document once, bounded, and says what went wrong.
     *
     * The version this replaces swallowed every failure into
     * `runCatching { … }.getOrNull()` and then accepted anything containing the
     * words `PRIVATE KEY`, so an unreadable file, a 2 GB file and a blog post
     * about SSH all looked identical from the screen: nothing happened, or a
     * key was "loaded" that could never authenticate. Each of those is now its
     * own [KeyImportProblem] with its own sentence.
     *
     * The bytes are decoded straight into a `char[]` the ViewModel takes
     * ownership of, and the byte buffer is wiped on the way out: the only copy
     * of the key this app keeps is one it can zero.
     */
    private fun readImportedKey(uri: Uri) {
        // One byte past the cap, so an oversized file is refused rather than
        // silently truncated into a key that cannot parse.
        val bytes = runCatching {
            contentResolver.openInputStream(uri)?.use { it.readBounded(MAX_KEY_BYTES + 1) }
        }.getOrNull()

        when {
            bytes == null -> sshViewModel.reportKeyImportProblem(KeyImportProblem.Unreadable)

            bytes.size > MAX_KEY_BYTES -> {
                bytes.fill(0)
                sshViewModel.reportKeyImportProblem(KeyImportProblem.TooLarge)
            }

            else -> importDecodedKey(bytes, uri)
        }
    }

    /** Decodes into a wipeable array; Charset.decode would retain an opaque copy. */
    private fun importDecodedKey(bytes: ByteArray, uri: Uri) {
        // UTF-8 produces no more UTF-16 code units than input bytes, so this is
        // sufficient without a growable buffer that could retain the key.
        val chars = CharArray(bytes.size)
        var ownedPem: CharArray? = null
        try {
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            val target = CharBuffer.wrap(chars)
            val decoded = decoder.decode(ByteBuffer.wrap(bytes), target, true)
            val flushed = if (decoded.isUnderflow) decoder.flush(target) else decoded
            if (!decoded.isUnderflow || !flushed.isUnderflow) {
                sshViewModel.reportKeyImportProblem(KeyImportProblem.NotAPrivateKey)
                return
            }

            ownedPem = chars.copyOf(target.position())
            sshViewModel.importPrivateKey(ownedPem, displayNameOf(uri))
            // The ViewModel now owns it, including rejection-path wiping.
            ownedPem = null
        } finally {
            bytes.fill(0)
            chars.fill('\u0000')
            ownedPem?.fill('\u0000')
        }
    }

    /**
     * The provider's own name for the document, which is the one the user
     * recognises. It is also attacker-controlled text, so
     * [com.hermesagent.mobile.data.ssh.sanitizeKeyDisplayName] — not this
     * method — decides what is safe to show.
     */
    private fun displayNameOf(uri: Uri): String = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment.orEmpty()

    private companion object {
        val KEY_MIME_TYPES = arrayOf("*/*")
    }
}
