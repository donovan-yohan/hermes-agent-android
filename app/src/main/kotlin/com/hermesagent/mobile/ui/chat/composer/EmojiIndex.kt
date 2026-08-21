package com.hermesagent.mobile.ui.chat.composer

import com.hermesagent.mobile.data.composer.CompletionItem

/** Small bundled offline index; prefix matches are intentionally ranked first. */
internal object EmojiIndex {
    private data class Entry(val emoji: String, val shortcode: String, val aliases: List<String>)

    private val entries = listOf(
        Entry("😀", "grinning", listOf("smile", "happy")),
        Entry("😂", "joy", listOf("laugh", "tears")),
        Entry("👍", "thumbsup", listOf("thumbs_up", "approve", "yes")),
        Entry("👀", "eyes", listOf("look", "review")),
        Entry("✅", "white_check_mark", listOf("check", "done", "success")),
        Entry("🚀", "rocket", listOf("ship", "launch")),
        Entry("🔥", "fire", listOf("hot")),
        Entry("🎉", "tada", listOf("celebrate", "party")),
        Entry("🤔", "thinking", listOf("think")),
        Entry("⚠️", "warning", listOf("warn", "caution")),
    )

    fun search(query: String, limit: Int = 8): List<CompletionItem> {
        val needle = query.trim().removePrefix(":").lowercase()
        val prefix = entries.filter { entry ->
            entry.shortcode.startsWith(needle) || entry.aliases.any { it.startsWith(needle) }
        }
        val loose = entries.filter { entry ->
            entry !in prefix && (entry.shortcode.contains(needle) || entry.aliases.any { it.contains(needle) })
        }
        return (prefix + loose).take(limit).map { entry ->
            CompletionItem(
                text = entry.emoji,
                display = "${entry.emoji}  :${entry.shortcode}:",
                detail = "Emoji",
                kind = "emoji",
            )
        }
    }
}
