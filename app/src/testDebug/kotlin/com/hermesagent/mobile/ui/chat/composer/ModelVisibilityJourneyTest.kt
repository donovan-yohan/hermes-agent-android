package com.hermesagent.mobile.ui.chat.composer

import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.hermesagent.mobile.data.composer.ComposerModelSelection
import com.hermesagent.mobile.data.composer.ModelCatalog
import com.hermesagent.mobile.data.composer.ModelControlsSnapshot
import com.hermesagent.mobile.data.composer.ModelOption
import com.hermesagent.mobile.data.composer.ModelProvider
import com.hermesagent.mobile.data.composer.modelVisibilityKey
import com.hermesagent.mobile.data.composer.setProviderVisibility
import com.hermesagent.mobile.data.composer.toggleModelVisibility
import com.hermesagent.mobile.ui.common.WIP_PILL
import com.hermesagent.mobile.ui.theme.AppearanceSelection
import com.hermesagent.mobile.ui.theme.BuiltinThemes
import com.hermesagent.mobile.ui.theme.HermesTheme
import com.hermesagent.mobile.ui.theme.HermesThemeMode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The `Edit models…` row and the Models sheet, against Desktop's catalog footer
 * (`apps/desktop/src/app/shell/model-catalog-menu.tsx:527-535`) and its
 * `ModelVisibilityDialog` (`apps/desktop/src/components/model-visibility-dialog.tsx:81-190`)
 * @ `3ca096de5f8183cb2e0ec23673f294d5978656a3`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class ModelVisibilityJourneyTest {
    @get:Rule
    val compose = createComposeRule()

    private var stored: Set<String>? = null

    private fun launch(initial: Set<String>? = null, catalog: ModelCatalog = CATALOG) {
        stored = initial
        compose.setContent {
            var keys by remember { mutableStateOf(stored) }
            HermesTheme(AppearanceSelection(BuiltinThemes.DEFAULT_NAME, HermesThemeMode.Dark)) {
                ModelControl(
                    catalog = catalog,
                    controls = ModelControlsSnapshot(selection = ComposerModelSelection("alpha", "acme")),
                    isLiveSession = true,
                    isManualNewDraft = false,
                    isLoading = false,
                    error = null,
                    isSaving = false,
                    isDeferred = false,
                    onSelectModel = {},
                    onSelectReasoning = {},
                    onSelectFast = {},
                    visibleModels = keys,
                    onToggleModelVisible = { provider, model ->
                        keys = toggleModelVisibility(keys, catalog.providers, provider, model)
                        stored = keys
                    },
                    onSetProviderModelsVisible = { provider, visible ->
                        keys = setProviderVisibility(keys, catalog.providers, provider, visible)
                        stored = keys
                    },
                )
            }
        }
        compose.waitForIdle()
    }

    private fun openPicker() {
        compose.onNodeWithTag("Composer model control").performClick()
        compose.waitForIdle()
    }

    /**
     * Material3 draws the sheet's scrim with its own accessible name and a click
     * action that requests dismissal, which is the only affordance this sheet
     * offers — Desktop's dialog closes the same way (`model-visibility-dialog.tsx:82`).
     */
    private fun dismissSheet() {
        compose.onNodeWithContentDescription("Close sheet").performClick()
        compose.waitForIdle()
    }

    private fun openModelsSheet() {
        openPicker()
        compose.onNodeWithTag(EDIT_MODELS_TAG).performClick()
        compose.waitForIdle()
    }

    @Test
    fun `Edit models closes the catalog list and never sits above a model row`() {
        launch()
        openPicker()

        // Desktop's trailing row, after a separator (`model-catalog-menu.tsx:527-535`).
        val editModels = compose.onNodeWithTag(EDIT_MODELS_TAG).assertIsDisplayed().getBoundsInRoot().top
        val lastModel = compose
            .onNodeWithContentDescription("Use gamma from Acme")
            .getBoundsInRoot().top
        val reasoning = compose.onNodeWithText("Reasoning").getBoundsInRoot().top

        assertEquals(true, lastModel < editModels)
        // The adapted Reasoning section is this app's, and it comes after.
        assertEquals(true, editModels < reasoning)
        compose.onNodeWithText("Edit models…").assertIsDisplayed()
    }

    @Test
    fun `the Models sheet carries Desktop's title, search and provider rows`() {
        launch()
        openModelsSheet()

        compose.onNodeWithTag(MODEL_VISIBILITY_SHEET_TAG).assertIsDisplayed()
        // `title: 'Models'`, `search: 'Search models'` (`en.ts:2848-2849`).
        compose.onNodeWithText("Models").assertIsDisplayed()
        compose.onNodeWithText("Search models").assertIsDisplayed()
        // Desktop renders the provider name uppercase (`model-visibility-dialog.tsx:127`).
        compose.onNodeWithText("ACME").assertIsDisplayed()
        compose.onNodeWithTag(modelVisibilityToggleTag("acme", "alpha")).assertIsDisplayed()
    }

    @Test
    fun `a model switch hides one model, and the picker stops offering it`() {
        launch()
        openModelsSheet()

        compose.onNodeWithTag(modelVisibilityToggleTag("acme", "beta")).performClick()
        compose.waitForIdle()

        assertEquals(
            setOf(modelVisibilityKey("acme", "alpha"), modelVisibilityKey("acme", "gamma")),
            stored,
        )
        compose
            .onNodeWithTag(modelVisibilityToggleTag("acme", "beta"))
            .assert(hasContentDescription("Show beta"))

        // The second half of the claim: the picker itself, reopened, no longer
        // offers the row. Desktop resolves the same set for its own catalog
        // menu (`model-catalog-menu.tsx:179-188` @ `3ca096de`).
        dismissSheet()
        openPicker()
        compose.onNodeWithContentDescription("Use beta from Acme").assertDoesNotExist()
        compose.onNodeWithContentDescription("Use gamma from Acme").assertIsDisplayed()
    }

    @Test
    fun `a provider added after the last customisation still reaches the picker`() {
        // The stored set names Acme only. Resolving it against the catalog
        // expands the untouched provider's curated default, which is what keeps
        // a newly authenticated provider from vanishing (`:179-188`).
        launch(initial = setOf(modelVisibilityKey("acme", "alpha")), catalog = CATALOG_WITH_NEWCOMER)
        openPicker()

        compose.onNodeWithContentDescription("Use alpha from Acme").assertIsDisplayed()
        compose.onNodeWithContentDescription("Use beta from Acme").assertDoesNotExist()
        compose.onNodeWithContentDescription("Use delta from Newcomer").assertIsDisplayed()
    }

    @Test
    fun `the picker's default is the host's featured shortlist, not a bare top-N`() {
        // Never customised, so the picker resolves the default itself
        // (`model-catalog-menu.tsx:179-188` @ `3ca096de`) and
        // `expandProviderDefaults` cuts the provider that ships a
        // `featured_models` manifest down to it (`model-visibility.ts:114-132`).
        launch(catalog = CATALOG_WITH_FEATURED)
        openPicker()

        compose.onNodeWithContentDescription("Use two from Curated").assertIsDisplayed()
        compose.onNodeWithContentDescription("Use one from Curated").assertDoesNotExist()
        compose.onNodeWithContentDescription("Use three from Curated").assertDoesNotExist()
        // A provider the manifest says nothing about keeps every family it ships.
        compose.onNodeWithContentDescription("Use four from Plain").assertIsDisplayed()
    }

    @Test
    fun `the picker never offers a MoA preset, and the Models sheet still lists one`() {
        // The Gateway ships MoA presets as a virtual `moa` provider whose
        // "models" are preset names (`hermes_cli/inventory.py:1000-1015`).
        // Desktop keeps that row out of the picker's groups
        // (`model-catalog-menu.tsx:172-175`) and renders presets as their own
        // section; presets are not ported here, so resolving the shortlist over
        // that row would expand a preset into the curated default and offer it
        // where a model id belongs.
        launch(catalog = CATALOG_WITH_MOA)
        openPicker()

        compose.onNodeWithContentDescription("Use council from Mixture of Agents").assertDoesNotExist()
        compose.onNodeWithText("Mixture of Agents").assertDoesNotExist()
        compose.onNodeWithContentDescription("Use alpha from Acme").assertIsDisplayed()

        // Desktop's own dialog filters nothing (`model-visibility-dialog.tsx:61-64`),
        // so the row a person can still curate stays curatable.
        compose.onNodeWithTag(EDIT_MODELS_TAG).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(modelVisibilityToggleTag("moa", "council")).assertIsDisplayed()
    }

    @Test
    fun `the provider checkbox bulk-toggles, and reports its partial state`() {
        launch()
        openModelsSheet()

        // Hiding everything is a choice the sentinel records, so the defaults
        // are not silently re-expanded (`model-visibility.ts:16-26`).
        compose.onNodeWithTag(providerVisibilityToggleTag("acme")).performClick()
        compose.waitForIdle()
        assertEquals(setOf("acme::"), stored)
        compose.onNodeWithContentDescription("Show every Acme model")
            .assert(hasStateDescription("Off"))

        // From empty or partial, Desktop's tri-state turns everything on
        // (`next !== false`, `model-visibility-dialog.tsx:142`).
        compose.onNodeWithTag(providerVisibilityToggleTag("acme")).performClick()
        compose.waitForIdle()
        assertEquals(
            setOf("alpha", "beta", "gamma").map { modelVisibilityKey("acme", it) }.toSet(),
            stored,
        )

        compose.onNodeWithTag(modelVisibilityToggleTag("acme", "beta")).performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Show every Acme model")
            .assert(hasStateDescription("Partly on"))
    }

    @Test
    fun `Add provider ships visible and disabled behind the marker chip`() {
        launch()
        openModelsSheet()

        // `addProvider: 'Add provider…'` (`en.ts:2851`). Provider setup is not
        // ported, so the control is present and marked rather than missing.
        compose.onNodeWithText("Add provider…").assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithTag(WIP_PILL, useUnmergedTree = true).assertIsDisplayed()
    }

    private companion object {
        val CATALOG = ModelCatalog(
            providers = listOf(
                ModelProvider(
                    "acme",
                    "Acme",
                    listOf(ModelOption("alpha"), ModelOption("beta"), ModelOption("gamma")),
                ),
            ),
            effectiveSelection = ComposerModelSelection("alpha", "acme"),
        )

        /** [CATALOG] plus a provider authenticated after the last edit. */
        val CATALOG_WITH_NEWCOMER = CATALOG.copy(
            providers = CATALOG.providers + ModelProvider("newcomer", "Newcomer", listOf(ModelOption("delta"))),
        )

        /** A host that ships a `featured_models` manifest for one provider. */
        val CATALOG_WITH_FEATURED = ModelCatalog(
            providers = listOf(
                ModelProvider(
                    "curated",
                    "Curated",
                    listOf(ModelOption("one"), ModelOption("two"), ModelOption("three")),
                    featured = listOf("two"),
                ),
                ModelProvider("plain", "Plain", listOf(ModelOption("four"))),
            ),
            effectiveSelection = ComposerModelSelection("two", "curated"),
        )

        /** [CATALOG] plus the Gateway's virtual Mixture-of-Agents row. */
        val CATALOG_WITH_MOA = CATALOG.copy(
            providers = CATALOG.providers +
                ModelProvider("moa", "Mixture of Agents", listOf(ModelOption("council"))),
        )
    }
}
