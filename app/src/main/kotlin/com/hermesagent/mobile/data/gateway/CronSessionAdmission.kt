package com.hermesagent.mobile.data.gateway

private val CRON_PROFILE = Regex("[a-z0-9][a-z0-9_-]{0,63}")
internal fun isConcreteCronProfile(profile: String): Boolean =
    profile !in setOf("all", "current") && CRON_PROFILE.matches(profile)

private val CRON_EXECUTION_ID = Regex("^cron_.+_\\d{8}_\\d{6}$")
fun isCronExecutionSessionId(id: String): Boolean = CRON_EXECUTION_ID.matches(id.trim())

/** Desktop open-cron-run.ts @ e05b16348b1d06a3311237423b0a4fc30d9c5aa1; finite timestamps hardened. */
internal fun GatewaySessionDetail.isResumableCronRun(nowMillis: Long): Boolean =
    ended || (schedulerOwned ?: isActive ?: lastActiveSeconds?.let {
        it.isFinite() && nowMillis.toDouble() - it * 1000 < 300_000
    } ?: false)

internal data class CronSessionOwner(val endpointGeneration: Long, val profile: String, val sessionId: String)
internal enum class CronSessionVerdict { Writable, ViewOnly, LookupUnavailable }

/** Caller serializes under its state lock. Failures never invent scheduler ownership. */
internal class CronSessionAdmission {
    private val verdicts = mutableMapOf<CronSessionOwner, CronSessionVerdict>()
    fun hasVerdict(owner: CronSessionOwner): Boolean = verdicts.containsKey(owner)
    fun hasRememberedSession(endpoint: Long, sessionId: String): Boolean = verdicts.keys.any {
        it.endpointGeneration == endpoint && it.sessionId == sessionId
    }
    fun rememberedProfile(endpoint: Long, sessionId: String): String? = verdicts.keys
        .filter { it.endpointGeneration == endpoint && it.sessionId == sessionId }
        .singleOrNull()?.profile
    fun verdict(owner: CronSessionOwner): CronSessionVerdict = verdicts[owner] ?: CronSessionVerdict.LookupUnavailable
    fun record(owner: CronSessionOwner, detail: GatewaySessionDetail?, nowMillis: Long): CronSessionVerdict {
        // Endpoint generations never repeat; retire old truth rather than growing without bound.
        verdicts.keys.removeAll { it.endpointGeneration != owner.endpointGeneration }
        if (detail == null) return verdicts.getOrPut(owner) { CronSessionVerdict.LookupUnavailable }
        val writable = (!detail.source.isNullOrEmpty() && detail.source != "cron") || detail.isResumableCronRun(nowMillis)
        return (if (writable) CronSessionVerdict.Writable else CronSessionVerdict.ViewOnly).also { verdicts[owner] = it }
    }
}
