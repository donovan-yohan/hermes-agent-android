package com.hermesagent.mobile.ui.profiles

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
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
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.Preview
import com.hermesagent.mobile.ui.theme.AppearanceSelection
import com.hermesagent.mobile.ui.theme.HermesThemeMode
import com.hermesagent.mobile.data.profiles.HermesProfile
import com.hermesagent.mobile.ui.common.EmptyState
import com.hermesagent.mobile.ui.common.Hairline
import com.hermesagent.mobile.ui.common.ProfileGlyph
import com.hermesagent.mobile.ui.common.SearchField
import com.hermesagent.mobile.ui.theme.HermesTheme

/** What the read-only roster renders. Editing a profile is deliberately not ported. */
data class ProfilesUiState(
    val profiles: List<HermesProfile> = emptyList(),
    val loaded: Boolean = false,
    val connected: Boolean = false,
)

internal const val PROFILE_ROSTER_TAG = "Profile roster"

/**
 * The profiles roster: Desktop's manage overlay, read-only.
 *
 * Port of `apps/desktop/src/app/profiles/index.tsx:105-268` @
 * `f82f2dbabd9e66b714f2b4f8a40447fe0c13e732`, whose `Panel*` grammar maps onto
 * this app's `SectionLabel`/`SearchField`/`EmptyState` primitives. Desktop's own
 * panel stacks the list above the detail once the card narrows
 * (`app/overlays/panel.tsx:88-98,126-128`), which is the phone shape, so the
 * list keeps a bounded height and the detail sits under it.
 *
 * Create, rename, delete, export/import and the SOUL.md editor are Desktop
 * affordances this slice does not ship.
 */
@Composable
fun ProfilesScreen(
    state: ProfilesUiState,
    modifier: Modifier = Modifier,
) {
    val tokens = HermesTheme.tokens
    var query by rememberSaveable { mutableStateOf("") }
    var selectedName by rememberSaveable { mutableStateOf<String?>(null) }

    // Desktop selects the default profile (else the first row) as soon as the
    // list lands, so the detail is never empty for a roster that has rows.
    val selected = state.profiles.firstOrNull { it.name == selectedName }
        ?: state.profiles.firstOrNull(HermesProfile::isDefault)
        ?: state.profiles.firstOrNull()

    val visible = state.profiles.filter { it.matches(query) }

    Column(modifier.fillMaxWidth().fillMaxHeight().testTag(PROFILE_ROSTER_TAG)) {
        when {
            !state.loaded -> EmptyState(
                title = if (state.connected) LOADING_PROFILES else CONNECT_FIRST_TITLE,
                description = if (state.connected) LOADING_DESC else CONNECT_FIRST_DESC,
            )

            state.profiles.isEmpty() -> EmptyState(title = NO_PROFILES, description = CREATE_DESC)

            else -> {
                // The count is Desktop's PanelHeader subtitle; the app shell's
                // overlay header already carries it, so it is not repeated here.
                SearchField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = SEARCH_PROFILES,
                    modifier = Modifier.padding(horizontal = HermesTheme.spacing.pageInset),
                )
                LazyColumn(
                    Modifier.heightIn(max = 260.dp).testTag("Profile roster list"),
                ) {
                    items(items = visible, key = { it.name }) { profile ->
                        ProfileRosterRow(
                            profile = profile,
                            active = profile.name == selected?.name,
                            onSelect = { selectedName = profile.name },
                        )
                    }
                }
                Hairline(color = tokens.strokeQuaternary)
                // A roster with rows always has a selection: Desktop picks the
                // default profile (else the first row) the moment the list
                // lands (`app/profiles/index.tsx:56-62`).
                ProfileDetail(requireNotNull(selected), Modifier.weight(1f))
            }
        }
    }
}

/**
 * One list row: the shared glyph plus the label
 * (`apps/desktop/src/app/profiles/index.tsx:206-223`). The Default badge lives
 * on the detail, as it does upstream — the row's home glyph already says it.
 */
@Composable
private fun ProfileRosterRow(
    profile: HermesProfile,
    active: Boolean,
    onSelect: () -> Unit,
) {
    val tokens = HermesTheme.tokens
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = HermesTheme.spacing.touchTarget)
            .background(
                if (active) tokens.sessionRowActiveSurface else Color.Transparent,
                RoundedCornerShape(6.dp),
            )
            .clickable(role = Role.Button, onClick = onSelect)
            .testTag("Profile row ${profile.name}")
            .padding(horizontal = HermesTheme.spacing.pageInset, vertical = 8.dp)
            .semantics { selected = active },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ProfileGlyph(profile = profile, size = 16.dp, active = active)
        Text(
            text = profile.label,
            style = HermesTheme.type.sessionTitle,
            color = if (active) tokens.textPrimary else tokens.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProfileDetail(profile: HermesProfile, modifier: Modifier = Modifier) {
    val tokens = HermesTheme.tokens
    Column(
        modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = HermesTheme.spacing.pageInset, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = profile.label,
                style = HermesTheme.type.bodyStrong,
                color = tokens.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (profile.isDefault) RosterPill(DEFAULT_BADGE, good = true)
            if (profile.hasEnv) RosterPill(ENV_BADGE, good = false)
        }
        if (profile.path.isNotBlank()) {
            Text(
                text = displayPath(profile.path),
                style = HermesTheme.type.code,
                color = tokens.textQuaternary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        MetaRow(MODEL_LABEL) {
            val model = profile.model
            if (model.isNullOrBlank()) {
                Text(NOT_SET, style = HermesTheme.type.code, color = tokens.textQuaternary)
            } else {
                Text(
                    text = profile.provider?.takeIf(String::isNotBlank)?.let { "$model · $it" } ?: model,
                    style = HermesTheme.type.code,
                    color = tokens.textSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        MetaRow(SKILLS_LABEL) {
            Text(
                text = profile.skillCount.toString(),
                style = HermesTheme.type.caption,
                color = tokens.textSecondary,
            )
        }
        if (profile.description.isNotBlank()) {
            Text(
                text = profile.description,
                style = HermesTheme.type.caption,
                color = tokens.textTertiary,
            )
        }
    }
}

/**
 * Desktop's inspector-style key/value grid, label column and all
 * (`app/overlays/panel.tsx:324-335`). The label is plain quiet text there, not
 * the uppercase section label, so it stays plain here.
 */
@Composable
private fun MetaRow(label: String, value: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = label,
            style = HermesTheme.type.caption,
            color = HermesTheme.tokens.textTertiary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(META_LABEL_WIDTH),
        )
        Box(Modifier.weight(1f)) { value() }
    }
}

/** Desktop's `grid-cols-[5rem_1fr]` label column, at this app's type scale. */
private val META_LABEL_WIDTH = 76.dp

/** Desktop's `PanelPill` (`app/overlays/panel.tsx:361-372`), in two tones. */
@Composable
private fun RosterPill(text: String, good: Boolean) {
    val tokens = HermesTheme.tokens
    Text(
        text = text,
        style = HermesTheme.type.scaffoldMeta,
        color = if (good) tokens.statusUnread else tokens.textTertiary,
        modifier = Modifier
            .background(
                if (good) tokens.statusUnread.copy(alpha = 0.12f) else tokens.widgetSurface,
                RoundedCornerShape(999.dp),
            )
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/** Desktop's search matches on name or model (`app/profiles/index.tsx:89-91`). */
internal fun HermesProfile.matches(query: String): Boolean {
    val needle = query.trim().lowercase()
    if (needle.isEmpty()) return true
    return name.lowercase().contains(needle) || model.orEmpty().lowercase().contains(needle)
}

/**
 * Collapse the user's home to `~` for display only, the conservative half of
 * `apps/desktop/src/lib/display-path.ts` — this client never knows the remote
 * `$HOME`, so only the common `/Users/<name>` and `/home/<name>` prefixes
 * collapse. Paint only: the real path is what a request would ever carry.
 */
internal fun displayPath(raw: String): String {
    val path = raw.trim().replace('\\', '/').trimEnd('/').ifEmpty { raw.trim() }
    val prefix = listOf("/Users/", "/home/").firstOrNull(path::startsWith) ?: return path
    val rest = path.removePrefix(prefix)
    val slash = rest.indexOf('/')
    return if (slash < 0) "~" else "~" + rest.substring(slash)
}

// Copy is Desktop's, verbatim: apps/desktop/src/i18n/en.ts:1755-1809.
internal const val NO_PROFILES = "No profiles yet."
internal const val SEARCH_PROFILES = "Search profiles..."
internal const val LOADING_PROFILES = "Loading profiles..."
internal const val DEFAULT_BADGE = "Default"
internal const val ENV_BADGE = ".env"
internal const val MODEL_LABEL = "Model"
internal const val SKILLS_LABEL = "Skills"
internal const val NOT_SET = "Not set"
internal const val CREATE_DESC =
    "Profiles are independent Hermes environments: separate config, skills, and SOUL.md."

// Android-only states: this app can be looking at no Gateway at all.
private const val CONNECT_FIRST_TITLE = "No profiles"
private const val CONNECT_FIRST_DESC = "Connect to a Gateway to load its profiles."
private const val LOADING_DESC = "Hermes is reading this Gateway's profiles."

internal fun profileCount(count: Int): String = "$count ${if (count == 1) "profile" else "profiles"}"

// ── Previews ───────────────────────────────────────────────────────────────
// Every profile and path below is invented.

private fun previewRoster() = ProfilesUiState(
    profiles = listOf(
        HermesProfile(
            name = "default",
            path = "/example/home/.hermes",
            isDefault = true,
            model = "a-model",
            provider = "a-provider",
            skillCount = 7,
            hasEnv = true,
        ),
        HermesProfile(name = "work", path = "/example/home/.hermes-work", model = "b-model", skillCount = 4),
        HermesProfile(
            name = "lab",
            path = "/example/home/.hermes-lab",
            displayName = "Lab bench",
            description = "Bench experiments.",
        ),
    ),
    loaded = true,
    connected = true,
)

@Composable
private fun PreviewRoster(selection: AppearanceSelection, state: ProfilesUiState) {
    HermesTheme(selection) {
        Column(Modifier.background(HermesTheme.tokens.chatSurface)) {
            ProfilesScreen(state)
        }
    }
}

@Preview(name = "Profiles roster · dark", widthDp = 412, heightDp = 892)
@Composable
private fun ProfilesPreviewDark() =
    PreviewRoster(AppearanceSelection("nous", HermesThemeMode.Dark), previewRoster())

@Preview(name = "Profiles roster · light", widthDp = 412, heightDp = 892)
@Composable
private fun ProfilesPreviewLight() =
    PreviewRoster(AppearanceSelection("nous", HermesThemeMode.Light), previewRoster())

@Preview(name = "Profiles roster · empty", widthDp = 412, heightDp = 892)
@Composable
private fun ProfilesPreviewEmpty() = PreviewRoster(
    AppearanceSelection("nous", HermesThemeMode.Dark),
    ProfilesUiState(loaded = true, connected = true),
)
