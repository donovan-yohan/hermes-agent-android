package com.hermesagent.mobile.ui

import com.hermesagent.mobile.data.ssh.AuthMethod
import com.hermesagent.mobile.data.composer.CompletionItem
import com.hermesagent.mobile.data.composer.ComposerModelSelection
import com.hermesagent.mobile.data.composer.FastMode
import com.hermesagent.mobile.data.composer.ReasoningEffort
import com.hermesagent.mobile.data.gateway.ApprovalMode
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
    val onBranchFromReply: ((String) -> Unit)? = null,
    val onRegenerateReply: ((String) -> Unit)? = null,
    val onRenameSession: (suspend (String, String) -> Unit) = { _, _ -> },
    val onDeleteSession: (suspend (String) -> Unit) = { _ -> },
    /**
     * Pin, read-state and archive. Not `suspend`: every one of these verbs
     * takes the row off the list it was pressed on, so a coroutine owned by
     * that row's composition would be cancelled before the write landed. The
     * ViewModel owns the scope.
     */
    val onSetSessionPinned: ((String, Boolean) -> Unit) = { _, _ -> },
    val onSetSessionUnread: ((String, Boolean) -> Unit) = { _, _ -> },
    val onSetSessionArchived: ((String, Boolean) -> Unit) = { _, _ -> },
    /** Desktop's `Archived` filter: show the archived set instead of the live one. */
    val onArchivedVisibleChange: (Boolean) -> Unit = {},
    val onMarkAllSessionsRead: () -> Unit = {},
    /** Scope the sidebar to one Hermes profile and start fresh there. */
    val onSelectProfile: (String) -> Unit = {},
    /** Desktop's opt-in unified view; it does not change which profile is active. */
    val onShowAllProfiles: () -> Unit = {},
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
    val onRefreshCodingContext: () -> Unit = {},
    val onOpenCodingReview: () -> Unit = {},
    val onDismissCodingReview: () -> Unit = {},
    val onRefreshProcesses: () -> Unit = {},
    val onKillProcess: (String) -> Unit = {},
    /** Write the host's approval mode; the repository owns the rollback. */
    val onSelectApprovalMode: (ApprovalMode) -> Unit = {},
    val onSelectModel: (ComposerModelSelection) -> Unit = {},
    /** Show or hide one model in the picker. */
    val onToggleModelVisible: (provider: String, model: String) -> Unit = { _, _ -> },
    /** Show or hide every model a provider serves. */
    val onSetProviderModelsVisible: (provider: String, visible: Boolean) -> Unit = { _, _ -> },
    val onSelectReasoning: (ReasoningEffort) -> Unit = {},
    val onSelectFast: (FastMode) -> Unit = {},
    val onEditorSelectionChange: (text: String, selectionStart: Int, selectionEnd: Int) -> Unit = { _, _, _ -> },
    val onCompletionSelected: (CompletionItem) -> Unit = {},
    val onInsertText: (String) -> Unit = {},
    val onPickFiles: () -> Unit = {},
    val onRemoveAttachment: (String) -> Unit = {},
    val onShowEarlierMessages: () -> Unit = {},
    val onToggleReadAloud: (String) -> Unit = {},
    val onToggleDictation: () -> Unit = {},
    val onToggleConversation: () -> Unit = {},
    val onToggleVoiceMute: () -> Unit = {},
)

class AppearanceActions(
    val onSelectTheme: (String) -> Unit = {},
    val onSelectMode: (HermesThemeMode) -> Unit = {},
    /** Desktop's `setIntroSplash` (`store/intro-splash.ts:11-13` @ `3ca096de`). */
    val onSetIntroSplash: (Boolean) -> Unit = {},
)

class GatewayActions(
    val onModeChange: (GatewayConnectionMode) -> Unit = {},
    val onRemoteUrlChange: (String) -> Unit = {},
    val onProviderChange: (String) -> Unit = {},
    val onConnectRemote: () -> Unit = {},
    /**
     * Dial the Hermes this device is running. Separate from [onConnectRemote]
     * because it has no interactive step at all: there is no browser, no
     * sign-in and no process for this app to start — only a socket.
     */
    val onConnectLocal: () -> Unit = {},
    val onDisconnect: () -> Unit = {},
    val onForgetSignIn: () -> Unit = {},
)

/**
 * The saved-connections registry and the session-rail switcher.
 *
 * Navigation is deliberately absent for the same reason
 * [com.hermesagent.mobile.plugins.relay.RelayActions] omits it: the shell
 * decides where "Manage gateways…" goes, not the rail.
 */
class ConnectionsActions(
    val onSelect: (String) -> Unit = {},
    val onBeginAdd: () -> Unit = {},
    val onBeginEdit: (String) -> Unit = {},
    val onCancelEditor: () -> Unit = {},
    val onEditKind: (com.hermesagent.mobile.data.connections.ConnectionKind) -> Unit = {},
    val onEditLabel: (String) -> Unit = {},
    val onEditUrl: (String) -> Unit = {},
    val onEditProvider: (String) -> Unit = {},
    val onEditDestination: (String) -> Unit = {},
    /** The Local route's session token. Held by the form, never read back from the store. */
    val onEditToken: (String) -> Unit = {},
    val onSaveEditor: () -> Unit = {},
    val onRequestRemove: (String) -> Unit = {},
    val onCancelRemove: () -> Unit = {},
    val onConfirmRemove: () -> Unit = {},
    /**
     * The Gateways surface is leaving. Ends the editor's credential lifetime —
     * see [com.hermesagent.mobile.ui.gateway.ConnectionsViewModel.releaseScreen]
     * — for the same reason [SshActions.onLeaveScreen] exists: the ViewModel
     * outlives the screen the person believes they closed.
     */
    val onLeaveScreen: () -> Unit = {},
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
