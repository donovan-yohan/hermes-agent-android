@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.hermesagent.mobile.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hermesagent.mobile.ui.theme.HermesTheme

/**
 * The settings grammar Desktop's `app/settings/primitives.tsx` defines
 * (`SectionHeading:31-52`, `ListRow:108-155`, and `Badge`-backed `Pill:27-29`
 * @ `f82fdba…` — pinned SHA `f82f2dbabd9e66b714f2b4f8a40447fe0c13e732`),
 * rendered for a phone.
 *
 * `ListRow` is a container query, not a viewport one: it puts the control
 * beside the label only above `@2xl`, and stacks below it. A phone is always
 * below it, so the stacked form here *is* Desktop's own narrow rendering
 * rather than a mobile invention.
 */

/** Desktop's `Pill` tones, minus the ones no Android surface uses yet. */
enum class PillTone { Muted, Primary }

/** Small status/metadata tag. App radius, not a full pill (`components/ui/badge.tsx:7-21`). */
@Composable
fun Pill(text: String, modifier: Modifier = Modifier, tone: PillTone = PillTone.Muted) {
    val tokens = HermesTheme.tokens
    Text(
        text = text,
        style = HermesTheme.type.scaffold.copy(fontWeight = FontWeight.Medium),
        color = when (tone) {
            PillTone.Muted -> tokens.textTertiary
            PillTone.Primary -> tokens.accent
        },
        modifier = modifier
            .background(
                color = when (tone) {
                    PillTone.Muted -> tokens.widgetSurface
                    PillTone.Primary -> tokens.accent.copy(alpha = 0.10f)
                },
                shape = RoundedCornerShape(3.dp),
            )
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/** Icon + title heading above a run of rows (`settings/primitives.tsx:31-52`). */
@Composable
fun SettingsSectionHeading(
    icon: HermesIcon,
    title: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(top = 8.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        HermesIconGlyph(icon, color = HermesTheme.tokens.textTertiary)
        Text(
            text = title,
            style = HermesTheme.type.bodyStrong,
            color = HermesTheme.tokens.textPrimary,
        )
    }
}

/**
 * One settings row: a title block, an optional caption under it, and an
 * optional control below both — Desktop's stacked `ListRow`.
 */
@Composable
fun SettingsListRow(
    modifier: Modifier = Modifier,
    description: String? = null,
    action: (@Composable () -> Unit)? = null,
    title: @Composable () -> Unit,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            title()
            if (description != null) {
                Text(
                    text = description,
                    style = HermesTheme.type.caption,
                    color = HermesTheme.tokens.textTertiary,
                )
            }
        }
        action?.invoke()
    }
}

/**
 * Desktop's `ConfirmDialog` as the phone's equivalent surface: a bottom sheet
 * whose destructive action is the one thing it offers, plus Cancel. Dismissing
 * it is Cancel, which is the same contract as Esc or the backdrop there.
 */
@Composable
fun ConfirmSheet(
    title: String,
    description: String,
    confirmLabel: String,
    cancelLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    testTag: String,
    destructive: Boolean = true,
) {
    val tokens = HermesTheme.tokens
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = tokens.cardSurface,
        contentColor = tokens.textPrimary,
        scrimColor = tokens.textPrimary.copy(alpha = .32f),
        modifier = Modifier.testTag(testTag),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = HermesTheme.spacing.pageInset, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, style = HermesTheme.type.screenTitle, color = tokens.textPrimary)
            Text(description, style = HermesTheme.type.body, color = tokens.textSecondary)
            PrimaryButton(
                label = confirmLabel,
                onClick = onConfirm,
                modifier = Modifier.fillMaxWidth(),
                container = if (destructive) tokens.destructive else tokens.accent,
            )
            TextButton(cancelLabel, onDismiss, color = tokens.textTertiary)
        }
    }
}
