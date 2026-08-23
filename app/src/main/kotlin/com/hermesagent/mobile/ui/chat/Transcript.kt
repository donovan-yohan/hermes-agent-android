package com.hermesagent.mobile.ui.chat

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.hermesagent.mobile.data.attachments.ImageRefLines
import com.hermesagent.mobile.data.gateway.GatewayImageLoader
import com.hermesagent.mobile.data.markdown.InlineSpan
import com.hermesagent.mobile.data.markdown.MarkdownBlock
import com.hermesagent.mobile.data.markdown.TableSizing
import com.hermesagent.mobile.data.markdown.parseMarkdown
import com.hermesagent.mobile.data.session.AssistantTurn
import com.hermesagent.mobile.data.session.ReasoningActivity
import com.hermesagent.mobile.data.session.SessionProgress
import com.hermesagent.mobile.data.session.ToolActivity
import com.hermesagent.mobile.data.session.ToolState
import com.hermesagent.mobile.data.session.TranscriptEntry
import com.hermesagent.mobile.data.session.UserTurn
import com.hermesagent.mobile.ui.common.AttachmentThumbnails
import com.hermesagent.mobile.ui.common.EmptyState
import com.hermesagent.mobile.ui.common.ErrorState
import com.hermesagent.mobile.ui.common.DitherMark
import com.hermesagent.mobile.ui.common.HermesIcon
import com.hermesagent.mobile.ui.common.HermesIconButton
import com.hermesagent.mobile.ui.common.HermesIconGlyph
import com.hermesagent.mobile.ui.common.ScaffoldRow
import com.hermesagent.mobile.ui.theme.HermesTheme
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The transcript.
 *
 * Grammar taken from Desktop, and the two halves are deliberately asymmetric
 * (`apps/desktop/src/components/assistant-ui/thread/` @ `f82f2dba`):
 *
 * - **The user speaks in a bubble** — a soft `--dt-user-bubble` fill with a
 *   hairline, aligned to the end (`user-message.tsx:67`).
 * - **The assistant does not.** Its prose is flat on the chat surface, full
 *   width, no card (`assistant-message.tsx:189-197`). This is what stops the
 *   transcript becoming the generic Material card pile.
 * - **Tool runs are scaffolding, not messages** — one quiet line in the
 *   scaffold colour, with the payload as an inset (`scaffold-row.tsx`).
 */
@Composable
fun Transcript(
    entries: List<TranscriptEntry>,
    listState: LazyListState,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    isWorking: Boolean = false,
    activityStartedAtMillis: Long? = null,
    progress: SessionProgress? = null,
    imageLoader: GatewayImageLoader? = null,
) {
    val spacing = HermesTheme.spacing
    val hasRunningActivity = entries.any {
        (it is ReasoningActivity && it.state == ToolState.Running) ||
            (it is ToolActivity && it.state == ToolState.Running)
    }
    val turnIsWorking = isWorking || entries.any { it is AssistantTurn && it.streaming }
    val showTurnProgress = turnIsWorking && !hasRunningActivity

    if (entries.isEmpty() && !showTurnProgress) {
        Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            EmptyState(
                title = "No messages yet",
                description = "Start a conversation with Hermes.",
            )
        }
        return
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(spacing.blockGap),
    ) {
        items(items = entries, key = { it.id }) { entry ->
            when (entry) {
                is UserTurn -> UserBubble(entry, imageLoader)
                is AssistantTurn -> AssistantProse(entry)
                is ReasoningActivity -> ReasoningRow(entry)
                is ToolActivity -> ToolRow(entry)
            }
        }
        if (showTurnProgress) {
            item(key = "turn-progress") {
                TurnProgressRow(activityStartedAtMillis, progress)
            }
        }
    }
}

@Composable
private fun UserBubble(turn: UserTurn, imageLoader: GatewayImageLoader?) {
    val tokens = HermesTheme.tokens
    // Persisted user turns carry trailing `@image:<path>` lines (the
    // gateway's persist-time rewrite); render them as thumbnails instead of
    // placeholder prose, exactly like Desktop's extractImageRefs.
    val (bodyText, imageRefs) = remember(turn.text) { ImageRefLines.split(turn.text) }
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // Desktop parity: no body text, no bubble — the thumbnail stands alone.
        if (bodyText.isNotBlank()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Box(
                    Modifier
                        .widthIn(max = 320.dp)
                        .background(tokens.userBubble, RoundedCornerShape(14.dp))
                        .border(1.dp, tokens.userBubbleBorder, RoundedCornerShape(14.dp))
                        .padding(horizontal = 12.dp, vertical = 9.dp)
                        // Keep the bubble as one accessible message. Merging removes
                        // the duplicate readable Text child while preserving any
                        // descendant actions if the bubble gains one later.
                        .semantics(mergeDescendants = true) {
                            contentDescription = "You said: $bodyText"
                        },
                ) {
                    // The parent retains its accessible label and any future actions;
                    // only this visual leaf is silent so TalkBack does not read the
                    // message twice through the merged subtree.
                    Text(
                        bodyText,
                        style = HermesTheme.type.body,
                        color = tokens.textPrimary,
                        modifier = Modifier.clearAndSetSemantics {},
                    )
                }
            }
        }
        imageRefs.forEach { ref ->
            AttachedImageRow(
                refLine = ref,
                imageLoader = imageLoader,
                modifier = Modifier
                    .padding(end = HermesTheme.spacing.textIndent)
                    .testTag("Transcript attached image ${ref.hashCode()}"),
            )
        }
    }
}

/**
 * One attached image below a user turn. Fetches the gateway-staged bytes over
 * the connection-owned loader (bounded, authenticated, 15s) and shows the
 * downsampled thumbnail; failure degrades to a quiet file chip, never an error
 * toast. Tapping opens the full-size bitmap over the transcript.
 */
@Composable
private fun AttachedImageRow(
    refLine: String,
    imageLoader: GatewayImageLoader?,
    modifier: Modifier = Modifier,
) {
    val tokens = HermesTheme.tokens
    val path = remember(refLine) { ImageRefLines.pathOf(refLine) }
    var thumbnail by remember(refLine) { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember(refLine) { mutableStateOf(false) }
    var showFull by remember(refLine) { mutableStateOf(false) }
    var full by remember(refLine) { mutableStateOf<ImageBitmap?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(refLine, imageLoader, path) {
        if (path == null || imageLoader == null) {
            // No fetch is possible — but the turn must not vanish: degrade to
            // the quiet chip instead of rendering nothing (image-only turns
            // in disconnected/cached history).
            failed = true
            return@LaunchedEffect
        }
        imageLoader.load(path).fold(
            onSuccess = { bytes ->
                val decoded = withContext(Dispatchers.Default) {
                    AttachmentThumbnails.decodeComposer(bytes)
                }
                thumbnail = decoded
                failed = decoded == null
            },
            onFailure = { failed = true },
        )
    }

    val label = remember(refLine) { path?.substringAfterLast('/')?.ifBlank { null } ?: "image" }
    if (failed) {
        Row(
            modifier
                .background(tokens.widgetSurface, RoundedCornerShape(8.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            HermesIconGlyph(HermesIcon.File, size = 12.sp, color = tokens.textTertiary)
            Text(label, style = HermesTheme.type.caption, color = tokens.textSecondary, maxLines = 1)
        }
        return
    }

    val current = thumbnail ?: return
    if (showFull) {
        FullSizeImageOverlay(
            bitmap = full ?: current,
            label = label,
            onDismiss = { showFull = false },
        )
    }
    Image(
        bitmap = current,
        contentDescription = label,
        contentScale = ContentScale.Fit,
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .widthIn(max = 220.dp)
            .heightIn(max = 220.dp)
            .background(tokens.widgetSurface)
            .clickable(role = Role.Button) {
                // The full-resolution decode is only paid on tap; a transcript
                // full of images stays cheap until the user opens one.
                if (full == null && path != null && imageLoader != null) {
                    scope.launch {
                        imageLoader.load(path).fold(
                            onSuccess = { bytes ->
                                withContext(Dispatchers.Default) {
                                    AttachmentThumbnails.decodeTranscript(bytes)
                                }?.let { full = it }
                            },
                            onFailure = {},
                        )
                    }
                }
                showFull = true
            },
    )
}

/** Full-screen tap-through viewer with a dismiss affordance. */
@Composable
private fun FullSizeImageOverlay(
    bitmap: ImageBitmap,
    label: String,
    onDismiss: () -> Unit,
) {
    val tokens = HermesTheme.tokens
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxSize()
                .background(tokens.chatSurface.copy(alpha = 0.97f))
                .testTag("Full size image"),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 48.dp),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    bitmap = bitmap,
                    contentDescription = label,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            HermesIconButton(
                icon = HermesIcon.Close,
                contentDescription = "Close image",
                onClick = onDismiss,
                modifier = Modifier
                    .padding(bottom = 24.dp)
                    .testTag("Close full size image"),
            )
        }
    }
}

/**
 * What a screen reader hears when a turn starts producing.
 *
 * Deliberately constant. A live region re-announces whenever its description
 * changes, so anything derived from the streamed text would read the reply
 * aloud a token at a time — the sighted equivalent of the dots being the only
 * cue is one announcement, not a running commentary. The finished prose is
 * readable as ordinary text once it lands.
 */
@Composable
private fun AssistantProse(turn: AssistantTurn) {
    val blocks = remember(turn.markdown) { parseMarkdown(turn.markdown) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(end = HermesTheme.spacing.textIndent)
            .then(
                if (turn.streaming) {
                    Modifier.semantics {
                        contentDescription = "Hermes started replying"
                        liveRegion = LiveRegionMode.Polite
                    }
                } else {
                    Modifier
                },
            ),
        verticalArrangement = Arrangement.spacedBy(HermesTheme.spacing.turnGap),
    ) {
        for (block in blocks) {
            MarkdownBlockView(block)
        }

        if (turn.stopped) {
            ScaffoldRow(label = "Stopped by you")
        }

        turn.error?.let { message ->
            ErrorState(title = "That turn failed", description = message)
        }
    }
}

@Composable
private fun ReasoningRow(activity: ReasoningActivity) {
    var expanded by rememberSaveable(activity.id) { mutableStateOf(activity.state == ToolState.Running) }
    LaunchedEffect(activity.state) {
        if (activity.state != ToolState.Running) expanded = false
    }
    val seconds = liveElapsedSeconds(activity.startedAtMillis, activity.elapsedSeconds, activity.state == ToolState.Running)
    val title = when {
        activity.state == ToolState.Running -> "Thinking"
        seconds < 1.0 -> "Thought briefly"
        else -> "Thought for ${seconds.elapsedLabel()}"
    }
    DisclosureRow(
        title = title,
        meta = if (activity.state == ToolState.Running) seconds.elapsedLabel() else null,
        icon = null,
        expanded = expanded,
        state = activity.state,
        onToggle = { expanded = !expanded },
        contentDescription = "Reasoning, ${if (expanded) "expanded" else "collapsed"}",
    )
    if (expanded && activity.text.isNotBlank()) {
        Text(
            text = activity.text,
            style = HermesTheme.type.caption,
            color = HermesTheme.tokens.textTertiary,
            modifier = Modifier.padding(start = 36.dp, end = 12.dp, bottom = 4.dp),
        )
    }
}

@Composable
private fun ToolRow(activity: ToolActivity) {
    val stateLabel = when (activity.state) {
        ToolState.Running -> "running"
        ToolState.Done -> "done"
        ToolState.Failed -> "failed"
        ToolState.Stopped -> "stopped"
    }

    val payloadAvailable = activity.inlineDiff != null || activity.argsText != null ||
        activity.resultText != null || activity.detail.isNotBlank()
    var expanded by rememberSaveable(activity.id, activity.inlineDiff != null) {
        mutableStateOf(activity.inlineDiff != null)
    }
    val seconds = liveElapsedSeconds(activity.startedAtMillis, activity.elapsedSeconds, activity.state == ToolState.Running)
    val title = activity.displayTitle()

    activity.inlineDiff?.let { diff ->
        InlineDiffPanel(
            diff = diff,
            argsText = activity.argsText,
            expanded = expanded,
            onToggle = { expanded = !expanded },
            contentDescription = "Tool $title, $stateLabel",
        )
        return
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        DisclosureRow(
            title = title,
            meta = when {
                activity.state == ToolState.Running -> seconds.elapsedLabel()
                activity.state == ToolState.Done && seconds > 0.0 -> seconds.durationLabel()
                else -> null
            },
            icon = activity.icon(),
            expanded = expanded,
            state = activity.state,
            onToggle = { expanded = !expanded },
            contentDescription = "Tool $title, $stateLabel",
            enabled = payloadAvailable,
        )
        if (expanded) {
            ToolPayload(activity)
        }
    }
}

@Composable
private fun DisclosureRow(
    title: String,
    meta: String?,
    icon: HermesIcon?,
    expanded: Boolean,
    state: ToolState,
    onToggle: () -> Unit,
    contentDescription: String,
    enabled: Boolean = true,
) {
    val tokens = HermesTheme.tokens
    val iconColor = when (state) {
        ToolState.Running -> tokens.accent
        ToolState.Failed -> tokens.destructive
        ToolState.Stopped -> tokens.textQuaternary
        ToolState.Done -> tokens.scaffoldText
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onToggle)
            .semantics(mergeDescendants = true) {
                this.contentDescription = contentDescription
                if (enabled) stateDescription = if (expanded) "Expanded" else "Collapsed"
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon?.let {
            HermesIconGlyph(icon = it, color = iconColor, size = 13.sp)
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text = title,
            style = HermesTheme.type.scaffold,
            color = tokens.scaffoldText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        meta?.let {
            Text(
                text = it,
                style = HermesTheme.type.scaffoldMeta,
                color = tokens.scaffoldMeta,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        if (enabled) {
            Spacer(Modifier.width(8.dp))
            HermesIconGlyph(
                icon = if (expanded) HermesIcon.ChevronDown else HermesIcon.ChevronRight,
                color = tokens.scaffoldMeta,
                size = 12.sp,
            )
        }
    }
}

@Composable
private fun ToolPayload(activity: ToolActivity) {
    val tokens = HermesTheme.tokens
    val result = activity.terminalOutput() ?: activity.resultText
    val sections = buildList {
        activity.argsText?.let { add("Arguments" to it) }
        result?.takeIf { it != activity.argsText }?.let { add("Result" to it) }
        activity.detail.takeIf { detail ->
            detail.isNotBlank() && detail != result && detail != activity.argsText
        }?.let { add("Details" to it) }
    }
    if (sections.isEmpty()) return

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(tokens.widgetSurface, RoundedCornerShape(10.dp))
            .border(1.dp, tokens.strokeTertiary, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        sections.forEach { (label, value) ->
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(label, style = HermesTheme.type.sectionLabel, color = tokens.textQuaternary)
                Text(
                    text = value,
                    style = HermesTheme.type.code,
                    color = tokens.textSecondary,
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                )
            }
        }
    }
}

@Composable
private fun InlineDiffPanel(
    diff: String,
    argsText: String?,
    expanded: Boolean,
    onToggle: () -> Unit,
    contentDescription: String,
) {
    val tokens = HermesTheme.tokens
    val lines = remember(diff) { diff.lines() }
    val added = remember(lines) { lines.count { it.startsWith("+") && !it.startsWith("+++") } }
    val removed = remember(lines) { lines.count { it.startsWith("-") && !it.startsWith("---") } }
    val path = remember(diff, argsText) { diff.filePath() ?: argsText.jsonStringField("path") ?: "Patched file" }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(tokens.widgetSurface, RoundedCornerShape(8.dp))
            .border(1.dp, tokens.strokeSecondary, RoundedCornerShape(8.dp)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(role = Role.Button, onClick = onToggle)
                .semantics(mergeDescendants = true) {
                    this.contentDescription = contentDescription
                    stateDescription = if (expanded) "Expanded" else "Collapsed"
                }
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HermesIconGlyph(HermesIcon.Edit, color = tokens.scaffoldText, size = 13.sp)
            Spacer(Modifier.width(7.dp))
            Text(
                text = path,
                style = HermesTheme.type.scaffold,
                color = tokens.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "+$added  −$removed",
                style = HermesTheme.type.scaffoldMeta,
                color = tokens.scaffoldMeta,
            )
            Spacer(Modifier.width(8.dp))
            HermesIconGlyph(
                icon = if (expanded) HermesIcon.ChevronDown else HermesIcon.ChevronRight,
                color = tokens.scaffoldMeta,
                size = 12.sp,
            )
        }
        if (expanded) {
            Column(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                lines.filterNot { it.startsWith("--- ") || it.startsWith("+++ ") }.forEach { line ->
                    val background = when {
                        line.startsWith("+") -> tokens.statusUnread.copy(alpha = 0.10f)
                        line.startsWith("-") -> tokens.destructive.copy(alpha = 0.10f)
                        else -> tokens.chatSurface.copy(alpha = 0f)
                    }
                    val foreground = when {
                        line.startsWith("+") -> tokens.statusUnread
                        line.startsWith("-") -> tokens.destructive
                        else -> tokens.textSecondary
                    }
                    Text(
                        text = line.ifEmpty { " " },
                        style = HermesTheme.type.code,
                        color = foreground,
                        modifier = Modifier
                            .background(background)
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 1.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun TurnProgressRow(startedAtMillis: Long?, progress: SessionProgress?) {
    val tokens = HermesTheme.tokens
    val seconds = liveElapsedSeconds(startedAtMillis, 0.0, running = true)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .semantics {
                contentDescription = progress?.text ?: "Hermes is working"
                liveRegion = LiveRegionMode.Polite
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.width(18.dp))
        DitherMark(tokens.accent)
        Spacer(Modifier.width(10.dp))
        progress?.let {
            Text(
                text = it.text,
                style = HermesTheme.type.scaffold,
                color = tokens.scaffoldText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        } ?: Spacer(Modifier.weight(1f))
        Text(seconds.elapsedLabel(), style = HermesTheme.type.scaffoldMeta, color = tokens.scaffoldMeta)
    }
}

@Composable
private fun liveElapsedSeconds(startedAtMillis: Long?, fallback: Double, running: Boolean): Double {
    if (!running || startedAtMillis == null) return fallback
    val elapsed by produceState(initialValue = fallback, startedAtMillis) {
        while (true) {
            value = maxOf(fallback, (System.currentTimeMillis() - startedAtMillis).coerceAtLeast(0) / 1_000.0)
            delay(250)
        }
    }
    return elapsed
}

private fun ToolActivity.icon(): HermesIcon {
    val normalized = toolName.lowercase()
    return when {
        inlineDiff != null || normalized.contains("patch") || normalized.contains("edit") || normalized.contains("write_file") -> HermesIcon.Edit
        normalized.contains("terminal") || normalized.contains("command") || normalized.contains("shell") || normalized.contains("exec") -> HermesIcon.Terminal
        normalized.contains("search") || normalized.contains("web") || normalized.contains("browser") -> HermesIcon.Search
        normalized.contains("read") || normalized.contains("file") -> HermesIcon.File
        else -> HermesIcon.SymbolMethod
    }
}

private fun ToolActivity.displayTitle(): String {
    val normalized = toolName.lowercase()
    if (inlineDiff != null || normalized.contains("patch")) return "Patched file"
    val command = argsText.jsonStringField("command") ?: detail.takeIf { normalized.contains("terminal") }
    if (command != null && normalized.contains("terminal")) {
        val commands = command.lineSequence().map(String::trim).filter(String::isNotBlank).toList()
        val first = commands.firstOrNull()?.removePrefix("$ ")?.take(72).orEmpty()
        val additional = commands.size - 1
        val suffix = when {
            additional == 1 -> " + 1 command"
            additional > 1 -> " + $additional commands"
            else -> ""
        }
        return "${if (state == ToolState.Running) "Running" else "Ran"} $first$suffix".trim()
    }
    val path = argsText.jsonStringField("path")
    return when {
        normalized.contains("read_file") && path != null -> "Read $path"
        normalized.contains("write_file") && path != null -> "Wrote $path"
        normalized.contains("search") -> "Searched ${detail.take(72)}".trim()
        else -> label.replace('_', ' ').replaceFirstChar { it.titlecase() }
    }
}

private fun ToolActivity.terminalOutput(): String? {
    if (icon() != HermesIcon.Terminal) return null
    val root = resultText?.let { text -> runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() }
        ?: return resultText
    val output = root["output"]?.jsonPrimitive?.contentOrNull.orEmpty()
    val exitCode = root["exit_code"]?.jsonPrimitive?.intOrNull
    return buildString {
        append(output)
        if (exitCode != null && exitCode != 0) {
            if (isNotEmpty() && !endsWith('\n')) append('\n')
            append("Exit code: $exitCode")
        }
    }.takeIf(String::isNotBlank)
}

private fun String.filePath(): String? = lineSequence()
    .firstOrNull { it.startsWith("+++ ") }
    ?.removePrefix("+++ ")
    ?.removePrefix("b/")
    ?.takeIf { it != "/dev/null" }

private fun String?.jsonStringField(name: String): String? {
    val text = this ?: return null
    return runCatching {
        Json.parseToJsonElement(text).jsonObject[name]?.jsonPrimitive?.contentOrNull
    }.getOrNull()?.takeIf(String::isNotBlank)
}

/** Match Desktop's whole-second timer and m:ss format. */
private fun Double.elapsedLabel(): String {
    val whole = coerceAtLeast(0.0).toLong()
    return if (whole < 60L) {
        "${whole}s"
    } else {
        "${whole / 60}:${(whole % 60).toString().padStart(2, '0')}"
    }
}

private fun Double.durationLabel(): String =
    if (this in 0.0..<1.0) "${(this * 1_000).roundToInt()}ms" else elapsedLabel()

@Composable
private fun MarkdownBlockView(block: MarkdownBlock) {
    val tokens = HermesTheme.tokens
    when (block) {
        is MarkdownBlock.Paragraph ->
            Text(block.spans.annotated(), style = HermesTheme.type.body, color = tokens.textPrimary)

        is MarkdownBlock.Heading ->
            Text(
                block.spans.annotated(),
                style = if (block.level <= 2) HermesTheme.type.screenTitle else HermesTheme.type.bodyStrong,
                color = tokens.textPrimary,
            )

        is MarkdownBlock.Bullets ->
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                for (item in block.items) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("•", style = HermesTheme.type.body, color = tokens.textTertiary)
                        Text(item.annotated(), style = HermesTheme.type.body, color = tokens.textPrimary)
                    }
                }
            }

        is MarkdownBlock.Numbered ->
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                block.items.forEachIndexed { index, item ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("${index + 1}.", style = HermesTheme.type.body, color = tokens.textTertiary)
                        Text(item.annotated(), style = HermesTheme.type.body, color = tokens.textPrimary)
                    }
                }
            }

        is MarkdownBlock.Table -> TableView(block)

        is MarkdownBlock.CodeFence -> CodeFenceView(block)
    }
}

/** Tag on [TableView]'s scroll container so journeys can find it. */
internal const val MarkdownTableScrollerTag = "markdown_table_scroller"

/**
 * A pipe table rendered as an actual grid.
 *
 * Desktop lets a wide table overflow its message with an inner scroller
 * (`apps/desktop/src/styles.css`, `.md table` @ `f82f2dba`); a phone has less
 * width still, so the same contract applies as code fences: the block owns its
 * horizontal scroll and the page body never moves sideways. Columns size to
 * their content and shrink toward their widest unbreakable run under a tight
 * viewport ([TableSizing]); cells wrap within their column, so long inline
 * code breaks instead of clipping.
 */
@Composable
private fun TableView(block: MarkdownBlock.Table) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .background(HermesTheme.tokens.widgetSurface, shape)
            .border(1.dp, HermesTheme.tokens.strokeTertiary, shape)
            .clipToBounds(),
    ) {
        Box(
            Modifier
                .testTag(MarkdownTableScrollerTag)
                .horizontalScroll(rememberScrollState()),
        ) {
            TableGrid(block)
        }
    }
}

/** Hairline anchors recorded by [TableGrid]'s measure pass for its draw pass. */
private class TableGridGeometry(
    val columnStarts: List<Float>,
    val rowStarts: List<Float>,
    val width: Float,
    val height: Float,
)

/** One measured cell and where its row/column boundary puts it. */
private class TableCellPlacement(
    val placeable: Placeable,
    val x: Float,
    val y: Float,
)

@Composable
private fun TableGrid(block: MarkdownBlock.Table) {
    val tokens = HermesTheme.tokens
    val interiorRule = tokens.strokeQuaternary
    val headerRule = tokens.strokeSecondary
    // Written during measure, read by drawBehind on the following frame.
    var geometry by remember(block) { mutableStateOf<TableGridGeometry?>(null) }

    SubcomposeLayout(
        Modifier.drawBehind {
            val grid = geometry ?: return@drawBehind
            for (x in grid.columnStarts.drop(1)) {
                drawLine(interiorRule, Offset(x, 0f), Offset(x, grid.height), strokeWidth = 1.dp.toPx())
            }
            grid.rowStarts.getOrNull(1)?.let { y ->
                drawLine(headerRule, Offset(0f, y), Offset(grid.width, y), strokeWidth = 1.dp.toPx())
            }
            for (y in grid.rowStarts.drop(2)) {
                drawLine(interiorRule, Offset(0f, y), Offset(grid.width, y), strokeWidth = 1.dp.toPx())
            }
        },
    ) { constraints ->
        val columns = block.columnCount.coerceAtLeast(1)
        val rowCount = block.rows.size + 1

        fun cellSpans(row: Int, column: Int): List<InlineSpan> =
            if (row == 0) {
                block.header.getOrNull(column)?.spans.orEmpty()
            } else {
                block.rows.getOrNull(row - 1)?.getOrNull(column)?.spans.orEmpty()
            }

        val cells = Array(rowCount) { row ->
            Array(columns) { column ->
                subcompose(row * columns + column) { MarkdownCell(cellSpans(row, column), row == 0) }
            }
        }

        // Column sizing from intrinsics: a Measurable may be measured exactly
        // once, so the probe pass reads intrinsic widths instead of measuring.
        val targets = IntArray(columns) { column ->
            (0 until rowCount).maxOf { row -> cells[row][column].first().maxIntrinsicWidth(0) }
        }
        val floors = IntArray(columns) { column ->
            (0 until rowCount).maxOf { row -> cells[row][column].first().minIntrinsicWidth(0) }
        }
        val widths = TableSizing.resolve(
            targets,
            floors,
            if (constraints.hasBoundedWidth) constraints.maxWidth else null,
        )

        // Pass two: wrap onto the shared boundaries and place once.
        val columnStarts = FloatArray(columns)
        var gridWidth = 0f
        for (column in 0 until columns) {
            columnStarts[column] = gridWidth
            gridWidth += widths[column]
        }
        val rowStarts = FloatArray(rowCount)
        var gridHeight = 0f
        val placed = mutableListOf<TableCellPlacement>()
        for (row in 0 until rowCount) {
            rowStarts[row] = gridHeight
            var rowHeight = 0
            val rowPlacements = ArrayList<TableCellPlacement>(columns)
            for (column in 0 until columns) {
                val placeable = cells[row][column].first().measure(
                    Constraints(minWidth = widths[column], maxWidth = widths[column]),
                )
                rowPlacements += TableCellPlacement(placeable, columnStarts[column], gridHeight)
                if (placeable.height > rowHeight) rowHeight = placeable.height
            }
            placed += rowPlacements
            gridHeight += rowHeight
        }

        geometry = TableGridGeometry(
            columnStarts = columnStarts.toList(),
            rowStarts = rowStarts.toList(),
            width = gridWidth,
            height = gridHeight,
        )
        layout(gridWidth.toInt().coerceAtLeast(constraints.minWidth), gridHeight.toInt()) {
            for (cell in placed) cell.placeable.place(cell.x.roundToInt(), cell.y.roundToInt())
        }
    }
}

@Composable
private fun MarkdownCell(spans: List<InlineSpan>, header: Boolean) {
    Text(
        text = spans.ifEmpty { listOf(InlineSpan.Plain(" ")) }.annotated(),
        style = if (header) HermesTheme.type.bodyStrong else HermesTheme.type.body,
        color = HermesTheme.tokens.textPrimary,
        softWrap = true,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

/**
 * A fence keeps its own horizontal scroll: wrapping code is worse than
 * scrolling it, and the page body must never scroll sideways.
 */
@Composable
private fun CodeFenceView(block: MarkdownBlock.CodeFence) {
    val tokens = HermesTheme.tokens
    Column(
        Modifier
            .fillMaxWidth()
            .background(tokens.widgetSurface, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        block.language?.let {
            Text(it, style = HermesTheme.type.sectionLabel, color = tokens.textTertiary)
        }
        Text(
            text = block.code.trimEnd('\n').ifEmpty { " " },
            style = HermesTheme.type.code,
            color = tokens.textSecondary,
            modifier = Modifier.horizontalScroll(rememberScrollState()),
        )
    }
}

/**
 * Inline spans as one annotated string, so text still selects and wraps.
 *
 * Known limitation: a *single token* with no break opportunity that is wider
 * than the remaining line (a 60-character identifier in running prose) still
 * pushes past the margin — Compose's [LineBreak] has no `anywhere` word-break
 * policy on this BOM, and inserting zero-width spaces would corrupt copied
 * identifiers. Tables and fences contain their own overflow, which is where
 * this actually bites; revisit if upstream ships `WordBreak.Anywhere`.
 */
@Composable
private fun List<InlineSpan>.annotated(): AnnotatedString {
    val tokens = HermesTheme.tokens
    val mono = HermesTheme.type.code.fontFamily
    return buildAnnotatedString {
        for (span in this@annotated) {
            when (span) {
                is InlineSpan.Plain -> append(span.text)
                is InlineSpan.Strong -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(span.text) }
                is InlineSpan.Emphasis -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(span.text) }
                is InlineSpan.Code -> withStyle(
                    SpanStyle(
                        fontFamily = mono,
                        background = tokens.inlineCodeBackground,
                        color = tokens.inlineCodeForeground,
                    ),
                ) { append(span.text) }
            }
        }
    }
}
