package com.hermesagent.mobile.data.composer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The visibility arithmetic, against Desktop's own store
 * (`apps/desktop/src/store/model-visibility.ts` @
 * `3ca096de5f8183cb2e0ec23673f294d5978656a3`) and the picker's `groupModels`
 * (`apps/desktop/src/app/shell/model-catalog-menu.tsx:546-601`).
 */
class ModelVisibilityTest {

    private fun provider(
        id: String,
        vararg models: String,
        featured: List<String> = emptyList(),
    ) = ModelProvider(
        id = id,
        label = id.replaceFirstChar(Char::uppercase),
        models = models.map { ModelOption(it) },
        featured = featured,
    )

    @Test
    fun `a fast sibling collapses into its base, and a stray fast model stands alone`() {
        val families = collapseModelFamilies(listOf("alpha", "alpha-fast", "beta-fast", "gamma"))

        assertEquals(
            listOf(
                ModelFamily("alpha", "alpha-fast"),
                ModelFamily("beta-fast", null),
                ModelFamily("gamma", null),
            ),
            families,
        )
    }

    @Test
    fun `a date-pinned snapshot superseded by its rolling alias is dropped`() {
        val families = collapseModelFamilies(listOf("alpha", "alpha-20251101", "delta-20250104"))

        // The pin whose alias is present is the duplicate; one with no alias is
        // the only name that model has, so it stays (`model-visibility.ts:53-56`).
        assertEquals(listOf("alpha", "delta-20250104"), families.map(ModelFamily::id))
    }

    @Test
    fun `the curated default is the top fifty families, and featured wins where the host ships one`() {
        val many = provider("bulk", *(1..60).map { "model-$it" }.toTypedArray())
        val curated = provider("aggregator", "a", "b", "c", featured = listOf("b"))

        val keys = defaultVisibleKeys(listOf(many, curated))

        assertEquals(DEFAULT_VISIBLE_PER_PROVIDER + 1, keys.size)
        assertTrue(modelVisibilityKey("bulk", "model-50") in keys)
        assertFalse(modelVisibilityKey("bulk", "model-51") in keys)
        // `expandProviderDefaults` prefers the backend shortlist (`:120-132`).
        assertEquals(setOf(modelVisibilityKey("aggregator", "b")), keys.filter { it.startsWith("aggregator::") }.toSet())
    }

    @Test
    fun `a provider the person never touched keeps its curated default`() {
        val providers = listOf(provider("openai", "gpt"), provider("acme", "one", "two"))
        val stored = setOf(modelVisibilityKey("openai", "gpt"))

        val resolved = effectiveVisibleKeys(stored, providers)

        assertTrue(modelVisibilityKey("acme", "one") in resolved)
        assertTrue(modelVisibilityKey("acme", "two") in resolved)
    }

    @Test
    fun `hiding a provider's last model records a sentinel that survives another provider's edit`() {
        val providers = listOf(provider("openai", "gpt"), provider("acme", "one"))

        val hidden = toggleModelVisibility(null, providers, "acme", "one")
        assertTrue(emptyProviderSentinelKey("acme") in hidden)
        // The sentinel is bookkeeping and never displays as a model.
        assertFalse(emptyProviderSentinelKey("acme") in effectiveVisibleKeys(hidden, providers))
        // "Hid everything" is not "never customised": the default must not
        // silently come back (`model-visibility.ts:16-26,150-162`).
        assertFalse(modelVisibilityKey("acme", "one") in effectiveVisibleKeys(hidden, providers))

        val afterOtherEdit = toggleModelVisibility(hidden, providers, "openai", "gpt")
        assertTrue(emptyProviderSentinelKey("acme") in afterOtherEdit)
    }

    @Test
    fun `re-enabling one model clears only that provider's sentinel and restores nothing else`() {
        val providers = listOf(provider("acme", "one", "two"))
        val emptied = setProviderVisibility(null, providers, "acme", visible = false)
        assertTrue(emptyProviderSentinelKey("acme") in emptied)

        val restored = toggleModelVisibility(emptied, providers, "acme", "two")

        assertFalse(emptyProviderSentinelKey("acme") in restored)
        assertEquals(setOf(modelVisibilityKey("acme", "two")), restored)
    }

    @Test
    fun `a provider master switch enables every family and hiding records the sentinel`() {
        val providers = listOf(provider("acme", "one", "one-fast", "two"))

        val all = setProviderVisibility(null, providers, "acme", visible = true)
        // `one-fast` is `one`'s family member, not its own row.
        assertEquals(
            setOf(modelVisibilityKey("acme", "one"), modelVisibilityKey("acme", "two")),
            all,
        )
        assertEquals(ProviderVisibility.All, providerVisibility(providers[0], effectiveVisibleKeys(all, providers)))

        val none = setProviderVisibility(all, providers, "acme", visible = false)
        assertEquals(setOf(emptyProviderSentinelKey("acme")), none)
        assertEquals(ProviderVisibility.None, providerVisibility(providers[0], effectiveVisibleKeys(none, providers)))

        val partial = toggleModelVisibility(none, providers, "acme", "two")
        assertEquals(ProviderVisibility.Some, providerVisibility(providers[0], effectiveVisibleKeys(partial, providers)))
    }

    @Test
    fun `a provider with no models is left empty rather than stranding a sentinel`() {
        val providers = listOf(provider("empty"))

        val all = setProviderVisibility(null, providers, "empty", visible = true)

        assertEquals(emptySet<String>(), all)
    }

    @Test
    fun `the picker shows only the visible set, and search spans the whole catalog`() {
        val providers = listOf(provider("acme", "alpha", "beta", "gamma"))
        val stored = setOf(modelVisibilityKey("acme", "alpha"))
        val visible = effectiveVisibleKeys(stored, providers)

        val collapsed = visibleModelGroups(providers, query = "", visible = visible, current = null)
        assertEquals(listOf("alpha"), collapsed.single().models.map(ModelOption::id))

        // "Search spans every family, regardless of visibility" (`:569-571`).
        val searched = visibleModelGroups(providers, query = "gam", visible = visible, current = null)
        assertEquals(listOf("gamma"), searched.single().models.map(ModelOption::id))
    }

    @Test
    fun `the current model is always offered, in the catalog's own order, until a query narrows`() {
        val providers = listOf(provider("acme", "alpha", "beta", "gamma"))
        val visible = setOf(modelVisibilityKey("acme", "gamma"))

        val kept = visibleModelGroups(
            providers,
            query = "",
            visible = visible,
            current = ComposerModelSelection("beta", "acme"),
        )
        assertEquals(listOf("beta", "gamma"), kept.single().models.map(ModelOption::id))

        // While searching the pin is skipped: a query means "show me matches".
        val searched = visibleModelGroups(
            providers,
            query = "alpha",
            visible = visible,
            current = ComposerModelSelection("beta", "acme"),
        )
        assertEquals(listOf("alpha"), searched.single().models.map(ModelOption::id))
    }

    @Test
    fun `a session running the fast variant keeps its base family on offer`() {
        val providers = listOf(provider("acme", "alpha", "alpha-fast", "beta"))
        val visible = setOf(modelVisibilityKey("acme", "beta"))

        val kept = visibleModelGroups(
            providers,
            query = "",
            visible = visible,
            current = ComposerModelSelection("alpha-fast", "acme"),
        )

        assertEquals(listOf("alpha", "beta"), kept.single().models.map(ModelOption::id))
    }

    @Test
    fun `the picker's default is the curated set, not a bare top-N`() {
        // Desktop resolves visibility against the catalog it fetched and hands
        // `groupModels` a set, never null (`model-catalog-menu.tsx:179-188`), so
        // the never-customised default flows through `expandProviderDefaults`:
        // the host's `featured_models` where a provider ships one, the top 50
        // only where it does not.
        val bulk = provider("bulk", *(1..60).map { "model-$it" }.toTypedArray())
        val curated = provider("curated", "alpha", "beta", "gamma", featured = listOf("beta"))
        val providers = listOf(bulk, curated)

        val groups = visibleModelGroups(
            providers,
            query = "",
            visible = effectiveVisibleKeys(null, providers),
            current = null,
        )

        assertEquals(DEFAULT_VISIBLE_PER_PROVIDER, groups.first().models.size)
        assertEquals(listOf("beta"), groups.last().models.map(ModelOption::id))
    }

    @Test
    fun `the picker drops the virtual MoA row before the shortlist resolves`() {
        // `providers?.filter(provider => provider.slug.toLowerCase() !== 'moa')`
        // (`model-catalog-menu.tsx:172-175`). Resolving over the raw catalog
        // would expand the preset into everyone's curated default, including
        // the customised sets that never named it.
        val providers = listOf(provider("acme", "alpha"), provider("MoA", "council"))
        val offered = pickerProviders(providers)

        assertEquals(listOf("acme"), offered.map(ModelProvider::id))
        assertFalse(effectiveVisibleKeys(null, offered).any { it.startsWith("MoA::") })
        // The Models sheet resolves over the unfiltered list, exactly as
        // Desktop's dialog does (`model-visibility-dialog.tsx:61-66`).
        assertTrue(effectiveVisibleKeys(null, providers).contains(modelVisibilityKey("MoA", "council")))
    }

    @Test
    fun `a provider that appeared after the last customisation is still offered`() {
        // `resolveVisibleKeys` expands any provider with neither a stored key
        // nor a sentinel (`model-visibility.ts:134-165`); reaching the picker
        // with the raw stored set instead would drop that provider entirely.
        val providers = listOf(provider("acme", "alpha", "beta"), provider("newcomer", "delta"))
        val stored = setOf(modelVisibilityKey("acme", "alpha"))

        val groups = visibleModelGroups(
            providers,
            query = "",
            visible = effectiveVisibleKeys(stored, providers),
            current = null,
        )

        assertEquals(listOf("acme", "newcomer"), groups.map(ModelProvider::id))
        assertEquals(listOf("alpha"), groups.first().models.map(ModelOption::id))
        assertEquals(listOf("delta"), groups.last().models.map(ModelOption::id))
    }
}
