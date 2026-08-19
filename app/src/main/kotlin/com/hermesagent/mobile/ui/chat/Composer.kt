package com.hermesagent.mobile.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.hermesagent.mobile.ui.common.CenteredTextFieldContent
import com.hermesagent.mobile.ui.common.Hairline
import com.hermesagent.mobile.ui.theme.HermesTheme

/**
 * The composer.
 *
 * Multiline by default with `ImeAction.Default`, so Enter inserts a newline
 * and sending is an explicit tap. That is the opposite of Desktop, where Enter
 * submits — and it is deliberate: on a soft keyboard there is no modifier to
 * hold, so an Enter-to-send composer eats half-written messages.
 *
 * The send control becomes a stop control while a turn is running, in the same
 * position. One affordance, one place, so cancelling never means hunting.
 */
@Composable
fun Composer(
    draft: String,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    isStreaming: Boolean,
    canSend: Boolean,
    modifier: Modifier = Modifier,
    statusLine: String,
) {
    val tokens = HermesTheme.tokens

    Column(modifier.fillMaxWidth().background(tokens.chatSurface)) {
        Hairline()
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = HermesTheme.spacing.pageInset, vertical = 10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(
                    Modifier
                        .weight(1f)
                        .heightIn(min = HermesTheme.spacing.touchTarget)
                        .border(
                            width = 1.dp,
                            color = if (isStreaming) tokens.composerRing else tokens.strokeSecondary,
                            shape = RoundedCornerShape(16.dp),
                        )
                        .testTag("Composer field shell"),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    BasicTextField(
                        value = draft,
                        onValueChange = onDraftChange,
                        textStyle = HermesTheme.type.body.copy(color = tokens.textPrimary),
                        cursorBrush = SolidColor(tokens.composerRing),
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Sentences,
                            imeAction = ImeAction.Default,
                        ),
                        keyboardActions = KeyboardActions(),
                        maxLines = 6,
                        modifier = Modifier
                            .fillMaxWidth()
                            // The editable node owns the complete touch target.
                            .heightIn(min = HermesTheme.spacing.touchTarget)
                            .semantics { contentDescription = "Message Hermes" },
                        decorationBox = { innerTextField ->
                            CenteredTextFieldContent(
                                isEmpty = draft.isEmpty(),
                                contentTag = "Composer text content",
                                horizontalPadding = 14.dp,
                                placeholder = {
                                    Text(
                                        text = "Message Hermes",
                                        style = HermesTheme.type.body,
                                        color = tokens.textTertiary,
                                    )
                                },
                                innerTextField = innerTextField,
                            )
                        },
                    )
                }

                if (isStreaming) {
                    StopButton(onStop)
                } else {
                    SendButton(onSend, enabled = canSend)
                }
            }

            Text(
                text = statusLine,
                style = HermesTheme.type.scaffoldMeta,
                color = tokens.scaffoldMeta,
                modifier = Modifier.padding(
                    top = 4.dp,
                    start = 14.dp,
                ),
            )
        }
    }
}

@Composable
private fun SendButton(onClick: () -> Unit, enabled: Boolean) {
    val tokens = HermesTheme.tokens
    Box(
        modifier = Modifier
            .size(HermesTheme.spacing.touchTarget)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics(mergeDescendants = true) {
                contentDescription = "Send message"
                if (!enabled) disabled()
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(36.dp)
                .background(
                    if (enabled) tokens.accent else tokens.accent.copy(alpha = 0.25f),
                    RoundedCornerShape(10.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Send,
                contentDescription = null,
                tint = tokens.accentForeground,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** A square, the universal stop glyph. Cancels in the current frame. */
@Composable
private fun StopButton(onClick: () -> Unit) {
    val tokens = HermesTheme.tokens
    Box(
        modifier = Modifier
            .size(HermesTheme.spacing.touchTarget)
            .semantics { contentDescription = "Stop generating" }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(36.dp)
                .border(1.dp, tokens.composerRing, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(12.dp).background(tokens.composerRing, RoundedCornerShape(2.dp)))
        }
    }
}
