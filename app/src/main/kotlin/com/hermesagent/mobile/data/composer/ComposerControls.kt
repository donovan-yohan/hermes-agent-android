package com.hermesagent.mobile.data.composer

/**
 * UI-neutral composer control state.  These types deliberately carry durable
 * configuration only: a runtime session id belongs at the Gateway boundary,
 * and Android URIs/paths never become remote references.
 */
data class ComposerModelSelection(
    val model: String,
    val provider: String = "",
    val source: Source = Source.Default,
) {
    enum class Source { Default, Manual }

    val isSpecified: Boolean get() = model.isNotBlank()
}

sealed interface ReasoningEffort {
    val wireValue: String

    data object None : ReasoningEffort { override val wireValue = "none" }
    data object Minimal : ReasoningEffort { override val wireValue = "minimal" }
    data object Low : ReasoningEffort { override val wireValue = "low" }
    data object Medium : ReasoningEffort { override val wireValue = "medium" }
    data object High : ReasoningEffort { override val wireValue = "high" }
    data object XHigh : ReasoningEffort { override val wireValue = "xhigh" }
    data object Max : ReasoningEffort { override val wireValue = "max" }
    data object Ultra : ReasoningEffort { override val wireValue = "ultra" }
    data class Unknown(override val wireValue: String) : ReasoningEffort

    companion object {
        /**
         * Ascending scale, mirroring the backend's VALID_REASONING_EFFORTS
         * (hermes_constants.py) and Desktop's REASONING_EFFORTS
         * (apps/desktop/src/lib/reasoning-effort.ts). `none` is not a level;
         * it is thinking disabled and owned by its own choice.
         */
        val LEVELS: List<ReasoningEffort> = listOf(Minimal, Low, Medium, High, XHigh, Max, Ultra)

        fun fromWire(raw: String?): ReasoningEffort? = raw?.trim()?.takeIf(String::isNotEmpty)?.let {
            when (it.lowercase()) {
                "none", "off", "false", "disabled" -> None
                "minimal" -> Minimal
                "low" -> Low
                "medium" -> Medium
                "high" -> High
                "xhigh", "extra_high" -> XHigh
                "max" -> Max
                "ultra" -> Ultra
                else -> Unknown(it)
            }
        }
    }
}

sealed interface FastMode {
    val wireValue: String

    data object Normal : FastMode { override val wireValue = "normal" }
    data object Fast : FastMode { override val wireValue = "fast" }
    data class Unknown(override val wireValue: String) : FastMode

    companion object {
        fun fromWire(raw: String?): FastMode? = raw?.trim()?.takeIf(String::isNotEmpty)?.let {
            when (it.lowercase()) {
                "normal", "off" -> Normal
                "fast", "on", "priority" -> Fast
                else -> Unknown(it)
            }
        }
    }
}

data class ModelOption(
    val id: String,
    val label: String = id,
    val supportsReasoning: Boolean = true,
    val supportsFast: Boolean = false,
)

data class ModelProvider(
    val id: String,
    val label: String = id,
    val models: List<ModelOption> = emptyList(),
    /**
     * The backend's curated shortlist for this provider — one flagship per lab,
     * so an aggregator serving dozens of models does not flood the default
     * picker (`hermes_cli/inventory.py:513-568`, `apps/desktop/src/types/
     * hermes.ts:391-395` @ `3ca096de5f8183cb2e0ec23673f294d5978656a3`). Empty
     * for a provider with no manifest entry, which is what makes
     * [com.hermesagent.mobile.data.composer.DEFAULT_VISIBLE_PER_PROVIDER] the
     * fallback rather than the rule.
     */
    val featured: List<String> = emptyList(),
)

data class ModelCatalog(
    val providers: List<ModelProvider> = emptyList(),
    val effectiveSelection: ComposerModelSelection? = null,
)

data class ModelControlsSnapshot(
    val selection: ComposerModelSelection? = null,
    val reasoning: ReasoningEffort? = null,
    val fast: FastMode? = null,
)

data class ComposerControlState(
    val catalog: ModelCatalog,
    val controls: ModelControlsSnapshot,
)

/**
 * Session-scoped effective controls projected from a Gateway `session.info`.
 * Presence flags preserve the difference between an omitted field and an
 * authoritative empty/default value.
 */
data class SessionComposerControls(
    val durableId: String,
    val selection: ComposerModelSelection? = null,
    val hasSelection: Boolean = false,
    val reasoning: ReasoningEffort? = null,
    val hasReasoning: Boolean = false,
    val fast: FastMode? = null,
    val hasFast: Boolean = false,
) {
    fun applyTo(previous: ModelControlsSnapshot): ModelControlsSnapshot = previous.copy(
        selection = if (hasSelection) selection else previous.selection,
        reasoning = if (hasReasoning) reasoning else previous.reasoning,
        fast = if (hasFast) fast else previous.fast,
    )

    fun overlay(newer: SessionComposerControls): SessionComposerControls {
        require(durableId == newer.durableId)
        return copy(
            selection = if (newer.hasSelection) newer.selection else selection,
            hasSelection = hasSelection || newer.hasSelection,
            reasoning = if (newer.hasReasoning) newer.reasoning else reasoning,
            hasReasoning = hasReasoning || newer.hasReasoning,
            fast = if (newer.hasFast) newer.fast else fast,
            hasFast = hasFast || newer.hasFast,
        )
    }
}

/** A snapshotted new-session payload; null means do not override that Gateway default. */
data class NewSessionComposerOverrides(
    val selection: ComposerModelSelection? = null,
    val reasoning: ReasoningEffort? = null,
    val fast: FastMode? = null,
)

sealed interface ControlMutationResult {
    data object Applied : ControlMutationResult
    /** The Gateway accepted a busy-session model selection for the next turn. */
    data object Deferred : ControlMutationResult
    data class Rejected(val safeMessage: String) : ControlMutationResult
}

enum class CompletionTrigger { Slash, At, Emoji }

data class CompletionItem(
    val text: String,
    val display: String = text,
    val detail: String = "",
    val kind: String = "",
)

data class CompletionResult(
    val items: List<CompletionItem> = emptyList(),
    val replaceFrom: Int? = null,
)

sealed interface ComposerReference {
    val value: String
    val wireText: String

    data class File(override val value: String) : ComposerReference {
        override val wireText = "@file:${quoteComposerReferenceValue(value)}"
    }
    data class Folder(override val value: String) : ComposerReference {
        override val wireText = "@folder:${quoteComposerReferenceValue(value)}"
    }
    data class Url(override val value: String) : ComposerReference {
        override val wireText = "@url:${quoteComposerReferenceValue(value)}"
    }
    data class Session(override val value: String) : ComposerReference {
        override val wireText = "@session:${quoteComposerReferenceValue(value)}"
    }
    data class Git(override val value: String) : ComposerReference {
        override val wireText = "@git:${quoteComposerReferenceValue(value)}"
    }
    data class Simple(override val value: String) : ComposerReference {
        override val wireText = "@$value"
    }
}

/** Desktop rich-editor.ts quoteRefValue: typed references always carry a fence. */
fun quoteComposerReferenceValue(value: String): String = when {
    '`' !in value -> "`$value`"
    '"' !in value -> "\"$value\""
    '\'' !in value -> "'$value'"
    else -> value
}
