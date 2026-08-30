@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.hermesagent.mobile.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
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

/**
 * How much room a pill takes around its word.
 *
 * [Compact] is for a pill that shares a line with the control it qualifies
 * rather than sitting in a row of its own, where Desktop's padding is what
 * pushes the controls beside it onto a second line.
 */
enum class PillDensity { Default, Compact }

/** Small status/metadata tag. App radius, not a full pill (`components/ui/badge.tsx:7-21`). */
@Composable
fun Pill(
    text: String,
    modifier: Modifier = Modifier,
    tone: PillTone = PillTone.Muted,
    density: PillDensity = PillDensity.Default,
) {
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
            .padding(
                horizontal = if (density == PillDensity.Compact) 4.dp else 6.dp,
                vertical = if (density == PillDensity.Compact) 1.dp else 2.dp,
            ),
    )
}

/**
 * The marker on a control this app has not built yet — the owner's word, short
 * because it shares a row with the control it qualifies.
 *
 * Not Desktop vocabulary, since Desktop is not missing anything, which is why
 * it lives with the primitive rather than in a surface's copy object. The
 * parity ledger's taxonomy still calls this class "coming soon"; this constant
 * is only what is drawn.
 */
const val WIP_PILL: String = "WIP"

/**
 * What a screen reader says where [WIP_PILL] is what an eye sees.
 *
 * The chip is an initialism because a phone row has no space for the phrase;
 * TalkBack has no such constraint and would spell it out letter by letter, so
 * the spoken form is the words. Same fact, two renderings — which is the whole
 * reason this is a second constant rather than one string used for both.
 */
const val WIP_SPOKEN: String = "Work in progress."

/**
 * [WIP_PILL] as the chip it is, at the density a shared row can afford.
 *
 * Visual only, and deliberately: every control this sits inside is `clickable`
 * or `selectable`, which merges its descendants, and a `contentDescription`
 * here is hoisted into that merge where it *replaces* the host's own text
 * rather than joining it. A Hermes Cloud card carrying a speaking pill
 * announced "Work in progress." and nothing else — it lost its name. So the
 * chip stays silent and each host says the whole phrase itself, ending in
 * [WIP_SPOKEN]; see [ComingSoonAction], [com.hermesagent.mobile.ui.common.ModeCard]
 * and [com.hermesagent.mobile.ui.common.ChoiceButton].
 */
@Composable
fun WipPill(modifier: Modifier = Modifier) {
    Pill(
        WIP_PILL,
        // The tag is the only thing left, so a test can still find the chip it
        // can no longer read. Clearing the text is the point: a host that
        // forgets its `status` should render a silent chip rather than quietly
        // start spelling "W-I-P" into the merge.
        modifier.clearAndSetSemantics { testTag = WIP_PILL },
        density = PillDensity.Compact,
    )
}

/**
 * A control a ported surface is expected to have, rendered disabled and marked.
 *
 * The pill is what stops a dimmed control from reading as one that is merely
 * unavailable right now: there is no state in which these light up, and a
 * person is entitled to know that before they go hunting for the condition
 * that would enable it. Omitting the control instead would say the surface was
 * never meant to have it, which is a different and less honest claim.
 *
 * One spoken node, so a screen reader announces the action and its status
 * together rather than reading a label and leaving the pill to follow: this
 * row owns the whole phrase, ending in [WIP_SPOKEN], and the [WipPill] beside
 * the label draws [WIP_PILL] without saying anything of its own.
 */
@Composable
fun ComingSoonAction(label: String, modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        modifier = modifier.semantics(mergeDescendants = true) {
            contentDescription = "$label. $WIP_SPOKEN"
            disabled()
        },
    ) {
        TextButton(label = label, onClick = {}, enabled = false)
        WipPill()
    }
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
                .imePadding()
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
