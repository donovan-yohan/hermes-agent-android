package com.hermesagent.mobile.data.prefs

import com.hermesagent.mobile.data.composer.ComposerModelSelection
import com.hermesagent.mobile.data.composer.FastMode
import com.hermesagent.mobile.data.composer.ReasoningEffort
import java.security.MessageDigest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * Identity supplied by the connection owner. The persisted key is a digest so
 * a DataStore key never repeats an endpoint/profile identifier.
 */
data class ComposerControlsScope(
    val connectionIdentity: String,
    val profileIdentity: String,
) {
    init {
        require(connectionIdentity.isNotBlank())
    }

    internal fun storageKey(): String = MessageDigest.getInstance("SHA-256")
        .digest("$connectionIdentity\u0000$profileIdentity".toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

/** Only a deliberate new-draft choice is durable; Gateway defaults are not cached here. */
data class NewDraftComposerPreference(
    val selection: ComposerModelSelection? = null,
    val reasoning: ReasoningEffort? = null,
    val fast: FastMode? = null,
) {
    init {
        require(selection?.source != ComposerModelSelection.Source.Default)
    }
}

interface ComposerControlsStore {
    /** Current connection/profile scope, owned by persisted connection settings. */
    val activeScope: Flow<ComposerControlsScope>

    fun preference(scope: ComposerControlsScope): Flow<NewDraftComposerPreference?>

    suspend fun saveManual(scope: ComposerControlsScope, preference: NewDraftComposerPreference)
    suspend fun clearManual(scope: ComposerControlsScope)

    /**
     * Which `provider::model` keys the picker offers, or null while the person
     * has never customised the list — in which case the curated default
     * applies. Desktop keeps the same nullable set in one `localStorage` entry
     * (`apps/desktop/src/store/model-visibility.ts:6,87-89` @
     * `3ca096de5f8183cb2e0ec23673f294d5978656a3`); it is scoped per connection
     * here for the same reason a saved model pick is — two Gateways are two
     * catalogs, and a shortlist that crossed between them would name models the
     * other host does not have.
     */
    fun visibleModels(scope: ComposerControlsScope): Flow<Set<String>?> = flowOf(null)

    suspend fun saveVisibleModels(scope: ComposerControlsScope, keys: Set<String>) = Unit
}

/** Small in-memory implementation for ViewModel and pure persistence tests. */
internal class TransientComposerControlsStore(
    initialScope: ComposerControlsScope = ComposerControlsScope("transient", "default"),
) : ComposerControlsStore {
    private val state = MutableStateFlow<Map<ComposerControlsScope, NewDraftComposerPreference>>(emptyMap())
    private val visible = MutableStateFlow<Map<ComposerControlsScope, Set<String>>>(emptyMap())
    private val scope = MutableStateFlow(initialScope)
    override val activeScope: Flow<ComposerControlsScope> = scope

    override fun visibleModels(scope: ComposerControlsScope): Flow<Set<String>?> =
        visible.map { it[scope] }

    override suspend fun saveVisibleModels(scope: ComposerControlsScope, keys: Set<String>) {
        visible.value = visible.value + (scope to keys.toSet())
    }

    override fun preference(scope: ComposerControlsScope): Flow<NewDraftComposerPreference?> =
        state.map { it[scope] }

    override suspend fun saveManual(scope: ComposerControlsScope, preference: NewDraftComposerPreference) {
        state.value = state.value + (scope to preference.asManual())
    }

    override suspend fun clearManual(scope: ComposerControlsScope) {
        state.value = state.value - scope
    }
}

internal fun NewDraftComposerPreference.asManual(): NewDraftComposerPreference = copy(
    selection = selection?.copy(source = ComposerModelSelection.Source.Manual),
)
