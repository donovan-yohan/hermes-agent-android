package com.hermesagent.mobile.ui.appearance

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.hermesagent.mobile.ui.AppearanceActions
import com.hermesagent.mobile.ui.common.Hairline
import com.hermesagent.mobile.ui.common.SectionLabel
import com.hermesagent.mobile.ui.common.SegmentedControl
import com.hermesagent.mobile.ui.theme.AppearanceSelection
import com.hermesagent.mobile.ui.theme.BuiltinThemes
import com.hermesagent.mobile.ui.theme.HermesTheme
import com.hermesagent.mobile.ui.theme.HermesThemeMode
import com.hermesagent.mobile.ui.theme.HermesThemePreset
import com.hermesagent.mobile.ui.theme.HermesTokens
import com.hermesagent.mobile.ui.theme.paletteFor

/**
 * Appearance.
 *
 * The list is [BuiltinThemes.ALL], rendered generically — there is no switch on
 * a theme name anywhere in this file. Each row previews its own palette in its
 * own colours (background, accent, user bubble, hairline), which is how a
 * broken preset shows up before it ships.
 */
@Composable
fun AppearanceScreen(
    selection: AppearanceSelection,
    actions: AppearanceActions,
    modifier: Modifier = Modifier,
) {
    val tokens = HermesTheme.tokens

    Column(modifier.fillMaxSize().background(tokens.chatSurface)) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            item {
                Text(
                    text = "Skins are ported from Hermes Desktop. Mode chooses the light or dark " +
                        "half of the same skin — it never swaps the skin.",
                    style = HermesTheme.type.caption,
                    color = tokens.textTertiary,
                    modifier = Modifier.padding(
                        horizontal = HermesTheme.spacing.pageInset,
                        vertical = 12.dp,
                    ),
                )
            }

            item {
                Column(
                    Modifier.padding(horizontal = HermesTheme.spacing.pageInset),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SectionLabel("Mode")
                    SegmentedControl(
                        options = HermesThemeMode.entries,
                        selected = selection.mode,
                        label = { it.name },
                        describe = { "${it.name} mode" },
                        onSelect = actions.onSelectMode,
                    )
                }
            }

            item {
                SectionLabel(
                    "Skin",
                    Modifier.padding(
                        start = HermesTheme.spacing.pageInset,
                        top = 22.dp,
                        bottom = 6.dp,
                    ),
                )
            }

            items(items = BuiltinThemes.ALL, key = { it.name }) { preset ->
                ThemeRow(
                    preset = preset,
                    isSelected = preset.name == selection.themeName,
                    dark = HermesTheme.isDark,
                    onClick = { actions.onSelectTheme(preset.name) },
                )
            }
        }
    }
}

@Composable
private fun ThemeRow(
    preset: HermesThemePreset,
    isSelected: Boolean,
    dark: Boolean,
    onClick: () -> Unit,
) {
    val tokens = HermesTheme.tokens
    // Preview each preset in its OWN resolved tokens, not the active theme's.
    val previewTokens = HermesTokens.from(preset.paletteFor(dark), dark)

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = HermesTheme.spacing.touchTarget)
                .clickable(onClick = onClick)
                .padding(horizontal = HermesTheme.spacing.pageInset, vertical = 10.dp)
                .semantics {
                    this.selected = isSelected
                    contentDescription = "${preset.label} skin. ${preset.description}"
                },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PalettePreview(previewTokens)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(preset.label, style = HermesTheme.type.sessionTitle, color = tokens.textPrimary)
                Text(preset.description, style = HermesTheme.type.caption, color = tokens.textTertiary)
            }
            if (isSelected) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = tokens.accent,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        Hairline(Modifier.padding(start = HermesTheme.spacing.pageInset))
    }
}

/** A thumbnail of the skin: surface, accent, bubble, hairline. */
@Composable
private fun PalettePreview(preview: HermesTokens) {
    Box(
        Modifier
            .size(width = 52.dp, height = 34.dp)
            .background(preview.chatSurface, RoundedCornerShape(6.dp))
            .border(1.dp, preview.strokeTertiary, RoundedCornerShape(6.dp)),
    ) {
        Column(
            Modifier.padding(5.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Box(Modifier.width(26.dp).height(4.dp).background(preview.textPrimary, RoundedCornerShape(2.dp)))
            Box(Modifier.width(18.dp).height(4.dp).background(preview.scaffoldText, RoundedCornerShape(2.dp)))
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                Box(Modifier.size(7.dp).background(preview.accent, CircleShape))
                Box(Modifier.width(20.dp).height(7.dp).background(preview.userBubble, RoundedCornerShape(3.dp)))
            }
        }
    }
}

@Preview(name = "Appearance · nous light", widthDp = 412, heightDp = 892)
@Composable
private fun AppearancePreviewLight() {
    val selection = AppearanceSelection("nous", HermesThemeMode.Light)
    HermesTheme(selection) { AppearanceScreen(selection, AppearanceActions()) }
}

@Preview(name = "Appearance · ember dark", widthDp = 412, heightDp = 892)
@Composable
private fun AppearancePreviewEmber() {
    val selection = AppearanceSelection("ember", HermesThemeMode.Dark)
    HermesTheme(selection) { AppearanceScreen(selection, AppearanceActions()) }
}
