package com.hermesagent.mobile.data.gateway

import java.util.ArrayDeque
import okio.Buffer

/**
 * A [GatewayHttp] that answers from a script and remembers what it was asked.
 *
 * Shared by every typed client written over this transport, because what those
 * tests need from a fake is identical: the request as the client shaped it
 * (path, verb, query, timeout, bound), the body it encoded, and a queue of
 * answers. Two copies of this drift; one does not. Results are handed out in
 * order and an exhausted queue answers an empty `200`, so a test only scripts
 * the hops it is actually about.
 */
internal class RecordingGatewayHttp(vararg results: GatewayHttpResult) : GatewayHttp {
    val requests = mutableListOf<GatewayHttpRequest>()
    val bodies = mutableListOf<String>()
    private val queue = ArrayDeque(results.toList())

    override suspend fun execute(request: GatewayHttpRequest): GatewayHttpResult {
        requests.add(request)
        val buffer = Buffer()
        request.body?.writeTo(buffer)
        bodies.add(buffer.readUtf8())
        return if (queue.isEmpty()) {
            GatewayHttpResult.Success(200, ByteArray(0))
        } else {
            queue.removeFirst()
        }
    }
}
