@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.hermesagent.mobile.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hermesagent.mobile.data.session.ContextBreakdown
import com.hermesagent.mobile.data.session.ContextMeterState
import com.hermesagent.mobile.data.session.ContextUsageCategory
import com.hermesagent.mobile.data.session.SessionUsage
import com.hermesagent.mobile.ui.theme.HermesTheme

internal const val CONTEXT_METER_TAG = "context-meter"
internal const val CONTEXT_USAGE_SHEET_TAG = "context-usage-sheet"
internal const val CONTEXT_USAGE_TITLE_TAG = "context-usage-title"
internal const val CONTEXT_USAGE_BAR_TAG = "context-usage-bar"

/** The legend swatch for one category, so a test can read the ink it paints. */
internal fun contextUsageSwatchTag(id: String): String = "context-usage-swatch-$id"

/** The bar segment for one category, tagged for the same reason. */
internal fun contextUsageSegmentTag(id: String): String = "context-usage-segment-$id"

/**
 * Top-bar Context Meter component in the ChatTopBar.
 *
 * Pinned to upstream `apps/desktop/src/lib/statusbar.tsx:44-60` @
 * `3ca096de5f8183cb2e0ec23673f294d5978656a3`.
 */
@Composable
fun ContextMeter(
    label: String,
    detail: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = HermesTheme.tokens
    // `clickable` already merges this row's children into one semantics node, so
    // a blanket `contentDescription` here would *replace* the figures a sighted
    // user reads with the bare name. The name rides on `onClickLabel` instead,
    // which is what Desktop's own accessible name for the item is
    // (`en.ts:2963` @ `3ca096de5f8183cb2e0ec23673f294d5978656a3`), and the
    // merged children keep speaking `30k/200k` and `[████░░░░░░] 40%`.
    Row(
        modifier = modifier
            .testTag(CONTEXT_METER_TAG)
            .clickable(
                role = Role.Button,
                onClick = onClick,
                onClickLabel = ContextUsageCopy.CONTEXT_USAGE,
            )
            .padding(horizontal = 4.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = HermesTheme.type.caption,
            color = tokens.textSecondary,
            maxLines = 1,
        )
        if (detail.isNotEmpty()) {
            Text(
                text = detail,
                style = HermesTheme.type.caption,
                color = tokens.textTertiary,
                maxLines = 1,
            )
        }
    }
}

/**
 * Context Usage popover analog from Hermes Desktop
 * (`apps/desktop/src/app/shell/context-usage-panel.tsx` @
 * `3ca096de5f8183cb2e0ec23673f294d5978656a3`), as a bottom sheet.
 */
@Composable
fun ContextUsageSheet(
    meterState: ContextMeterState?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = HermesTheme.tokens
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = tokens.cardSurface,
        contentColor = tokens.textPrimary,
        scrimColor = tokens.textPrimary.copy(alpha = 0.32f),
        modifier = modifier.testTag(CONTEXT_USAGE_SHEET_TAG),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = HermesTheme.spacing.pageInset, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ContextUsagePanelContent(
                usage = meterState?.usage ?: SessionUsage(),
                breakdown = meterState?.breakdown,
                loading = meterState?.loading == true,
            )
        }
    }
}

@Composable
fun ContextUsagePanelContent(
    usage: SessionUsage,
    breakdown: ContextBreakdown?,
    loading: Boolean,
    modifier: Modifier = Modifier,
) {
    val tokens = HermesTheme.tokens
    val contextMax = usage.contextMax ?: 0L
    val contextUsed = usage.contextUsed ?: 0L
    val contextPercent = (usage.contextPercent ?: 0).coerceIn(0, 100)

    val categories = remember(breakdown?.categories) {
        (breakdown?.categories.orEmpty()).map { category ->
            category.copy(
                label = ContextUsageCopy.categoryLabel(category.id, category.label),
            )
        }
    }

    val segmentTotal = categories.sumOf { it.tokens }.takeIf { it > 0 }
        ?: contextUsed.takeIf { it > 0 }
        ?: 1L

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Header row: Title + tokenSummary
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(
                text = ContextUsageCopy.TITLE,
                style = HermesTheme.type.screenTitle,
                color = tokens.textPrimary,
                modifier = Modifier.testTag(CONTEXT_USAGE_TITLE_TAG),
            )
            Text(
                text = ContextUsageCopy.tokenSummary(
                    "~${compactNumber(contextUsed)}",
                    compactNumber(contextMax),
                ),
                style = HermesTheme.type.caption,
                color = tokens.textSecondary,
            )
        }

        // Percent Full
        Text(
            text = ContextUsageCopy.percentFull(contextPercent),
            style = HermesTheme.type.caption,
            color = tokens.textPrimary,
        )

        // Segmented Bar (6dp height, rounded 3dp)
        ContextUsageBar(
            categories = categories,
            segmentTotal = segmentTotal,
        )

        // Legend list
        if (categories.isNotEmpty()) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                categories.forEach { category ->
                    ContextUsageCategoryRow(category = category)
                }
            }
        }

        // Loading or Empty state
        if (loading && categories.isEmpty()) {
            Text(
                text = ContextUsageCopy.LOADING,
                style = HermesTheme.type.caption,
                color = tokens.textTertiary,
            )
        } else if (!loading && categories.isEmpty()) {
            Text(
                text = ContextUsageCopy.EMPTY,
                style = HermesTheme.type.caption,
                color = tokens.textTertiary,
            )
        }
    }
}

@Composable
private fun ContextUsageBar(
    categories: List<ContextUsageCategory>,
    segmentTotal: Long,
    modifier: Modifier = Modifier,
) {
    val tokens = HermesTheme.tokens
    val barShape = RoundedCornerShape(3.dp)
    val barBackground = if (categories.isNotEmpty()) tokens.strokeTertiary else tokens.widgetSurface

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(barShape)
            .background(barBackground)
            .testTag(CONTEXT_USAGE_BAR_TAG),
    ) {
        categories.forEach { category ->
            // Desktop gives every category `min-w-px` so a zero-token one still
            // shows a 1px sliver (`context-usage-panel.tsx:89` @
            // `3ca096de5f8183cb2e0ec23673f294d5978656a3`). `Modifier.weight`
            // has no such floor, and the producer already filters
            // `if tokens > 0` (`agent/context_breakdown.py:161`), so this is
            // unreachable at the pin and ledgered in `docs/parity/context-usage.md`.
            if (category.tokens > 0) {
                val weight = (category.tokens.toFloat() / segmentTotal.toFloat()).coerceAtLeast(0.0001f)
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .weight(weight)
                        .background(resolveCategoryColor(category.color, tokens))
                        .testTag(contextUsageSegmentTag(category.id)),
                )
            }
        }
    }
}

@Composable
private fun ContextUsageCategoryRow(
    category: ContextUsageCategory,
    modifier: Modifier = Modifier,
) {
    val tokens = HermesTheme.tokens
    val swatchColor = resolveCategoryColor(category.color, tokens)

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f).padding(end = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(swatchColor, RoundedCornerShape(2.dp))
                    .testTag(contextUsageSwatchTag(category.id)),
            )
            Text(
                text = category.label,
                style = HermesTheme.type.caption,
                color = tokens.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = compactNumber(category.tokens),
            style = HermesTheme.type.caption,
            color = tokens.textPrimary,
        )
    }
}
