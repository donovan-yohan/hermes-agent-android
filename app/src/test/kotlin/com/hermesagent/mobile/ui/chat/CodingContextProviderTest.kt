package com.hermesagent.mobile.ui.chat

import com.hermesagent.mobile.data.gateway.GatewayHttp
import com.hermesagent.mobile.data.gateway.GatewayHttpRequest
import com.hermesagent.mobile.data.gateway.GatewayHttpResult
import java.util.ArrayDeque
import kotlinx.coroutines.test.runTest
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CodingContextProviderTest {
    @Test
    fun `authenticated status keeps exact path and resolves matching pull request`() = runTest {
        val http = QueueGatewayHttp(
            """{"branch":"feat/composer","detached":false,"ahead":2,"behind":1,"untracked":4,"added":83,"removed":37}""",
            """{"ghReady":true,"prs":[{"branch":"feat/composer","draft":false,"number":23,"state":"open","url":"https://github.com/acme/repo/pull/23"}]}""",
        )
        val provider = GatewayCodingContextProvider { http }

        val result = provider.contextFor("/home/example/work/repo") as CodingContext.Available
        val pullRequest = provider.pullRequestFor(result.worktreePath, result.branch)

        assertEquals("feat/composer", result.branch)
        assertEquals("/home/example/work/repo", result.worktreePath)
        assertEquals(83, result.additions)
        assertEquals(37, result.deletions)
        assertEquals(2, result.ahead)
        assertEquals(1, result.behind)
        assertEquals(4, result.untracked)
        assertNull(result.pullRequest)
        assertEquals(23, pullRequest?.number)
        assertEquals("api/git/status", http.requests[0].path)
        assertEquals("/home/example/work/repo", http.requests[0].query["path"])
        assertEquals("api/git/review/pr-list", http.requests[1].path)
        assertTrue(http.bodies[1].contains("\"path\":\"/home/example/work/repo\""))
        assertTrue(http.bodies[1].contains("\"feat/composer\""))
    }

    @Test
    fun `malformed status and unavailable transport make no repository claim`() = runTest {
        assertSame(CodingContext.Unavailable, GatewayCodingContextProvider { null }.contextFor("/repo"))

        val malformed = QueueGatewayHttp("""{"branch":"main","added":-1,"removed":0}""")
        assertSame(CodingContext.Unavailable, GatewayCodingContextProvider { malformed }.contextFor("/repo"))
    }

    @Test
    fun `overlong path is rejected rather than queried in truncated form`() = runTest {
        val http = QueueGatewayHttp("""{"branch":"main","added":0,"removed":0}""")

        assertSame(
            CodingContext.Unavailable,
            GatewayCodingContextProvider { http }.contextFor("/" + "x".repeat(4_096)),
        )
        assertTrue(http.requests.isEmpty())
    }

    @Test
    fun `unsafe pull request URL is ignored without dropping git status`() = runTest {
        val http = QueueGatewayHttp(
            """{"branch":"main","detached":false,"added":1,"removed":0}""",
            """{"prs":[{"branch":"main","draft":false,"number":7,"state":"open","url":"javascript:alert(1)"}]}""",
        )

        val provider = GatewayCodingContextProvider { http }
        val result = provider.contextFor("/repo") as CodingContext.Available
        assertNull(provider.pullRequestFor(result.worktreePath, result.branch))
    }

    @Test
    fun `review list parses bounded changed file truth`() = runTest {
        val http = QueueGatewayHttp(
            """{"files":[
                {"path":"app/Main.kt","added":12,"removed":3,"status":"M","staged":true},
                {"path":"app/New.kt","added":8,"removed":0,"status":"?","staged":false}
            ]}""",
        )

        val review = GatewayCodingContextProvider { http }.reviewFor("/repo") as CodingReviewResult.Available
        assertEquals(listOf("app/Main.kt", "app/New.kt"), review.files.map { it.path })
        assertEquals(12, review.files.first().additions)
        assertTrue(review.files.first().staged)
        assertEquals("uncommitted", http.requests.single().query["scope"])
    }

    @Test
    fun `display path hides only the home identity`() {
        assertEquals("~/Documents/repo", displayWorktreePath("/home/alice/Documents/repo"))
        assertEquals("~\\work\\repo", displayWorktreePath("C:\\Users\\alice\\work\\repo"))
        assertEquals("/srv/repos/shared", displayWorktreePath("/srv/repos/shared"))
    }
}

private class QueueGatewayHttp(vararg bodies: String) : GatewayHttp {
    private val responses = ArrayDeque(bodies.toList())
    val requests = mutableListOf<GatewayHttpRequest>()
    val bodies = mutableListOf<String>()

    override suspend fun execute(request: GatewayHttpRequest): GatewayHttpResult {
        requests += request
        val buffer = Buffer()
        request.body?.writeTo(buffer)
        bodies += buffer.readUtf8()
        return GatewayHttpResult.Success(200, responses.removeFirst().encodeToByteArray())
    }
}
