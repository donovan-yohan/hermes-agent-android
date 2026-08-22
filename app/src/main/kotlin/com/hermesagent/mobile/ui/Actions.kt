package com.hermesagent.mobile.ui

import com.hermesagent.mobile.data.ssh.AuthMethod
import com.hermesagent.mobile.data.composer.CompletionItem
import com.hermesagent.mobile.data.composer.ComposerModelSelection
import com.hermesagent.mobile.data.composer.FastMode
import com.hermesagent.mobile.data.composer.ReasoningEffort
import com.hermesagent.mobile.data.gateway.GatewayConnectionMode
import com.hermesagent.mobile.data.prefs.SidebarGrouping
import com.hermesagent.mobile.ui.theme.HermesThemeMode

/**
 * What each surface can ask for, grouped by surface.
 *
 * Screens take a state plus one of these instead of a long parameter list, so
 * adding an action is one edit rather than four signatures, and a preview or a
 * test can hand over a no-op bundle. They stay separate bundles because the
 * surfaces are separate — a screen should not be able to reach an action that
 * belongs to another one.
 */
class ChatActions(
    val onQueryChange: (String) -> Unit = {},
    val onDraftChange: (String) -> Unit = {},
    val onRefreshNavigation: () -> Unit = {},
    val onSidebarGroupingChange: (SidebarGrouping) -> Unit = {},
    val onSelectProject: (String) -> Unit = {},
    val onExitProject: () -> Unit = {},
    val onCreateProject: (name: String, folderPath: String) -> Unit = { _, _ -> },
    val onSelectSession: (String) -> Unit = {},
    val onCreateSession: () -> Unit = {},
    val onSend: () -> Unit = {},
    val onStop: () -> Unit = {},
    val onRedirect: () -> Unit = {},
    val onQueue: () -> Unit = {},
    val onSendNext: (String) -> Unit = {},
    val onResumeQueue: () -> Unit = {},
    val onEditQueuedEntry: (String) -> Unit = {},
    val onQueueEditTextChange: (String) -> Unit = {},
    val onSaveQueueEdit: () -> Unit = {},
    val onCancelQueueEdit: () -> Unit = {},
    val onDeleteQueuedEntry: (String) -> Unit = {},
    val onRedirectQueuedEntry: (String) -> Unit = {},
    val onMarkQueuedEntryReady: (String) -> Unit = {},
    val onHistoryOlder: () -> Boolean = { false },
    val onHistoryNewer: () -> Boolean = { false },
    val onUndoDraft: () -> Boolean = { false },
    val onRedoDraft: () -> Boolean = { false },
    val onRespondToPendingInput: (com.hermesagent.mobile.data.gateway.PendingInputAction) -> Unit = {},
    val onDismissSecurePending: () -> Unit = {},
    val onComposerStatusOpened: () -> Unit = {},
    val onRefreshProcesses: () -> Unit = {},
    val onKillProcess: (String) -> Unit = {},
    val onSelectModel: (ComposerModelSelection) -> Unit = {},
    val onSelectReasoning: (ReasoningEffort) -> Unit = {},
    val onSelectFast: (FastMode) -> Unit = {},
    val onEditorSelectionChange: (text: String, selectionStart: Int, selectionEnd: Int) -> Unit = { _, _, _ -> },
    val onCompletionSelected: (CompletionItem) -> Unit = {},
    val onInsertText: (String) -> Unit = {},
    val onPickFiles: () -> Unit = {},
    val onRemoveAttachment: (String) -> Unit = {},
    val onToggleDictation: () -> Unit = {},
)

class AppearanceActions(
    val onSelectTheme: (String) -> Unit = {},
    val onSelectMode: (HermesThemeMode) -> Unit = {},
)

class GatewayActions(
    val onModeChange: (GatewayConnectionMode) -> Unit = {},
    val onRemoteUrlChange: (String) -> Unit = {},
    val onProviderChange: (String) -> Unit = {},
    val onConnectRemote: () -> Unit = {},
    val onDisconnect: () -> Unit = {},
    val onForgetSignIn: () -> Unit = {},
)

class SshActions(
    val onDestinationChange: (String) -> Unit = {},
    val onRemoteProfileChange: (String) -> Unit = {},
    val onAuthMethodChange: (AuthMethod) -> Unit = {},
    val onPasswordChange: (String) -> Unit = {},
    val onPassphraseChange: (String) -> Unit = {},
    val onImportKey: () -> Unit = {},
    val onForgetKey: () -> Unit = {},
    val onConnect: () -> Unit = {},
    val onDisconnect: () -> Unit = {},
    val onProbe: () -> Unit = {},
    val onCancelProbe: () -> Unit = {},
    val onAcceptHostKey: () -> Unit = {},
    val onDismissHostKey: () -> Unit = {},
    val onForgetHostKey: () -> Unit = {},
    /**
     * The SSH surface is leaving. Ends the screen's credential lifetime — see
     * [com.hermesagent.mobile.ui.ssh.SshViewModel.releaseScreen]. It is an
     * action rather than a lifecycle callback because the screen, not the
     * Activity, is what the secrets belong to.
     */
    val onLeaveScreen: () -> Unit = {},
)
