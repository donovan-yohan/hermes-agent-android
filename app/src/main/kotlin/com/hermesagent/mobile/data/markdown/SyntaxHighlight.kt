package com.hermesagent.mobile.data.markdown

import java.util.Locale

/**
 * Bounded syntax tokenisation for one rendered diff line.
 *
 * Desktop hands the change *content* to Shiki and layers the add/remove tint on
 * top of the highlighted output, so a changed line reads as code in the file's
 * own language and as a change at the same time
 * (`apps/desktop/src/components/chat/diff-lines.tsx:469-487`, and the
 * `diffLineTransformer` that re-applies the tint at `:453-467`, @
 * `437116f9497c80d242ce034ff7f5d81dc277a337`). Shiki is a multi-megabyte grammar
 * and theme engine, loaded as a lazy chunk there for exactly that reason;
 * Android has no equivalent, so this is the *bounded* half of Desktop's own rule
 * — the same choice the pin makes when `canHighlight` is false
 * (`diff-lines.tsx:607-632`), with the language set drawn narrower.
 *
 * Three things this deliberately is:
 *
 *  - **Per line.** The panel paints one `Text` per diff row, so the lexer's unit
 *    is a line. A block comment or unterminated string therefore colours to the
 *    end of *its* line rather than carrying state into the next row — a
 *    divergence from a real grammar, invisible for the shape a rendered diff
 *    actually has (self-contained changed lines), and ledgered in
 *    `docs/parity/tool-output-fidelity.md`.
 *  - **Bounded.** The same character and line budget Desktop refuses to
 *    highlight past (`shiki-highlighter.tsx:34-35,56-70` @ the same SHA), so a
 *    hostile payload cannot turn composition into a grammar walk.
 *  - **Compose-free.** This is data; only the panel that paints it needs a theme.
 *
 * It never claims a language it does not carry. Desktop maps a filename to a
 * Shiki id and highlights whenever that id resolves; this app carries lexing
 * shapes for a subset of those ids and answers `null` for the rest — which is
 * the *coloured* diff, never a wrong one.
 */

/** What one run of a line is. Desktop's equivalent is a Shiki token scope. */
enum class SyntaxTokenKind { Plain, Keyword, String, Comment, Number, Function }

/** One run: its kind, and the exact characters it covers. */
data class SyntaxToken(val kind: SyntaxTokenKind, val text: String)

/**
 * Desktop's own highlight budget, verbatim.
 *
 * `shiki-highlighter.tsx:34-35` @
 * `437116f9497c80d242ce034ff7f5d81dc277a337`. The numbers are read off the pin
 * rather than invented, so a diff Desktop declines to highlight is one this
 * declines too — the two fall back at the same size.
 */
internal const val MAX_HIGHLIGHT_CHARS = 150_000
internal const val MAX_HIGHLIGHT_LINES = 3_000

/** Whether [code] is past the budget, so the plain coloured diff is what paints. */
internal fun exceedsHighlightBudget(code: String): Boolean {
    if (code.length > MAX_HIGHLIGHT_CHARS) return true
    var lines = 1
    for (ch in code) {
        if (ch == '\n' && ++lines > MAX_HIGHLIGHT_LINES) return true
    }
    return false
}

/**
 * The lexicon a language is tokenised with.
 *
 * Four knobs, because that is what separates the families in Desktop's map:
 * which introducer starts a line comment, whether a block comment exists, which
 * quote characters open a string, and which words are keywords.
 */
internal data class SyntaxLanguage(
    val lineComment: String?,
    val blockComment: Pair<String, String>?,
    val quotes: String,
    val keywords: Set<String>,
)

/**
 * The language a filename tokenises as, or null for the plain coloured diff.
 *
 * The extension→language step is Desktop's, ported whole
 * (`lib/markdown-code.ts:157-242` @
 * `437116f9497c80d242ce034ff7f5d81dc277a337`): last path segment, lowercased,
 * text after the final dot, and the bare name for `Dockerfile`/`Makefile`. The
 * second step is this app's: an id with no lexing shape here answers null, where
 * Desktop would ask Shiki for the grammar.
 */
internal fun syntaxLanguageForPath(path: String?): SyntaxLanguage? =
    SYNTAX_BY_LANGUAGE_ID[shikiLanguageIdForFilename(path)]

/**
 * `shikiLanguageForFilename`. An extension outside the map returns `""` there
 * (`markdown-code.ts:240-242`), so it does here.
 */
internal fun shikiLanguageIdForFilename(path: String?): String =
    SHIKI_LANGUAGE_BY_EXTENSION[filenameExtToken(path)].orEmpty()

/** `filenameExtToken` (`markdown-code.ts:157-163` @ the same SHA). */
private fun filenameExtToken(path: String?): String {
    val base = path.orEmpty().replace('\\', '/').substringAfterLast('/').lowercase(Locale.US)
    val dot = base.lastIndexOf('.')
    return if (dot > 0) base.substring(dot + 1) else base
}

/**
 * Tokenise one line of change content in [language].
 *
 * Returns runs that concatenate back to [line] exactly: the panel paints the
 * whole line whatever the lexer recognised, so a token can never be lost or
 * duplicated by highlighting. An empty line yields one empty plain run.
 */
internal fun tokenizeSyntaxLine(line: String, language: SyntaxLanguage): List<SyntaxToken> {
    if (line.isEmpty()) return listOf(SyntaxToken(SyntaxTokenKind.Plain, ""))
    val out = ArrayList<SyntaxToken>()
    var plain = StringBuilder()
    var i = 0

    fun flushPlain() {
        if (plain.isNotEmpty()) {
            out.add(SyntaxToken(SyntaxTokenKind.Plain, plain.toString()))
            plain = StringBuilder()
        }
    }

    fun emit(kind: SyntaxTokenKind, body: String) {
        flushPlain()
        out.add(SyntaxToken(kind, body))
    }

    while (i < line.length) {
        val comment = commentRun(line, i, language)
        if (comment != null) {
            emit(SyntaxTokenKind.Comment, comment)
            // A line comment is the rest of the row by definition; a block
            // comment without its close runs to the row's end for the same
            // reason (`SyntaxHighlight.kt`'s per-line bound).
            return finish(out, line)
        }

        val ch = line[i]
        if (language.quotes.indexOf(ch) >= 0) {
            val body = stringRun(line, i, ch)
            emit(SyntaxTokenKind.String, body)
            i += body.length
            continue
        }

        if (ch.isDigit() && isWordStart(line, i)) {
            val end = wordEnd(line, i)
            emit(SyntaxTokenKind.Number, line.substring(i, end))
            i = end
            continue
        }

        if (isIdentifierStart(ch)) {
            val end = wordEnd(line, i)
            val word = line.substring(i, end)
            if (word in language.keywords) {
                emit(SyntaxTokenKind.Keyword, word)
            } else if (isCallAt(line, end)) {
                emit(SyntaxTokenKind.Function, word)
            } else {
                plain.append(word)
            }
            i = end
            continue
        }

        plain.append(ch)
        i += 1
    }

    return finish(out, line)
}

/**
 * Close the run list: whatever is still plain is appended, so the tokens always
 * reconstruct [line].
 */
private fun finish(out: MutableList<SyntaxToken>, line: String): List<SyntaxToken> {
    val joined = StringBuilder()
    out.forEach { joined.append(it.text) }
    if (joined.length < line.length) {
        out.add(SyntaxToken(SyntaxTokenKind.Plain, line.substring(joined.length)))
    }
    return out
}

/**
 * The comment starting at [index], or null when none does.
 *
 * A marker only opens a comment where a token could begin: `http://x` must not
 * become `http:` plus a comment, so the character before the marker has to be
 * something other than an identifier character.
 */
private fun commentRun(line: String, index: Int, language: SyntaxLanguage): String? {
    if (!isWordStart(line, index)) return null

    val block = language.blockComment
    if (block != null && line.startsWith(block.first, index)) {
        val close = line.indexOf(block.second, startIndex = index + block.first.length)
        return if (close < 0) line.substring(index) else line.substring(index, close + block.second.length)
    }

    val marker = language.lineComment
    if (marker != null && line.startsWith(marker, index)) return line.substring(index)

    return null
}

/** Whether a token may begin here: index 0, or after a non-word character. */
private fun isWordStart(line: String, index: Int): Boolean {
    if (index == 0) return true
    val previous = line[index - 1]
    return !isIdentifierPart(previous)
}

private fun isIdentifierStart(ch: Char): Boolean = ch.isLetter() || ch == '_'

private fun isIdentifierPart(ch: Char): Boolean = ch.isLetterOrDigit() || ch == '_'

/** `"…"` / `'…'` with backslash escapes, to the closing quote or end of line. */
private fun stringRun(line: String, start: Int, quote: Char): String {
    var i = start + 1
    while (i < line.length) {
        when (line[i]) {
            '\\' -> i += 2
            quote -> return line.substring(start, i + 1)
            else -> i += 1
        }
    }
    return line.substring(start)
}

/** To the end of an identifier or numeric literal, digits and dots included. */
private fun wordEnd(line: String, start: Int): Int {
    var i = start
    while (i < line.length && (isIdentifierPart(line[i]) || line[i] == '.')) i += 1
    return i
}

/** A word followed by `(` reads as a call, which Desktop paints in its call ink. */
private fun isCallAt(line: String, end: Int): Boolean {
    var i = end
    while (i < line.length && line[i] == ' ') i += 1
    return i < line.length && line[i] == '('
}

// ── The language tables ─────────────────────────────────────────────────────

/** `markdown-code.ts:168-238` @ `437116f9497c80d242ce034ff7f5d81dc277a337`. */
private val SHIKI_LANGUAGE_BY_EXTENSION: Map<String, String> = mapOf(
    "astro" to "astro", "bash" to "bash", "c" to "c", "cc" to "cpp", "cjs" to "javascript",
    "clj" to "clojure", "cpp" to "cpp", "cs" to "csharp", "css" to "css", "cxx" to "cpp",
    "dart" to "dart", "dockerfile" to "docker", "ex" to "elixir", "exs" to "elixir",
    "fish" to "fish", "go" to "go", "gql" to "graphql", "graphql" to "graphql", "h" to "c",
    "hpp" to "cpp", "hs" to "haskell", "htm" to "html", "html" to "html", "ini" to "ini",
    "java" to "java", "jl" to "julia", "js" to "javascript", "json" to "json", "json5" to "json5",
    "jsonc" to "jsonc", "jsx" to "jsx", "kt" to "kotlin", "kts" to "kotlin", "less" to "less",
    "lua" to "lua", "makefile" to "make", "markdown" to "markdown", "md" to "markdown",
    "mdx" to "mdx", "mjs" to "javascript", "ml" to "ocaml", "mts" to "typescript", "nix" to "nix",
    "php" to "php", "pl" to "perl", "proto" to "proto", "ps1" to "powershell", "py" to "python",
    "pyi" to "python", "r" to "r", "rb" to "ruby", "rs" to "rust", "sass" to "sass",
    "scala" to "scala", "scss" to "scss", "sh" to "bash", "sql" to "sql", "svelte" to "svelte",
    "swift" to "swift", "tf" to "terraform", "toml" to "toml", "ts" to "typescript", "tsx" to "tsx",
    "vue" to "vue", "xml" to "xml", "yaml" to "yaml", "yml" to "yaml", "zig" to "zig",
    "zsh" to "bash",
)

private fun cLike(keywords: Set<String>) = SyntaxLanguage("//", "/*" to "*/", "\"'", keywords)
private fun hashLike(keywords: Set<String>) = SyntaxLanguage("#", null, "\"'", keywords)
private fun dashLike(keywords: Set<String>) = SyntaxLanguage("--", null, "\"'", keywords)

private val KOTLIN_KEYWORDS = setOf(
    "as", "break", "by", "catch", "class", "companion", "constructor", "continue", "crossinline",
    "data", "do", "dynamic", "else", "enum", "expect", "external", "false", "final", "finally",
    "for", "fun", "get", "if", "import", "in", "infix", "init", "inline", "inner", "interface",
    "internal", "is", "it", "lateinit", "noinline", "null", "object", "open", "operator", "out",
    "override", "package", "private", "protected", "public", "reified", "return", "sealed", "set",
    "super", "suspend", "tailrec", "this", "throw", "true", "try", "typealias", "val", "var",
    "vararg", "when", "where", "while",
)

private val JAVA_KEYWORDS = setOf(
    "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const",
    "continue", "default", "do", "double", "else", "enum", "extends", "false", "final", "finally",
    "float", "for", "goto", "if", "implements", "import", "instanceof", "int", "interface", "long",
    "native", "new", "null", "package", "private", "protected", "public", "return", "short",
    "static", "strictfp", "super", "switch", "synchronized", "this", "throw", "throws", "transient",
    "true", "try", "void", "volatile", "while", "var", "record", "sealed", "permits", "yield",
)

private val PYTHON_KEYWORDS = setOf(
    "and", "as", "assert", "async", "await", "break", "class", "continue", "def", "del", "elif",
    "else", "except", "False", "finally", "for", "from", "global", "if", "import", "in", "is",
    "lambda", "None", "nonlocal", "not", "or", "pass", "raise", "return", "True", "try", "while",
    "with", "yield", "self", "match", "case",
)

private val JS_KEYWORDS = setOf(
    "as", "async", "await", "break", "case", "catch", "class", "const", "continue", "debugger",
    "default", "delete", "do", "else", "enum", "export", "extends", "false", "finally", "for",
    "from", "function", "get", "if", "implements", "import", "in", "instanceof", "interface",
    "let", "new", "null", "of", "package", "private", "protected", "public", "readonly", "return",
    "satisfies", "set", "static", "super", "switch", "this", "throw", "true", "try", "type",
    "typeof", "undefined", "var", "void", "while", "with", "yield",
    // TypeScript's own surface, in the same set because the map sends both
    // spellings to one closely related grammar.
    "declare", "is", "keyof", "namespace", "never", "any", "unknown", "string", "number", "boolean",
    "infer", "asserts", "override", "abstract",
)

private val BASH_KEYWORDS = setOf(
    "case", "do", "done", "elif", "else", "esac", "fi", "for", "function", "if", "in", "local",
    "return", "select", "then", "time", "until", "while", "export", "declare", "readonly", "set",
)

private val SQL_KEYWORDS = setOf(
    "ALTER", "AND", "AS", "ASC", "BEGIN", "BETWEEN", "BY", "CASE", "CAST", "COMMIT", "CREATE",
    "DELETE", "DESC", "DISTINCT", "DROP", "ELSE", "END", "EXISTS", "FROM", "GROUP", "HAVING",
    "IN", "INDEX", "INNER", "INSERT", "INTO", "IS", "JOIN", "KEY", "LEFT", "LIKE", "LIMIT",
    "NOT", "NULL", "ON", "OR", "ORDER", "OUTER", "PRIMARY", "SELECT", "SET", "TABLE", "THEN",
    "UNION", "UNIQUE", "UPDATE", "VALUES", "WHEN", "WHERE", "WITH",
)

/**
 * Each shape is `lineComment`, `blockComment`, quotes, and the keyword set for
 * that family.
 *
 * Several C-like ids carry an empty keyword set: they still tokenise comments,
 * strings and numbers — the runs that carry most of a diff's readability — and
 * simply paint no keyword. That is a partial highlight, never a wrong one, and
 * it is the honest bound this file draws: carrying forty keyword lists would be
 * a second copy of Shiki's grammars to keep in step with the pin.
 */
private val SYNTAX_BY_LANGUAGE_ID: Map<String, SyntaxLanguage> = buildMap {
    for (id in listOf("c", "cpp", "css", "less", "sass", "scss", "dart", "graphql", "zig", "proto")) {
        put(id, cLike(emptySet()))
    }
    put("kotlin", cLike(KOTLIN_KEYWORDS))
    put("java", cLike(JAVA_KEYWORDS))
    put("javascript", cLike(JS_KEYWORDS))
    put("jsx", cLike(JS_KEYWORDS))
    put("typescript", cLike(JS_KEYWORDS))
    put("tsx", cLike(JS_KEYWORDS))
    put(
        "go",
        cLike(
            setOf(
                "break", "case", "chan", "const", "continue", "default", "defer", "else",
                "fallthrough", "for", "func", "go", "goto", "if", "import", "interface", "map",
                "package", "range", "return", "select", "struct", "switch", "type", "var", "nil",
                "true", "false",
            ),
        ),
    )
    put(
        "rust",
        cLike(
            setOf(
                "as", "async", "await", "break", "const", "continue", "crate", "dyn", "else", "enum",
                "extern", "false", "fn", "for", "if", "impl", "in", "let", "loop", "match", "mod",
                "move", "mut", "pub", "ref", "return", "self", "static", "struct", "super", "trait",
                "true", "type", "unsafe", "use", "where", "while",
            ),
        ),
    )
    put(
        "csharp",
        cLike(
            setOf(
                "abstract", "as", "base", "bool", "break", "byte", "case", "catch", "char", "class",
                "const", "continue", "decimal", "default", "delegate", "do", "double", "else",
                "enum", "event", "false", "finally", "float", "for", "foreach", "get", "if",
                "implicit", "in", "int", "interface", "internal", "is", "lock", "long", "namespace",
                "new", "null", "object", "operator", "out", "override", "params", "private",
                "protected", "public", "readonly", "ref", "return", "sealed", "set", "static",
                "string", "struct", "switch", "this", "throw", "true", "try", "typeof", "using",
                "var", "virtual", "void", "while",
            ),
        ),
    )
    put(
        "swift",
        cLike(
            setOf(
                "as", "break", "case", "catch", "class", "continue", "default", "defer", "deinit",
                "do", "else", "enum", "extension", "fallthrough", "false", "fileprivate", "for",
                "func", "guard", "if", "import", "in", "init", "inout", "internal", "is", "let",
                "nil", "open", "operator", "private", "protocol", "public", "repeat", "return",
                "self", "static", "struct", "subscript", "super", "switch", "throw", "throws",
                "true", "try", "typealias", "var", "where", "while",
            ),
        ),
    )
    put(
        "scala",
        cLike(
            setOf(
                "abstract", "case", "catch", "class", "def", "do", "else", "extends", "false",
                "final", "finally", "for", "if", "implicit", "import", "lazy", "match", "new",
                "null", "object", "override", "package", "private", "protected", "return", "sealed",
                "super", "this", "throw", "trait", "true", "try", "type", "val", "var", "while",
                "with", "yield",
            ),
        ),
    )
    put(
        "php",
        cLike(
            setOf(
                "abstract", "and", "array", "as", "break", "callable", "case", "catch", "class",
                "clone", "const", "continue", "declare", "default", "do", "echo", "else", "elseif",
                "empty", "extends", "final", "finally", "fn", "for", "foreach", "function", "global",
                "if", "implements", "include", "instanceof", "interface", "isset", "list", "match",
                "namespace", "new", "or", "print", "private", "protected", "public", "readonly",
                "require", "return", "static", "switch", "throw", "trait", "try", "unset", "use",
                "var", "while", "xor", "yield",
            ),
        ),
    )
    put(
        "terraform",
        hashLike(
            setOf(
                "data", "for", "if", "in", "locals", "module", "output", "provider", "resource",
                "terraform", "variable", "true", "false", "null",
            ),
        ),
    )
    put("nix", hashLike(setOf("assert", "else", "if", "in", "inherit", "let", "or", "rec", "then", "with")))

    put("python", hashLike(PYTHON_KEYWORDS))
    put(
        "ruby",
        hashLike(
            setOf(
                "alias", "and", "begin", "break", "case", "class", "def", "do", "else", "elsif",
                "end", "ensure", "false", "for", "if", "in", "module", "next", "nil", "not", "or",
                "redo", "rescue", "retry", "return", "self", "super", "then", "true", "undef",
                "unless", "until", "when", "while", "yield", "require", "attr_accessor",
            ),
        ),
    )
    put(
        "perl",
        hashLike(
            setOf(
                "chomp", "die", "each", "else", "elsif", "eval", "for", "foreach", "given", "goto",
                "if", "last", "local", "my", "next", "our", "package", "print", "redo", "require",
                "return", "sub", "unless", "until", "use", "when", "while",
            ),
        ),
    )
    put(
        "r",
        hashLike(
            setOf("break", "else", "FALSE", "for", "function", "if", "Inf", "NA", "NaN", "next", "NULL", "repeat", "return", "TRUE", "while"),
        ),
    )
    put("yaml", hashLike(setOf("true", "false", "null", "yes", "no", "on", "off")))
    put("toml", hashLike(setOf("true", "false")))
    put("ini", hashLike(emptySet()))
    put("docker", hashLike(emptySet()))
    put("make", hashLike(emptySet()))
    put("fish", hashLike(BASH_KEYWORDS))
    put("bash", hashLike(BASH_KEYWORDS))
    put(
        "julia",
        hashLike(
            setOf(
                "begin", "break", "catch", "const", "continue", "do", "else", "elseif", "end",
                "export", "false", "finally", "for", "function", "global", "if", "import", "in",
                "let", "local", "macro", "module", "quote", "return", "struct", "true", "try",
                "using", "while",
            ),
        ),
    )
    put(
        "elixir",
        hashLike(
            setOf(
                "case", "cond", "def", "defmodule", "defp", "defstruct", "do", "else", "end",
                "false", "fn", "for", "if", "import", "nil", "raise", "receive", "require",
                "rescue", "true", "unless", "use", "when",
            ),
        ),
    )
    put(
        "clojure",
        SyntaxLanguage(
            ";",
            null,
            "\"",
            setOf("def", "defn", "defmacro", "fn", "if", "let", "loop", "nil", "recur", "require", "true", "false", "do", "when", "cond", "ns"),
        ),
    )
    put(
        "haskell",
        dashLike(
            setOf("case", "class", "data", "deriving", "do", "else", "foreign", "if", "import", "in", "instance", "let", "module", "newtype", "of", "then", "type", "where", "True", "False"),
        ),
    )
    put(
        "lua",
        SyntaxLanguage(
            "--",
            null,
            "\"'",
            setOf("and", "break", "do", "else", "elseif", "end", "false", "for", "function", "if", "in", "local", "nil", "not", "or", "repeat", "return", "then", "true", "until", "while", "self"),
        ),
    )
    put(
        "ocaml",
        SyntaxLanguage(
            "//",
            "(*" to "*)",
            "\"'",
            setOf("and", "as", "begin", "class", "do", "done", "else", "end", "exception", "false", "for", "fun", "function", "if", "in", "let", "match", "module", "mutable", "new", "object", "of", "open", "rec", "sig", "struct", "then", "true", "try", "type", "val", "when", "while", "with"),
        ),
    )
    put(
        "powershell",
        hashLike(
            setOf("begin", "break", "catch", "class", "continue", "do", "else", "elseif", "end", "enum", "exit", "filter", "finally", "for", "foreach", "function", "if", "in", "param", "process", "return", "switch", "throw", "trap", "try", "until", "using", "while", "workflow"),
        ),
    )

    put("sql", SyntaxLanguage("--", "/*" to "*/", "\"'", SQL_KEYWORDS))

    put("json", SyntaxLanguage(null, null, "\"", setOf("true", "false", "null")))
    put("json5", cLike(setOf("true", "false", "null")))
    put("jsonc", cLike(setOf("true", "false", "null")))

    for (id in listOf("html", "xml", "markdown", "mdx", "vue", "svelte", "astro")) {
        put(id, SyntaxLanguage(null, "<!--" to "-->", "\"'", emptySet()))
    }
}
