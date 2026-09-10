package com.hermesagent.mobile.data.composer

data class ComposerReferenceSpan(
    val kind: String,
    val value: String,
    val start: Int,
    val end: Int,
    val valueStart: Int,
    val valueEnd: Int
)

private val referenceRegex = Regex("""@(file|folder|url|session):(`[^`\n]+`|"[^"\n]+"|'[^'\n]+')""")

fun composerReferenceSpans(text: String): List<ComposerReferenceSpan> {
    return referenceRegex.findAll(text).map { match ->
        val kind = match.groupValues[1]
        val start = match.range.first
        val end = match.range.last + 1
        val valueStart = start + kind.length + 3
        val valueEnd = end - 1
        val value = text.substring(valueStart, valueEnd)
        ComposerReferenceSpan(kind, value, start, end, valueStart, valueEnd)
    }.toList()
}

fun maskComposerReferences(text: String): String {
    val spans = composerReferenceSpans(text)
    if (spans.isEmpty()) return text
    val builder = StringBuilder(text)
    for (span in spans) {
        for (i in span.start until span.end) {
            builder[i] = '\uFFFC'
        }
    }
    return builder.toString()
}

fun hiddenUrlLabelRanges(value: String): List<IntRange> {
    val schemeIdx = value.indexOf("://")
    if (schemeIdx == -1) return emptyList()
    val schemeEnd = schemeIdx + 3
    
    var authEnd = value.length
    for (i in schemeEnd until value.length) {
        val c = value[i]
        if (c == '/' || c == '?' || c == '#') {
            authEnd = i
            break
        }
    }
    
    val host = value.substring(schemeEnd, authEnd)
    if (host.isEmpty()) return emptyList()
    
    val hidden = mutableListOf<IntRange>()
    hidden.add(0 until schemeEnd)
    
    val userInfoIdx = value.lastIndexOf('@', authEnd - 1)
    val hostStart = if (userInfoIdx >= schemeEnd) {
        hidden.add(schemeEnd..userInfoIdx) // up to and including @
        userInfoIdx + 1
    } else {
        schemeEnd
    }
    
    val portIdx = if (value.getOrNull(hostStart) == '[') {
        val bracketEnd = value.indexOf(']', hostStart)
        if (bracketEnd != -1 && bracketEnd < authEnd) {
            value.indexOf(':', bracketEnd)
        } else {
            value.indexOf(':', hostStart)
        }
    } else {
        value.indexOf(':', hostStart)
    }
    
    if (portIdx != -1 && portIdx < authEnd) {
        hidden.add(portIdx until authEnd)
    }
    
    val wwwStr = "www."
    if (value.length >= hostStart + wwwStr.length && 
        value.substring(hostStart, hostStart + wwwStr.length).equals(wwwStr, ignoreCase = true) &&
        hostStart + wwwStr.length < authEnd &&
        (portIdx == -1 || hostStart + wwwStr.length < portIdx)
    ) {
        hidden.add(hostStart until (hostStart + wwwStr.length))
    }
    
    val hashIdx = value.indexOf('#', authEnd)
    if (hashIdx != -1) {
        hidden.add(hashIdx until value.length)
    }
    
    val pathEnd = if (hashIdx != -1) hashIdx else value.length
    if (pathEnd > authEnd && value[pathEnd - 1] == '/') {
        hidden.add((pathEnd - 1) until pathEnd)
    }
    
    return hidden.sortedBy { it.first }
}

fun composerReferenceLabel(kind: String, value: String): String {
    return when (kind) {
        "url" -> {
            val hidden = hiddenUrlLabelRanges(value)
            val builder = StringBuilder()
            var current = 0
            for (range in hidden) {
                if (current < range.first) {
                    builder.append(value.substring(current, range.first))
                }
                current = range.last + 1
            }
            if (current < value.length) {
                builder.append(value.substring(current))
            }
            builder.toString()
        }
        "file", "folder" -> value.removePrefix("./")
        else -> value
    }
}

private val exactMatchRegex = Regex("""^@(file|folder|url|session):(\S+)$""")
fun fenceCompletionReference(text: String): String {
    val match = exactMatchRegex.matchEntire(text) ?: return text
    val kind = match.groupValues[1]
    val value = match.groupValues[2]
    if (value.startsWith("`") || value.startsWith("\"") || value.startsWith("'")) {
        return text
    }
    return when (kind) {
        "file" -> ComposerReference.File(value).wireText
        "folder" -> ComposerReference.Folder(value).wireText
        "url" -> ComposerReference.Url(value).wireText
        "session" -> ComposerReference.Session(value).wireText
        else -> text
    }
}
