package com.hermesagent.mobile.data.gateway

import java.util.Locale
import kotlinx.serialization.json.*

/** projects.ts:201-220 + lib/path-compare.ts:12-26 @ 81a55437f3f31a9f12a30c53b6c5d49d420898aa. */
internal fun projectOwningCwd(tree: JsonElement, cwd: String?): String? {
    if (cwd.isNullOrBlank()) return null
    fun text(node: JsonObject, key: String) = (node[key] as? JsonPrimitive)?.contentOrNull
    fun key(path: String): String {
        val clean = path.trim().replace('\\', '/').trimEnd('/').ifEmpty { "/" }
        return if (Regex("^[A-Za-z]:(/|$)").containsMatchIn(clean) || clean.startsWith("//")) clean.lowercase(Locale.ROOT) else clean
    }
    val child = key(cwd)
    var owner: String? = null
    var longest = -1
    ((tree as? JsonObject)?.get("projects") as? JsonArray).orEmpty().forEach { element ->
        val project = element as? JsonObject ?: return@forEach
        val paths = mutableListOf<String?>()
        paths += text(project, "path")
        (project["repos"] as? JsonArray).orEmpty().forEach { repoElement ->
            val repo = repoElement as? JsonObject ?: return@forEach
            paths += text(repo, "path")
            (repo["groups"] as? JsonArray).orEmpty().forEach { group ->
                paths += (group as? JsonObject)?.let { text(it, "path") }
            }
        }
        paths.filterNotNull().map(String::trim).filter(String::isNotEmpty).forEach { path ->
            val parent = key(path)
            if ((child == parent || child.startsWith("$parent/")) && path.length > longest) {
                owner = text(project, "id")
                longest = path.length
            }
        }
    }
    return owner
}
