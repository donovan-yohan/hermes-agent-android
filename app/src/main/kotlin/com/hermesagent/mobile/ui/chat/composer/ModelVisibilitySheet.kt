@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.hermesagent.mobile.ui.chat.composer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hermesagent.mobile.data.composer.ModelCatalog
import com.hermesagent.mobile.data.composer.ModelProvider
import com.hermesagent.mobile.data.composer.ProviderVisibility
import com.hermesagent.mobile.data.composer.collapseModelFamilies
import com.hermesagent.mobile.data.composer.effectiveVisibleKeys
import com.hermesagent.mobile.data.composer.modelVisibilityKey
import com.hermesagent.mobile.data.composer.providerVisibility
import com.hermesagent.mobile.ui.common.ComingSoonAction
import com.hermesagent.mobile.ui.common.HermesIcon
import com.hermesagent.mobile.ui.common.HermesIconGlyph
import com.hermesagent.mobile.ui.common.TokenSwitch
import com.hermesagent.mobile.ui.theme.HermesTheme

/**
 * The Models sheet — Desktop's `ModelVisibilityDialog`
 * (`apps/desktop/src/components/model-visibility-dialog.tsx:81-190` @
 * `3ca096de5f8183cb2e0ec23673f294d5978656a3`) as the bottom sheet this app uses
 * for every searchable list.
 *
 * Nothing here reaches the Gateway: `model.options` carries no visibility field,
 * and the shortlist is an on-device preference scoped to the connection. The
 * ledger is `docs/parity/model-visibility.md`.
 */

/** `Models` (`i18n/en.ts:2848` @ `3ca096de`). */
const val MODEL_VISIBILITY_TITLE = "Models"

/** `Search models` (`i18n/en.ts:2849` @ `3ca096de`). */
private const val SEARCH_MODELS = "Search models"

/** `No authenticated providers.` (`i18n/en.ts:2850` @ `3ca096de`). */
private const val NO_AUTHENTICATED_PROVIDERS = "No authenticated providers."

/**
 * What the sheet says while the catalog read is still in flight, where Desktop
 * draws a `GlyphSpinner` (`model-visibility-dialog.tsx:103`). App-authored, and
 * the same line the picker already says for the same pending read.
 */
private const val MODELS_LOADING = "Loading model choices…"

/** `Add provider…` (`i18n/en.ts:2851` @ `3ca096de`). */
const val ADD_PROVIDER = "Add provider…"

/** `Edit models…` (`i18n/en.ts:2861` @ `3ca096de`). */
const val EDIT_MODELS = "Edit models…"

internal const val MODEL_VISIBILITY_SHEET_TAG = "Models sheet"

internal const val EDIT_MODELS_TAG = "Edit models row"

internal fun providerVisibilityToggleTag(providerId: String): String = "models-provider-$providerId"

internal fun modelVisibilityToggleTag(providerId: String, model: String): String =
    "models-row-$providerId-$model"

@Composable
internal fun ModelVisibilitySheet(
    catalog: ModelCatalog?,
    visibleModels: Set<String>?,
    onToggleModel: (provider: String, model: String) -> Unit,
    onSetProviderVisible: (provider: String, visible: Boolean) -> Unit,
    onDismiss: () -> Unit,
    /** The catalog read is still in flight, so an empty list is not yet an answer. */
    isLoading: Boolean = false,
) {
    val tokens = HermesTheme.tokens
    var query by remember { mutableStateOf("") }
    // Desktop shares one persisted collapse set with the picker
    // (`store/provider-collapse.ts:22`). This app's picker has no collapse to
    // share, so it lives for the life of the sheet.
    var collapsed by remember { mutableStateOf(emptySet<String>()) }

    // Desktop drops providers with no models at all (`model-visibility-dialog.tsx:62`).
    val providers = remember(catalog) {
        catalog?.providers?.filter { it.models.isNotEmpty() }.orEmpty()
    }
    val visible = remember(visibleModels, providers) { effectiveVisibleKeys(visibleModels, providers) }
    val needle = query.trim().lowercase()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = tokens.cardSurface,
        contentColor = tokens.textPrimary,
        scrimColor = tokens.textPrimary.copy(alpha = .32f),
        modifier = Modifier.testTag(MODEL_VISIBILITY_SHEET_TAG),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                // Above the scroll, so the keyboard shrinks the viewport rather
                // than travelling with the content and leaving the footer
                // unreachable (`scripts/check-repo-invariants.sh`, check 14).
                .imePadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = HermesTheme.spacing.pageInset, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(MODEL_VISIBILITY_TITLE, style = HermesTheme.type.screenTitle, color = tokens.textPrimary)
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = HermesTheme.spacing.touchTarget)
                    .background(tokens.widgetSurface, RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                HermesIconGlyph(HermesIcon.Search, color = tokens.textTertiary)
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle = HermesTheme.type.body.copy(color = tokens.textPrimary),
                    cursorBrush = SolidColor(tokens.composerRing),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = SEARCH_MODELS },
                    decorationBox = { inner ->
                        if (query.isEmpty()) {
                            Text(SEARCH_MODELS, style = HermesTheme.type.body, color = tokens.textTertiary)
                        }
                        inner()
                    },
                )
            }

            if (providers.isEmpty()) {
                // Desktop draws a spinner while the catalog query is pending
                // and only says the sentence once it settles
                // (`model-visibility-dialog.tsx:101-104`). A refresh while the
                // sheet is open must not flash "no providers" at a host that
                // has them; this app has no spinner primitive, so it reuses the
                // one line the picker already says for the same pending read.
                Text(
                    if (isLoading) MODELS_LOADING else NO_AUTHENTICATED_PROVIDERS,
                    style = HermesTheme.type.body,
                    color = tokens.textTertiary,
                    modifier = Modifier
                        .heightIn(min = HermesTheme.spacing.touchTarget)
                        .padding(vertical = 12.dp),
                )
            } else {
                LazyColumn(
                    Modifier.heightIn(max = 380.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    providers.forEach { provider ->
                        val families = collapseModelFamilies(provider.models.map { it.id })
                        val labels = provider.models.associate { it.id to it.label }
                        val matching = families.filter { family ->
                            needle.isEmpty() || matchesModelQuery(provider, family.id, labels[family.id], needle)
                        }
                        if (matching.isEmpty()) return@forEach

                        item(key = "visibility-provider:${provider.id}") {
                            ProviderHeaderRow(
                                provider = provider,
                                state = providerVisibility(provider, visible),
                                // A query is a narrowing action; Desktop
                                // ignores the collapse while one is typed
                                // (`model-visibility-dialog.tsx:121`).
                                collapsed = provider.id in collapsed && needle.isEmpty(),
                                onToggleCollapsed = {
                                    collapsed = if (provider.id in collapsed) {
                                        collapsed - provider.id
                                    } else {
                                        collapsed + provider.id
                                    }
                                },
                                onSetVisible = { onSetProviderVisible(provider.id, it) },
                            )
                        }
                        if (provider.id in collapsed && needle.isEmpty()) return@forEach
                        items(matching, key = { "visibility:${provider.id}:${it.id}" }) { family ->
                            ModelVisibilityRow(
                                providerId = provider.id,
                                model = family.id,
                                label = labels[family.id] ?: family.id,
                                on = modelVisibilityKey(provider.id, family.id) in visible,
                                onToggle = { onToggleModel(provider.id, family.id) },
                            )
                        }
                    }
                }
            }

            // Provider setup is not ported, so the control ships visible and
            // disabled behind the marker chip rather than silently missing.
            ComingSoonAction(ADD_PROVIDER)
        }
    }
}

private fun matchesModelQuery(
    provider: ModelProvider,
    model: String,
    label: String?,
    needle: String,
): Boolean = listOfNotNull(model, provider.label, provider.id, label)
    .joinToString(" ")
    .lowercase()
    .contains(needle)

@Composable
private fun ProviderHeaderRow(
    provider: ModelProvider,
    state: ProviderVisibility,
    collapsed: Boolean,
    onToggleCollapsed: () -> Unit,
    onSetVisible: (Boolean) -> Unit,
) {
    val tokens = HermesTheme.tokens
    Row(
        Modifier.fillMaxWidth().padding(top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            Modifier
                .weight(1f)
                .heightIn(min = HermesTheme.spacing.touchTarget)
                .clickable(role = Role.Button, onClick = onToggleCollapsed)
                .semantics {
                    contentDescription = provider.label
                    stateDescription = if (collapsed) "Collapsed" else "Expanded"
                },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = provider.label.uppercase(),
                style = HermesTheme.type.sectionLabel,
                color = tokens.textTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            HermesIconGlyph(
                if (collapsed) HermesIcon.ChevronRight else HermesIcon.ChevronDown,
                color = tokens.textQuaternary,
                size = 10.sp,
            )
        }
        ProviderCheckbox(
            label = provider.label,
            state = state,
            modifier = Modifier.testTag(providerVisibilityToggleTag(provider.id)),
            // Desktop's tri-state checkbox turns everything on from either the
            // empty or the partial state (`next !== false`,
            // `model-visibility-dialog.tsx:142`).
            onToggle = { onSetVisible(state != ProviderVisibility.All) },
        )
    }
}

/** Desktop's tri-state `Checkbox`, drawn from tokens rather than Material's. */
@Composable
private fun ProviderCheckbox(
    label: String,
    state: ProviderVisibility,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = HermesTheme.tokens
    Box(
        modifier
            .size(HermesTheme.spacing.touchTarget)
            .clickable(role = Role.Checkbox, onClick = onToggle)
            .semantics {
                contentDescription = "Show every $label model"
                stateDescription = when (state) {
                    ProviderVisibility.All -> "On"
                    ProviderVisibility.None -> "Off"
                    ProviderVisibility.Some -> "Partly on"
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(16.dp)
                .border(
                    1.dp,
                    if (state == ProviderVisibility.None) tokens.strokeSecondary else tokens.accent,
                    CheckShape,
                )
                .background(if (state == ProviderVisibility.None) tokens.cardSurface else tokens.accent, CheckShape),
            contentAlignment = Alignment.Center,
        ) {
            when (state) {
                ProviderVisibility.All -> HermesIconGlyph(
                    HermesIcon.Check,
                    color = tokens.accentForeground,
                    size = 10.sp,
                )

                ProviderVisibility.Some -> Box(
                    Modifier.width(8.dp).height(2.dp).background(tokens.accentForeground),
                )

                ProviderVisibility.None -> Unit
            }
        }
    }
}

@Composable
private fun ModelVisibilityRow(
    providerId: String,
    model: String,
    label: String,
    on: Boolean,
    onToggle: () -> Unit,
) {
    val tokens = HermesTheme.tokens
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = HermesTheme.spacing.touchTarget)
            .clickable(role = Role.Switch, onClick = onToggle)
            .testTag(modelVisibilityToggleTag(providerId, model))
            .semantics {
                contentDescription = "Show $label"
                stateDescription = if (on) "On" else "Off"
            }
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = label,
            style = HermesTheme.type.body,
            color = tokens.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        TokenSwitch(on = on)
    }
}

private val CheckShape = RoundedCornerShape(3.dp)
