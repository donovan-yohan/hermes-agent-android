package com.hermesagent.mobile

import android.content.Intent
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
import com.hermesagent.mobile.data.gateway.GatewayBrowserLauncher
import com.hermesagent.mobile.data.ssh.KeyDocument
import com.hermesagent.mobile.data.ssh.KeyImportGate
import com.hermesagent.mobile.data.ssh.KeyImportProblem
import com.hermesagent.mobile.data.ssh.readKeyDocument
import com.hermesagent.mobile.ui.AppearanceActions
import com.hermesagent.mobile.ui.ChatActions
import com.hermesagent.mobile.ui.GatewayActions
import com.hermesagent.mobile.ui.HermesApp
import com.hermesagent.mobile.ui.SshActions
import com.hermesagent.mobile.ui.chat.ChatViewModel
import com.hermesagent.mobile.ui.gateway.GatewaySettingsViewModel
import com.hermesagent.mobile.ui.ssh.SshViewModel
import com.hermesagent.mobile.ui.theme.AppearanceSelection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

/**
 * The single activity, and the single wiring site.
 *
 * The process-scoped Gateway graph lives in [HermesApplication]; this class
 * only binds it to two ViewModels and the UI. The graph remains small enough
 * that a DI framework would add indirection without a second composition root.
 */
class MainActivity : ComponentActivity() {

    private val app get() = application as HermesApplication
    private val preferences get() = app.preferences

    private val chatViewModel: ChatViewModel by viewModels {
        ChatViewModel.factory(
            cache = app.cache,
            repository = app.sessionRepository,
            sidebarViewStore = preferences,
            composerControlsStore = preferences,
            draftStore = app.draftStore,
            draftScope = app.appScope,
            composerQueueController = app.composerQueueController,
            switchComposerQueueScope = app::switchComposerQueueScope,
        )
    }
    private val sshViewModel: SshViewModel by viewModels {
        SshViewModel.factory(preferences, app.gatewayConnection)
    }
    private val gatewaySettingsViewModel: GatewaySettingsViewModel by viewModels {
        GatewaySettingsViewModel.factory(preferences, app.gatewayConnection)
    }
    private val gatewayBrowser = GatewayBrowserLauncher { url ->
        withContext(Dispatchers.Main.immediate) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }
    }
    private val keyImports = KeyImportGate()
    private var pendingPickerToken: Long? = null

    /**
     * Storage Access Framework: the user picks a key file, we read it once and
     * keep it in memory. No persisted URI permission is taken — a key the app
     * can re-read after a restart is a key the app effectively stores.
     */
    private val pickKey = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val token = pendingPickerToken
        pendingPickerToken = null
        if (uri != null && token != null) importPickedKey(uri, token)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val chatState by chatViewModel.uiState.collectAsStateWithLifecycle()
            val gatewayState by gatewaySettingsViewModel.uiState.collectAsStateWithLifecycle()
            val sshState by sshViewModel.uiState.collectAsStateWithLifecycle()
            val appearance by preferences.appearance.collectAsStateWithLifecycle(AppearanceSelection())

            HermesApp(
                chatState = chatState,
                gatewayState = gatewayState,
                sshState = sshState,
                appearance = appearance,
                chatActions = ChatActions(
                    onQueryChange = chatViewModel::setQuery,
                    onDraftChange = chatViewModel::setDraft,
                    onRefreshNavigation = chatViewModel::refreshSessionNavigation,
                    onSidebarGroupingChange = chatViewModel::setSidebarGrouping,
                    onSelectProject = chatViewModel::selectProject,
                    onExitProject = chatViewModel::exitProject,
                    onCreateProject = chatViewModel::createProject,
                    onSelectSession = chatViewModel::selectSession,
                    onCreateSession = { chatViewModel.createSession() },
                    onSend = chatViewModel::submit,
                    onStop = chatViewModel::stop,
                    onRedirect = chatViewModel::redirectDraftFromUi,
                    onQueue = chatViewModel::queueDraft,
                    onSendNext = chatViewModel::sendNext,
                    onResumeQueue = chatViewModel::resumeQueue,
                    onEditQueuedEntry = chatViewModel::beginQueueEdit,
                    onQueueEditTextChange = chatViewModel::setQueueEditText,
                    onSaveQueueEdit = chatViewModel::saveQueueEdit,
                    onCancelQueueEdit = chatViewModel::cancelQueueEdit,
                    onDeleteQueuedEntry = chatViewModel::deleteQueuedEntry,
                    onRedirectQueuedEntry = chatViewModel::redirectQueuedEntry,
                    onMarkQueuedEntryReady = chatViewModel::markQueuedEntryReadyAfterReview,
                    onHistoryOlder = chatViewModel::historyOlder,
                    onHistoryNewer = chatViewModel::historyNewer,
                    onUndoDraft = chatViewModel::undoDraft,
                    onRedoDraft = chatViewModel::redoDraft,
                    onRespondToPendingInput = chatViewModel::respondToPendingInput,
                    onDismissSecurePending = chatViewModel::dismissSecurePending,
                    onComposerStatusOpened = chatViewModel::composerStatusOpened,
                    onRefreshProcesses = chatViewModel::refreshProcesses,
                    onKillProcess = chatViewModel::killProcess,
                    onSelectModel = chatViewModel::selectModel,
                    onSelectReasoning = chatViewModel::selectReasoning,
                    onSelectFast = chatViewModel::selectFast,
                    onEditorSelectionChange = chatViewModel::onEditorSelectionChange,
                    onCompletionSelected = chatViewModel::onCompletionSelected,
                    onInsertText = chatViewModel::onInsertText,
                ),
                appearanceActions = AppearanceActions(
                    onSelectTheme = { name -> lifecycleScope.launch { preferences.setTheme(name) } },
                    onSelectMode = { mode -> lifecycleScope.launch { preferences.setMode(mode) } },
                ),
                gatewayActions = GatewayActions(
                    onModeChange = gatewaySettingsViewModel::setMode,
                    onRemoteUrlChange = gatewaySettingsViewModel::setRemoteUrl,
                    onProviderChange = gatewaySettingsViewModel::setProvider,
                    onConnectRemote = { gatewaySettingsViewModel.connectRemote(gatewayBrowser) },
                    onDisconnect = gatewaySettingsViewModel::disconnect,
                    onForgetSignIn = gatewaySettingsViewModel::forgetSignIn,
                ),
                sshActions = SshActions(
                    onDestinationChange = sshViewModel::setDestination,
                    onRemoteProfileChange = sshViewModel::setRemoteHermesProfile,
                    onAuthMethodChange = sshViewModel::setAuthMethod,
                    onPasswordChange = sshViewModel::setPassword,
                    onPassphraseChange = sshViewModel::setKeyPassphrase,
                    onImportKey = {
                        pendingPickerToken = keyImports.begin()
                        pickKey.launch(KEY_MIME_TYPES)
                    },
                    onForgetKey = sshViewModel::forgetPrivateKey,
                    onConnect = sshViewModel::connect,
                    onDisconnect = sshViewModel::disconnect,
                    onProbe = sshViewModel::runProbe,
                    onCancelProbe = sshViewModel::cancelProbe,
                    onAcceptHostKey = sshViewModel::acceptPendingHostKey,
                    onDismissHostKey = sshViewModel::dismissPendingHostKey,
                    onForgetHostKey = sshViewModel::forgetAcceptedHostKey,
                    onLeaveScreen = {
                        keyImports.invalidate()
                        pendingPickerToken = null
                        sshViewModel.releaseScreen()
                    },
                ),
            )
        }
    }

    override fun onStop() {
        chatViewModel.flushDraft()
        super.onStop()
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
     * The picker delivers its result on the main thread and the two provider
     * calls behind it are IPC, so the read and the name query happen on
     * [Dispatchers.IO] and only the bounded outcome comes back here. The
     * coroutine is `lifecycleScope`'s, so an Activity that goes away takes the
     * pending read with it.
     *
     * The import epoch closes the navigation race: leaving Gateways invalidates
     * the pending result, and a late result wipes its bounded bytes instead of
     * repopulating the Activity-scoped ViewModel.
     *
     * One Activity-destruction window remains, stated rather than hidden: if
     * lifecycle cancellation wins after the read returns but before this
     * continuation resumes, the bounded byte array becomes unreachable without
     * an explicit wipe. Keeping the lifecycle coroutine alive solely to scrub
     * that array would let a remote DocumentsProvider outlive the Activity.
     */
    private fun importPickedKey(uri: Uri, token: Long) = lifecycleScope.launch {
        val document = readKeyDocument(
            io = Dispatchers.IO,
            openStream = { contentResolver.openInputStream(uri) },
            displayName = { displayNameOf(uri) },
        )
        when (val current = keyImports.claim(token, document) ?: return@launch) {
            is KeyDocument.Refused -> sshViewModel.reportKeyImportProblem(current.problem)
            is KeyDocument.Read -> importDecodedKey(current.bytes, current.displayName)
        }
    }

    /**
     * Decodes into a wipeable array; Charset.decode would retain an opaque copy.
     *
     * Runs on the main thread on purpose: it is arithmetic over at most 64 KiB
     * and it ends by handing the array to the ViewModel, which is main-thread
     * state.
     */
    private fun importDecodedKey(bytes: ByteArray, displayName: String) {
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
            sshViewModel.importPrivateKey(ownedPem, displayName)
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
