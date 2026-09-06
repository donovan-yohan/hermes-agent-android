package com.hermesagent.mobile.plugins

import androidx.compose.runtime.Composable

/**
 * Payload of a `transcript.directives` contribution's `data`.
 *
 * The model addresses the contribution by emitting a paragraph of the form
 * `::name{key="value"}`; the transcript parses that narrow shape and renders
 * the first registered contribution whose [name] matches.
 */
data class TranscriptDirectiveContribution(
    /** The name the model addresses: `::<name>{...}`. */
    val name: String,
    /** Renders the directive leaf inline in the assistant message. */
    val render: @Composable (attrs: Map<String, String>, source: String, streaming: Boolean) -> Unit,
)

