package com.hermesagent.mobile.data.gateway

/** Immutable owner identity captured by a rendered backend admission row. Memory only. */
enum class GatewayAdmissionStatus { Queued, Unknown }
data class GatewayAdmissionKey(
    val connectionGeneration: Long,
    val endpointGeneration: Long,
    val runtimeSessionId: String,
    val admissionId: String,
    val status: GatewayAdmissionStatus,
    val executionGeneration: Long?,
)
