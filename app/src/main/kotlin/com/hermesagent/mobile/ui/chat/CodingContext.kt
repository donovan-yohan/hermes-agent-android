package com.hermesagent.mobile.ui.chat

import com.hermesagent.mobile.data.gateway.GatewayHttp
import com.hermesagent.mobile.data.gateway.GatewayHttpRequest
import com.hermesagent.mobile.data.gateway.GatewayHttpResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Repository status stays behind the connection-owned authenticated HTTP leg.
 * Callers must supply the exact session.info cwd reported by the Gateway; this
 * boundary never infers a filesystem path on the phone.
 */
interface CodingContextProvider {
    suspend fun contextFor(worktreePath: String): CodingContext
    suspend fun pullRequestFor(worktreePath: String, branch: String): CodingPullRequest? = null
    suspend fun reviewFor(worktreePath: String): CodingReviewResult

    data object Unavailable : CodingContextProvider {
        override suspend fun contextFor(worktreePath: String): CodingContext = CodingContext.Unavailable
        override suspend fun pullRequestFor(worktreePath: String, branch: String): CodingPullRequest? = null
        override suspend fun reviewFor(worktreePath: String): CodingReviewResult = CodingReviewResult.Unavailable
    }
}

sealed interface CodingContext {
    data object Unavailable : CodingContext

    /** Backend-authored cwd/branch while the authenticated status call runs. */
    data class Loading(
        val worktreePath: String,
        val branch: String?,
    ) : CodingContext

    data class Available(
        val branch: String,
        val worktreePath: String,
        val additions: Int,
        val deletions: Int,
        val ahead: Int = 0,
        val behind: Int = 0,
        val untracked: Int = 0,
        val detached: Boolean = false,
        val pullRequest: CodingPullRequest? = null,
    ) : CodingContext
}

data class CodingPullRequest(
    val number: Int,
    val url: String,
    val state: String,
    val draft: Boolean,
)

data class CodingReviewFile(
    val path: String,
    val additions: Int,
    val deletions: Int,
    val status: String,
    val staged: Boolean,
)

sealed interface CodingReviewResult {
    data class Available(val files: List<CodingReviewFile>) : CodingReviewResult
    data object Unavailable : CodingReviewResult
}

sealed interface CodingReviewUiState {
    data object Closed : CodingReviewUiState
    data class Loading(val worktreePath: String) : CodingReviewUiState
    data class Ready(val worktreePath: String, val files: List<CodingReviewFile>) : CodingReviewUiState
    data class Failed(val worktreePath: String) : CodingReviewUiState
}

/** Pinned Desktop parity: `/api/git/status` plus best-effort branch PR lookup. */
internal class GatewayCodingContextProvider(
    private val http: () -> GatewayHttp?,
) : CodingContextProvider {
    override suspend fun contextFor(worktreePath: String): CodingContext = withContext(Dispatchers.IO) {
        val path = worktreePath.takeIf { it.isNotBlank() && it.length <= MAX_WORKTREE_PATH }
            ?: return@withContext CodingContext.Unavailable
        val status = requestJson(
            GatewayHttpRequest(
                path = "api/git/status",
                method = "GET",
                body = null,
                timeoutMillis = GIT_TIMEOUT_MILLIS,
                query = mapOf("path" to path),
                maxResponseBytes = MAX_GIT_RESPONSE_BYTES.toLong(),
            ),
        ) as? JsonObject ?: return@withContext CodingContext.Unavailable
        val detached = status.primitiveBoolean("detached") == true
        val branch = status.string("branch")?.takeIf(String::isNotBlank)
            ?: if (detached) "Detached HEAD" else return@withContext CodingContext.Unavailable
        val additions = status.int("added")?.takeIf { it >= 0 }
            ?: return@withContext CodingContext.Unavailable
        val deletions = status.int("removed")?.takeIf { it >= 0 }
            ?: return@withContext CodingContext.Unavailable
        val ahead = status.optionalNonNegativeInt("ahead") ?: return@withContext CodingContext.Unavailable
        val behind = status.optionalNonNegativeInt("behind") ?: return@withContext CodingContext.Unavailable
        val untracked = status.optionalNonNegativeInt("untracked") ?: return@withContext CodingContext.Unavailable
        CodingContext.Available(
            branch = branch,
            worktreePath = path,
            additions = additions,
            deletions = deletions,
            ahead = ahead,
            behind = behind,
            untracked = untracked,
            detached = detached,
        )
    }

    override suspend fun pullRequestFor(worktreePath: String, branch: String): CodingPullRequest? =
        withContext(Dispatchers.IO) {
            val path = worktreePath.takeIf { it.isNotBlank() && it.length <= MAX_WORKTREE_PATH }
                ?: return@withContext null
            val safeBranch = branch.takeIf { it.isNotBlank() && it.length <= MAX_SESSION_BRANCH }
                ?: return@withContext null
            loadPullRequest(path, safeBranch)
        }

    override suspend fun reviewFor(worktreePath: String): CodingReviewResult = withContext(Dispatchers.IO) {
        val path = worktreePath.takeIf { it.isNotBlank() && it.length <= MAX_WORKTREE_PATH }
            ?: return@withContext CodingReviewResult.Unavailable
        val root = requestJson(
            GatewayHttpRequest(
                path = "api/git/review/list",
                method = "GET",
                body = null,
                timeoutMillis = GIT_TIMEOUT_MILLIS,
                query = mapOf("path" to path, "scope" to "uncommitted"),
                maxResponseBytes = MAX_GIT_RESPONSE_BYTES.toLong(),
            ),
        ) as? JsonObject ?: return@withContext CodingReviewResult.Unavailable
        val files = root["files"] as? JsonArray ?: return@withContext CodingReviewResult.Unavailable
        val parsed = files.map { element ->
            val file = element as? JsonObject ?: return@withContext CodingReviewResult.Unavailable
            val filePath = file.string("path")
                ?.replace(STATUS_WHITESPACE, " ")
                ?.trim()
                ?.takeIf(String::isNotEmpty)
                ?.take(MAX_REVIEW_PATH)
                ?: return@withContext CodingReviewResult.Unavailable
            CodingReviewFile(
                path = filePath,
                additions = file.int("added")?.takeIf { it >= 0 }
                    ?: return@withContext CodingReviewResult.Unavailable,
                deletions = file.int("removed")?.takeIf { it >= 0 }
                    ?: return@withContext CodingReviewResult.Unavailable,
                status = file.string("status")?.take(MAX_REVIEW_STATUS).orEmpty(),
                staged = file.primitiveBoolean("staged") == true,
            )
        }
        CodingReviewResult.Available(parsed)
    }

    private suspend fun loadPullRequest(path: String, branch: String): CodingPullRequest? {
        val payload = buildJsonObject {
            put("path", path)
            put("branches", JsonArray(listOf(JsonPrimitive(branch))))
            put("numbers", JsonArray(emptyList()))
        }.toString()
        val root = requestJson(
            GatewayHttpRequest(
                path = "api/git/review/pr-list",
                method = "POST",
                body = payload.toRequestBody(JSON_MEDIA_TYPE),
                timeoutMillis = GIT_TIMEOUT_MILLIS,
                maxResponseBytes = MAX_GIT_RESPONSE_BYTES.toLong(),
            ),
        ) as? JsonObject ?: return null
        val rows = root["prs"] as? JsonArray ?: return null
        return rows.mapNotNull { element ->
            val row = element as? JsonObject ?: return@mapNotNull null
            val number = row.int("number")?.takeIf { it > 0 } ?: return@mapNotNull null
            val url = row.string("url")
                ?.takeIf { it.length <= MAX_EXTERNAL_URL }
                ?.takeIf(::isSafeHttpUrl)
                ?: return@mapNotNull null
            val rowBranch = row.string("branch") ?: return@mapNotNull null
            if (rowBranch != branch) return@mapNotNull null
            CodingPullRequest(
                number = number,
                url = url,
                state = row.string("state").orEmpty().lowercase().take(MAX_REVIEW_STATUS),
                draft = row.primitiveBoolean("draft") == true,
            )
        }.firstOrNull()
    }

    private suspend fun requestJson(request: GatewayHttpRequest): JsonElement? {
        val transport = http() ?: return null
        return when (val result = transport.execute(request)) {
            is GatewayHttpResult.Rejected -> null
            is GatewayHttpResult.Success -> {
                val body = result.bodyBytes
                try {
                    if (body.size > MAX_GIT_RESPONSE_BYTES) return null
                    runCatching { Json.parseToJsonElement(body.toString(Charsets.UTF_8)) }.getOrNull()
                } finally {
                    body.fill(0)
                }
            }
        }
    }
}

internal fun displayWorktreePath(raw: String): String = raw
    .replace(UNIX_HOME_PREFIX, "~")
    .replace(WINDOWS_HOME_PREFIX, "~")

private fun isSafeHttpUrl(raw: String): Boolean = raw.toHttpUrlOrNull()?.scheme in setOf("http", "https")

private fun JsonObject.string(name: String): String? =
    (this[name] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.contentOrNull

private fun JsonObject.int(name: String): Int? = (this[name] as? JsonPrimitive)?.intOrNull

/** Missing counters are compatible with older Gateways; malformed counters are not. */
private fun JsonObject.optionalNonNegativeInt(name: String): Int? {
    val value = this[name] ?: return 0
    return (value as? JsonPrimitive)?.intOrNull?.takeIf { it >= 0 }
}

private fun JsonObject.primitiveBoolean(name: String): Boolean? =
    (this[name] as? JsonPrimitive)?.booleanOrNull

private val JSON_MEDIA_TYPE = "application/json".toMediaType()
private val STATUS_WHITESPACE = Regex("[\\r\\n\\t]+")
private val UNIX_HOME_PREFIX = Regex("^/(?:home|Users)/[^/]+(?=/|$)")
private val WINDOWS_HOME_PREFIX = Regex("^[A-Za-z]:\\\\Users\\\\[^\\\\]+(?=\\\\|$)")
private const val GIT_TIMEOUT_MILLIS = 8_000L
private const val MAX_GIT_RESPONSE_BYTES = 2 * 1024 * 1024
private const val MAX_WORKTREE_PATH = 4_096
private const val MAX_REVIEW_PATH = 1_024
private const val MAX_REVIEW_STATUS = 8
private const val MAX_SESSION_BRANCH = 512
private const val MAX_EXTERNAL_URL = 4_096
