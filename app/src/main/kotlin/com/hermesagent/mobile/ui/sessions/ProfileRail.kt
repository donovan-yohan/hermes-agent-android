package com.hermesagent.mobile.ui.sessions

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.tooling.preview.Preview
import com.hermesagent.mobile.ui.theme.AppearanceSelection
import com.hermesagent.mobile.ui.theme.HermesThemeMode
import com.hermesagent.mobile.data.profiles.DEFAULT_PROFILE
import com.hermesagent.mobile.data.profiles.HermesProfile
import com.hermesagent.mobile.data.profiles.ProfileScope
import com.hermesagent.mobile.data.profiles.normalizeProfileKey
import com.hermesagent.mobile.ui.common.Hairline
import com.hermesagent.mobile.ui.common.HermesIcon
import com.hermesagent.mobile.ui.common.HermesIconGlyph
import com.hermesagent.mobile.ui.common.ProfileGlyph
import com.hermesagent.mobile.ui.common.SectionLabel
import com.hermesagent.mobile.ui.theme.HermesTheme

/**
 * What the rail renders. UI-only: the roster is a cache of Gateway truth and
 * the scope is a saved view preference.
 */
data class ProfileRailState(
    val profiles: List<HermesProfile> = emptyList(),
    val scope: ProfileScope = ProfileScope(),
    /** True once one `profiles.list` has answered on this connection. */
    val loaded: Boolean = false,
) {
    val defaultProfile: HermesProfile? = profiles.firstOrNull(HermesProfile::isDefault)

    /** Unordered names alphabetise (`apps/desktop/src/store/profile.ts:92-106`). */
    val named: List<HermesProfile> =
        profiles.filterNot(HermesProfile::isDefault).sortedBy { it.name.lowercase() }

    /** Desktop hides the default↔all toggle and the squares until a second profile exists. */
    val multiProfile: Boolean get() = profiles.size > 1

    val activeKey: String get() = normalizeProfileKey(scope.activeProfile)

    /** True while the scope is the default profile and not the unified view. */
    val onDefault: Boolean get() = !scope.isAll && activeKey == normalizeProfileKey(defaultProfile?.name)

    /**
     * Nothing to switch between, and nothing to manage, until a Gateway has
     * answered. Once it has, the rail stays — an empty roster still needs its
     * one route to "Manage profiles…".
     */
    val visible: Boolean get() = loaded

    /**
     * The roster row a session's `profile` names. A row from a profile this
     * roster has not heard of still gets a mark rather than nothing: the
     * session says who owns it, and the roster is only how it is painted.
     */
    fun owner(profileName: String?): HermesProfile {
        val key = normalizeProfileKey(profileName)
        return profiles.firstOrNull { it.key == key }
            ?: HermesProfile(name = key, isDefault = key == DEFAULT_PROFILE)
    }
}

/** The rail's own actions; navigation belongs to the app shell. */
class ProfileRailActions(
    val onSelectProfile: (String) -> Unit = {},
    val onShowAllProfiles: () -> Unit = {},
    val onManageProfiles: () -> Unit = {},
)

internal const val PROFILE_RAIL_TAG = "Profile rail"
internal const val PROFILE_PICKER_TAG = "Profile picker"

/**
 * Arc-Spaces-style profile rail at the sidebar foot: a default↔all toggle
 * pinned left, the coloured named profiles between, and Manage pinned right.
 * The active profile pops in its own colour — the "where am I" cue.
 *
 * Port of `apps/desktop/src/app/chat/sidebar/profile-switcher.tsx:119-345` @
 * `f82f2dbabd9e66b714f2b4f8a40447fe0c13e732`.
 *
 * Desktop stops scaling the strip past thirteen profiles and collapses to a
 * compact menu (`:49`). A phone's budget is width, not count, so the strip
 * collapses as soon as the squares stop fitting beside the two pinned pills —
 * and it collapses to a sheet, because a pointer dropdown anchored to a 20px
 * square is not a phone control. Drag-reorder and long-press-recolour live only
 * on Desktop's squares path and are not ported.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileRail(
    state: ProfileRailState,
    actions: ProfileRailActions,
    modifier: Modifier = Modifier,
) {
    if (!state.visible) return
    val tokens = HermesTheme.tokens
    var pickerVisible by rememberSaveable { mutableStateOf(false) }

    Column(modifier.fillMaxWidth().background(tokens.sidebarSurface)) {
        Hairline(color = tokens.strokeQuaternary)
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 4.dp)
                .testTag(PROFILE_RAIL_TAG),
        ) {
            val target = HermesTheme.spacing.touchTarget
            // Every pinned pill holds its slot; whatever is left is the strip's
            // budget. One square per slot, never a squeezed square.
            val pinnedPills = if (state.multiProfile || state.defaultProfile != null) 2 else 1
            val capacity = ((maxWidth - target * pinnedPills) / target).toInt().coerceAtLeast(0)
            val condensed = state.named.size > capacity

            Row(verticalAlignment = Alignment.CenterVertically) {
                // One control toggles default ↔ all: home face when scoped to a
                // profile, layers face when showing everything. Leaving a
                // profile therefore never lands on all.
                val defaultProfile = state.defaultProfile
                if (state.multiProfile && defaultProfile != null) {
                    ProfilePill(
                        icon = if (state.scope.isAll) HermesIcon.Layers else HermesIcon.Home,
                        contentDescription = if (state.onDefault) {
                            SHOW_ALL_PROFILES
                        } else {
                            switchToProfile(defaultProfile.label)
                        },
                        active = state.scope.isAll || state.onDefault,
                        onClick = {
                            if (state.onDefault) actions.onShowAllProfiles()
                            else actions.onSelectProfile(defaultProfile.name)
                        },
                    )
                } else if (state.multiProfile) {
                    ProfilePill(
                        icon = HermesIcon.Layers,
                        contentDescription = ALL_PROFILES_LABEL,
                        active = state.scope.isAll,
                        onClick = actions.onShowAllProfiles,
                    )
                } else if (defaultProfile != null) {
                    // Single profile: the active default's home mark, no toggle.
                    ProfilePill(
                        icon = HermesIcon.Home,
                        contentDescription = defaultProfile.label,
                        active = true,
                        onClick = { actions.onSelectProfile(defaultProfile.name) },
                    )
                }

                Row(
                    Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Start,
                ) {
                    if (state.multiProfile && condensed) {
                        CondensedProfileControl(
                            state = state,
                            onClick = { pickerVisible = true },
                            modifier = Modifier.weight(1f),
                        )
                    } else if (state.multiProfile) {
                        state.named.forEach { profile ->
                            ProfileSquare(
                                profile = profile,
                                active = !state.scope.isAll && profile.key == state.activeKey,
                                onClick = { actions.onSelectProfile(profile.name) },
                            )
                        }
                    }
                }

                // Always reachable, even with only the default profile.
                ProfilePill(
                    icon = HermesIcon.Ellipsis,
                    contentDescription = MANAGE_PROFILES,
                    active = false,
                    onClick = actions.onManageProfiles,
                )
            }
        }
    }

    if (pickerVisible) {
        ModalBottomSheet(
            onDismissRequest = { pickerVisible = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = HermesTheme.tokens.chatSurface,
        ) {
            ProfilePickerSheet(
                state = state,
                onSelect = { name ->
                    pickerVisible = false
                    actions.onSelectProfile(name)
                },
            )
        }
    }
}

/** home / layers / Manage are glyph action buttons: navigation, not identity. */
@Composable
private fun ProfilePill(
    icon: HermesIcon,
    contentDescription: String,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = HermesTheme.tokens
    Box(
        modifier
            .size(HermesTheme.spacing.touchTarget)
            .background(if (active) tokens.widgetSurface else Color.Transparent, RoundedCornerShape(4.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics {
                this.contentDescription = contentDescription
                selected = active
            },
        contentAlignment = Alignment.Center,
    ) {
        HermesIconGlyph(
            icon = icon,
            color = if (active) tokens.textPrimary else tokens.textTertiary,
        )
    }
}

/**
 * A profile *is* its coloured square — no icon-button chrome
 * (`profile-switcher.tsx:628-634`). Desktop's 20px square keeps its visual
 * size inside Android's 48dp touch floor.
 */
@Composable
private fun ProfileSquare(
    profile: HermesProfile,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .size(HermesTheme.spacing.touchTarget)
            .clickable(role = Role.Button, onClick = onClick)
            .testTag("Profile square ${profile.name}")
            .semantics {
                contentDescription = switchToProfile(profile.label)
                selected = active
            },
        contentAlignment = Alignment.Center,
    ) {
        ProfileGlyph(profile = profile, size = 20.dp, active = active)
    }
}

/**
 * The collapsed strip: the active profile's mark and name, or the section title
 * when the scope is default/all — the left pill already carries that state
 * (`profile-switcher.tsx:486-537`).
 */
@Composable
private fun CondensedProfileControl(
    state: ProfileRailState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = HermesTheme.tokens
    val active = state.named.firstOrNull { !state.scope.isAll && it.key == state.activeKey }
    Row(
        modifier
            .heightIn(min = HermesTheme.spacing.touchTarget)
            .clickable(role = Role.Button, onClick = onClick)
            .testTag(PROFILE_PICKER_TAG)
            .padding(horizontal = 6.dp)
            .semantics { contentDescription = PROFILES_TITLE },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (active != null) ProfileGlyph(profile = active, size = 16.dp, active = true)
        Text(
            text = active?.label ?: PROFILES_TITLE,
            style = HermesTheme.type.caption,
            color = if (active == null) tokens.textTertiary else tokens.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        HermesIconGlyph(icon = HermesIcon.ChevronDown, color = tokens.textTertiary, size = 12.sp)
    }
}

@Composable
private fun ProfilePickerSheet(
    state: ProfileRailState,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().navigationBarsPadding()) {
        SectionLabel(PROFILES_TITLE, Modifier.padding(start = 16.dp, bottom = 4.dp))
        LazyColumn(Modifier.heightIn(max = 420.dp)) {
            items(items = state.named, key = { it.name }) { profile ->
                ProfilePickerRow(
                    profile = profile,
                    active = !state.scope.isAll && profile.key == state.activeKey,
                    onClick = { onSelect(profile.name) },
                )
            }
        }
    }
}

@Composable
private fun ProfilePickerRow(
    profile: HermesProfile,
    active: Boolean,
    onClick: () -> Unit,
) {
    val tokens = HermesTheme.tokens
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = HermesTheme.spacing.touchTarget)
            .background(if (active) tokens.sessionRowActiveSurface else Color.Transparent)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .semantics { selected = active },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.width(2.dp))
        ProfileGlyph(profile = profile, size = 16.dp, active = active)
        Text(
            text = profile.label,
            style = HermesTheme.type.body,
            color = if (active) tokens.textPrimary else tokens.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// Copy is Desktop's, verbatim: apps/desktop/src/i18n/en.ts:1755,1766-1771.
internal const val PROFILES_TITLE = "Profiles"
internal const val ALL_PROFILES_LABEL = "All profiles"
internal const val SHOW_ALL_PROFILES = "Show all profiles"
internal const val MANAGE_PROFILES = "Manage profiles…"

internal fun switchToProfile(name: String): String = "Switch to $name"

// ── Previews ───────────────────────────────────────────────────────────────
// Every profile below is invented. No host, profile or person in this repo
// corresponds to anything real.

private fun previewRail(scope: ProfileScope, profiles: Int = 3) = ProfileRailState(
    profiles = listOf(
        HermesProfile(name = "default", isDefault = true, model = "a-model", skillCount = 7),
        HermesProfile(name = "work", model = "b-model", skillCount = 4),
        HermesProfile(name = "lab", displayName = "Lab bench"),
        HermesProfile(name = "review"),
        HermesProfile(name = "triage"),
        HermesProfile(name = "docs"),
        HermesProfile(name = "ops"),
        HermesProfile(name = "bench"),
    ).take(profiles),
    scope = scope,
)

@Composable
private fun PreviewRail(selection: AppearanceSelection, state: ProfileRailState) {
    HermesTheme(selection) {
        Column(Modifier.background(HermesTheme.tokens.sidebarSurface)) {
            ProfileRail(state = state, actions = ProfileRailActions())
        }
    }
}

@Preview(name = "Profile rail · dark", widthDp = 320, heightDp = 96)
@Composable
private fun ProfileRailPreviewDark() = PreviewRail(
    AppearanceSelection("nous", HermesThemeMode.Dark),
    previewRail(ProfileScope(activeProfile = "work")),
)

@Preview(name = "Profile rail · light", widthDp = 320, heightDp = 96)
@Composable
private fun ProfileRailPreviewLight() = PreviewRail(
    AppearanceSelection("nous", HermesThemeMode.Light),
    previewRail(ProfileScope(activeProfile = "work")),
)

@Preview(name = "Profile rail · all profiles", widthDp = 320, heightDp = 96)
@Composable
private fun ProfileRailPreviewAll() = PreviewRail(
    AppearanceSelection("nous", HermesThemeMode.Dark),
    previewRail(ProfileScope(activeProfile = "work", showAllProfiles = true)),
)

@Preview(name = "Profile rail · collapsed", widthDp = 320, heightDp = 96)
@Composable
private fun ProfileRailPreviewCollapsed() = PreviewRail(
    AppearanceSelection("nous", HermesThemeMode.Dark),
    previewRail(ProfileScope(activeProfile = "triage"), profiles = 8),
)
