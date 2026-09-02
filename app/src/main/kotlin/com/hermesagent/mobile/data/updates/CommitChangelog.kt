package com.hermesagent.mobile.data.updates

/**
 * Desktop's user-facing changelog builder, ported line for line from
 * `apps/desktop/src/lib/commit-changelog.ts` @
 * `3ca096de5f8183cb2e0ec23673f294d5978656a3`.
 *
 * It takes the raw commit subjects the Gateway's update check returns
 * (`hermes_cli/web_server.py:5157-5208` @ the pin), parses the Conventional
 * Commits 1.0 header, drops internal noise, and groups the rest into the four
 * buckets a person reading release notes actually cares about.
 *
 * Ported rather than re-invented, and tested against Desktop's own fixtures
 * (`commit-changelog.test.ts` @ the pin), because the *output* is product copy:
 * two implementations of "what a person sees in the update sheet" would drift
 * into two different products. The group labels are hard-coded English there
 * (`commit-changelog.ts:40-45`) — not routed through `en.ts` — so they are
 * hard-coded here too rather than invented in this app's own voice.
 */

/** Desktop's five buckets, in its own render order (`commit-changelog.ts:40-46`). */
enum class CommitGroupId(val label: String) {
    New("What's new"),
    Fixed("Fixed"),
    Faster("Faster"),
    Improved("Improved"),
    Other("Other improvements"),
}

/** One rendered bucket: a label and the tidied subjects under it. */
data class CommitGroup(val id: CommitGroupId, val label: String, val items: List<String>)

/** A Conventional Commits 1.0 header as far as this changelog reads it. */
data class ParsedCommit(
    val type: String?,
    val scope: String?,
    val breaking: Boolean,
    val subject: String,
)

/**
 * The neutral placeholder for an update whose every commit was filtered or
 * unreadable (`commit-changelog.ts:79`). Its label is deliberately *not* one of
 * [CommitGroupId]'s: "In this update" is what Desktop says when it cannot say
 * anything more specific.
 */
internal const val FALLBACK_GROUP_LABEL = "In this update"
internal const val FALLBACK_GROUP_ITEM = "Improvements and fixes"

private val FALLBACK_GROUP = CommitGroup(
    id = CommitGroupId.Other,
    label = FALLBACK_GROUP_LABEL,
    items = listOf(FALLBACK_GROUP_ITEM),
)

private val TYPE_TO_GROUP: Map<String, CommitGroupId> = mapOf(
    "feat" to CommitGroupId.New,
    "feature" to CommitGroupId.New,
    "fix" to CommitGroupId.Fixed,
    "bugfix" to CommitGroupId.Fixed,
    "hotfix" to CommitGroupId.Fixed,
    "revert" to CommitGroupId.Fixed,
    "perf" to CommitGroupId.Faster,
    "performance" to CommitGroupId.Faster,
    "refactor" to CommitGroupId.Improved,
    "a11y" to CommitGroupId.Improved,
    "ui" to CommitGroupId.Improved,
    "ux" to CommitGroupId.Improved,
)

/** Types a person reading release notes does not want to read (`:63-77`). */
private val HIDDEN_TYPES = setOf(
    "build", "chore", "ci", "dep", "deps", "doc", "docs",
    "lint", "release", "style", "test", "tests", "wip",
)

private val CONVENTIONAL_HEADER =
    Regex("""^(?<type>[a-zA-Z][a-zA-Z0-9_-]*)(?:\((?<scope>[^)]+)\))?(?<bang>!)?:\s+(?<subject>.+)$""")

private val WHITESPACE_RUN = Regex("""\s+""")
private val TRAILING_PUNCTUATION = Regex("""[.;,\s]+$""")

/** Parse a single commit header line per Conventional Commits 1.0 (`:84-103`). */
fun parseCommitHeader(raw: String): ParsedCommit {
    val header = raw.split('\n', limit = 2).first().removeSuffix("\r").trim()
    if (header.isEmpty()) {
        return ParsedCommit(type = null, scope = null, breaking = false, subject = "")
    }
    val match = CONVENTIONAL_HEADER.matchEntire(header)
        ?: return ParsedCommit(type = null, scope = null, breaking = false, subject = header)
    return ParsedCommit(
        type = match.groups["type"]!!.value.lowercase(),
        scope = match.groups["scope"]?.value,
        breaking = match.groups["bang"] != null,
        subject = match.groups["subject"]!!.value.trim(),
    )
}

/**
 * Collapse runs of whitespace, drop trailing punctuation, capitalise (`:105-116`).
 *
 * Capitalisation is `charAt(0).toUpperCase()` there. This uses
 * [Char.uppercaseChar] rather than a locale titlecase for the same reason:
 * one character in, one character out, and no locale — a Turkish device must not
 * turn `i` into `İ` in a changelog written in English by the host.
 */
private fun tidySubject(subject: String): String {
    val cleaned = TRAILING_PUNCTUATION.replace(WHITESPACE_RUN.replace(subject, " "), "").trim()
    if (cleaned.isEmpty()) return cleaned
    return cleaned.replaceFirstChar { it.uppercaseChar() }
}

/**
 * Build the small grouped changelog Desktop's update sheet renders (`:123-178`).
 *
 * Always at least one group: everything filtered or unreadable falls back to
 * [FALLBACK_GROUP]. The caps are Desktop's defaults, and they are what the
 * "+ N more changes included." line at the bottom of the sheet is measured
 * against.
 */
fun buildCommitChangelog(
    commits: List<String>,
    maxGroups: Int = MAX_GROUPS,
    maxPerGroup: Int = MAX_PER_GROUP,
    maxTotal: Int = MAX_TOTAL,
): List<CommitGroup> {
    // A LinkedHashMap because the JS `Map` it mirrors is insertion-ordered and
    // that order is what survives into the stable sort below.
    val groups = LinkedHashMap<CommitGroupId, MutableList<String>>()
    val seen = HashSet<String>()
    var total = 0

    for (commit in commits) {
        if (total >= maxTotal) break
        val parsed = parseCommitHeader(commit)
        if (parsed.type != null && parsed.type in HIDDEN_TYPES) continue
        val groupId = parsed.type?.let { TYPE_TO_GROUP[it] ?: CommitGroupId.Other } ?: CommitGroupId.Other
        val subject = tidySubject(parsed.subject)
        if (subject.isEmpty()) continue
        // Case-insensitive, so the same fix written twice with different
        // capitalisation is one line rather than two.
        val dedupeKey = subject.lowercase()
        if (dedupeKey in seen) continue
        val bucket = groups[groupId] ?: mutableListOf()
        // A subject dropped for a full bucket does *not* claim the dedupe key,
        // and its group is not created empty: Desktop pushes, stores and marks
        // seen only after this check (`:156-165`). Mirrored exactly, because
        // the alternative is two clients reading one host and listing
        // different changes.
        if (bucket.size >= maxPerGroup) continue
        bucket.add(subject)
        groups[groupId] = bucket
        seen.add(dedupeKey)
        total += 1
    }

    if (groups.isEmpty()) return listOf(FALLBACK_GROUP)

    return groups.entries
        .sortedBy { it.key.ordinal }
        .take(maxGroups)
        .map { (id, items) -> CommitGroup(id = id, label = id.label, items = items.toList()) }
}

/** How many items the sheet ended up showing, for the "+ N more" line. */
fun List<CommitGroup>.totalItems(): Int = sumOf { it.items.size }

internal const val MAX_GROUPS = 3
internal const val MAX_PER_GROUP = 4
internal const val MAX_TOTAL = 6
