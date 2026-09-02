package com.hermesagent.mobile.data.composer

/**
 * Which models the picker offers, ported from Desktop's
 * `apps/desktop/src/store/model-visibility.ts` @
 * `3ca096de5f8183cb2e0ec23673f294d5978656a3`.
 *
 * A pure client preference: `model.options` carries no visibility field
 * (`apps/desktop/src/types/hermes.ts:384-427`), so nothing here is Gateway
 * truth and nothing here is ever sent. The whole file is the arithmetic
 * Desktop's store does, with its own names kept so the two can be diffed.
 */

/**
 * Models shown per provider before the person has customised the list
 * (`store/model-visibility.ts:10`). Backend `models` arrive relevance-ordered.
 */
const val DEFAULT_VISIBLE_PER_PROVIDER = 50

/**
 * Stable key for a provider/model pair. `::` avoids colliding with model ids
 * that contain a single colon, e.g. `model:tag` (`:12-14`).
 */
fun modelVisibilityKey(provider: String, model: String): String = "$provider::$model"

/**
 * Stored when the person explicitly hides every model of a provider. It is what
 * tells "hid everything" apart from "never customised", so the curated defaults
 * are not silently re-expanded under them (`:16-26`).
 */
const val EMPTY_PROVIDER_SENTINEL = ""

fun emptyProviderSentinelKey(provider: String): String =
    modelVisibilityKey(provider, EMPTY_PROVIDER_SENTINEL)

fun isProviderSentinel(key: String): Boolean = key.endsWith("::")

/**
 * A model and its optional `…-fast` sibling as one row (`:28-33`).
 *
 * @param id the canonical (base) model, which is also the row's key.
 * @param fastId the fast variant when the provider ships one.
 */
data class ModelFamily(val id: String, val fastId: String? = null)

private val FAST_SUFFIX = Regex("-fast$", RegexOption.IGNORE_CASE)
private val DATE_SUFFIX = Regex("-\\d{8}$")

/**
 * Collapse a provider's models so a base model and its `-fast` variant become
 * one row with one toggle (`:35-69`).
 *
 * Order follows the base model's position. A `-fast` model with no base stands
 * on its own, and a date-pinned snapshot superseded by its rolling alias is
 * dropped as the duplicate it is.
 */
fun collapseModelFamilies(models: List<String>): List<ModelFamily> {
    val present = models.toSet()
    val families = mutableListOf<ModelFamily>()
    val consumed = mutableSetOf<String>()

    for (model in models) {
        if (model in consumed) continue
        if (FAST_SUFFIX.containsMatchIn(model) && FAST_SUFFIX.replace(model, "") in present) continue
        if (DATE_SUFFIX.containsMatchIn(model) && DATE_SUFFIX.replace(model, "") in present) continue

        val fastId = "$model-fast"
        val hasFast = fastId in present
        families += ModelFamily(id = model, fastId = fastId.takeIf { hasFast })
        consumed += model
        if (hasFast) consumed += fastId
    }
    return families
}

/**
 * A provider's curated default keys (`:114-132`).
 *
 * The backend's `featured_models` shortlist wins where there is one — one
 * flagship per lab, so an aggregator serving dozens of models across many labs
 * does not flood the default view — and the top-N collapsed families are the
 * fallback for a provider with no manifest entry
 * (`hermes_cli/inventory.py:513-568` @ `3ca096de`).
 */
private fun expandProviderDefaults(provider: ModelProvider, target: MutableSet<String>) {
    val families = collapseModelFamilies(provider.models.map(ModelOption::id))
    val defaults = if (provider.featured.isNotEmpty()) {
        families.filter { it.id in provider.featured }
    } else {
        families.take(DEFAULT_VISIBLE_PER_PROVIDER)
    }
    defaults.forEach { target += modelVisibilityKey(provider.id, it.id) }
}

/** The curated default key set across every provider (`:102-112`). */
fun defaultVisibleKeys(providers: List<ModelProvider>): Set<String> = buildSet {
    providers.forEach { expandProviderDefaults(it, this) }
}

/**
 * The canonical working set: stored keys plus the curated expansion for any
 * provider that has none (`:134-165`).
 *
 * Hide-all sentinels are **preserved** — this is the set the toggles mutate and
 * persist, so dropping one would silently re-enable a provider that was
 * emptied. [effectiveVisibleKeys] is the display form.
 */
fun resolveVisibleKeys(stored: Set<String>?, providers: List<ModelProvider>): Set<String> {
    if (stored == null) return defaultVisibleKeys(providers)
    if (stored.isEmpty()) return emptySet()

    val next = stored.toMutableSet()
    for (provider in providers) {
        val prefix = "${provider.id}::"
        val hasStoredProvider = stored.any { it.startsWith(prefix) && !isProviderSentinel(it) }
        val hasSentinel = emptyProviderSentinelKey(provider.id) in stored
        if (hasStoredProvider || hasSentinel) continue
        expandProviderDefaults(provider, next)
    }
    return next
}

/** The working set with bookkeeping sentinels stripped — they are not models (`:167-183`). */
fun effectiveVisibleKeys(stored: Set<String>?, providers: List<ModelProvider>): Set<String> =
    resolveVisibleKeys(stored, providers).filterNot(::isProviderSentinel).toSet()

/**
 * The next persisted set when one model row is toggled (`:185-220`).
 *
 * Seeded from [resolveVisibleKeys], not [effectiveVisibleKeys], so other
 * providers' hide-all sentinels survive. Turning the last visible model of a
 * provider off records that provider's sentinel; turning one back on clears
 * **that** sentinel only and promotes the provider to exactly the one model
 * re-enabled — the curated defaults are deliberately not restored.
 */
fun toggleModelVisibility(
    stored: Set<String>?,
    providers: List<ModelProvider>,
    providerId: String,
    model: String,
): Set<String> {
    val next = resolveVisibleKeys(stored, providers).toMutableSet()
    val key = modelVisibilityKey(providerId, model)
    val sentinel = emptyProviderSentinelKey(providerId)

    if (key in next) {
        next -= key
        val remaining = next.any { it.startsWith("$providerId::") && !isProviderSentinel(it) }
        if (!remaining) next += sentinel
    } else {
        next -= sentinel
        next += key
    }
    return next
}

/**
 * The next persisted set when a provider's master switch is flipped (`:222-262`).
 *
 * `visible` enables every collapsed family and clears the sentinel; hiding
 * removes them all and records the sentinel so the defaults are not re-expanded.
 * A provider with no models cannot be "all on", so it is left empty rather than
 * stranding a sentinel that would read as a deliberate hide-all.
 */
fun setProviderVisibility(
    stored: Set<String>?,
    providers: List<ModelProvider>,
    providerId: String,
    visible: Boolean,
): Set<String> {
    val next = resolveVisibleKeys(stored, providers).toMutableSet()
    val sentinel = emptyProviderSentinelKey(providerId)
    val families = collapseModelFamilies(
        providers.firstOrNull { it.id == providerId }?.models?.map(ModelOption::id).orEmpty(),
    )

    next.removeAll { it.startsWith("$providerId::") }

    if (visible) {
        families.forEach { next += modelVisibilityKey(providerId, it.id) }
        if (families.isEmpty()) next -= sentinel
    } else {
        next += sentinel
    }
    return next
}

/** A provider's master switch: all on, all off, or partway (`model-visibility-dialog.tsx:119`). */
enum class ProviderVisibility { All, None, Some }

fun providerVisibility(
    provider: ModelProvider,
    visible: Set<String>,
): ProviderVisibility {
    val families = collapseModelFamilies(provider.models.map(ModelOption::id))
    val on = families.count { modelVisibilityKey(provider.id, it.id) in visible }
    return when {
        on == 0 -> ProviderVisibility.None
        on == families.size -> ProviderVisibility.All
        else -> ProviderVisibility.Some
    }
}

/**
 * The catalog's virtual Mixture-of-Agents provider. Its `models` are MoA preset
 * *names*, not model ids (`hermes_cli/inventory.py:1000-1015` @ `3ca096de`).
 */
const val MOA_PROVIDER_ID = "moa"

/**
 * The providers the picker groups: the catalog minus the virtual `moa` row
 * (`model-catalog-menu.tsx:172-175` @ `3ca096de`, `providers?.filter(provider
 * => provider.slug.toLowerCase() !== 'moa')`).
 *
 * Desktop drops it there because it renders presets as their own searchable
 * section instead (`:165-170,194-200`); this app has not ported presets at all
 * (`docs/parity/model-visibility.md`), so dropping the row is what keeps them
 * off a surface that cannot honour them — a tap would send a preset name where
 * a model id belongs.
 *
 * It has to happen **before** [effectiveVisibleKeys] resolves, exactly as it
 * does upstream (`:182-185` resolves over `pickerProviders`): resolving over
 * the raw catalog expands `Mixture of Agents` into the curated default for
 * everyone, including the customised people whose stored set never named it.
 *
 * The Models sheet deliberately does **not** filter — Desktop's own dialog
 * lists every provider the catalog carries that has models at all
 * (`model-visibility-dialog.tsx:61-64`).
 */
fun pickerProviders(providers: List<ModelProvider>): List<ModelProvider> =
    providers.filterNot { it.id.equals(MOA_PROVIDER_ID, ignoreCase = true) }

/**
 * The provider-grouped rows the picker shows, ported from `groupModels`
 * (`apps/desktop/src/app/shell/model-catalog-menu.tsx:546-601` @ `3ca096de`).
 *
 * [providers] is [pickerProviders], never the raw catalog, and [visible] is the
 * **resolved** display set, never the raw stored one: Desktop resolves both
 * against the catalog it actually fetched before calling `groupModels`
 * (`:179-188`, `shownKeys = effectiveVisibleKeys(visibleModels,
 * pickerProviders)`), so the curated default flows through
 * `expandProviderDefaults` — which honours the host's `featured_models` — and a
 * provider that appeared after the last customisation is expanded rather than
 * dropped. `groupModels`' own top-N `else` branch (`:238-240`) is unreachable
 * for that reason and has no counterpart here.
 *
 * **Typing spans every family regardless of visibility** — a search is itself a
 * narrowing action, so anything stays reachable past the cut and matches are
 * deliberately not capped. The active model is always kept in its provider's
 * stable order so selecting one cannot shuffle the list, and that pin is
 * skipped while searching because a query means "show me matches".
 *
 * The one deliberate difference from Desktop is the group order: Desktop
 * re-sorts alphabetically by provider name (`:596-598`) to undo the backend
 * floating the current provider first; this app has always rendered
 * `model.options` order and that is a separate surface's question
 * (`docs/parity/model-visibility.md`).
 */
fun visibleModelGroups(
    providers: List<ModelProvider>,
    query: String,
    visible: Set<String>,
    current: ComposerModelSelection?,
): List<ModelProvider> {
    val needle = query.trim().lowercase()
    return providers.mapNotNull { provider ->
        val families = collapseModelFamilies(provider.models.map(ModelOption::id))
        if (families.isEmpty()) return@mapNotNull null
        val labels = provider.models.associate { it.id to it.label }

        val shown = when {
            needle.isNotEmpty() -> families.filter { family ->
                val haystack = listOfNotNull(
                    family.id,
                    family.fastId,
                    provider.label,
                    provider.id,
                    labels[family.id],
                ).joinToString(" ").lowercase()
                needle in haystack
            }.map(ModelFamily::id).toSet()

            else -> families
                .filter { modelVisibilityKey(provider.id, it.id) in visible }
                .map(ModelFamily::id)
                .toSet()
        }

        val activeId = if (needle.isEmpty() && current != null && current.provider == provider.id) {
            families.firstOrNull { it.id == current.model || it.fastId == current.model }?.id
        } else {
            null
        }

        val kept = families.filter { it.id in shown || it.id == activeId }.map(ModelFamily::id).toSet()
        val models = provider.models.filter { it.id in kept }
        provider.takeIf { models.isNotEmpty() }?.copy(models = models)
    }
}
