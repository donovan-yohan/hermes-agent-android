package com.hermesagent.mobile.data.session

/**
 * Session context window and usage statistics models.
 *
 * Pinned to upstream `apps/desktop/src/types/hermes.ts:718-727,770-784` @
 * `3ca096de5f8183cb2e0ec23673f294d5978656a3`.
 */
data class SessionUsage(
    val contextUsed: Long? = null,
    val contextMax: Long? = null,
    val contextPercent: Int? = null,
    val total: Long = 0,
    val input: Long = 0,
    val output: Long = 0,
    val calls: Int = 0,
    val model: String = "",
)

data class ContextUsageCategory(
    val id: String,
    val label: String,
    val tokens: Long,
    val color: String,
)

data class ContextBreakdown(
    val categories: List<ContextUsageCategory> = emptyList(),
    val contextMax: Long = 0,
    val contextPercent: Int = 0,
    val contextUsed: Long = 0,
    val estimatedTotal: Long = 0,
    val model: String = "",
)

/**
 * UI state for the Context Meter rendered in the chat header.
 */
data class ContextMeterState(
    val label: String,
    val detail: String,
    val usage: SessionUsage,
    val breakdown: ContextBreakdown?,
    val loading: Boolean = false,
)
