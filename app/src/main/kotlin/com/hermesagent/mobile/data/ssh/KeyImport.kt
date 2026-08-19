package com.hermesagent.mobile.data.ssh

/**
 * Why an imported key document was refused.
 *
 * Every branch is something the screen has a sentence for, because the failure
 * mode this replaces was silence: a `runCatching { … }.getOrNull()` that turned
 * an unreadable file, an oversized file and a text file that merely *mentions*
 * a private key into the same nothing-happened.
 */
enum class KeyImportProblem {
    /** The document could not be opened or read through the picker. */
    Unreadable,

    /** Longer than [MAX_KEY_BYTES]; a private key is a few kilobytes. */
    TooLarge,

    /** Read fine, but it is not a PEM private key. */
    NotAPrivateKey,
    ;

    fun message(): String = when (this) {
        Unreadable -> "That file could not be read. Pick it again, or export the key somewhere this " +
            "app can open."

        TooLarge -> "That file is larger than a private key ever is, so it was not read. Pick the " +
            "key file itself, not an archive or a disk image."

        NotAPrivateKey -> "That file is not an OpenSSH or PKCS#8 private key. It has to start with " +
            "`-----BEGIN … PRIVATE KEY-----` and end with the matching `-----END … PRIVATE KEY-----`."
    }
}

/** A private key is a few kilobytes; nothing bigger is read into memory. */
const val MAX_KEY_BYTES: Int = 64 * 1024

/**
 * Whether this text is a PEM private key, structurally.
 *
 * Deliberately stricter than "contains `PRIVATE KEY`": that accepted any
 * document with the phrase in it — a README, a blog post, a shell history —
 * and reported it on screen as a loaded key, so the failure only surfaced later
 * as an authentication error with no explanation. The whole document has to be
 * the key: a header first, a matching footer last, and a body between them.
 * That is also what sshj's own format detection requires, so accepting less
 * here would only move the failure later.
 *
 * It takes a [CharSequence] so a `char[]` can be checked through a
 * `CharBuffer` view without minting a `String` copy of the key that nothing can
 * wipe. It says nothing about whether the key parses or which algorithm it is;
 * sshj decides that when the probe uses it, and there is no reason to build a
 * second, weaker parser here.
 */
fun looksLikePrivateKey(text: CharSequence): Boolean = PEM_PRIVATE_KEY.matches(text)

/**
 * A display name safe to show and to persist.
 *
 * The name comes from a content provider, which means it comes from another
 * app: it can carry control characters, bidirectional overrides that make
 * `key.txt` render as `key.exe`, newlines that break a row into two, or a
 * kilobyte of padding. None of that is a file name, so none of it survives.
 */
fun sanitizeKeyDisplayName(raw: String?): String {
    val cleaned = raw.orEmpty()
        .filterNot(Char::isUnsafeInAName)
        .replace(WHITESPACE_RUN, " ")
        .trim()
        .take(MAX_NAME_CHARS)
        .trim()

    return cleaned.ifEmpty { FALLBACK_NAME }
}

private const val FALLBACK_NAME = "imported key"

private const val MAX_NAME_CHARS = 64

/** Any run of whitespace, tabs and newlines included, becomes one space. */
private val WHITESPACE_RUN = Regex("\\s+")

private val PEM_PRIVATE_KEY = Regex(
    "\\s*-----BEGIN ([A-Z0-9 ]*PRIVATE KEY)-----((?:(?!-----BEGIN |-----END )[\\s\\S])+?)-----END \\1-----\\s*",
)

/**
 * Control and format characters — the bidi overrides live in `FORMAT` — plus
 * the separators that would let a name climb out of its own row.
 */
private fun Char.isUnsafeInAName(): Boolean {
    // Whitespace survives filtering and is collapsed to a single space instead.
    if (isWhitespace()) return false
    if (this == '/' || this == '\\') return true

    return when (Character.getType(this).toByte()) {
        Character.CONTROL,
        Character.FORMAT,
        Character.SURROGATE,
        Character.PRIVATE_USE,
        Character.UNASSIGNED,
        -> true

        else -> false
    }
}
