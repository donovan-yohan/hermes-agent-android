package com.hermesagent.mobile.ui.chat.composer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hermesagent.mobile.data.composer.CompletionItem
import com.hermesagent.mobile.data.composer.CompletionTrigger
import com.hermesagent.mobile.ui.theme.HermesTheme

/**
 * Plain-text completion surface. The ViewModel owns remote query lifetime;
 * this only renders its fenced result and offers local emoji entries.
 */
@Composable
internal fun CompletionPopup(
    trigger: CompletionTrigger?,
    query: String,
    items: List<CompletionItem>,
    isLoading: Boolean,
    error: String?,
    selectedIndex: Int,
    onSelect: (CompletionItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (trigger == null) return
    val tokens = HermesTheme.tokens
    // The ViewModel owns `/` and `@` catalog/path/session sources (including
    // root starters). Emoji is deliberately local/offline, so it is the one
    // trigger with a Compose-owned source.
    val visible = visibleCompletionItems(trigger, query, items)
    LazyColumn(
        modifier
            .fillMaxWidth()
            .heightIn(max = 240.dp)
            .background(tokens.cardSurface, RoundedCornerShape(10.dp))
            .border(1.dp, tokens.strokeSecondary, RoundedCornerShape(10.dp))
            .testTag("Composer completion popup")
            .semantics {
                contentDescription = "${trigger.label()} completions"
                liveRegion = LiveRegionMode.Polite
            }
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        when {
            isLoading && visible.isEmpty() -> item { CompletionStatus("Loading ${trigger.label()} completions…") }
            error != null && visible.isEmpty() -> item { CompletionStatus(error) }
            visible.isEmpty() -> item { CompletionStatus("No ${trigger.label()} matches") }
            else -> itemsIndexed(
                visible,
                key = { index, item -> "${item.kind}:${item.text}:$index" },
            ) { index, item ->
                CompletionRow(item, isSelected = index == selectedIndex, onSelect = onSelect)
            }
        }
        if (error != null && visible.isNotEmpty()) item { CompletionStatus(error) }
    }
}

@Composable
private fun CompletionRow(
    item: CompletionItem,
    isSelected: Boolean,
    onSelect: (CompletionItem) -> Unit,
) {
    val tokens = HermesTheme.tokens
    val label = item.display.ifBlank { item.text }
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(min = HermesTheme.spacing.touchTarget)
            .clickable(role = Role.Button) { onSelect(item) }
            .semantics {
                contentDescription = listOf(label, item.detail).filter(String::isNotBlank).joinToString(". ")
                selected = isSelected
            }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        Text(label, style = HermesTheme.type.body, color = tokens.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        item.detail.takeIf(String::isNotBlank)?.let {
            Text(it, style = HermesTheme.type.scaffoldMeta, color = tokens.scaffoldMeta, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

internal fun visibleCompletionItems(
    trigger: CompletionTrigger?,
    query: String,
    items: List<CompletionItem>,
): List<CompletionItem> {
    val local = if (trigger == CompletionTrigger.Emoji) EmojiIndex.search(query) else emptyList()
    return (local + items).distinctBy { it.text }.take(8)
}

@Composable
private fun CompletionStatus(text: String) {
    Text(
        text,
        style = HermesTheme.type.caption,
        color = HermesTheme.tokens.textTertiary,
        modifier = Modifier.fillMaxWidth().heightIn(min = HermesTheme.spacing.touchTarget).padding(horizontal = 10.dp, vertical = 12.dp),
    )
}

private fun CompletionTrigger.label(): String = when (this) {
    CompletionTrigger.Slash -> "command"
    CompletionTrigger.At -> "reference"
    CompletionTrigger.Emoji -> "emoji"
}
