package com.hermesagent.mobile.data.markdown

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The one diff a file-edit tool row renders, from whichever field actually
 * carries it.
 *
 * Desktop resolves this in two steps and both are load-bearing
 * (`apps/desktop/src/components/assistant-ui/tool/fallback.tsx:375-392` @
 * `437116f9497c80d242ce034ff7f5d81dc277a337`):
 *
 * ```ts
 * const inlineDiff = stripInlineDiffChrome(sideDiff) || inlineDiffFromResult(toolResultRecord(stablePart))
 * const defaultOpen = Boolean(inlineDiff)
 * ```
 *
 * The live side-channel wins when it carries anything usable; otherwise the
 * **tool result** is decoded and read for `inline_diff` and then `diff`
 * (`fallback-model/index.ts:824-835` @ the same SHA), because a Gateway that
 * reports a patch only in its result object — `patch_tool` returns
 * `{"success":true,"diff":"…"}` (`tools/file_operations.py:1355-1357` @ the
 * same SHA) — or in a result that is itself a JSON *string*, still produced a
 * reviewable change. Without that second read a perfectly good patch reached the
 * generic collapsed disclosure and painted raw JSON, which is the bug this
 * exists to close.
 *
 * Everything is decided on the **chrome-stripped** text, so one value drives the
 * panel, its `+N`/`−N` stats, its syntax language and what Copy hands over —
 * four readers of one reading, not four readings of one wire field.
 *
 * It never invents one. A blank or malformed payload yields null, and nothing is
 * ever rebuilt from a tool's arguments: Desktop itself cannot reconstruct a
 * reloaded `write_file` because the before/after snapshot is not persisted in
 * the result (`fallback.tsx:546-553` @ the same SHA), and a diff composed from
 * `args.content` would claim an overwrite that was never applied. See
 * `docs/parity/tool-output-fidelity.md` for the new-file caveat.
 */
internal fun resolveEffectiveInlineDiff(sideDiff: String?, resultText: String?): String? {
    usableInlineDiff(sideDiff)?.let { return it }
    return inlineDiffFromResult(resultText)
}

/**
 * A candidate diff, chrome-stripped, or null when there is nothing to paint.
 *
 * Desktop tests the *raw* value and then strips (`index.ts:824-835`), so a
 * payload that is chrome and nothing else — a bare `  ┊ review diff` banner, or
 * a run of SGR around no text — yields `""` there and `Boolean(inlineDiff)` is
 * false, so no panel opens. Deciding on the stripped value here is the same
 * outcome by a shorter route, and it makes the returned string the value every
 * consumer reads rather than a second strip each of them has to remember.
 */
internal fun usableInlineDiff(value: String?): String? =
    stripInlineDiffChrome(value.orEmpty()).takeIf(String::isNotBlank)

/** Whether [value] is a diff a row would render as a panel. */
internal fun isUsableInlineDiff(value: String?): Boolean = usableInlineDiff(value) != null

/**
 * `inlineDiffFromResult` (`fallback-model/index.ts:824-835` @
 * `437116f9497c80d242ce034ff7f5d81dc277a337`), on the shape a result reaches
 * this app in.
 *
 * `parseMaybeObject(result)` there is `toolResultRecord` on Desktop: the result
 * may already be an object, or be the JSON *text* a string-typed result carries
 * (`lib/tool-result-metadata.ts:23-33` @ the same SHA). Both are read here, and
 * `inline_diff` is tried before `diff` — Desktop's order, so a payload carrying
 * both renders the one the sender considered canonical.
 *
 * Each candidate has to leave a non-blank diff after chrome-stripping: a
 * `"diff": {}`, a `"diff": ""` and a `"diff": 3` are all "no diff", never an
 * empty panel.
 */
private fun inlineDiffFromResult(resultText: String?): String? {
    val record = resultText.asResultRecord() ?: return null
    for (key in RESULT_DIFF_KEYS) {
        usableInlineDiff(record[key].asJsonString())?.let { return it }
    }
    return null
}

/**
 * A result as an object: the object itself, or the JSON text a string-typed
 * result carries. A result that is neither — including a malformed string — is
 * null, which is what `toolResultRecord` produces for it.
 */
private fun String?.asResultRecord(): JsonObject? {
    val text = this?.takeIf(String::isNotBlank) ?: return null
    return runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonObject
}

/** A JSON string value, or null for every other shape a result field can take. */
private fun JsonElement?.asJsonString(): String? = when (this) {
    null, JsonNull -> null
    else -> (this as? JsonPrimitive)?.takeIf { it.isString }?.content
}

/** `fallback-model/index.ts:827` @ the same SHA, in its order. */
private val RESULT_DIFF_KEYS = listOf("inline_diff", "diff")
