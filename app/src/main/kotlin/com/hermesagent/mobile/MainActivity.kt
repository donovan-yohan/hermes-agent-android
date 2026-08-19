package com.hermesagent.mobile

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.hermesagent.mobile.data.demo.DemoSessions
import com.hermesagent.mobile.data.ssh.readBounded
import com.hermesagent.mobile.ui.AppearanceActions
import com.hermesagent.mobile.ui.ChatActions
import com.hermesagent.mobile.ui.HermesApp
import com.hermesagent.mobile.ui.SshActions
import com.hermesagent.mobile.ui.chat.ChatViewModel
import com.hermesagent.mobile.ui.ssh.SshViewModel
import com.hermesagent.mobile.ui.theme.AppearanceSelection
import kotlinx.coroutines.launch

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

    private fun readImportedKey(uri: Uri) {
        val name = uri.lastPathSegment?.substringAfterLast('/') ?: "imported key"
        val pem = runCatching {
            contentResolver.openInputStream(uri)?.use { stream ->
                // A private key is a few KB; refuse to slurp an arbitrary file.
                String(stream.readBounded(MAX_KEY_BYTES), Charsets.UTF_8)
            }
        }.getOrNull()

        if (pem != null && pem.contains("PRIVATE KEY")) {
            sshViewModel.importPrivateKey(pem, name)
        }
    }

    private companion object {
        val KEY_MIME_TYPES = arrayOf("*/*")
        const val MAX_KEY_BYTES = 64 * 1024
    }
}
