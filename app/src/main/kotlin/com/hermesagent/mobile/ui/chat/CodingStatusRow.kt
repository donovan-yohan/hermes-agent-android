package com.hermesagent.mobile.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hermesagent.mobile.ui.common.HermesIcon
import com.hermesagent.mobile.ui.common.HermesIconButton
import com.hermesagent.mobile.ui.common.COPY_CONFIRM_MILLIS
import com.hermesagent.mobile.ui.common.copyToClipboard
import com.hermesagent.mobile.ui.theme.HermesTheme
import kotlinx.coroutines.delay

/**
 * Mobile adaptation of Desktop's coding-status base row.
 *
 * The shell itself stays inert. The git glyph/branch and diff counters open the
 * real authenticated changes view, the PR number opens its external URL, and
 * the copy control writes the raw server path while painting a tildified path.
 */
@Composable
internal fun CodingStatusRow(
    context: CodingContext,
    onOpenReview: () -> Unit,
    modifier: Modifier = Modifier,
    openExternal: ((String) -> Unit)? = null,
    copyPath: ((String) -> Unit)? = null,
) {
    val status = context as? CodingContext.Available ?: return
    val tokens = HermesTheme.tokens
    val platformContext = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val hasLineDelta = status.additions > 0 || status.deletions > 0
    val hasReviewCounts = status.ahead > 0 || status.behind > 0 || hasLineDelta || status.untracked > 0
    val counterStyle = HermesTheme.type.scaffold.copy(fontSize = 12.sp, fontFeatureSettings = "tnum")
    val branchTone = tokens.textTertiary.alphaMultiply(0.92f)
    val pathTone = tokens.textTertiary.alphaMultiply(0.50f)
    val divergenceTone = tokens.textTertiary.alphaMultiply(0.75f)
    var copied by remember(status.worktreePath) { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(COPY_CONFIRM_MILLIS)
            copied = false
        }
    }

    Column(modifier.fillMaxWidth().semantics { contentDescription = "Coding status" }) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = HermesTheme.spacing.touchTarget)
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HermesIconButton(
                icon = HermesIcon.GitBranch,
                contentDescription = "Review changes for branch ${status.branch}",
                onClick = onOpenReview,
                tint = tokens.diffAdded,
            )
            status.pullRequest?.let { pullRequest ->
                Text(
                    text = "#${pullRequest.number}",
                    style = HermesTheme.type.scaffoldMeta.copy(textDecoration = TextDecoration.Underline),
                    color = pullRequestColor(pullRequest),
                    modifier = Modifier
                        .heightIn(min = HermesTheme.spacing.touchTarget)
                        .clickable(role = Role.Button) {
                            val open = openExternal ?: { url: String -> uriHandler.openUri(url) }
                            runCatching { open(pullRequest.url) }
                        }
                        .semantics { contentDescription = "Open pull request #${pullRequest.number}" }
                        .padding(horizontal = 6.dp, vertical = 16.dp),
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = HermesTheme.spacing.touchTarget)
                    .clickable(role = Role.Button, onClick = onOpenReview)
                    .semantics { contentDescription = "Review changes for branch ${status.branch}" }
                    .padding(horizontal = 4.dp, vertical = 5.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = status.branch,
                    style = HermesTheme.type.caption,
                    color = branchTone,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = displayWorktreePath(status.worktreePath),
                    style = HermesTheme.type.code.copy(fontSize = 10.sp, lineHeight = 13.sp),
                    color = pathTone,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            HermesIconButton(
                icon = if (copied) HermesIcon.Check else HermesIcon.Copy,
                contentDescription = if (copied) "Repository path copied" else "Copy repository path",
                onClick = {
                    val copy = copyPath ?: { raw: String ->
                        copyToClipboard(platformContext, "Repository path", raw)
                    }
                    copy(status.worktreePath)
                    copied = true
                },
                tint = if (copied) tokens.taskCompleted else pathTone,
            )
            if (hasReviewCounts) {
                Row(
                    modifier = Modifier
                        .heightIn(min = HermesTheme.spacing.touchTarget)
                        .widthIn(min = HermesTheme.spacing.touchTarget)
                        .clickable(role = Role.Button, onClick = onOpenReview)
                        .semantics {
                            contentDescription = status.reviewCountDescription()
                        }
                        .padding(horizontal = 5.dp),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (status.ahead > 0) {
                        Text(
                            text = "↑${status.ahead}",
                            style = counterStyle,
                            color = divergenceTone,
                        )
                    }
                    if (status.behind > 0) {
                        Text(
                            text = "↓${status.behind}",
                            style = counterStyle,
                            color = divergenceTone,
                        )
                    }
                    if (hasLineDelta && status.additions > 0) {
                        Text(
                            text = "+${status.additions}",
                            style = counterStyle,
                            color = tokens.diffAdded,
                        )
                    }
                    if (hasLineDelta && status.deletions > 0) {
                        Text(
                            text = "−${status.deletions}",
                            style = counterStyle,
                            color = tokens.diffRemoved,
                        )
                    }
                    if (!hasLineDelta && status.untracked > 0) {
                        Text(
                            text = "${status.untracked} changed",
                            style = counterStyle,
                            color = tokens.gitUntracked,
                        )
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(tokens.strokeTertiary))
    }
}

private fun CodingContext.Available.reviewCountDescription(): String = buildString {
    append("Review changes")
    if (ahead > 0) append(", $ahead ahead")
    if (behind > 0) append(", $behind behind")
    if (additions > 0) append(", $additions additions")
    if (deletions > 0) append(", $deletions deletions")
    if (additions == 0 && deletions == 0 && untracked > 0) append(", $untracked untracked files")
}

@Composable
private fun pullRequestColor(pullRequest: CodingPullRequest) = when {
    pullRequest.draft -> HermesTheme.tokens.textQuaternary
    pullRequest.state == "closed" -> HermesTheme.tokens.diffRemoved
    pullRequest.state == "merged" -> HermesTheme.tokens.pullRequestMerged
    else -> HermesTheme.tokens.diffAdded
}

/** Authenticated changed-file destination for the coding row's link targets. */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun CodingReviewSheet(
    state: CodingReviewUiState,
    onDismiss: () -> Unit,
) {
    if (state is CodingReviewUiState.Closed) return
    val tokens = HermesTheme.tokens
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        dragHandle = null,
        containerColor = tokens.cardSurface,
        contentColor = tokens.textPrimary,
        scrimColor = tokens.textPrimary.copy(alpha = .32f),
        modifier = Modifier.semantics { contentDescription = "Coding changes" },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                // Inside the cap, not outside it: the keyboard's height comes
                // out of the sheet's own maximum rather than being added to it,
                // so sheet plus keyboard cannot outgrow a short screen whatever
                // height the sheet hands down.
                .heightIn(max = REVIEW_SHEET_MAX_HEIGHT)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = HermesTheme.spacing.pageInset, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("Changes", style = HermesTheme.type.screenTitle, color = tokens.textPrimary)
            Text(
                text = displayWorktreePath(state.worktreePath()),
                style = HermesTheme.type.code.copy(fontSize = 11.sp),
                color = tokens.textTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            when (state) {
                is CodingReviewUiState.Loading -> Text(
                    "Loading changed files…",
                    style = HermesTheme.type.caption,
                    color = tokens.textSecondary,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
                is CodingReviewUiState.Failed -> Text(
                    "Changes could not be loaded. Close this sheet and try again.",
                    style = HermesTheme.type.caption,
                    color = tokens.destructive,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
                is CodingReviewUiState.Ready -> if (state.files.isEmpty()) {
                    Text(
                        "No local changes.",
                        style = HermesTheme.type.caption,
                        color = tokens.textSecondary,
                        modifier = Modifier.padding(vertical = 16.dp),
                    )
                } else {
                    state.files.forEach { file -> CodingReviewFileRow(file) }
                }
                CodingReviewUiState.Closed -> Unit
            }
        }
    }
}

@Composable
private fun CodingReviewFileRow(file: CodingReviewFile) {
    val tokens = HermesTheme.tokens
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .semantics {
                contentDescription = buildString {
                    append(file.path)
                    append(", ${file.status.ifBlank { "changed" }}")
                    if (file.staged) append(", staged")
                    append(", ${file.additions} additions, ${file.deletions} deletions")
                }
            }
            .padding(horizontal = 4.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = file.status.ifBlank { "M" },
            style = HermesTheme.type.scaffoldMeta,
            color = tokens.textTertiary,
            modifier = Modifier.padding(end = 8.dp),
        )
        Text(
            text = file.path,
            style = HermesTheme.type.code.copy(fontSize = 11.sp),
            color = tokens.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (file.additions > 0) {
            Text("+${file.additions}", style = HermesTheme.type.scaffoldMeta, color = tokens.diffAdded)
        }
        if (file.deletions > 0) {
            Text(
                "−${file.deletions}",
                style = HermesTheme.type.scaffoldMeta,
                color = tokens.diffRemoved,
                modifier = Modifier.padding(start = 5.dp),
            )
        }
    }
}

private fun CodingReviewUiState.worktreePath(): String = when (this) {
    CodingReviewUiState.Closed -> ""
    is CodingReviewUiState.Loading -> worktreePath
    is CodingReviewUiState.Ready -> worktreePath
    is CodingReviewUiState.Failed -> worktreePath
}

private fun Color.alphaMultiply(multiplier: Float): Color = copy(alpha = alpha * multiplier)

private val REVIEW_SHEET_MAX_HEIGHT = 560.dp
