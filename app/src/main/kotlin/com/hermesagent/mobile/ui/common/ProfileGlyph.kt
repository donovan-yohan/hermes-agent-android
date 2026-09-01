package com.hermesagent.mobile.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.hermesagent.mobile.data.profiles.HermesProfile
import com.hermesagent.mobile.data.profiles.profileInitial
import com.hermesagent.mobile.data.profiles.resolveProfileColorArgb
import com.hermesagent.mobile.ui.theme.HermesTheme
import com.hermesagent.mobile.ui.theme.mixPremultiplied

/**
 * A profile's mark, in one place.
 *
 * Port of `apps/desktop/src/components/ui/profile-glyph.tsx:10-43` @
 * `3ca096de5f8183cb2e0ec23673f294d5978656a3`: the default profile is the `home`
 * codicon — it has no colour of its own and an initial would read as just
 * another named profile — and every other profile is a soft tint of its colour
 * carrying its initial.
 *
 * Presentational. The identity colour is derived from the profile name, not
 * from the theme, which is exactly what makes it an identity; the tint it sits
 * on is Desktop's own `color-mix(in srgb, colour 22%, transparent)`.
 */
@Composable
fun ProfileGlyph(
    profile: HermesProfile,
    modifier: Modifier = Modifier,
    size: Dp = 16.dp,
    /**
     * The rail square's own active treatment: a 30% tint behind a colour ring
     * rather than the resting 22% (`profile-switcher.tsx:677,702`). The shared
     * glyph on a session row, a roster line or the picker has no such variant
     * upstream, so those call sites leave it alone.
     */
    active: Boolean = false,
    contentDescription: String? = null,
) {
    val tokens = HermesTheme.tokens
    val semantics = if (contentDescription == null) {
        Modifier.clearAndSetSemantics {}
    } else {
        Modifier.semantics { this.contentDescription = contentDescription }
    }
    // The `home` face belongs to the default profile alone
    // (`profile-glyph.tsx:21-27`). Any other profile carries its initial, even
    // when it resolves to no identity colour — that case tints against
    // `--ui-text-quaternary` rather than falling back to the default's face
    // (`profile-glyph.tsx:37`).
    if (profile.isDefault) {
        Box(modifier.then(semantics).size(size), contentAlignment = Alignment.Center) {
            HermesIconGlyph(
                icon = HermesIcon.Home,
                color = tokens.textQuaternary,
                // Through density, so a raised font scale cannot push the mark
                // out of a square that is fixed in dp.
                size = with(LocalDensity.current) { (size * 0.75f).toSp() },
            )
        }
        return
    }
    val argb = resolveProfileColorArgb(profile)
    val hue = argb?.let(::Color) ?: tokens.textQuaternary
    // `color: color ?? undefined` (`profile-glyph.tsx:37`,
    // `profile-switcher.tsx:704`): a profile with an identity colour writes its
    // initial in it, and a colourless one leaves the initial to inherit its
    // container's ink. `LocalContentColor` is that inheritance here.
    val ink = argb?.let(::Color) ?: LocalContentColor.current
    val fill = mixPremultiplied(hue, if (active) 30f else 22f, Color.Transparent)
    Box(
        modifier
            .then(semantics)
            .size(size)
            .background(fill, ProfileGlyphShape)
            .then(if (active) Modifier.border(1.5.dp, hue, ProfileGlyphShape) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        val initialSize = with(LocalDensity.current) { (size * 0.5f).toSp() }
        // Full-strength ink. Desktop dims the whole rail square, not its
        // initial (`profile-switcher.tsx:696-697`), and the shared glyph on a
        // row or a roster line is never dimmed at all.
        Text(
            text = profileInitial(profile.name),
            color = ink,
            fontSize = initialSize,
            lineHeight = initialSize,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/**
 * A session row's owning-profile chip.
 *
 * Port of `apps/desktop/src/app/chat/profile-tag.tsx:12-29`: the shared glyph,
 * labelled with the profile's canonical key. Identity, not status — the session
 * status dot keeps its own semantics.
 */
@Composable
fun ProfileTag(
    profile: HermesProfile,
    modifier: Modifier = Modifier,
    size: Dp = 16.dp,
) {
    ProfileGlyph(
        profile = profile,
        modifier = modifier,
        size = size,
        contentDescription = ownedByProfileLabel(profile.key),
    )
}

/**
 * `t.sidebar.row.ownedByProfile` (`apps/desktop/src/i18n/en.ts:2193`), asserted
 * verbatim by Desktop's own `profile-tag.test.tsx:27,34,47`. Desktop labels the
 * chip with the canonical key rather than the display name, so this one does
 * too — the caller passes [HermesProfile.key].
 */
fun ownedByProfileLabel(profileKey: String): String = "Profile: $profileKey"

private val ProfileGlyphShape = RoundedCornerShape(3.dp)
