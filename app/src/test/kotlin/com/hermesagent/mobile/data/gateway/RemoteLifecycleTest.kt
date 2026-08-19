package com.hermesagent.mobile.data.gateway

import com.hermesagent.mobile.data.ssh.SshForward
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteLifecycleTest {

    @Test
    fun `startup writes the exact pinned ownership lock before bounded readiness`() = runTest {
        val runner = LifecycleRunner()
        val lifecycle = lifecycle(runner)

        val backend = lifecycle.start(OWNERSHIP_ID, RemoteHermesConfig(profile = "test-profile"))

        assertEquals(43117, backend.remotePort)
        assertEquals(0, backend.process.remotePort)
        assertEquals(64, backend.token.size)
        assertEquals("$TOKEN_OWNER_DIR/$START_NONCE.token", backend.process.tokenPath)
        assertEquals("$PROFILE_OWNER_DIR/$START_NONCE.log", backend.process.logPath)
        assertEquals(32, backend.process.tokenFingerprint.length)
        assertTrue(backend.process.tokenFingerprint.all { it in "0123456789abcdef" })

        val upload = runner.calls.single { it.stdin != null && it.command.contains("O_EXCL") }
        assertEquals(backend.token.toList(), upload.stdin?.toList())
        assertTrue(upload.command.contains("O_NOFOLLOW"))
        assertTrue(upload.command.contains("O_WRONLY"))
        assertTrue(upload.command.contains("dir_fd=dd"))
        assertTrue(upload.command.contains("os.fstat(dd)"))
        assertTrue(upload.command.contains("os.fstat(fd)"))
        assertTrue(upload.command.contains("read(65)"))

        val allArgv = runner.calls.joinToString("\n") { it.command }
        assertFalse("the token must never enter remote argv", allArgv.contains(backend.token.toString(Charsets.US_ASCII)))
        assertTrue(allArgv.contains("bash -lc 'command -v hermes'"))
        assertTrue(allArgv.contains("--profile 'test-profile' serve"))
        assertTrue(allArgv.contains("--host 127.0.0.1 --port 0"))
        assertTrue(
            allArgv.contains(
                "env -u HERMES_PROFILE HERMES_HOME='/srv/test-home/.hermes/profiles/test-profile' HERMES_DESKTOP=1",
            ),
        )

        val spawnLockWrites = runner.calls.filter { it.stdin?.firstOrNull() == '{'.code.toByte() }
        assertEquals(1, spawnLockWrites.size)
        val firstLockIndex = runner.calls.indexOf(spawnLockWrites.single())
        val readinessIndex = runner.calls.indexOfFirst { it.command.startsWith("cat --") }
        assertTrue("ownership must be recorded before readiness is consumed", firstLockIndex in 0 until readinessIndex)

        val spawnLock = Json.parseToJsonElement(spawnLockWrites.single().stdin!!.toString(Charsets.UTF_8)).jsonObject
        assertPinnedLock(spawnLock, port = 0)

        backend.adoptServedToken(null)
        val lockWrites = runner.calls.filter { it.stdin?.firstOrNull() == '{'.code.toByte() }
        assertEquals(2, lockWrites.size)
        val readyLock = Json.parseToJsonElement(lockWrites.last().stdin!!.toString(Charsets.UTF_8)).jsonObject
        assertPinnedLock(readyLock, port = 43117)
        assertFalse(lockWrites.any { it.stdin!!.toString(Charsets.UTF_8).contains(backend.token.toString(Charsets.US_ASCII)) })

        backend.shutdown()
        assertTrue(runner.calls.any { it.command == "kill -TERM -- 4242" })
        assertTrue(runner.calls.any { it.command.contains("hashlib.sha256") })
        assertTrue(runner.calls.any { it.command.contains("expected_records") })
        assertFalse(runner.calls.any { it.command.contains("kill -KILL") || it.command.contains("rm -f") })
    }

    @Test
    fun `served token adoption wipes spawn token and publishes its final fingerprint`() = runTest {
        val runner = LifecycleRunner()
        val backend = lifecycle(runner).start(OWNERSHIP_ID, RemoteHermesConfig(profile = "test-profile"))
        val spawnToken = runner.uploadedToken ?: error("fixture did not observe token upload")
        val spawnFingerprint = backend.process.tokenFingerprint
        val servedToken = "served-token_fixture-123".toByteArray(Charsets.US_ASCII)

        backend.adoptServedToken(servedToken)

        assertTrue(spawnToken.all { it == 0.toByte() })
        assertTrue(backend.token === servedToken)
        assertTrue(backend.token.any { it != 0.toByte() })
        assertFalse(spawnFingerprint == backend.process.tokenFingerprint)
        assertEquals(backend.process.tokenArtifactFingerprint, spawnFingerprint)
        val finalLock = runner.calls.last { it.stdin?.firstOrNull() == '{'.code.toByte() }
            .stdin!!.toString(Charsets.UTF_8).let(Json::parseToJsonElement).jsonObject
        assertEquals(43117, finalLock.getValue("port").jsonPrimitive.int)
        assertEquals(
            backend.process.tokenFingerprint,
            finalLock.getValue("tokenFingerprint").jsonPrimitive.content,
        )
        assertEquals(
            "~/.hermes/profiles/test-profile/desktop-ssh/$OWNERSHIP_ID/$START_NONCE.log",
            finalLock.getValue("logPath").jsonPrimitive.content,
        )

        backend.shutdown()
        assertTrue(servedToken.all { it == 0.toByte() })
    }

    @Test
    fun `custom login Hermes home keeps the Android lock child and reaper on one effective root`() = runTest {
        val inheritedHermesHome = "/srv/custom-hermes-home"
        val customProfileOwnerDir = "$inheritedHermesHome/profiles/test-profile/desktop-ssh/$OWNERSHIP_ID"
        val runner = LifecycleRunner(inheritedHermesHome = inheritedHermesHome)

        val backend = lifecycle(runner).start(
            OWNERSHIP_ID,
            RemoteHermesConfig(profile = "test-profile"),
        )

        assertFalse(inheritedHermesHome == "/srv/test-home/.hermes")
        assertEquals("$inheritedHermesHome/profiles/test-profile", backend.process.hermesHome)
        assertEquals("$TOKEN_OWNER_DIR/$START_NONCE.token", backend.process.tokenPath)
        assertEquals("$customProfileOwnerDir/backend.lock.json", backend.process.lockPath)
        assertEquals("$customProfileOwnerDir/$START_NONCE.log", backend.process.logPath)
        assertTrue(
            runner.calls.any {
                it.command == "test -d '$inheritedHermesHome/profiles/test-profile'"
            },
        )
        val spawn = runner.calls.single { it.command.contains("nohup setsid") }.command
        assertTrue(
            spawn.contains(
                "env -u HERMES_PROFILE HERMES_HOME='$inheritedHermesHome/profiles/test-profile' HERMES_DESKTOP=1",
            ),
        )
        assertTrue(spawn.contains("--profile 'test-profile' serve"))
        val capability = runner.calls.single { it.command.contains("serve --help") }.command
        assertTrue(capability.startsWith("env -u HERMES_PROFILE HERMES_HOME='$inheritedHermesHome/profiles/test-profile'"))
        assertTrue(capability.contains("--profile 'test-profile' serve --help"))
        assertTrue(
            runner.calls.any {
                it.command.startsWith("bash -lc ") &&
                    it.command.contains("\${HERMES_HOME:-\$HOME/.hermes}")
            },
        )
        val lock = runner.calls.single { it.stdin?.firstOrNull() == '{'.code.toByte() }
            .stdin!!.toString(Charsets.UTF_8).let(Json::parseToJsonElement).jsonObject
        assertEquals(backend.process.hermesHome, lock.getValue("hermesHome").jsonPrimitive.content)
        assertEquals(
            "$customProfileOwnerDir/$START_NONCE.log",
            lock.getValue("logPath").jsonPrimitive.content,
        )

        backend.shutdown()
    }

    @Test
    fun `explicit default profile keeps a custom Hermes root and clears inherited profile selection`() = runTest {
        val customHermesHome = "/srv/custom-hermes-home"
        val ownerDir = "$customHermesHome/desktop-ssh/$OWNERSHIP_ID"
        val runner = LifecycleRunner(inheritedHermesHome = customHermesHome)

        val backend = lifecycle(runner).start(OWNERSHIP_ID, RemoteHermesConfig())

        assertEquals("default", backend.process.profile)
        assertEquals(customHermesHome, backend.process.hermesHome)
        assertEquals("$TOKEN_OWNER_DIR/$START_NONCE.token", backend.process.tokenPath)
        assertEquals("$ownerDir/backend.lock.json", backend.process.lockPath)
        assertEquals("$ownerDir/$START_NONCE.log", backend.process.logPath)
        val spawn = runner.calls.single { it.command.contains("nohup setsid") }.command
        assertTrue(
            spawn.contains(
                "env -u HERMES_PROFILE HERMES_HOME='$customHermesHome' HERMES_DESKTOP=1",
            ),
        )
        assertTrue(spawn.contains("--profile 'default' serve"))
        val lock = runner.calls.single { it.stdin?.firstOrNull() == '{'.code.toByte() }
            .stdin!!.toString(Charsets.UTF_8).let(Json::parseToJsonElement).jsonObject
        assertEquals("default", lock.getValue("profile").jsonPrimitive.content)
        assertEquals(customHermesHome, lock.getValue("hermesHome").jsonPrimitive.content)
        assertEquals("$ownerDir/$START_NONCE.log", lock.getValue("logPath").jsonPrimitive.content)

        backend.shutdown()
    }

    @Test
    fun `dead child rejects mismatched served token as foreign and wipes it`() = runTest {
        val runner = LifecycleRunner(processDead = true)
        val backend = lifecycle(runner).start(OWNERSHIP_ID, RemoteHermesConfig(profile = "test-profile"))
        val servedToken = "foreign-token_fixture-456".toByteArray(Charsets.US_ASCII)
        var failure: Throwable? = null

        try {
            backend.adoptServedToken(servedToken)
        } catch (caught: Throwable) {
            failure = caught
        }

        assertTrue(failure is RemoteLifecycleException)
        assertTrue(failure?.message?.contains("different process") == true)
        assertTrue(servedToken.all { it == 0.toByte() })
        assertEquals(0, backend.process.remotePort)
        assertEquals(1, runner.calls.count { it.stdin?.firstOrNull() == '{'.code.toByte() })
        backend.shutdown()
    }

    @Test
    fun `final lock failure before rename cleans exact port-zero lock and final temp`() = runTest {
        assertFinalLockFailureCleanup(afterRename = false)
    }

    @Test
    fun `final lock failure after rename cleans exact positive lock`() = runTest {
        assertFinalLockFailureCleanup(afterRename = true)
    }

    @Test
    fun `readiness accepts only an exact bounded marker and port`() {
        assertEquals(1234, parseReadyPort("HERMES_BACKEND_READY port=1234"))
        assertEquals(65535, parseReadyPort("noise\nHERMES_DASHBOARD_READY port=65535\n"))
        assertEquals(null, parseReadyPort("prefix HERMES_BACKEND_READY port=22"))
        assertEquals(null, parseReadyPort("HERMES_BACKEND_READY port=65536"))
        assertEquals(null, parseReadyPort("HERMES_BACKEND_READY port=22 trailing"))
    }

    @Test
    fun `shell quoting is literal and hostile configured inputs are rejected`() {
        assertEquals("'plain'", posixQuote("plain"))
        assertEquals("'a'\"'\"'b'", posixQuote("a'b"))
        assertThrows(IllegalArgumentException::class.java) { posixQuote("line\nbreak") }
        assertThrows(IllegalArgumentException::class.java) { requireProfile("name; touch bad") }
        assertThrows(IllegalArgumentException::class.java) { requireExecutable("relative/hermes") }
        assertThrows(IllegalArgumentException::class.java) { requireExecutable("/tmp/../bin/hermes") }
    }

    @Test
    fun `explicit executable is validated and bypasses discovery without rewriting the launcher`() = runTest {
        val runner = LifecycleRunner()
        val backend = lifecycle(runner).start(
            OWNERSHIP_ID,
            RemoteHermesConfig(executable = "/opt/hermes/bin/hermes"),
        )

        assertEquals("/opt/hermes/bin/hermes", backend.process.executable)
        assertFalse(runner.calls.any { it.command.contains("command -v hermes") })
        assertTrue(runner.calls.any { it.command == "test -x '/opt/hermes/bin/hermes'" })
        assertTrue(runner.calls.any { it.command.contains("'/opt/hermes/bin/hermes' --profile 'default' serve --isolated") })
        val lock = runner.calls.last { it.stdin?.firstOrNull() == '{'.code.toByte() }
        val profile = Json.parseToJsonElement(lock.stdin!!.toString(Charsets.UTF_8))
            .jsonObject.getValue("profile").jsonPrimitive.content
        assertEquals("default", profile)
    }

    @Test
    fun `pid and lock strings obey the pinned validator bounds`() = runTest {
        val oversizedPid = LifecycleRunner(spawnOutput = "4194305")
        var pidFailure: Throwable? = null
        try {
            lifecycle(oversizedPid).start(OWNERSHIP_ID, RemoteHermesConfig())
        } catch (failure: Throwable) {
            pidFailure = failure
        }
        assertTrue(pidFailure is RemoteLifecycleException)
        assertTrue(oversizedPid.calls.any { it.command.contains("hashlib.sha256") })

        val oversizedStartedAt = LifecycleRunner()
        var stringFailure: Throwable? = null
        try {
            lifecycle(oversizedStartedAt, startedAt = "x".repeat(1_025)).start(OWNERSHIP_ID, RemoteHermesConfig())
        } catch (failure: Throwable) {
            stringFailure = failure
        }
        assertTrue(stringFailure is IllegalArgumentException)
        assertFalse(oversizedStartedAt.calls.any { it.stdin != null && it.command.contains("O_EXCL") })
    }

    @Test
    fun `foreign pid is never signalled or cleaned`() = runTest {
        val runner = LifecycleRunner(foreignArgv = true)
        RemoteHermesLifecycle(runner, wait = {}).cleanup(process())

        assertFalse(runner.calls.any { it.command.startsWith("kill -TERM") })
        assertFalse(runner.calls.any { it.command.contains("hashlib.sha256") || it.command.contains("expected_records") })
    }

    @Test
    fun `dead pid requires two observations before descriptor guarded cleanup`() = runTest {
        val runner = LifecycleRunner(processDead = true)
        var waits = 0
        RemoteHermesLifecycle(runner, wait = { waits += 1 }).cleanup(process())

        assertEquals(1, waits)
        assertEquals(2, runner.calls.count { it.command.contains("/proc/4242/cmdline") })
        assertFalse(runner.calls.any { it.command.startsWith("kill -TERM") })
        val tokenCleanup = runner.calls.single { it.command.contains("hashlib.sha256") }
        assertTrue(tokenCleanup.command.contains("O_NOFOLLOW"))
        assertTrue(tokenCleanup.command.contains("st_ino"))
        assertTrue(tokenCleanup.command.contains("st_size==64"))
        val lockCleanup = runner.calls.single { it.command.contains("expected_records") }
        assertTrue(lockCleanup.command.contains("type(parsed.get(\"pid\")) is int"))
        assertTrue(lockCleanup.command.contains(process().tokenFingerprint))
        assertFalse(runner.calls.any { it.command.contains("rm -f") })
    }

    @Test
    fun `installer wrapper cleanup sends term then requires two dead observations before guarded cleanup`() = runTest {
        val runner = LifecycleRunner(wrapperArgv = true)
        var waits = 0

        RemoteHermesLifecycle(runner, wait = { waits += 1 }).cleanup(process())

        assertEquals(2, waits)
        assertEquals(3, runner.calls.count { it.command.contains("/proc/4242/cmdline") })
        assertEquals(1, runner.calls.count { it.command == "kill -TERM -- 4242" })
        val tokenCleanup = runner.calls.single { it.command.contains("hashlib.sha256") }
        assertTrue(tokenCleanup.command.contains("O_NOFOLLOW"))
        assertTrue(tokenCleanup.command.contains("dir_fd=dd"))
        assertTrue(tokenCleanup.command.contains("st_ino"))
        val lockCleanup = runner.calls.single { it.command.contains("expected_records") }
        assertTrue(lockCleanup.command.contains("O_NOFOLLOW"))
        assertTrue(lockCleanup.command.contains("type(parsed.get(\"pid\")) is int"))
        assertFalse(runner.calls.any { it.command.contains("kill -KILL") || it.command.contains("rm -f") })
    }

    @Test
    fun `term survivor retains every artifact and is never escalated to kill`() = runTest {
        val runner = LifecycleRunner(survivesTerm = true)
        var waits = 0
        RemoteHermesLifecycle(runner, wait = { waits += 1 }).cleanup(process())

        assertEquals(50, waits)
        assertEquals(51, runner.calls.count { it.command.contains("/proc/4242/cmdline") })
        assertTrue(runner.calls.any { it.command == "kill -TERM -- 4242" })
        assertFalse(runner.calls.any { it.command.contains("kill -KILL") })
        assertFalse(runner.calls.any { it.command.contains("hashlib.sha256") || it.command.contains("expected_records") })
    }

    @Test
    fun `ownership uncertainty after term retains every artifact`() = runTest {
        val runner = LifecycleRunner(foreignAfterTerm = true)
        RemoteHermesLifecycle(runner, wait = {}).cleanup(process())

        assertTrue(runner.calls.any { it.command == "kill -TERM -- 4242" })
        assertEquals(2, runner.calls.count { it.command.contains("/proc/4242/cmdline") })
        assertFalse(runner.calls.any { it.command.contains("hashlib.sha256") || it.command.contains("expected_records") })
    }

    @Test
    fun `cancelled token upload attempts fingerprint guarded cleanup and wipes the live token`() = runTest {
        val runner = LifecycleRunner(blockTokenUpload = true)
        val startup = async { lifecycle(runner).start(OWNERSHIP_ID, RemoteHermesConfig()) }

        runner.tokenUploadStarted.await()
        startup.cancelAndJoin()

        assertTrue(startup.isCancelled)
        assertFalse(runner.calls.any { it.command.contains("nohup setsid") })
        assertTrue(runner.calls.any { it.command.contains("hashlib.sha256") })
        assertTrue(runner.uploadedToken?.all { it == 0.toByte() } == true)
    }

    @Test
    fun `failed token upload attempts fingerprint guarded cleanup and preserves the failure`() = runTest {
        val runner = LifecycleRunner(failTokenUpload = true)
        var failure: Throwable? = null

        try {
            lifecycle(runner).start(OWNERSHIP_ID, RemoteHermesConfig())
        } catch (caught: Throwable) {
            failure = caught
        }

        assertTrue(failure is RemoteLifecycleException)
        assertFalse(runner.calls.any { it.command.contains("nohup setsid") })
        assertTrue(runner.calls.any { it.command.contains("hashlib.sha256") })
        assertTrue(runner.uploadedToken?.all { it == 0.toByte() } == true)
    }

    @Test
    fun `invalid spawn result attempts fingerprint guarded token cleanup`() = runTest {
        val runner = LifecycleRunner(spawnOutput = "not-a-pid")
        var failure: Throwable? = null

        try {
            lifecycle(runner).start(OWNERSHIP_ID, RemoteHermesConfig())
        } catch (caught: Throwable) {
            failure = caught
        }

        assertTrue(failure is RemoteLifecycleException)
        assertTrue(runner.calls.any { it.command.contains("hashlib.sha256") })
        assertFalse(runner.calls.any { it.command.startsWith("kill -TERM") })
        assertTrue(runner.uploadedToken?.all { it == 0.toByte() } == true)
    }

    @Test
    fun `cancelled startup cleans a proven process only after confirmed death`() = runTest {
        val runner = LifecycleRunner(blockFirstLockWrite = true)
        val startup = async {
            lifecycle(runner).start(OWNERSHIP_ID, RemoteHermesConfig(profile = "test-profile"))
        }
        runner.firstLockWrite.await()
        startup.cancelAndJoin()

        assertTrue(startup.isCancelled)
        assertTrue(runner.calls.any { it.command.contains("/proc/4242/cmdline") })
        assertTrue(runner.calls.any { it.command == "kill -TERM -- 4242" })
        assertTrue(runner.calls.any { it.command.contains("hashlib.sha256") })
        assertTrue(runner.calls.any { it.command.contains("expected_records") })
        assertTrue(runner.uploadedToken?.all { it == 0.toByte() } == true)
    }

    @Test
    fun `ownership proof accepts direct wrapper and exact spawn proof without weakening foreign rejection`() {
        val process = process()
        val owned = listOf(
            process.executable,
            "--profile", process.profile!!,
            "serve",
            "--isolated",
            "--ssh-session-token-file", process.tokenPath,
            "--ssh-owner-nonce", process.nonce,
        )
        val wrapper = listOf(
            "/srv/test-home/.hermes/hermes-agent/venv/bin/python3",
            "/srv/test-home/.hermes/hermes-agent/venv/bin/hermes",
        ) + owned.drop(1)
        val spawnProof = listOf("/opt/installer/runtime") + owned.drop(1)
        assertTrue(ownsProcess(process, owned))
        assertTrue(ownsProcess(process, wrapper))
        assertTrue(ownsProcess(process, spawnProof))
        assertFalse(ownsProcess(process, owned.map { if (it == process.nonce) "0000000000000000" else it }))
        assertFalse(ownsProcess(process, owned + listOf("--ssh-owner-nonce", process.nonce)))
        assertFalse(ownsProcess(process, wrapper.map { if (it == process.tokenPath) "$TOKEN_OWNER_DIR/foreign.token" else it }))
        assertFalse(ownsProcess(process, wrapper.map { if (it == "--isolated") "--foreign" else it }))
        assertFalse(ownsProcess(process, wrapper + listOf("--profile", process.profile)))
        assertFalse(ownsProcess(process.copy(profile = "other"), owned))
    }

    private fun lifecycle(
        runner: LifecycleRunner,
        startedAt: String = FIXED_STARTED_AT,
    ) = RemoteHermesLifecycle(
        runner = runner,
        randomBytes = { size -> ByteArray(size) { index -> (index + size).toByte() } },
        wait = {},
        startedAt = { startedAt },
    )

    private suspend fun assertFinalLockFailureCleanup(afterRename: Boolean) {
        val runner = LifecycleRunner(
            failFinalLockWriteBeforeRename = !afterRename,
            failFinalLockWriteAfterRename = afterRename,
        )
        val backend = lifecycle(runner).start(OWNERSHIP_ID, RemoteHermesConfig(profile = "test-profile"))
        val servedToken = "served-token_lock-failure".toByteArray(Charsets.US_ASCII)
        var failure: Throwable? = null

        try {
            backend.adoptServedToken(servedToken)
        } catch (caught: Throwable) {
            failure = caught
        }

        assertTrue(failure is RemoteLifecycleException)
        assertEquals(if (afterRename) 43117 else 0, runner.currentLockPort())
        assertEquals(!afterRename, runner.hasTemporaryLock())

        backend.shutdown()

        assertTrue(servedToken.all { it == 0.toByte() })
        assertTrue(runner.tokenCleaned)
        assertTrue(runner.lockCleaned)
        assertTrue(runner.logCleaned)
        assertEquals(!afterRename, runner.tempCleaned)
        assertEquals(null, runner.currentLockPort())
        assertFalse(runner.hasTemporaryLock())
        val writtenLocks = runner.calls.filter { it.stdin?.firstOrNull() == '{'.code.toByte() }
            .map { Json.parseToJsonElement(it.stdin!!.toString(Charsets.UTF_8)).jsonObject }
        val cleanup = runner.calls.single { it.command.contains("expected_records") }
        assertTrue(writtenLocks.all { cleanup.command.contains(it.toString()) })
        assertTrue(cleanup.command.contains("temp_name"))
        assertTrue(cleanup.command.contains("O_NOFOLLOW"))
    }

    private fun assertPinnedLock(lock: JsonObject, port: Int) {
        // Exact shared schema from f82f2dbabd9e66b714f2b4f8a40447fe0c13e732:
        // hermes_cli/dashboard_procs.py:741-783 and
        // apps/desktop/electron/remote-lifecycle.test.ts:43-58.
        assertEquals(
            setOf(
                "schemaVersion", "protocolVersion", "ownershipId", "spawnNonce", "pid", "port", "profile",
                "hermesPath", "hermesHome", "logPath", "tokenFingerprint", "startedAt",
            ),
            lock.keys,
        )
        assertEquals(2, lock.getValue("schemaVersion").jsonPrimitive.int)
        assertEquals(1, lock.getValue("protocolVersion").jsonPrimitive.int)
        assertEquals(OWNERSHIP_ID, lock.getValue("ownershipId").jsonPrimitive.content)
        assertEquals(START_NONCE, lock.getValue("spawnNonce").jsonPrimitive.content)
        assertEquals(4242L, lock.getValue("pid").jsonPrimitive.long)
        assertEquals(port, lock.getValue("port").jsonPrimitive.int)
        assertEquals("test-profile", lock.getValue("profile").jsonPrimitive.content)
        assertEquals("/usr/local/bin/hermes", lock.getValue("hermesPath").jsonPrimitive.content)
        assertEquals("/srv/test-home/.hermes/profiles/test-profile", lock.getValue("hermesHome").jsonPrimitive.content)
        assertEquals(
            "~/.hermes/profiles/test-profile/desktop-ssh/$OWNERSHIP_ID/$START_NONCE.log",
            lock.getValue("logPath").jsonPrimitive.content,
        )
        assertEquals(32, lock.getValue("tokenFingerprint").jsonPrimitive.content.length)
        assertEquals(FIXED_STARTED_AT, lock.getValue("startedAt").jsonPrimitive.content)
        assertTrue(lock.getValue("logPath").jsonPrimitive.content.endsWith("/$OWNERSHIP_ID/$START_NONCE.log"))
    }

    private fun process() = OwnedRemoteProcess(
        pid = 4242,
        executable = "/usr/local/bin/hermes",
        profile = "test-profile",
        home = "/srv/test-home",
        hermesHome = "/srv/test-home/.hermes/profiles/test-profile",
        ownershipId = OWNERSHIP_ID,
        nonce = "0011223344556677",
        tokenPath = "$TOKEN_OWNER_DIR/0011223344556677.token",
        tokenArtifactFingerprint = "0123456789abcdef0123456789abcdef",
        tokenFingerprint = "0123456789abcdef0123456789abcdef",
        lockPath = "$PROFILE_OWNER_DIR/backend.lock.json",
        logPath = "$PROFILE_OWNER_DIR/0011223344556677.log",
        startedAt = FIXED_STARTED_AT,
        remotePort = 43117,
    )

    private data class Call(val command: String, val stdin: ByteArray?)

    private class LifecycleRunner(
        private val foreignArgv: Boolean = false,
        private val processDead: Boolean = false,
        private val blockFirstLockWrite: Boolean = false,
        private val blockTokenUpload: Boolean = false,
        private val failTokenUpload: Boolean = false,
        private val survivesTerm: Boolean = false,
        private val foreignAfterTerm: Boolean = false,
        private val wrapperArgv: Boolean = false,
        private val spawnOutput: String = "4242",
        private val failFinalLockWriteBeforeRename: Boolean = false,
        private val failFinalLockWriteAfterRename: Boolean = false,
        private val inheritedHermesHome: String = "/srv/test-home/.hermes",
    ) : RemoteCommandRunner {
        val calls = mutableListOf<Call>()
        val firstLockWrite = CompletableDeferred<Unit>()
        val tokenUploadStarted = CompletableDeferred<Unit>()
        var uploadedToken: ByteArray? = null
        var tokenCleaned = false
        var lockCleaned = false
        var logCleaned = false
        var tempCleaned = false
        private var lockWrites = 0
        private var termSent = false
        private var lock: JsonObject? = null
        private var temporaryLock: JsonObject? = null

        override suspend fun exec(
            command: String,
            stdin: ByteArray?,
            maxBytes: Int,
            timeoutMillis: Long,
        ): RemoteExec {
            calls += Call(command, stdin?.copyOf())
            if (stdin != null && command.contains("O_EXCL")) {
                uploadedToken = stdin
                tokenUploadStarted.complete(Unit)
                if (blockTokenUpload) awaitCancellation()
                if (failTokenUpload) throw RemoteLifecycleException("fixture upload failed")
            }
            if (stdin?.firstOrNull() == '{'.code.toByte()) {
                val incoming = Json.parseToJsonElement(stdin.toString(Charsets.UTF_8)).jsonObject
                val writeIndex = lockWrites++
                if (writeIndex == 0) lock = incoming
                if (blockFirstLockWrite && writeIndex == 0) {
                    firstLockWrite.complete(Unit)
                    awaitCancellation()
                }
                if (writeIndex > 0 && failFinalLockWriteBeforeRename) {
                    temporaryLock = incoming
                    return RemoteExec(byteArrayOf(), byteArrayOf(), 1, false)
                }
                if (writeIndex > 0) {
                    lock = incoming
                    temporaryLock = null
                    if (failFinalLockWriteAfterRename) {
                        return RemoteExec(byteArrayOf(), byteArrayOf(), 1, false)
                    }
                }
            }
            if (command.contains("hashlib.sha256")) tokenCleaned = true
            if (command.contains("expected_records=")) {
                temporaryLock?.takeIf { command.contains(it.toString()) }?.let {
                    tempCleaned = true
                    temporaryLock = null
                }
                lock?.takeIf { command.contains(it.toString()) }?.let {
                    lockCleaned = true
                    logCleaned = true
                    lock = null
                }
            }
            if (command == "kill -TERM -- 4242") termSent = true

            val stdout = when {
                command == "uname -s" -> "Linux"
                command == "uname -m" -> "x86_64"
                command.contains("\${HERMES_HOME") -> inheritedHermesHome
                command == "printf '%s' \"\$HOME\"" -> "/srv/test-home"
                command.startsWith("bash -lc") -> "/usr/local/bin/hermes"
                command.contains("serve --help") -> "--ssh-session-token-file --ssh-owner-nonce"
                command.contains("nohup setsid") -> spawnOutput
                command.startsWith("cat --") -> "HERMES_BACKEND_READY port=43117\n"
                command.contains("/proc/4242/cmdline") -> inspection()
                else -> ""
            }
            return RemoteExec(stdout.toByteArray(), byteArrayOf(), 0, false)
        }

        private fun inspection(): String = when {
            processDead || (termSent && !survivesTerm && !foreignAfterTerm) -> "DEAD\n"
            foreignArgv || (termSent && foreignAfterTerm) -> "ALIVE\n/usr/bin/python\nforeign.py\n"
            else -> "ALIVE\n" + ownedArgv().let { argv ->
                if (wrapperArgv) {
                    listOf(
                        "/srv/test-home/.hermes/hermes-agent/venv/bin/python3",
                        "/srv/test-home/.hermes/hermes-agent/venv/bin/hermes",
                    ) + argv.drop(1)
                } else {
                    argv
                }
            }.joinToString("\n")
        }

        private fun ownedArgv(): List<String> {
            val current = lock
            val executable = current?.get("hermesPath")?.jsonPrimitive?.content ?: "/usr/local/bin/hermes"
            val profile = current?.get("profile")?.jsonPrimitive?.content ?: "test-profile"
            val nonce = current?.get("spawnNonce")?.jsonPrimitive?.content ?: "0011223344556677"
            val ownershipId = current?.get("ownershipId")?.jsonPrimitive?.content ?: OWNERSHIP_ID
            return buildList {
                add(executable)
                if (profile.isNotEmpty()) addAll(listOf("--profile", profile))
                addAll(
                    listOf(
                        "serve",
                        "--isolated",
                        "--ssh-session-token-file", "/srv/test-home/.hermes/desktop-ssh/$ownershipId/$nonce.token",
                        "--ssh-owner-nonce", nonce,
                    ),
                )
            }
        }

        override fun openLoopbackForward(remotePort: Int): SshForward {
            throw AssertionError("not used")
        }

        fun currentLockPort(): Int? = lock?.get("port")?.jsonPrimitive?.int

        fun hasTemporaryLock(): Boolean = temporaryLock != null
    }

    private companion object {
        const val OWNERSHIP_ID = "0123456789abcdef0123456789abcdef"
        const val START_NONCE = "08090a0b0c0d0e0f"
        const val TOKEN_OWNER_DIR = "/srv/test-home/.hermes/desktop-ssh/$OWNERSHIP_ID"
        const val PROFILE_OWNER_DIR = "/srv/test-home/.hermes/profiles/test-profile/desktop-ssh/$OWNERSHIP_ID"
        const val FIXED_STARTED_AT = "2026-08-20T12:34:56Z"
    }
}
