package com.hermesagent.mobile.ui

import com.hermesagent.mobile.data.ssh.AuthMethod
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
    val onSelectSession: (String) -> Unit = {},
    val onCreateSession: () -> Unit = {},
    val onArchiveToggle: (id: String, archived: Boolean) -> Unit = { _, _ -> },
    val onRenameSession: (id: String, title: String) -> Unit = { _, _ -> },
    val onSend: () -> Unit = {},
    val onStop: () -> Unit = {},
    val onToggleArchived: () -> Unit = {},
    val onOpenSettings: () -> Unit = {},
)

class AppearanceActions(
    val onSelectTheme: (String) -> Unit = {},
    val onSelectMode: (HermesThemeMode) -> Unit = {},
)

class SshActions(
    val onHostChange: (String) -> Unit = {},
    val onPortChange: (String) -> Unit = {},
    val onUsernameChange: (String) -> Unit = {},
    val onAuthMethodChange: (AuthMethod) -> Unit = {},
    val onPasswordChange: (String) -> Unit = {},
    val onPassphraseChange: (String) -> Unit = {},
    val onImportKey: () -> Unit = {},
    val onForgetKey: () -> Unit = {},
    val onProbe: () -> Unit = {},
    val onCancelProbe: () -> Unit = {},
    val onAcceptHostKey: () -> Unit = {},
    val onDismissHostKey: () -> Unit = {},
    val onForgetHostKey: () -> Unit = {},
)
