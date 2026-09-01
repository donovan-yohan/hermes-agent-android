package com.hermesagent.mobile.data.gateway

import com.hermesagent.mobile.data.ssh.ExecOutcome
import com.hermesagent.mobile.data.ssh.SshForward
import com.hermesagent.mobile.data.ssh.SshTransport
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

internal data class RemoteHermesConfig(
    val profile: String? = null,
    val executable: String? = null,
)

internal data class RemoteExec(
    val stdout: ByteArray,
    val stderr: ByteArray,
    val exitStatus: Int?,
    val truncated: Boolean,
) {
    fun clear() {
        stdout.fill(0)
        stderr.fill(0)
    }
}

/** One SSH/process seam: fakes never need a network or a remote process. */
internal interface RemoteCommandRunner {
    suspend fun exec(
        command: String,
        stdin: ByteArray? = null,
        maxBytes: Int = 64 * 1024,
        timeoutMillis: Long = 15_000,
    ): RemoteExec

    fun openLoopbackForward(remotePort: Int): SshForward
}

internal class SshRemoteCommandRunner(
    private val transport: SshTransport,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : RemoteCommandRunner {
    override suspend fun exec(
        command: String,
        stdin: ByteArray?,
        maxBytes: Int,
        timeoutMillis: Long,
    ): RemoteExec = withContext(io) {
        runInterruptible {
            transport.exec(command, stdin, maxBytes, timeoutMillis).toRemote()
        }
    }

    override fun openLoopbackForward(remotePort: Int): SshForward =
        transport.openLoopbackForward(remotePort)

    private fun ExecOutcome.toRemote() = RemoteExec(stdout, stderr, exitStatus, truncated)
}

internal data class OwnedRemoteProcess(
    val pid: Long,
    val executable: String,
    val profile: String?,
    val home: String,
    val hermesHome: String,
    val ownershipId: String,
    val nonce: String,
    val tokenPath: String,
    val tokenArtifactFingerprint: String,
    val tokenFingerprint: String,
    val lockPath: String,
    val logPath: String,
    val startedAt: String,
    val remotePort: Int,
)

private data class OwnedTokenArtifact(
    val path: String,
    val fingerprint: String,
)

private data class ProcessInspection(
    val alive: Boolean,
    val argv: List<String>,
)

internal class RemoteBackend internal constructor(
    val remotePort: Int,
    token: ByteArray,
    process: OwnedRemoteProcess,
    private val lifecycle: RemoteHermesLifecycle,
) {
    private var ownedToken = token
    private val knownLockRecords = mutableListOf(process)

    var process: OwnedRemoteProcess = process
        private set

    val token: ByteArray
        get() = ownedToken

    suspend fun adoptServedToken(candidate: ByteArray?) {
        lifecycle.adoptServedToken(this, candidate)
    }

    internal fun replaceToken(candidate: ByteArray) {
        check(candidate !== ownedToken)
        val replaced = ownedToken
        ownedToken = candidate
        replaced.fill(0)
    }

    internal fun updateProcess(value: OwnedRemoteProcess) {
        process = value
        if (value !in knownLockRecords) knownLockRecords += value
    }

    fun clearToken() {
        ownedToken.fill(0)
    }

    suspend fun shutdown() {
        try {
            lifecycle.cleanup(process, knownLockRecords)
        } finally {
            clearToken()
        }
    }
}

internal class RemoteLifecycleException(message: String) : Exception(message)

/**
 * Starts one positively owned loopback-only `hermes serve` process.
 *
 * Safe reuse is deliberately not attempted in this slice: a reconnect starts
 * a fresh nonce, writes its ownership lock before consuming readiness, and
 * only terminates a process whose live argv proves that nonce, executable and
 * token path. The ADR records the process-restart limitation.
 */
internal class RemoteHermesLifecycle(
    private val runner: RemoteCommandRunner,
    private val randomBytes: (Int) -> ByteArray = ::secureBytes,
    private val wait: suspend (Long) -> Unit = { delay(it) },
    private val startedAt: () -> String = { Instant.now().toString() },
) {
    suspend fun start(
        ownershipId: String,
        config: RemoteHermesConfig,
    ): RemoteBackend {
        requireHex(ownershipId, 32, "ownership id")
        // An explicit default prevents upstream's sticky active_profile from
        // silently re-homing an otherwise unscoped Android backend.
        val profile = config.profile?.takeIf(String::isNotBlank)?.also(::requireProfile) ?: "default"
        val configuredExecutable = config.executable?.takeIf(String::isNotBlank)?.also(::requireExecutable)
        val nonceBytes = randomBytes(8)
        val nonce = try {
            require(nonceBytes.size == 8) { "nonce source must return 8 bytes." }
            nonceBytes.toHex()
        } finally {
            nonceBytes.fill(0)
        }
        val tokenBytes = randomBytes(32)
        val token = try {
            require(tokenBytes.size == 32) { "token source must return 32 bytes." }
            tokenBytes.toHexBytes()
        } finally {
            tokenBytes.fill(0)
        }
        val tokenArtifactFingerprint = tokenFingerprint(token)
        var tokenArtifact: OwnedTokenArtifact? = null
        var process: OwnedRemoteProcess? = null

        try {
            gatePlatform()
            val home = requiredText("printf '%s' \"\$HOME\"", "remote home")
            requireRemoteHome(home)
            // The pinned serve token reader accepts SSH bootstrap tokens only
            // below the account's literal $HOME/.hermes/desktop-ssh tree,
            // independent of HERMES_HOME. It is the one required machine-root
            // exception. The child, ownership lock, log, and orphan reaper
            // must instead share the explicitly selected effective Hermes
            // home. NousResearch/
            // hermes-agent @ 29112bef099274229cadff79cdff7bf7b99c4b77,
            // hermes_cli/main.py:510-518,664-689,10947-11021,
            // hermes_cli/profiles.py:2458-2492, and
            // hermes_cli/dashboard_procs.py:733-738,786-801.
            val configuredHermesHome = requiredText(
                "bash -lc " + posixQuote("printf '%s' \"\${HERMES_HOME:-\$HOME/.hermes}\""),
                "Hermes home",
            )
            requireRemoteHome(configuredHermesHome)
            val hermesRoot = hermesRoot(configuredHermesHome)
            val machineHermesHome = "$home/.hermes"
            val hermesHome = if (profile == "default") {
                hermesRoot
            } else {
                "$hermesRoot/profiles/$profile"
            }
            requireRemoteHome(machineHermesHome)
            requireRemoteHome(hermesHome)
            if (profile != "default") {
                // Do not let ownership-directory preparation manufacture an
                // otherwise nonexistent named profile before Hermes validates it.
                checkedExec("test -d ${posixQuote(hermesHome)}")
            }
            val executable = configuredExecutable ?: discoverExecutable(home)
            verifyExecutable(executable)
            verifyCapability(executable, profile, hermesHome)

            val ownerDir = "$hermesHome/desktop-ssh/$ownershipId"
            val tokenOwnerDir = "$machineHermesHome/desktop-ssh/$ownershipId"
            // Pinned Hermes validates the Desktop SSH runtime layout exactly:
            // <32-hex ownership>/<16-hex nonce>.token beneath OS $HOME/.hermes.
            // This one-shot staging artifact is consumed and unlinked before
            // serve starts; ownership lock/log state belongs to hermesHome.
            val tokenPath = "$tokenOwnerDir/$nonce.token"
            val lockPath = "$ownerDir/backend.lock.json"
            val logPath = "$ownerDir/$nonce.log"
            val lockStartedAt = startedAt()
            requireLockString(executable, "Hermes path")
            requireLockString(hermesHome, "Hermes home")
            requireLockString(logPath, "Hermes log path")
            requireLockString(lockStartedAt, "Hermes start time")
            tokenArtifact = OwnedTokenArtifact(tokenPath, tokenArtifactFingerprint)
            uploadToken(tokenPath, token)
            prepareOwnerDirectory(ownerDir)

            val pid = spawn(executable, profile, hermesHome, tokenPath, nonce, logPath)
            process = OwnedRemoteProcess(
                pid = pid,
                executable = executable,
                profile = profile,
                home = home,
                hermesHome = hermesHome,
                ownershipId = ownershipId,
                nonce = nonce,
                tokenPath = tokenPath,
                tokenArtifactFingerprint = tokenArtifactFingerprint,
                tokenFingerprint = tokenArtifactFingerprint,
                lockPath = lockPath,
                logPath = logPath,
                startedAt = lockStartedAt,
                remotePort = 0,
            )
            writeLock(process)

            val port = awaitReadiness(logPath)
            // Keep the shared lock at port=0 until the loopback forward has
            // passed authenticated readiness, the served dashboard token has
            // been resolved, and the exact child is inspected again. Pinned
            // Desktop ordering: 29112bef099274229cadff79cdff7bf7b99c4b77,
            // apps/desktop/electron/remote-lifecycle.ts:905-931.
            return RemoteBackend(port, token, process, this)
        } catch (failure: Throwable) {
            // Startup may be cancelled after spawn but before ownership reaches
            // the connection manager. Preserve that cancellation while still
            // proving process ownership and wiping the live token reference.
            withContext(NonCancellable) {
                try {
                    process?.let { runCatching { cleanup(it) } }
                        ?: tokenArtifact?.let { runCatching { removeOwnedToken(it) } }
                } finally {
                    token.fill(0)
                }
            }
            throw failure
        }
    }

    private suspend fun gatePlatform() {
        val os = requiredText("uname -s", "remote operating system")
        if (os != "Linux") {
            throw RemoteLifecycleException("This Gateway requires a Linux host.")
        }
        val architecture = requiredText("uname -m", "remote architecture")
        if (architecture !in SUPPORTED_LINUX_ARCHITECTURES) {
            throw RemoteLifecycleException("This Linux architecture is not supported by this build.")
        }
    }

    private suspend fun discoverExecutable(home: String): String {
        // Pinned Desktop uses a login shell because a non-interactive SSH PATH
        // misses per-user installs. 29112bef099274229cadff79cdff7bf7b99c4b77,
        // apps/desktop/electron/remote-lifecycle.ts:136-205.
        val commandPath = execText("bash -lc ${posixQuote("command -v hermes")} 2>/dev/null || true")
            .lineSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .lastOrNull()
        val candidates = buildList {
            if (commandPath != null) add(commandPath)
            add("$home/.local/bin/hermes")
            add("/usr/local/bin/hermes")
            add("$home/.hermes/hermes-agent/venv/bin/hermes")
        }
        return candidates.firstOrNull { candidate ->
            runCatching {
                requireExecutable(candidate)
                checkedExec("test -x ${posixQuote(candidate)}")
                true
            }.getOrDefault(false)
        } ?: throw RemoteLifecycleException("Hermes was not found on this host. Install or update Hermes, then reconnect.")
    }

    private suspend fun verifyExecutable(executable: String) {
        checkedExec("test -x ${posixQuote(executable)}")
    }

    private suspend fun verifyCapability(executable: String, profile: String, hermesHome: String) {
        val outcome = runner.exec(
            "${hermesEnvironment(hermesHome)} ${posixQuote(executable)} " +
                "--profile ${posixQuote(profile)} serve --help",
            maxBytes = 64 * 1024,
        )
        try {
            val help = outcome.stdout.toString(Charsets.UTF_8) + outcome.stderr.toString(Charsets.UTF_8)
            if (outcome.exitStatus != 0 || outcome.truncated ||
                "ssh-session-token-file" !in help || "ssh-owner-nonce" !in help
            ) {
                throw RemoteLifecycleException("Update Hermes on this host, then reconnect.")
            }
        } finally {
            outcome.clear()
        }
    }

    private suspend fun uploadToken(tokenPath: String, token: ByteArray) {
        // Descriptor-relative exclusive creation is the pinned Desktop seam:
        // 29112bef099274229cadff79cdff7bf7b99c4b77,
        // apps/desktop/electron/remote-lifecycle.ts:606-634. The read is bounded
        // to the exact 64-byte hex token so a cut-off SSH stdin unlinks partial data.
        val script = """
            import os,stat,sys
            p=${pythonString(tokenPath)}
            d=os.path.dirname(p)
            n=os.path.basename(p)
            os.makedirs(d,mode=0o700,exist_ok=True)
            df=os.O_RDONLY|getattr(os,"O_DIRECTORY",0)|getattr(os,"O_NOFOLLOW",0)
            dd=os.open(d,df)
            try:
             ds=os.fstat(dd)
             if not stat.S_ISDIR(ds.st_mode):raise SystemExit("unsafe token directory")
             if hasattr(os,"getuid") and ds.st_uid!=os.getuid():raise SystemExit("token directory owner mismatch")
             if (ds.st_mode&0o777)!=0o700:os.fchmod(dd,0o700)
             fl=os.O_WRONLY|os.O_CREAT|os.O_EXCL|getattr(os,"O_NOFOLLOW",0)
             fd=os.open(n,fl,0o600,dir_fd=dd)
             try:
              os.fchmod(fd,0o600)
              fs=os.fstat(fd)
              if not stat.S_ISREG(fs.st_mode) or fs.st_nlink!=1:raise SystemExit("unsafe token file")
              if hasattr(os,"getuid") and fs.st_uid!=os.getuid():raise SystemExit("token file owner mismatch")
              if (fs.st_mode&0o777)!=0o600:raise SystemExit("unsafe token mode")
              data=sys.stdin.buffer.read(${TOKEN_BYTES + 1})
              if len(data)!=${TOKEN_BYTES}:raise SystemExit("invalid token length")
              view=memoryview(data)
              while view:
               wrote=os.write(fd,view)
               if wrote<=0:raise OSError("token write failed")
               view=view[wrote:]
              os.fsync(fd)
             except BaseException:
              identity=os.fstat(fd)
              os.close(fd)
              fd=-1
              try:
               current=os.stat(n,dir_fd=dd,follow_symlinks=False)
               if (current.st_dev,current.st_ino)==(identity.st_dev,identity.st_ino):os.unlink(n,dir_fd=dd)
              except OSError:pass
              raise
             finally:
              if fd>=0:os.close(fd)
            finally:os.close(dd)
        """.trimIndent()
        checkedExec("python3 -c ${posixScriptQuote(script)}", stdin = token)
    }

    private suspend fun prepareOwnerDirectory(ownerDir: String) {
        val script = """
            import os,stat
            d=${pythonString(ownerDir)}
            os.makedirs(d,mode=0o700,exist_ok=True)
            df=os.O_RDONLY|getattr(os,"O_DIRECTORY",0)|getattr(os,"O_NOFOLLOW",0)
            dd=os.open(d,df)
            try:
             ds=os.fstat(dd)
             if not stat.S_ISDIR(ds.st_mode):raise SystemExit("unsafe ownership directory")
             if hasattr(os,"getuid") and ds.st_uid!=os.getuid():raise SystemExit("ownership directory owner mismatch")
             if (ds.st_mode&0o777)!=0o700:os.fchmod(dd,0o700)
            finally:os.close(dd)
        """.trimIndent()
        checkedExec("python3 -c ${posixScriptQuote(script)}")
    }

    private suspend fun spawn(
        executable: String,
        profile: String,
        hermesHome: String,
        tokenPath: String,
        nonce: String,
        logPath: String,
    ): Long {
        val profileArg = " --profile ${posixQuote(profile)}"
        val serve = "${hermesEnvironment(hermesHome, desktop = true)} ${posixQuote(executable)}$profileArg " +
            "serve --isolated --host 127.0.0.1 --port 0 " +
            "--ssh-session-token-file ${posixQuote(tokenPath)} --ssh-owner-nonce ${posixQuote(nonce)}"
        val command = "umask 077; : > ${posixQuote(logPath)}; " +
            "nohup setsid $serve > ${posixQuote(logPath)} 2>&1 < /dev/null & " +
            "printf '%s' \"\$!\""
        return requiredText(command, "Hermes process id")
            .lineSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .lastOrNull()
            ?.toLongOrNull()
            ?.takeIf { it in 1..MAX_REMOTE_PID }
            ?: throw RemoteLifecycleException("Hermes did not return a valid process id.")
    }

    private fun hermesEnvironment(hermesHome: String, desktop: Boolean = false): String =
        buildString {
            // HERMES_PROFILE is a separate inherited process label upstream;
            // it must not conflict with the explicit --profile selected by
            // this connection. Linux `env -u` removes it before the wrapper
            // or Hermes process sees the Android-owned invocation.
            append("env -u HERMES_PROFILE HERMES_HOME=")
            append(posixQuote(hermesHome))
            if (desktop) append(" HERMES_DESKTOP=1")
        }

    private suspend fun writeLock(process: OwnedRemoteProcess) {
        val bytes = process.lockJson().toByteArray(Charsets.UTF_8)
        try {
            val temporary = "${process.lockPath}.tmp-${process.nonce}"
            checkedExec(
                "umask 077; cat > ${posixQuote(temporary)} && chmod 600 -- ${posixQuote(temporary)} && " +
                    "mv -f -- ${posixQuote(temporary)} ${posixQuote(process.lockPath)}",
                stdin = bytes,
            )
        } finally {
            bytes.fill(0)
        }
    }

    private suspend fun awaitReadiness(logPath: String): Int {
        repeat(READINESS_ATTEMPTS) {
            val text = execText("cat -- ${posixQuote(logPath)} 2>/dev/null || true", maxBytes = READINESS_LOG_BYTES)
            parseReadyPort(text)?.let { return it }
            wait(READINESS_POLL_MILLIS)
        }
        throw RemoteLifecycleException("Hermes did not become ready. Check the remote installation and reconnect.")
    }

    /**
     * Consumes an optional token candidate from the already-forwarded public
     * dashboard, proves the exact child is still ours, then publishes the
     * final port/token fingerprint atomically through the shared lock.
     */
    internal suspend fun adoptServedToken(backend: RemoteBackend, candidate: ByteArray?) {
        var adopted = candidate
        try {
            check(backend.process.remotePort == 0) { "The served dashboard token was already resolved." }
            if (adopted != null && !isValidServedToken(adopted)) {
                adopted.fill(0)
                adopted = null
            }

            // This inspection deliberately happens after the public fetch.
            // Unlike a PID-only check, exact argv ownership fails closed when
            // another process races the forwarded port.
            val inspection = inspectProcess(backend.process.pid)
            val owned = inspection.alive && ownsProcess(backend.process, inspection.argv)
            val differs = adopted != null && !adopted.contentEquals(backend.token)
            if (!owned) {
                val message = if (differs) {
                    "The served Gateway belongs to a different process. Reconnect to continue."
                } else {
                    "Hermes exited while the served Gateway token was being resolved. Reconnect to continue."
                }
                throw RemoteLifecycleException(message)
            }

            if (adopted != null) {
                if (adopted === backend.token) {
                    adopted = null
                } else if (adopted.contentEquals(backend.token)) {
                    adopted.fill(0)
                    adopted = null
                } else {
                    backend.replaceToken(adopted)
                    adopted = null
                }
            }

            val ready = backend.process.copy(
                remotePort = backend.remotePort,
                tokenFingerprint = tokenFingerprint(backend.token),
            )
            // Record both exact locally-derived variants before the atomic
            // remote write. If SSH reports failure around mv, guarded cleanup
            // accepts and deletes only whichever verified port-zero or final
            // lock state actually exists.
            backend.updateProcess(ready)
            writeLock(ready)
        } finally {
            adopted?.takeIf { it !== backend.token }?.fill(0)
        }
    }

    internal suspend fun cleanup(
        process: OwnedRemoteProcess,
        knownLockRecords: List<OwnedRemoteProcess> = listOf(process),
    ) {
        if (process.pid !in 1..MAX_REMOTE_PID) return
        requireKnownLockVariants(process, knownLockRecords)
        val initial = inspectProcess(process.pid)
        if (initial.alive && !ownsProcess(process, initial.argv)) return

        var consecutiveDead = if (initial.alive) 0 else 1
        if (initial.alive) checkedExec("kill -TERM -- ${process.pid}", allowFailure = true)

        repeat(CLEANUP_DEATH_ATTEMPTS) {
            wait(CLEANUP_POLL_MILLIS)
            val inspection = inspectProcess(process.pid)
            if (!inspection.alive) {
                consecutiveDead += 1
                if (consecutiveDead >= REQUIRED_DEAD_OBSERVATIONS) {
                    removeLifecycleFiles(process, knownLockRecords)
                    return
                }
            } else {
                consecutiveDead = 0
                // A surviving process must remain positively ours on every poll.
                // Ownership uncertainty retains both process and artifacts.
                if (!ownsProcess(process, inspection.argv)) return
            }
        }
    }

    private suspend fun inspectProcess(pid: Long): ProcessInspection {
        val output = execText(
            "if kill -0 -- $pid 2>/dev/null; then printf 'ALIVE\\n'; " +
                "tr '\\000' '\\n' < /proc/$pid/cmdline; else printf 'DEAD\\n'; fi",
            maxBytes = 16 * 1024,
            allowFailure = true,
        )
        val lines = output.lineSequence().filter(String::isNotEmpty).toList()
        return when (lines.firstOrNull()) {
            "DEAD" -> ProcessInspection(alive = false, argv = emptyList())
            "ALIVE" -> ProcessInspection(alive = true, argv = lines.drop(1))
            else -> ProcessInspection(alive = true, argv = emptyList())
        }
    }

    private suspend fun removeLifecycleFiles(
        process: OwnedRemoteProcess,
        knownLockRecords: List<OwnedRemoteProcess>,
    ) {
        removeOwnedToken(OwnedTokenArtifact(process.tokenPath, process.tokenArtifactFingerprint))
        val expectedRecords = knownLockRecords.joinToString(",", prefix = "[", postfix = "]") { it.lockJson() }
        val script = """
            import json,os,stat
            d=${pythonString(process.ownerDir())}
            expected_records=$expectedRecords
            lock_name="backend.lock.json"
            log_name=${pythonString("${process.nonce}.log")}
            temp_name=${pythonString("backend.lock.json.tmp-${process.nonce}")}
            try:
             df=os.O_RDONLY|getattr(os,"O_DIRECTORY",0)|getattr(os,"O_NOFOLLOW",0)
             dd=os.open(d,df)
             try:
              ds=os.fstat(dd)
              safe_dir=stat.S_ISDIR(ds.st_mode) and (ds.st_mode&0o777)==0o700 and (not hasattr(os,"getuid") or ds.st_uid==os.getuid())
              if safe_dir:
               def exact_record(name):
                try:
                 fd=os.open(name,os.O_RDONLY|getattr(os,"O_NOFOLLOW",0),dir_fd=dd)
                 try:
                  fs=os.fstat(fd)
                  safe=stat.S_ISREG(fs.st_mode) and fs.st_nlink==1 and fs.st_size<=${MAX_LOCK_BYTES} and (fs.st_mode&0o777)==0o600 and (not hasattr(os,"getuid") or fs.st_uid==os.getuid())
                  raw=os.read(fd,${MAX_LOCK_BYTES + 1}) if safe else b""
                  parsed=json.loads(raw.decode("utf-8")) if raw and len(raw)<=${MAX_LOCK_BYTES} else None
                  exact_types=isinstance(parsed,dict) and type(parsed.get("pid")) is int and type(parsed.get("port")) is int
                  return (fs.st_dev,fs.st_ino) if exact_types and parsed in expected_records else None
                 finally:os.close(fd)
                except (OSError,UnicodeDecodeError,ValueError):return None
               temp_identity=exact_record(temp_name)
               if temp_identity:
                try:
                 current=os.stat(temp_name,dir_fd=dd,follow_symlinks=False)
                 if (current.st_dev,current.st_ino)==temp_identity:os.unlink(temp_name,dir_fd=dd)
                except OSError:pass
               lock_identity=exact_record(lock_name)
               if lock_identity:
                 try:
                  gs=os.stat(log_name,dir_fd=dd,follow_symlinks=False)
                  safe_log=stat.S_ISREG(gs.st_mode) and gs.st_nlink==1 and (gs.st_mode&0o777)==0o600 and (not hasattr(os,"getuid") or gs.st_uid==os.getuid())
                  if safe_log:os.unlink(log_name,dir_fd=dd)
                 except OSError:pass
                 current=os.stat(lock_name,dir_fd=dd,follow_symlinks=False)
                 if (current.st_dev,current.st_ino)==lock_identity:os.unlink(lock_name,dir_fd=dd)
             finally:os.close(dd)
            except (OSError,UnicodeDecodeError,ValueError):pass
        """.trimIndent()
        checkedExec("python3 -c ${posixScriptQuote(script)}", allowFailure = true)
    }

    private fun requireKnownLockVariants(
        process: OwnedRemoteProcess,
        variants: List<OwnedRemoteProcess>,
    ) {
        require(variants.size in 1..2 && variants.distinct().size == variants.size)
        require(variants.all { variant ->
            variant.copy(remotePort = process.remotePort, tokenFingerprint = process.tokenFingerprint) == process
        }) { "Cleanup lock variants may differ only by final port and token fingerprint." }
    }

    private suspend fun removeOwnedToken(artifact: OwnedTokenArtifact) {
        requireHex(artifact.fingerprint, TOKEN_FINGERPRINT_HEX_LENGTH, "token fingerprint")
        val script = """
            import hashlib,os,stat
            p=${pythonString(artifact.path)}
            expected=${pythonString(artifact.fingerprint)}
            d=os.path.dirname(p)
            n=os.path.basename(p)
            try:
             df=os.O_RDONLY|getattr(os,"O_DIRECTORY",0)|getattr(os,"O_NOFOLLOW",0)
             dd=os.open(d,df)
             try:
              ds=os.fstat(dd)
              safe_dir=stat.S_ISDIR(ds.st_mode) and (ds.st_mode&0o777)==0o700 and (not hasattr(os,"getuid") or ds.st_uid==os.getuid())
              if safe_dir:
               fd=os.open(n,os.O_RDONLY|getattr(os,"O_NOFOLLOW",0),dir_fd=dd)
               try:
                fs=os.fstat(fd)
                safe_file=stat.S_ISREG(fs.st_mode) and fs.st_nlink==1 and fs.st_size==${TOKEN_BYTES} and (fs.st_mode&0o777)==0o600 and (not hasattr(os,"getuid") or fs.st_uid==os.getuid())
                data=os.read(fd,${TOKEN_BYTES + 1}) if safe_file else b""
                if len(data)==${TOKEN_BYTES} and hashlib.sha256(data).hexdigest()[:${TOKEN_FINGERPRINT_HEX_LENGTH}]==expected:
                 current=os.stat(n,dir_fd=dd,follow_symlinks=False)
                 if (current.st_dev,current.st_ino)==(fs.st_dev,fs.st_ino):os.unlink(n,dir_fd=dd)
               finally:os.close(fd)
             finally:os.close(dd)
            except OSError:pass
        """.trimIndent()
        checkedExec("python3 -c ${posixScriptQuote(script)}", allowFailure = true)
    }

    private suspend fun requiredText(command: String, what: String): String {
        val value = execText(command).trim()
        if (value.isEmpty()) throw RemoteLifecycleException("The host did not report its $what.")
        return value
    }

    private suspend fun execText(
        command: String,
        maxBytes: Int = 64 * 1024,
        allowFailure: Boolean = false,
    ): String {
        val outcome = runner.exec(command, maxBytes = maxBytes)
        try {
            if (!allowFailure && (outcome.exitStatus != 0 || outcome.truncated)) {
                throw RemoteLifecycleException("A required remote command failed. Check the host and reconnect.")
            }
            return outcome.stdout.toString(Charsets.UTF_8)
        } finally {
            outcome.clear()
        }
    }

    private suspend fun checkedExec(
        command: String,
        stdin: ByteArray? = null,
        allowFailure: Boolean = false,
    ) {
        val outcome = runner.exec(command, stdin = stdin)
        try {
            if (!allowFailure && (outcome.exitStatus != 0 || outcome.truncated)) {
                throw RemoteLifecycleException("A required remote command failed. Check the host and reconnect.")
            }
        } finally {
            outcome.clear()
        }
    }

    private companion object {
        const val READINESS_ATTEMPTS = 60
        const val READINESS_POLL_MILLIS = 250L
        const val READINESS_LOG_BYTES = 64 * 1024
        const val CLEANUP_DEATH_ATTEMPTS = 50
        const val CLEANUP_POLL_MILLIS = 100L
        const val REQUIRED_DEAD_OBSERVATIONS = 2
        const val MAX_REMOTE_PID = 4_194_304L
        const val MAX_LOCK_BYTES = 64 * 1024
        const val TOKEN_BYTES = 64
        val SUPPORTED_LINUX_ARCHITECTURES = setOf("x86_64", "aarch64", "arm64", "armv7l")
    }
}

internal fun parseReadyPort(log: String): Int? = READY_LINE.findAll(log)
    .mapNotNull { it.groupValues[1].toIntOrNull() }
    .firstOrNull { it in 1..65535 }

internal fun ownsProcess(process: OwnedRemoteProcess, argv: List<String>): Boolean {
    if (process.nonce.length != 16 || process.nonce.any { it !in "0123456789abcdef" }) return false
    val serve = argv.singleIndexOf("serve") ?: return false
    val isolated = argv.singleIndexOf("--isolated") ?: return false
    val owner = argv.singleIndexOf("--ssh-owner-nonce") ?: return false
    val token = argv.singleIndexOf("--ssh-session-token-file") ?: return false
    if (isolated <= serve || owner <= serve || token <= serve) return false
    if (argv.getOrNull(owner + 1) != process.nonce || argv.getOrNull(token + 1) != process.tokenPath) return false
    val profileMatches = if (process.profile == null) {
        "--profile" !in argv
    } else {
        val profile = argv.singleIndexOf("--profile") ?: return false
        profile < serve && argv.getOrNull(profile + 1) == process.profile
    }
    if (!profileMatches) return false

    // Installer launchers may exec into Python plus the Hermes entrypoint, and
    // some installs leave neither known path in argv. Match Desktop's alternate
    // spawn proof without weakening the exact, unique serve/owner/token/profile
    // shape. NousResearch/hermes-agent @
    // 29112bef099274229cadff79cdff7bf7b99c4b77,
    // apps/desktop/electron/remote-lifecycle.ts:400-465.
    val expectedEntrypoints = setOf(
        process.executable,
        "${process.hermesHome.trimEnd('/')}/hermes-agent/venv/bin/hermes",
    )
    val direct = argv.firstOrNull() in expectedEntrypoints
    val pythonEntrypoint = argv.getOrNull(1) in expectedEntrypoints &&
        argv.firstOrNull()?.substringAfterLast('/')?.startsWith("python") == true
    val exactSpawnProof = argv.getOrNull(owner + 1) == process.nonce &&
        argv.getOrNull(token + 1) == process.tokenPath && profileMatches
    return direct || pythonEntrypoint || exactSpawnProof
}

private fun List<String>.singleIndexOf(value: String): Int? =
    indices.filter { this[it] == value }.singleOrNull()

internal fun posixQuote(value: String): String {
    require('\u0000' !in value && '\n' !in value && '\r' !in value) { "Shell values cannot contain control lines." }
    return "'" + value.replace("'", "'\"'\"'") + "'"
}

private fun posixScriptQuote(value: String): String {
    require('\u0000' !in value && '\r' !in value) { "Shell scripts cannot contain NUL or carriage return." }
    return "'" + value.replace("'", "'\"'\"'") + "'"
}

private fun pythonString(value: String): String = JsonPrimitive(value).toString()

internal fun requireProfile(value: String) {
    require(PROFILE.matches(value)) { "Hermes profile names may contain letters, digits, dot, underscore, and dash." }
}

internal fun requireExecutable(value: String) {
    require(value.startsWith('/')) { "The configured Hermes executable must be an absolute path." }
    require(EXECUTABLE.matches(value) && value.split('/').none { it == ".." }) {
        "The configured Hermes executable path contains unsupported characters."
    }
}

private fun requireRemoteHome(value: String) {
    require(value.startsWith('/') && '\u0000' !in value && '\n' !in value && '\r' !in value) {
        "The remote home directory is invalid."
    }
}

/** Mirrors upstream's root recovery when HERMES_HOME already names a profile. */
private fun hermesRoot(configuredHome: String): String {
    val home = configuredHome.trimEnd('/').ifEmpty { "/" }
    val parent = home.substringBeforeLast('/', missingDelimiterValue = "")
    return if (parent.substringAfterLast('/') == "profiles") {
        parent.substringBeforeLast('/', missingDelimiterValue = "").ifEmpty { "/" }
    } else {
        home
    }
}

private fun requireLockString(value: String, label: String) {
    require(value.toByteArray(Charsets.UTF_8).size <= 1_024) { "$label is too long for the ownership record." }
}

private fun requireHex(value: String, length: Int, label: String) {
    require(value.length == length && value.all { it in "0123456789abcdef" }) { "$label must be $length lowercase hex characters." }
}

private fun OwnedRemoteProcess.ownerDir(): String = "$hermesHome/desktop-ssh/$ownershipId"

private fun OwnedRemoteProcess.lockLogPath(): String {
    val defaultHome = "${home.trimEnd('/')}/.hermes"
    val canonicalHome = when (hermesHome) {
        defaultHome -> "~/.hermes"
        "$defaultHome/profiles/$profile" -> "~/.hermes/profiles/$profile"
        else -> null
    }
    return canonicalHome?.let { "$it/desktop-ssh/$ownershipId/$nonce.log" } ?: logPath
}

private fun OwnedRemoteProcess.lockJson(): String = buildJsonObject {
    // Exact shared ownership record. Pinned validators:
    // 29112bef099274229cadff79cdff7bf7b99c4b77,
    // apps/desktop/electron/remote-lifecycle.ts:292-370 and
    // hermes_cli/dashboard_procs.py:722-783.
    put("schemaVersion", JsonPrimitive(2))
    put("protocolVersion", JsonPrimitive(1))
    put("ownershipId", JsonPrimitive(ownershipId))
    put("spawnNonce", JsonPrimitive(nonce))
    put("pid", JsonPrimitive(pid))
    put("port", JsonPrimitive(remotePort))
    put("profile", JsonPrimitive(profile.orEmpty()))
    put("hermesPath", JsonPrimitive(executable))
    put("hermesHome", JsonPrimitive(hermesHome))
    // Pinned Desktop accepts the canonical spelling under its literal default
    // root; the reaper accepts a relocated custom prefix but requires this
    // exact ownership/nonce suffix.
    put("logPath", JsonPrimitive(lockLogPath()))
    put("tokenFingerprint", JsonPrimitive(tokenFingerprint))
    put("startedAt", JsonPrimitive(startedAt))
}.toString()

private fun secureBytes(size: Int): ByteArray = ByteArray(size).also(SecureRandom()::nextBytes)

private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

private fun ByteArray.toHexBytes(): ByteArray = ByteArray(size * 2).also { encoded ->
    forEachIndexed { index, byte ->
        val value = byte.toInt() and 0xff
        encoded[index * 2] = HEX[value ushr 4].code.toByte()
        encoded[index * 2 + 1] = HEX[value and 0x0f].code.toByte()
    }
}

private fun sha256Hex(value: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(value)
    return try {
        digest.toHex()
    } finally {
        digest.fill(0)
    }
}

private fun tokenFingerprint(value: ByteArray): String =
    sha256Hex(value).take(TOKEN_FINGERPRINT_HEX_LENGTH)

internal fun isValidServedToken(value: ByteArray): Boolean =
    value.size in 1..MAX_SERVED_TOKEN_BYTES && value.all { byte ->
        byte.toInt() and 0xff in 0x21..0x7e
    }

private val READY_LINE = Regex("(?m)^(?:HERMES_BACKEND_READY|HERMES_DASHBOARD_READY) port=([0-9]{1,5})$")
private val PROFILE = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
private val EXECUTABLE = Regex("/[A-Za-z0-9_+.,/@%:=~-]+(?:/[A-Za-z0-9_+.,@%:=~-]+)*")
private const val HEX = "0123456789abcdef"
private const val TOKEN_FINGERPRINT_HEX_LENGTH = 32
private const val MAX_SERVED_TOKEN_BYTES = 512
