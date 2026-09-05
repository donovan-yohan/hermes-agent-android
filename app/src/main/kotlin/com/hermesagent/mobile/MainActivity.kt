package com.hermesagent.mobile

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.hermesagent.mobile.data.gateway.EXTRA_SIGN_IN_ORIGIN
import com.hermesagent.mobile.data.gateway.GatewayConnectionStatus
import com.hermesagent.mobile.data.gateway.SignInOrigin
import com.hermesagent.mobile.data.gateway.signInOriginFrom
import com.hermesagent.mobile.data.notifications.ACTION_OPEN_SESSION
import com.hermesagent.mobile.data.notifications.ANDROID_TIRAMISU
import com.hermesagent.mobile.data.notifications.EXTRA_DURABLE_SESSION_ID
import com.hermesagent.mobile.data.notifications.NotificationPermissionStep
import com.hermesagent.mobile.data.notifications.notificationPermissionStep
import com.hermesagent.mobile.data.ssh.KeyDocument
import com.hermesagent.mobile.data.ssh.KeyImportGate
import com.hermesagent.mobile.data.ssh.KeyImportProblem
import com.hermesagent.mobile.data.ssh.readKeyDocument
import com.hermesagent.mobile.ui.AppearanceActions
import com.hermesagent.mobile.ui.ChatActions
import com.hermesagent.mobile.ui.ConnectionsActions
import com.hermesagent.mobile.ui.GatewayActions
import com.hermesagent.mobile.ui.HermesApp
import com.hermesagent.mobile.ui.HermesNavigationAsk
import com.hermesagent.mobile.ui.RelayActions
import com.hermesagent.mobile.ui.SshActions
import com.hermesagent.mobile.ui.chat.ChatViewModel
import com.hermesagent.mobile.ui.common.NotificationPermissionPrompt
import com.hermesagent.mobile.ui.gateway.ConnectionsViewModel
import com.hermesagent.mobile.ui.gateway.GatewaySettingsViewModel
import com.hermesagent.mobile.ui.handBackDestination
import com.hermesagent.mobile.ui.relay.RelayChannelReader
import com.hermesagent.mobile.data.relay.RelayMessageFormat
import com.hermesagent.mobile.ui.relay.RelayPoster
import com.hermesagent.mobile.ui.relay.RelayViewModel
import com.hermesagent.mobile.ui.system.SystemActions
import com.hermesagent.mobile.ui.system.SystemViewModel
import com.hermesagent.mobile.ui.ssh.SshViewModel
import com.hermesagent.mobile.ui.theme.AppearanceSelection
import com.hermesagent.mobile.ui.theme.HermesTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
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
            codingContextProvider = app.codingContextProvider,
            sidebarViewStore = preferences,
            profileScopeStore = preferences,
            profileRepository = app.profileRepository,
            composerControlsStore = preferences,
            draftStore = app.draftStore,
            draftScope = app.appScope,
            composerQueueController = app.composerQueueController,
            switchComposerQueueScope = app::switchComposerQueueScope,
            replySpeaker = app.replySpeaker,
            connectionGeneration = { app.gatewayConnection.currentGeneration },
        )
    }
    private val sshViewModel: SshViewModel by viewModels {
        SshViewModel.factory(preferences, app.gatewayConnection)
    }
    private val gatewaySettingsViewModel: GatewaySettingsViewModel by viewModels {
        GatewaySettingsViewModel.factory(
            store = preferences,
            gateway = app.gatewayConnection,
            // Saving a different address is leaving an endpoint, so it tears
            // down through the same path a connection switch uses.
            leaveEndpoint = app.connectionSwitch::leaveCurrentEndpoint,
        )
    }

    /**
     * The saved-connections registry. Switching is the process-scoped
     * controller's job, not this ViewModel's: a re-home outlives the screen
     * that asked for it.
     */
    private val connectionsViewModel: ConnectionsViewModel by viewModels {
        ConnectionsViewModel.factory(
            store = preferences,
            gateway = app.gatewayConnection,
            switch = app.connectionSwitch,
        )
    }

    /**
     * The Relay surface reads and posts through the process-scoped plugin
     * client and the process-scoped availability controller; it owns neither.
     * Both seams stay this thin on purpose: the surface's whole retry policy is
     * expressed against one result type, never against a transport.
     */
    private val relayViewModel: RelayViewModel by viewModels {
        RelayViewModel.factory(
            availability = app.relayAvailability.state,
            refreshAvailability = app.relayAvailability::refresh,
            reader = object : RelayChannelReader {
                override suspend fun channels() = app.relayRepository.channels()

                override suspend fun history(channelId: String, limit: Int) =
                    app.relayRepository.history(channelId, limit)
            },
            poster = object : RelayPoster {
                override suspend fun post(
                    channelId: String,
                    text: String,
                    format: RelayMessageFormat,
                    clientMessageId: String,
                ) = app.relayRepository.post(channelId, text, format, clientMessageId)
            },
        )
    }
    /**
     * The System panel. It owns the restart poll and the status read; the
     * six-minute update engine it drives is app-scoped, so nothing here can
     * cancel an apply by going away.
     */
    private val systemViewModel: SystemViewModel by viewModels {
        SystemViewModel.factory(api = app.systemApi, updates = app.updateController)
    }
    private val keyImports = KeyImportGate()
    private var pendingPickerToken: Long? = null

    /**
     * Which surface the next sign-in would be starting from, reported by the
     * shell as the person moves through it ([HermesApp]).
     *
     * Read at the tap and baked into the launcher handed over there, because
     * the sign-in outlives this Activity: by the time the browser hands back,
     * this field may belong to a second instance that was rebuilt behind it.
     *
     * Deliberately *not* in [onSaveInstanceState], unlike
     * [notificationRationaleDismissed]. This is a mirror, not an owner: the
     * composition holds the saved copy and reports it on first composition, and
     * the only thing that reads this field is a tap on a composed screen. There
     * is no moment where it can be read before the value that fills it has
     * arrived, and a second saved copy would be a second lifecycle to keep in
     * step with the first.
     */
    private var signInOrigin = SignInOrigin.Gateways

    /**
     * The hand-back's ask to go back where the journey started, and a counter
     * so a second one is a second ask ([HermesNavigationAsk]).
     */
    private var navigationAsk by mutableStateOf<HermesNavigationAsk?>(null)
    private var navigationAsks = 0L

    /**
     * Shown once, when a live Gateway first makes the grant worth anything.
     * The result is deliberately ignored: Android refuses a second request
     * after two refusals, so re-asking would be a dialog that does nothing.
     * Turning notifications back on is the OS settings screen's job.
     */
    private val requestPostNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private var notificationRationaleVisible by mutableStateOf(false)

    /**
     * Dismissing is "not now", not "never": the next launch may ask again.
     * Saved instance state, because a rotation is not a new launch and having
     * the dialog reappear mid-turn is exactly the nag this avoids.
     */
    private var notificationRationaleDismissed = false

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

    /**
     * Storage Access Framework: attachment sources are read once through the
     * lifetime-scoped grant and only their bytes ever leave this process.
     */
    private var pendingDictationGrant: (() -> Unit)? = null
    private val requestMicPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val resume = pendingDictationGrant
            pendingDictationGrant = null
            if (granted) {
                resume?.invoke()
            } else {
                chatViewModel.reportDictationPermissionDenied()
            }
        }

    private val pickAttachments =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            for (uri in uris) {
                chatViewModel.addAttachmentFromGrant(
                    uriString = uri.toString(),
                    displayName = queryDisplayName(uri) ?: "attachment",
                    claimedMime = contentResolver.getType(uri),
                )
            }
        }

    private fun queryDisplayName(uri: Uri): String? =
        runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        notificationRationaleDismissed = savedInstanceState?.getBoolean(STATE_RATIONALE_DISMISSED) == true
        openSessionFromIntent(intent)
        returnFromSignIn(intent)
        followVisibleSession()
        followNotificationPermissionNeed()
        // Attachment grants are read on IO; only bytes enter the ViewModel.
        chatViewModel.openAttachmentStream = { uriString ->
            runCatching { contentResolver.openInputStream(Uri.parse(uriString)) }.getOrNull()
        }
        // Voice engine hooks: bounded capture and typed Gateway routes only.
        // Dictation requires an explicit runtime mic grant; denial surfaces a
        // recovery message instead of silently failing to capture.
        val mic = com.hermesagent.mobile.data.voice.AndroidMicCapture(Dispatchers.IO)
        chatViewModel.onToggleDictationRequested = {
            if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                chatViewModel.toggleDictation()
            } else {
                pendingDictationGrant = { chatViewModel.toggleDictation() }
                requestMicPermission.launch(android.Manifest.permission.RECORD_AUDIO)
            }
        }
        var dictationRecordingJob: kotlinx.coroutines.Job = lifecycleScope.launch { }
        chatViewModel.onDictationCapture = { durableSessionId, onDone ->
            val started = lifecycleScope.launch { mic.start() }
            dictationRecordingJob = lifecycleScope.launch {
                started.join()
                while (chatViewModel.uiState.value.voice
                    is com.hermesagent.mobile.data.voice.VoiceUiState.DictationRecording
                ) {
                    chatViewModel.reportDictationLevel(mic.pump())
                    delay(100)
                }
            }
            ({
                dictationRecordingJob.cancel()
                lifecycleScope.launch {
                    val pcm = mic.stop()
                    val key = com.hermesagent.mobile.data.voice.VoiceSessionKey(
                        connectionGeneration = app.gatewayConnection.currentGeneration,
                        durableSessionId = durableSessionId,
                    )
                    val captured = com.hermesagent.mobile.data.voice.CapturedAudio("audio/wav", pcm)
                    // Transport and provider failures must surface as honest
                    // state, never masquerade as silence.
                    val result = runCatching {
                        app.voiceRepository.transcribe(key, captured)
                    }.getOrElse { failure ->
                        captured.close()
                        chatViewModel.reportDictationFailure(
                            (failure as? com.hermesagent.mobile.data.voice.VoiceTransportException)
                                ?.safeMessage
                                ?: "Dictation could not be completed. Try again.",
                        )
                        return@launch
                    }
                    captured.close()
                    onDone(result)
                }
            })
        }

        setContent {
            val chatState by chatViewModel.uiState.collectAsStateWithLifecycle()
            val gatewayState by gatewaySettingsViewModel.uiState.collectAsStateWithLifecycle()
            val sshState by sshViewModel.uiState.collectAsStateWithLifecycle()
            val appearance by preferences.appearance.collectAsStateWithLifecycle(AppearanceSelection())
            // The initial value is the same default the store applies to an
            // absent key, so a person who turned the splash OFF can see it for
            // the one frame before DataStore answers. That is the shape the
            // `appearance` collection above already has — it paints the default
            // skin for the same frame — so this follows the surface's existing
            // behaviour rather than inventing a second startup policy. Closing
            // it means holding the first frame until both reads land, which is
            // a decision about app launch, not about this preference.
            val introSplash by preferences.introSplash.collectAsStateWithLifecycle(true)
            // Collected from the shell, not from the Relay screen: the Settings
            // entry point has to be able to say Relay is unavailable on this
            // Gateway before anyone opens it. Availability probes on connection
            // edges only, so holding this costs nothing.
            val relayState by relayViewModel.uiState.collectAsStateWithLifecycle()
            val connectionsState by connectionsViewModel.uiState.collectAsStateWithLifecycle()
            val systemState by systemViewModel.uiState.collectAsStateWithLifecycle()
            val systemActions = remember {
                SystemActions(
                    onRefresh = systemViewModel::refresh,
                    onRestartGateway = systemViewModel::restartGateway,
                    onOpenUpdates = systemViewModel::openUpdates,
                    onCheckUpdates = systemViewModel::checkForUpdates,
                    onApplyUpdate = systemViewModel::applyUpdate,
                    onCloseUpdates = systemViewModel::closeUpdates,
                )
            }
            val connectionsActions = remember {
                ConnectionsActions(
                    onSelect = connectionsViewModel::select,
                    onBeginAdd = connectionsViewModel::beginAdd,
                    onBeginEdit = connectionsViewModel::beginEdit,
                    onCancelEditor = connectionsViewModel::cancelEditor,
                    onEditKind = connectionsViewModel::editKind,
                    onEditLabel = connectionsViewModel::editLabel,
                    onEditUrl = connectionsViewModel::editUrl,
                    onEditProvider = connectionsViewModel::editProvider,
                    onEditDestination = connectionsViewModel::editDestination,
                    onEditToken = connectionsViewModel::editToken,
                    onSaveEditor = connectionsViewModel::saveEditor,
                    onRequestRemove = connectionsViewModel::requestRemove,
                    onCancelRemove = connectionsViewModel::cancelRemove,
                    onConfirmRemove = connectionsViewModel::confirmRemove,
                    onLeaveScreen = connectionsViewModel::releaseScreen,
                )
            }
            // Remembered so the instance is stable: rebuilding it every
            // recomposition would invalidate every per-row click lambda in the
            // channel list while Relay is open.
            val relayActions = remember {
                RelayActions(
                    onSelectChannel = relayViewModel::selectChannel,
                    onClearSelection = relayViewModel::clearSelection,
                    onRetry = relayViewModel::retry,
                    onDraftChange = relayViewModel::setDraft,
                    onSend = relayViewModel::sendDraft,
                    onRetrySend = relayViewModel::retrySend,
                    onResume = relayViewModel::surfaceResumed,
                    onPause = relayViewModel::surfacePaused,
                )
            }

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
                    onBranchFromReply = chatViewModel::branchFromReply,
                    onRegenerateReply = chatViewModel::regenerateReply,
                    onRenameSession = chatViewModel::renameSessionAsync,
                    onDeleteSession = chatViewModel::deleteSessionAsync,
                    onSetSessionPinned = chatViewModel::setSessionPinnedAsync,
                    onSetSessionUnread = chatViewModel::setSessionUnreadAsync,
                    onSetSessionArchived = chatViewModel::setSessionArchivedAsync,
                    onArchivedVisibleChange = chatViewModel::setArchivedVisible,
                    onMarkAllSessionsRead = chatViewModel::markAllSessionsRead,
                    onSelectProfile = chatViewModel::selectProfile,
                    onShowAllProfiles = chatViewModel::showAllProfiles,
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
                    onRefreshCodingContext = chatViewModel::refreshCodingContext,
                    onOpenCodingReview = chatViewModel::openCodingReview,
                    onDismissCodingReview = chatViewModel::dismissCodingReview,
                    onRefreshProcesses = chatViewModel::refreshProcesses,
                    onKillProcess = chatViewModel::killProcess,
                    onSelectApprovalMode = chatViewModel::selectApprovalMode,
                    onSelectModel = chatViewModel::selectModel,
                    onToggleModelVisible = chatViewModel::toggleModelVisible,
                    onSetProviderModelsVisible = chatViewModel::setProviderModelsVisible,
                    onSelectReasoning = chatViewModel::selectReasoning,
                    onSelectFast = chatViewModel::selectFast,
                    onEditorSelectionChange = chatViewModel::onEditorSelectionChange,
                    onCompletionSelected = chatViewModel::onCompletionSelected,
                    onInsertText = chatViewModel::onInsertText,
                    onPickFiles = { pickAttachments.launch(arrayOf("*/*")) },
                    onRemoveAttachment = chatViewModel::removeAttachment,
                    onShowEarlierMessages = chatViewModel::showEarlierMessages,
                    onToggleReadAloud = chatViewModel::toggleReadAloud,
                    onToggleDictation = { chatViewModel.requestToggleDictation() },
                    onToggleConversation = chatViewModel::toggleVoiceConversation,
                    onToggleVoiceMute = chatViewModel::toggleVoiceMute,
                ),
                appearanceActions = AppearanceActions(
                    onSelectTheme = { name -> lifecycleScope.launch { preferences.setTheme(name) } },
                    onSelectMode = { mode -> lifecycleScope.launch { preferences.setMode(mode) } },
                    onSetIntroSplash = { on -> lifecycleScope.launch { preferences.setIntroSplash(on) } },
                ),
                introSplash = introSplash,
                gatewayActions = GatewayActions(
                    onModeChange = gatewaySettingsViewModel::setMode,
                    onRemoteUrlChange = gatewaySettingsViewModel::setRemoteUrl,
                    onProviderChange = gatewaySettingsViewModel::setProvider,
                    // Process-scoped: the sign-in it starts outlives this Activity, so the
                    // launcher must not hold it.
                    onConnectRemote = {
                        gatewaySettingsViewModel.connectRemote(app.signInBrowser.startedFrom(signInOrigin))
                    },
                    onConnectLocal = gatewaySettingsViewModel::connectLocal,
                    onDisconnect = gatewaySettingsViewModel::disconnect,
                    onForgetSignIn = gatewaySettingsViewModel::forgetSignIn,
                ),
                relayState = relayState,
                relayActions = relayActions,
                systemState = systemState,
                systemActions = systemActions,
                connectionsState = connectionsState,
                connectionsActions = connectionsActions,
                navigationAsk = navigationAsk,
                onSignInOriginChange = { signInOrigin = it },
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

            // Its own theme root rather than a parameter on HermesApp: a
            // Dialog is a separate window, and this keeps the OS permission
            // story out of the app's navigation shape entirely.
            if (notificationRationaleVisible) {
                HermesTheme(appearance) {
                    NotificationPermissionPrompt(
                        onContinue = {
                            notificationRationaleVisible = false
                            notificationRationaleDismissed = true
                            app.appScope.launch { app.notificationPreferences.markNotificationPermissionAsked() }
                            if (Build.VERSION.SDK_INT >= ANDROID_TIRAMISU) {
                                requestPostNotifications.launch(POST_NOTIFICATIONS)
                            }
                        },
                        onDismiss = {
                            notificationRationaleVisible = false
                            notificationRationaleDismissed = true
                        },
                    )
                }
            }
        }
    }

    /**
     * A notification tap lands here. The extra is consumed, so an Activity
     * recreate does not re-navigate away from wherever the user has since gone.
     *
     * The sign-in hand-back also arrives here, because it resumes this instance
     * rather than starting a second one (`GatewaySignInBrowser.returnIntent`).
     * It carries no action, and one optional extra: where the sign-in started.
     * Without that extra — every hand-back before #116, and every one from the
     * Gateways pane — it is still ignored below, which was the whole intent:
     * come forward, change nothing. [setIntent] is what keeps that true —
     * without it `getIntent()` would still answer the notification intent that
     * launched this Activity, and the next thing to read it would act on a
     * navigation the person already consumed.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openSessionFromIntent(intent)
        returnFromSignIn(intent)
    }

    /**
     * A sign-in that started in the sessions drawer finishes there.
     *
     * The hand-back Intent is the only thing that survives the round trip with
     * that knowledge — the person may have been in a browser for minutes, and
     * this Activity may have been destroyed and rebuilt behind them — so the
     * origin travels on it (`GatewaySignInBrowser.returnIntent`). A hand-back
     * that says Gateways, or says nothing, keeps what it has always done: come
     * forward, change nothing.
     *
     * The extra is consumed for the same reason the notification one is: an
     * Activity recreate must not re-navigate a journey already finished.
     */
    private fun returnFromSignIn(intent: Intent?) {
        val destination = handBackDestination(signInOriginFrom(intent)) ?: return
        intent?.removeExtra(EXTRA_SIGN_IN_ORIGIN)
        navigationAsks += 1
        navigationAsk = HermesNavigationAsk(destination, navigationAsks)
    }

    private fun openSessionFromIntent(intent: Intent?) {
        if (intent?.action != ACTION_OPEN_SESSION) return
        val durableSessionId = intent.getStringExtra(EXTRA_DURABLE_SESSION_ID)?.takeIf(String::isNotBlank) ?: return
        intent.removeExtra(EXTRA_DURABLE_SESSION_ID)
        chatViewModel.selectSession(durableSessionId)
    }

    /**
     * Tells the process-scoped notifier which conversation is on screen — the
     * Android reading of Desktop's `$activeSessionId`
     * (`apps/desktop/src/store/native-notifications.ts:142` @
     * `3ca096de5f8183cb2e0ec23673f294d5978656a3`).
     */
    private fun followVisibleSession() {
        lifecycleScope.launch {
            chatViewModel.uiState
                .map { it.activeSession?.id }
                .distinctUntilChanged()
                .collect(app.notificationPresence::visibleSessionChanged)
        }
    }

    /** Asks for `POST_NOTIFICATIONS` at the first moment the grant buys anything. */
    private fun followNotificationPermissionNeed() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                combine(
                    app.notificationPreferences.notificationPermissionAsked,
                    app.gatewayConnection.state.map { it.status == GatewayConnectionStatus.Connected },
                ) { asked, connected -> asked to connected }
                    .distinctUntilChanged()
                    .collect { (asked, connected) ->
                        val step = notificationPermissionStep(
                            sdkInt = Build.VERSION.SDK_INT,
                            granted = postNotificationsGranted(),
                            alreadyAsked = asked,
                            gatewayConnected = connected,
                        )
                        notificationRationaleVisible =
                            step == NotificationPermissionStep.Rationale && !notificationRationaleDismissed
                    }
            }
        }
    }

    private fun postNotificationsGranted(): Boolean =
        Build.VERSION.SDK_INT < ANDROID_TIRAMISU ||
            checkSelfPermission(POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_RATIONALE_DISMISSED, notificationRationaleDismissed)
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

        /** Named rather than referenced: the constant only exists from API 33. */
        const val POST_NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS"
        const val STATE_RATIONALE_DISMISSED = "notificationRationaleDismissed"
    }
}
