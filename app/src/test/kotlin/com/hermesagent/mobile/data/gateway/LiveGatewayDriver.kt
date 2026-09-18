package com.hermesagent.mobile.data.gateway

import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.security.SecureRandom
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Assert.assertTrue
import org.junit.Assume

/**
 * The opt-in lane that pairs the production Android client with a real upstream
 * Gateway, and nothing that stands in for either.
 *
 * The Gateway is the target build's own `hermes_cli.web_server.start_server`,
 * headless, imported from a read-only snapshot and bound to loopback on a port
 * this harness allocates. The client is [OkHttpGatewayRpcClient] over a real
 * socket. Neither is substituted, so the one thing this lane proves that a fake
 * cannot is exactly what #307 is about: what a *current* backend does with a
 * connection that did — or did not — advertise `server_requests`.
 *
 * It cannot be Python-only for that reason. The transport under test is a JVM
 * WebSocket, and the mobile repo has already learned what a Python-side
 * assertion about OkHttp is worth. The driver inside the server process says
 * only how the *server* settled; every claim is read back here, off the real
 * socket, through production code.
 *
 * ## Opt-in, and Gateway-free by default
 *
 * `./gradlew check` and CI never start a Gateway: without the opt-in property
 * every test here is `assumeTrue`-skipped, which JUnit reports as skipped rather
 * than passed. The lane is expected to stay local — an Android CI runner has no
 * Python toolchain and no snapshot.
 *
 * ```bash
 * ./gradlew :app:testDebugUnitTest --tests '*LiveGatewaySmokeTest*' \
 *   -Dhermes.liveGateway=1 \
 *   -Dhermes.liveGateway.driver=$PWD/tools/live-gateway-smoke/hm307_control_driver.py \
 *   -Dhermes.liveGateway.project=/path/to/hermes-agent@pin \
 *   -Dhermes.liveGateway.python=/path/to/venv/bin/python
 * ```
 *
 * The run's session token is minted here, handed to the child through its own
 * environment, and never logged, printed or written to a file. The child
 * inherits nothing else: no profile, no config, no inherited provider
 * credential, and a throwaway `HERMES_HOME`.
 */
internal class LiveGatewayDriver private constructor(
    private val process: Process,
    private val toChild: OutputStreamWriter,
    private val token: ByteArray,
    /** The child's stderr. Kept only so a failure can name what the server said. */
    private val serverLog: File,
) : AutoCloseable {

    private val markers = LinkedBlockingQueue<String>()
    private val controlLock = Any()
    private val reader = Thread {
        runCatching {
            BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8)).useLines { lines ->
                lines.forEach { markers.put(it) }
            }
        }
    }.apply {
        name = "hm307-driver-stdout"
        isDaemon = true
        start()
    }

    /** The real loopback port the Gateway inside the driver bound. */
    var port: Int = 0
        private set

    /** What the driver actually imported, as reported on its READY marker. */
    var provenance: JsonObject = JsonObject(emptyMap())
        private set

    /** The upstream commit the snapshot the server was imported from is at. */
    val snapshotSha: String get() = provenance["snapshot_sha"]?.jsonPrimitive?.content.orEmpty()

    /** The snapshot root the driver was told to import from, as it reported it. */
    val snapshotRoot: String get() = provenance["project"]?.jsonPrimitive?.content.orEmpty()

    /** The module the running server was actually loaded from. */
    val gatewayModulePath: String
        get() = provenance["gateway_server_module"]?.jsonPrimitive?.content.orEmpty()

    /** The run's token, for the client to dial with, and for redacting output. */
    fun sessionToken(): ByteArray = token.copyOf()

    /** The base URL form the Local route normalizes and reads back. */
    val baseUrl: String get() = "http://127.0.0.1:$port"

    /**
     * Text safe to put in an assertion message.
     *
     * The real server's stderr is captured to a file, and its access log line
     * carries the request line for `/api/ws`, which contains `?token=`. The
     * driver scrubs its own fd 2 before any of it is written, and this
     * independently scrubs the token again on the way out, so a failure message
     * can quote the server freely without ever carrying the run's credential.
     */
    fun redact(text: String): String {
        val secret = token.toString(Charsets.US_ASCII)
        return if (secret.isBlank()) text else text.replace(secret, REDACTED_TOKEN)
    }

    /**
     * Park one real `tui_gateway.server_requests.send` on a real bound session
     * and report how the *server* settled it.
     *
     * The driver is synchronous and one control line is written at a time, so
     * the next RESULT marker belongs to this call.
     */
    fun park(
        sessionId: String,
        params: JsonObject = buildJsonObject {
            put("question", SMOKE_QUESTION)
            putJsonArray("choices") {
                add(JsonPrimitive("a"))
                add(JsonPrimitive("b"))
            }
        },
        method: String = "clarify",
        timeoutSeconds: Int = 10,
        withinMillis: Long = 30_000,
    ): Settlement = synchronized(controlLock) {
        toChild.write(
            buildJsonObject {
                put("op", "request")
                put("session_id", sessionId)
                put("method", method)
                put("params", params)
                put("timeout", timeoutSeconds)
            }.toString(),
        )
        toChild.write("\n")
        toChild.flush()
        awaitSettlement(withinMillis)
    }

    /** The next RESULT marker, which belongs to the control line just written. */
    private fun awaitSettlement(withinMillis: Long): Settlement {
        val deadline = System.currentTimeMillis() + withinMillis
        while (true) {
            val remaining = deadline - System.currentTimeMillis()
            val line = if (remaining > 0) markers.poll(remaining, TimeUnit.MILLISECONDS) else null
            checkNotNull(line) { "the driver reported no settlement; server log:\n${serverLogTail()}" }
            if (line.startsWith(RESULT_MARKER)) return settlementOf(line)
        }
    }

    fun serverLogTail(take: Int = 2_000): String =
        redact(runCatching { serverLog.readText().takeLast(take) }.getOrDefault("<unreadable>"))

    override fun close() {
        runCatching { synchronized(controlLock) { toChild.write("{\"op\":\"quit\"}\n"); toChild.flush() } }
        runCatching { toChild.close() }
        runCatching { if (!process.waitFor(20, TimeUnit.SECONDS)) process.destroyForcibly() }
        reader.interrupt()
        token.fill(0)
    }

    /** This run's process, so a test can assert the server is still alive. */
    fun isAlive(): Boolean = process.isAlive

    /**
     * Consume markers until the driver reports its real port.
     *
     * The driver only writes this once `GET /api/health` answered, so a READY
     * here means a live Gateway, not merely a process that started.
     */
    fun awaitReady(withinMillis: Long = 180_000) {
        val deadline = System.currentTimeMillis() + withinMillis
        while (true) {
            val remaining = deadline - System.currentTimeMillis()
            val line = if (remaining > 0) markers.poll(remaining, TimeUnit.MILLISECONDS) else null
            when {
                line == null -> error("the driver never reached READY; server log:\n${serverLogTail()}")
                line.startsWith(READY_MARKER) -> {
                    port = portOf(line)
                    provenance = provenanceOf(line)
                    check(port > 0) { "the driver's READY marker named no port: $line" }
                    return
                }

                line.startsWith(ERROR_MARKER) -> error("the driver failed: $line")
            }
        }
    }

    /** How the server's own `send()` returned for one parked request. */
    internal data class Settlement(
        val sessionId: String,
        val method: String,
        /** The server's own gate, read immediately before the send. */
        val answerable: Boolean,
        /** False when the wait returned without an answer (refused, cancelled, timed out). */
        val settled: Boolean,
        /** The `answer` the server read out of the client's response frame, when there was one. */
        val answer: String?,
        val elapsedMillis: Long,
    )

    internal data class Config(val driver: File, val project: File, val python: File)

    internal companion object {
        const val OPT_IN = "hermes.liveGateway"
        const val SMOKE_QUESTION = "hm307 smoke question"
        private const val REDACTED_TOKEN = "<redacted>"
        private const val READY_MARKER = "HM307 READY"
        private const val RESULT_MARKER = "HM307 RESULT"
        private const val ERROR_MARKER = "HM307 ERROR"
        private val PORT = Regex("port=(\\d+)")
        private val PROVENANCE = Regex("provenance=(\\{.*\\})")

        /**
         * The opt-in configuration.
         *
         * Not opted in: an assumption failure, which JUnit reports as *skipped*
         * — the lane must never look like it ran.
         *
         * Opted in: every path is **required**. A misconfigured invocation fails
         * loudly, because "explicitly asked for a live Gateway and got a silent
         * skip" is the exact false confidence this slice exists to remove.
         */
        fun optIn(): Config {
            Assume.assumeTrue(
                "$OPT_IN is not set: this test drives a real upstream Gateway process over a real " +
                    "socket, so it is opt-in and deliberately absent from check and CI.",
                System.getProperty(OPT_IN) != null,
            )
            fun required(suffix: String): File {
                val name = "$OPT_IN.$suffix"
                val value = System.getProperty(name)
                assertTrue("$name must be set when $OPT_IN is enabled", !value.isNullOrBlank())
                val file = File(value!!)
                assertTrue("$name does not exist: $file", file.exists())
                return file
            }
            return Config(
                driver = required("driver"),
                project = required("project"),
                python = required("python"),
            )
        }

        /**
         * Starts the driver, which starts the real Gateway inside it, and waits
         * for its READY marker (which is written only after the real health
         * route answered).
         *
         * The child's environment is **built from nothing**. This test runs
         * inside a live Hermes session on the dev host, so inheriting that
         * session's environment would point the probe at the user's own runtime,
         * config and credentials. Only what is set below reaches the child.
         */
        fun start(config: Config): LiveGatewayDriver {
            val installDir = kotlin.io.path.createTempDirectory("hm307-live-gateway").toFile()
            installDir.setReadable(false, false)
            installDir.setReadable(true, true)
            installDir.setWritable(false, false)
            installDir.setWritable(true, true)
            installDir.setExecutable(false, false)
            installDir.setExecutable(true, true)
            val hermesHome = File(installDir, "hermes-home").apply { mkdirs() }
            val home = File(installDir, "home").apply { mkdirs() }
            val serverLog = File(installDir, "server.log")
            val token = randomToken()

            val builder = ProcessBuilder(
                config.python.absolutePath,
                config.driver.absolutePath,
                "--project",
                config.project.absolutePath,
            )
            val environment = builder.environment()
            environment.clear()
            // The canonical name, and no other: `hermes_cli/web_server.py::
            // _resolve_session_token` reads exactly this variable and mints a
            // random one of its own when it is absent or empty, so an
            // obfuscated name here authenticates nothing. Every other Hermes
            // and provider variable is absent, which is the actual protection;
            // the driver also re-cleans its own environment before importing
            // the server.
            environment["HERMES_HOME"] = hermesHome.absolutePath
            environment["HERMES_DASHBOARD_SESSION_TOKEN"] = token.toString(Charsets.US_ASCII)
            environment["HOME"] = home.absolutePath
            environment["PATH"] = System.getenv("PATH") ?: "/usr/bin:/bin"
            environment["LANG"] = "C.UTF-8"
            environment["TZ"] = "UTC"
            builder.redirectError(serverLog)

            val process = builder.start()
            val driver = LiveGatewayDriver(
                process = process,
                toChild = OutputStreamWriter(process.outputStream, Charsets.UTF_8),
                token = token,
                serverLog = serverLog,
            )
            try {
                driver.awaitReady(180_000)
            } catch (failure: Throwable) {
                driver.close()
                throw failure
            }
            return driver
        }

        /**
         * A run-scoped token. `hermes_cli/web_server.py::_resolve_session_token`
         * returns exactly this environment value when it is set, so the harness
         * knows the token without reading a file and without touching the
         * user's own token.
         */
        private fun randomToken(): ByteArray {
            val bytes = ByteArray(32)
            SecureRandom().nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }.toByteArray(Charsets.US_ASCII)
        }

        private fun settlementOf(line: String): Settlement {
            val payload = Json.parseToJsonElement(line.substringAfter(RESULT_MARKER).trim()).jsonObject
            val answer = (payload["result"] as? JsonObject)
                ?.get("answer")
                ?.let { (it as? JsonPrimitive)?.content }
            return Settlement(
                sessionId = payload["session_id"]?.jsonPrimitive?.content.orEmpty(),
                method = payload["method"]?.jsonPrimitive?.content.orEmpty(),
                answerable = payload["answerable"]?.jsonPrimitive?.content == "true",
                settled = payload["settled"]?.jsonPrimitive?.content == "true",
                answer = answer,
                elapsedMillis = payload["elapsed_ms"]?.jsonPrimitive?.content?.toLongOrNull() ?: -1,
            )
        }

        private fun portOf(line: String): Int =
            PORT.find(line)?.groupValues?.get(1)?.toIntOrNull() ?: 0

        private fun provenanceOf(line: String): JsonObject = PROVENANCE.find(line)
            ?.let { runCatching { Json.parseToJsonElement(it.groupValues[1]).jsonObject }.getOrNull() }
            ?: JsonObject(emptyMap())
    }
}
