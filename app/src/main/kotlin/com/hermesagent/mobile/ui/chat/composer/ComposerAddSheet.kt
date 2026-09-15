@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.hermesagent.mobile.ui.chat.composer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hermesagent.mobile.ui.common.CenteredTextFieldContent
import com.hermesagent.mobile.ui.common.HermesIcon
import com.hermesagent.mobile.ui.common.HermesIconGlyph
import com.hermesagent.mobile.ui.theme.HermesTheme

/**
 * Android's context menu. Files and images stage locally acquired bytes
 * through the Gateway before submit; URL and prompt snippets stay text
 * controls; folder acquisition remains deferred until a bounded archive
 * protocol exists. A thumbnail adds and keeps this sheet open so several
 * images can join one message; the sheet otherwise closes as its actions do today.
 * Attachment chips are the only removal surface; this sheet never removes one.
 */
@Composable
internal fun ComposerAddControl(
    onInsertText: (String) -> Unit,
    enabled: Boolean,
    onPickFiles: () -> Unit = {},
    recentImages: RecentImagesUiState = RecentImagesUiState(),
    onAddRecentImage: (Long) -> Unit = {},
    onRequestRecentImageAccess: () -> Unit = {},
    onPickPhotos: () -> Unit = {},
    onSheetOpened: () -> Unit = {},
    onSheetClosed: () -> Unit = {},
    onDismiss: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var sheet by remember { mutableStateOf<AddSheet?>(null) }
    val tokens = HermesTheme.tokens
    Box(
        modifier
            .size(HermesTheme.spacing.touchTarget)
            .clickable(enabled = enabled, role = Role.Button) { sheet = AddSheet.Menu }
            .semantics {
                contentDescription = "Add to message"
                if (!enabled) disabled()
            }
            .testTag("Composer add control"),
        contentAlignment = Alignment.Center,
    ) {
        HermesIconGlyph(HermesIcon.Add, color = if (enabled) tokens.textSecondary else tokens.textQuaternary, size = 15.sp)
    }
    when (sheet) {
        AddSheet.Menu -> ComposerAddSheet(
            onDismiss = {
                sheet = null
                onDismiss()
            },
            onChoose = { chosen ->
                if (chosen == AddSheet.Files) {
                    sheet = AddSheet.Done
                    onPickFiles()
                } else {
                    sheet = chosen
                }
            },
            recentImages = recentImages,
            onAddRecentImage = onAddRecentImage,
            onRequestRecentImageAccess = onRequestRecentImageAccess,
            onPickPhotos = onPickPhotos,
            onSheetOpened = onSheetOpened,
            onSheetClosed = onSheetClosed,
        )
        AddSheet.Url -> UrlReferenceSheet(
            onDismiss = { sheet = AddSheet.Menu },
            onInsert = { reference ->
                onInsertText(reference)
                sheet = null
                onDismiss()
            },
        )
        AddSheet.Snippets -> PromptSnippetSheet(
            onDismiss = { sheet = AddSheet.Menu },
            onInsert = { snippet ->
                onInsertText(snippet)
                sheet = null
                onDismiss()
            },
        )
        AddSheet.Done, AddSheet.Files, null -> Unit
    }
}

private enum class AddSheet { Menu, Url, Snippets, Files, Done }

@Composable
private fun ComposerAddSheet(
    onDismiss: () -> Unit,
    onChoose: (AddSheet) -> Unit,
    recentImages: RecentImagesUiState,
    onAddRecentImage: (Long) -> Unit,
    onRequestRecentImageAccess: () -> Unit,
    onPickPhotos: () -> Unit,
    onSheetOpened: () -> Unit,
    onSheetClosed: () -> Unit,
) {
    val tokens = HermesTheme.tokens
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = tokens.cardSurface,
        contentColor = tokens.textPrimary,
        scrimColor = tokens.overlayScrim,
        modifier = Modifier.testTag("Composer add sheet"),
    ) {
        LaunchedEffect(Unit) { onSheetOpened() }
        // The rail's rows and thumbnails are device material held in memory for
        // one look, like every other attachment read: closing the sheet is what
        // takes them away, and nothing re-reads the library until it reopens.
        DisposableEffect(Unit) { onDispose { onSheetClosed() } }
        ComposerAddSheetContent(
            recentImages = recentImages,
            onAddRecentImage = onAddRecentImage,
            onRequestRecentImageAccess = onRequestRecentImageAccess,
            onPickPhotos = onPickPhotos,
            onChooseFiles = { onChoose(AddSheet.Files) },
            onChooseUrl = { onChoose(AddSheet.Url) },
            onChooseSnippets = { onChoose(AddSheet.Snippets) },
        )
    }
}

/**
 * The sheet's own body, without its dialog. The app's sheet, the Compose tests
 * and the debug capture fixture all render this one composition, so a capture
 * shows what a person sees rather than a reconstruction of it.
 */
@Composable
internal fun ComposerAddSheetContent(
    recentImages: RecentImagesUiState,
    onAddRecentImage: (Long) -> Unit,
    onRequestRecentImageAccess: () -> Unit,
    onPickPhotos: () -> Unit,
    onChooseFiles: () -> Unit,
    onChooseUrl: () -> Unit,
    onChooseSnippets: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = HermesTheme.tokens
    Column(
        modifier
            .fillMaxWidth()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .testTag("Composer add sheet content")
            .padding(horizontal = HermesTheme.spacing.pageInset, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("Add to message", style = HermesTheme.type.screenTitle, color = tokens.textPrimary)
        RecentImagesSection(
            state = recentImages,
            onAddImage = onAddRecentImage,
            onRequestAccess = onRequestRecentImageAccess,
            onChoosePhotos = onPickPhotos,
        )
        AddRow(
            label = "Files",
            description = "Attach a file from this device",
            icon = HermesIcon.File,
            onClick = onChooseFiles,
        )
        AddRow(
            label = "URL",
            description = "Add a remote URL reference",
            icon = HermesIcon.Link,
            onClick = onChooseUrl,
        )
        AddRow(
            label = "Prompt snippets",
            description = "Insert a reusable prompt",
            icon = HermesIcon.SymbolMethod,
            onClick = onChooseSnippets,
        )
        Text(
            "Files upload through the Gateway when you send. Folders aren't available yet.",
            style = HermesTheme.type.scaffoldMeta,
            color = tokens.scaffoldMeta.copy(alpha = 1f),
            modifier = Modifier
                .padding(start = 8.dp, end = 8.dp, bottom = 4.dp)
                .padding(horizontal = HermesTheme.spacing.pageInset / 2),
        )
    }
}

@Composable
internal fun AddRow(
    label: String,
    description: String,
    icon: HermesIcon,
    testTag: String? = null,
    onClick: () -> Unit,
) {
    val tokens = HermesTheme.tokens
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = HermesTheme.spacing.touchTarget)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "$label. $description" }
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
            .padding(horizontal = 8.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        HermesIconGlyph(icon, color = tokens.textTertiary)
        Column {
            Text(label, style = HermesTheme.type.body, color = tokens.textPrimary)
            Text(
                description,
                style = HermesTheme.type.scaffoldMeta,
                color = tokens.scaffoldMeta,
            )
        }
    }
}

@Composable
private fun UrlReferenceSheet(onDismiss: () -> Unit, onInsert: (String) -> Unit) {
    var url by remember { mutableStateOf("") }
    val tokens = HermesTheme.tokens
    val valid = validComposerUrl(url)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = tokens.cardSurface,
        contentColor = tokens.textPrimary,
        scrimColor = tokens.overlayScrim,
        modifier = Modifier.testTag("Composer URL sheet"),
    ) {
        Column(
            Modifier.fillMaxWidth().imePadding().padding(horizontal = HermesTheme.spacing.pageInset, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Add URL", style = HermesTheme.type.screenTitle, color = tokens.textPrimary)
            Text("Use a full http:// or https:// URL.", style = HermesTheme.type.caption, color = tokens.textTertiary)
            BasicTextField(
                value = url,
                onValueChange = { url = it },
                textStyle = HermesTheme.type.body.copy(color = tokens.textPrimary),
                cursorBrush = SolidColor(tokens.composerRing),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = HermesTheme.spacing.touchTarget)
                    .background(tokens.widgetSurface, RoundedCornerShape(8.dp))
                    .semantics { contentDescription = "URL to add" }
                    .padding(horizontal = 10.dp),
                decorationBox = { inner ->
                    CenteredTextFieldContent(
                        isEmpty = url.isEmpty(),
                        contentTag = "Composer URL text",
                        placeholder = { Text("https://example.com", style = HermesTheme.type.body, color = tokens.textTertiary) },
                        innerTextField = inner,
                    )
                },
            )
            Text(
                "Add URL",
                style = HermesTheme.type.bodyStrong,
                color = if (valid) tokens.accentForeground else tokens.textQuaternary,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = HermesTheme.spacing.touchTarget)
                    .background(if (valid) tokens.accent else tokens.widgetSurface, RoundedCornerShape(8.dp))
                    .clickable(enabled = valid, role = Role.Button) { onInsert(composerUrlReferenceText(url.trim())) }
                    .semantics {
                        contentDescription = "Add URL reference"
                        if (!valid) disabled()
                    }
                    .padding(horizontal = 12.dp, vertical = 12.dp),
            )
        }
    }
}

@Composable
internal fun PromptSnippetSheet(onDismiss: () -> Unit, onInsert: (String) -> Unit) {
    val tokens = HermesTheme.tokens
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = tokens.cardSurface,
        contentColor = tokens.textPrimary,
        scrimColor = tokens.overlayScrim,
        modifier = Modifier.testTag("Composer prompt snippets sheet"),
    ) {
        Column(
            Modifier.fillMaxWidth().imePadding().padding(horizontal = HermesTheme.spacing.pageInset, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("Prompt snippets", style = HermesTheme.type.screenTitle, color = tokens.textPrimary)
            Text("Insert exact prompt text, then tailor it for this session.", style = HermesTheme.type.caption, color = tokens.textTertiary)
            promptSnippets.forEach { snippet ->
                AddRow(snippet.label, snippet.description, HermesIcon.SymbolMethod) {
                    onInsert(snippet.text)
                }
            }
        }
    }
}

private data class PromptSnippet(val label: String, val description: String, val text: String)

private val promptSnippets = listOf(
    PromptSnippet("Code review", "Review a change for correctness and risk.", "Review this change for correctness, regressions, and missing tests. Explain the highest-impact findings first."),
    PromptSnippet("Implementation plan", "Turn a goal into safe, verifiable steps.", "Make a concise implementation plan. Name the affected files, risks, and the focused checks that prove each step."),
    PromptSnippet("Explain this", "Ask for a clear walkthrough.", "Explain this clearly: what it does, why it works, and the important tradeoffs."),
)
