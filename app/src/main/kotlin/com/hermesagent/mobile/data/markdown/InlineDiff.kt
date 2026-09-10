package com.hermesagent.mobile.data.markdown

/**
 * The gateway's rendered `inline_diff`, turned into something a phone can paint.
 *
 * `tool.complete` carries `inline_diff` as the joined output of Hermes'
 * *terminal* diff renderer (`tui_gateway/tool_progress.py:233-237` @
 * `72a3277cd7937fd0f0a2a3e3fddbed21d7b1c8bd`). That payload is written for a
 * TTY, so it arrives with three kinds of chrome no GUI client wants:
 *
 *  - a leading `  ┊ review diff` banner (`agent/display.py:656-664` @ the same
 *    SHA), sometimes itself wrapped in `ESC[33m … ESC[0m`;
 *  - truecolour SGR around every classified line — `ESC[38;2;r;g;bm` for the
 *    foreground and, on `+`/`-` lines, a tinted `ESC[48;2;r;g;bm` background
 *    (`agent/display.py:63-81,84-105,672-690`);
 *  - `@@` hunk headers, a collapsed `a/x → b/x` arrow line in place of the
 *    `---`/`+++` pair, and a `… omitted N diff line(s)` trailer when the
 *    renderer capped the payload (`agent/display.py:703-731`).
 *
 * Desktop's tool row does exactly this cleanup before rendering, and this file
 * is a port of the two places it lives:
 * `apps/desktop/src/components/assistant-ui/tool/fallback-model/index.ts` and
 * `apps/desktop/src/components/chat/diff-lines.tsx`, both @
 * `72a3277cd7937fd0f0a2a3e3fddbed21d7b1c8bd`.
 *
 * Nothing here imports Compose: the classification is data, and the panel that
 * paints it is the only thing that should need a theme.
 */

/** What one rendered diff line *is*. `diff-lines.tsx:69-79` @ `72a3277cd7`. */
enum class DiffKind { Add, Remove, Context }

/**
 * One renderable line: its kind, and its text with the `+`/`-`/space gutter
 * already removed. `diff-lines.tsx:169-215` @ `72a3277cd7`.
 *
 * Desktop also carries `oldNo`/`newNo` on this record so its *preview* pane can
 * draw a line-number gutter. The compact panel inside a tool card renders no
 * numbers (`diff-lines.tsx:583-641`), and that compact panel is the only shape
 * this app ships, so the two fields are deliberately absent rather than
 * computed and dropped.
 */
data class DiffLine(val kind: DiffKind, val text: String)

/** How many lines a diff adds and removes. `index.ts:41-44` @ `72a3277cd7`. */
data class DiffLineStats(val added: Int, val removed: Int)

/**
 * `index.ts:775-781` @ `72a3277cd7` — the exact three steps, in order: strip
 * every escape, drop a leading `┊ review diff` banner line, trim.
 *
 * The banner match is deliberately loose about whitespace on both sides and
 * case-insensitive about the words, because the renderer emits it with two
 * leading spaces and the TUI wraps it in `ESC[33m` (`agent/display.py:661`;
 * `ui-tui/src/__tests__/createGatewayEventHandler.test.ts:758`). [stripAnsi]
 * has already removed that wrapper by the time the pattern runs, so the pattern
 * only has to survive the spacing.
 *
 * Only *one* banner is removed, and only if it is the first line — upstream's
 * regex is unanchored at the end and not global, and a `┊ review diff` that
 * turned up mid-payload is file content, not chrome.
 */
fun stripInlineDiffChrome(value: String): String {
    if (value.isEmpty()) return ""
    return stripAnsi(value).replace(REVIEW_DIFF_BANNER, "").trim()
}

/**
 * Count added and removed lines. `index.ts:46-59` @ `72a3277cd7`.
 *
 * Counted on the *cleaned* diff and on raw lines — before markers are stripped
 * and before the header zone is skipped, exactly as upstream: `+` that is not
 * `+++`, `-` that is not `---`.
 */
fun countDiffLineStats(diff: String): DiffLineStats {
    var added = 0
    var removed = 0
    for (line in diff.split('\n')) {
        if (line.startsWith("+") && !line.startsWith("+++")) {
            added += 1
        } else if (line.startsWith("-") && !line.startsWith("---")) {
            removed += 1
        }
    }
    return DiffLineStats(added = added, removed = removed)
}

/**
 * Drop the file-header preamble. `diff-lines.tsx:114-134` @ `72a3277cd7`.
 *
 * A git-style unified diff opens with `diff --git`, `index …`, `--- a/path`,
 * `+++ b/path`; Hermes' renderer collapses that pair into its own `a/path →
 * b/path` arrow line instead. All of it just repeats the path the tool row
 * already shows in its header, and for an absolute path it repeats it badly
 * (`a//Users/…`). Skip the leading zone — blank lines, arrow lines, known
 * prefixes — and stop at the first line that is none of those, which for a real
 * payload is the first `@@`.
 */
fun stripDiffFileHeaders(diff: String): String {
    val lines = diff.split('\n')
    var start = 0
    while (start < lines.size) {
        val line = lines[start]
        if (line.isBlank() || isArrowHeaderLine(line) || DIFF_HEADER_PREFIXES.any(line::startsWith)) {
            start += 1
            continue
        }
        break
    }
    return lines.subList(start, lines.size).joinToString("\n")
}

/**
 * Cleaned diff → renderable lines. `diff-lines.tsx:169-215` @ `72a3277cd7`.
 *
 * File headers and `@@` headers are dropped, a blank context line separates one
 * hunk from the next, gutter markers are stripped and the kind is recorded.
 * When the payload carries no parseable hunk at all — which is what a `write_file`
 * of a brand-new file looks like — every remaining line is classified in place
 * (`diff-lines.tsx:172-177`), so nothing is silently swallowed.
 */
fun parseDiff(diff: String): List<DiffLine> {
    val hunks = parseHunks(diff)

    if (hunks.isEmpty()) {
        // Fallback for unexpected non-hunk payloads (`diff-lines.tsx:172-177`).
        return stripDiffFileHeaders(diff).split('\n').map { DiffLine(diffKind(it), stripDiffMarker(it)) }
    }

    val out = ArrayList<DiffLine>()
    var emitted = false
    for (hunk in hunks) {
        if (emitted) out.add(DiffLine(DiffKind.Context, ""))
        for (line in hunk) {
            out.add(line)
            emitted = true
        }
    }
    return out
}

/**
 * `index.ts:778` @ `72a3277cd7` — `/^\s*┊\s*review diff\s*\n/i`.
 *
 * Kotlin's `^` and JavaScript's agree without a multiline flag (start of input),
 * and both `\s` classes include the newline, so the greedy `\s*\n` tail backs
 * off to the last newline the same way in either engine.
 */
private val REVIEW_DIFF_BANNER = Regex("""^\s*┊\s*review diff\s*\n""", RegexOption.IGNORE_CASE)

/** `diff-lines.tsx:96-105` @ `72a3277cd7`. Matched against the raw line. */
private val DIFF_HEADER_PREFIXES = listOf(
    "diff --git",
    "index ",
    "--- ",
    "+++ ",
    "similarity ",
    "rename ",
    "new file",
    "deleted file",
)

/** `diff-lines.tsx:110` @ `72a3277cd7`. */
private val ARROW_HEADER = Regex("""^\S.*→\s*\S+$""")

/** `diff-lines.tsx:110` @ `72a3277cd7` — a diff line is never a header. */
private val DIFF_MARKER_START = Regex("""^[+\-@]""")

/** `diff-lines.tsx:141` @ `72a3277cd7`. */
private val HUNK_HEADER = Regex("""@@ -(\d+)(?:,\d+)? \+(\d+)(?:,\d+)? @@""")

/**
 * Hermes' collapsed `a/path → b/path` line. `diff-lines.tsx:107-111` @
 * `72a3277cd7`.
 */
internal fun isArrowHeaderLine(line: String): Boolean {
    val trimmed = line.trim()
    if (trimmed.length > MAX_ARROW_HEADER_LENGTH) return false
    return trimmed.contains('→') && ARROW_HEADER.matches(trimmed) && !DIFF_MARKER_START.containsMatchIn(trimmed)
}

/**
 * The longest line still worth testing against [ARROW_HEADER].
 *
 * `^\S.*→\s*\S+$` is quadratic on a hostile line: the greedy `.*` walks back
 * over every `→` in turn, and each attempt re-scans the tail. A 32 KB line of
 * arrows — the ingest cap, `GatewaySessionRepository.MAX_TOOL_PAYLOAD` — costs
 * seconds, and this runs inside a `remember {}` on the composition thread.
 * `AGENTS.md` treats tool output as untrusted, so the size of the input has to
 * bound the work rather than the shape of it.
 *
 * A cap is honest here because of what the line *is*: two file paths and an
 * arrow. `PATH_MAX` is 4096 on Linux, so 1024 already refuses nothing a real
 * header could carry, and the regex over 1 KB is microseconds. Desktop has no
 * cap (`diff-lines.tsx:110` @ `72a3277cd7`) — a divergence ledgered in
 * `docs/parity/tool-output-fidelity.md`.
 */
private const val MAX_ARROW_HEADER_LENGTH = 1024

/** `diff-lines.tsx:69-79` @ `72a3277cd7`. */
internal fun diffKind(line: String): DiffKind = when {
    line.startsWith("+") && !line.startsWith("+++") -> DiffKind.Add
    line.startsWith("-") && !line.startsWith("---") -> DiffKind.Remove
    else -> DiffKind.Context
}

/**
 * Drop the leading `+`/`-`/space gutter. `diff-lines.tsx:83-89` @ `72a3277cd7`.
 *
 * Changes read by colour alone, and the rest of the indentation is kept. A
 * context line only loses a character when it actually *has* the space gutter:
 * a trailer like `… omitted 3 diff line(s)` is passed through whole.
 */
internal fun stripDiffMarker(line: String): String =
    if (diffKind(line) != DiffKind.Context || line.startsWith(" ")) line.drop(1) else line

/**
 * Split the body into hunks. `diff-lines.tsx:135-166` @ `72a3277cd7`.
 *
 * A `@@` line whose header does not parse closes the current hunk rather than
 * opening one, so its body is dropped instead of being mislabelled; `\` lines
 * (git's `\ No newline at end of file`) are skipped; anything before the first
 * hunk is skipped. Each hunk is returned as its classified lines — the
 * old/new line numbers upstream tracks here feed a gutter this app's compact
 * panel does not draw (see [DiffLine]).
 */
private fun parseHunks(diff: String): List<List<DiffLine>> {
    val hunks = ArrayList<ArrayList<DiffLine>>()
    var active: ArrayList<DiffLine>? = null

    for (line in stripDiffFileHeaders(diff).split('\n')) {
        if (line.startsWith("@@")) {
            if (HUNK_HEADER.find(line) == null) {
                active = null
                continue
            }
            active = ArrayList()
            hunks.add(active)
            continue
        }
        val current = active ?: continue
        if (line.startsWith("\\")) continue
        current.add(DiffLine(diffKind(line), stripDiffMarker(line)))
    }

    return hunks
}
