package com.hermesagent.mobile.ui.system

import com.hermesagent.mobile.data.updates.GatewayUpdateStage
import com.hermesagent.mobile.data.updates.GatewayUpdateStatusKey

/**
 * Every visible string on the System panel and the updates sheet, verbatim from
 * Hermes Desktop at `3ca096de5f8183cb2e0ec23673f294d5978656a3`.
 *
 * One object, with the `en.ts` line beside each constant, for the reason
 * `ConnectionsCopy` is one object: a copy diff in review is then a diff of this
 * file against `apps/desktop/src/i18n/en.ts`, rather than a hunt through
 * composables. Nothing here is this app's own voice — where Desktop has a
 * string, that string is what ships, curly apostrophes included.
 */
internal object SystemCopy {

    // ── Settings row (`en.ts:1548`) ────────────────────────────────────────
    /** `commandCenter.sectionEntries.system.title`. */
    const val TITLE = "System panel"

    /** `commandCenter.sectionEntries.system.detail`. */
    const val DETAIL = "Gateway status, logs, restart/update"

    // ── Status block (`en.ts:1561-1563, 1573`) ─────────────────────────────
    /** `commandCenter.gatewayRunning`. */
    const val GATEWAY_RUNNING = "Messaging gateway running"

    /** `commandCenter.gatewayStopped`. */
    const val GATEWAY_STOPPED = "Messaging gateway stopped"

    /** `commandCenter.loadingStatus`. */
    const val LOADING_STATUS = "Loading status..."

    /** `commandCenter.hermesActiveSessions(version, count)`. */
    fun hermesActiveSessions(version: String, count: Long): String =
        "Hermes $version · Active sessions $count"

    // ── Actions (`en.ts:1564-1571`) ────────────────────────────────────────
    /** `commandCenter.restartGateway`. */
    const val RESTART_GATEWAY = "Restart gateway"

    /** `commandCenter.updateHermes`. */
    const val UPDATE_HERMES = "Update Hermes"

    /** `commandCenter.gatewayRestartFailed`. */
    const val GATEWAY_RESTART_FAILED = "Gateway restart failed."

    /** `commandCenter.actionRunning`. */
    const val ACTION_RUNNING = "running"

    /** `commandCenter.actionDone`. */
    const val ACTION_DONE = "done"

    /** `commandCenter.actionFailed`. */
    const val ACTION_FAILED = "failed"

    /**
     * The action progress line, `${systemAction.name} · <state>`
     * (`apps/desktop/src/app/command-center/index.tsx:453-462` @ the pin). The
     * name is the host's own action name, which is one of two fixed strings.
     */
    fun actionProgress(action: String, state: String): String = "$action · $state"

    // ── Recent logs (`en.ts:1574-1575, 1594-1596`) ─────────────────────────
    /** `commandCenter.recentLogs`. */
    const val RECENT_LOGS = "Recent logs"

    /** `commandCenter.noLogs`. */
    const val NO_LOGS = "No logs loaded yet."

    /** `commandCenter.logFile`. */
    const val LOG_FILE = "Log file"

    /** `commandCenter.logLevel`. */
    const val LOG_LEVEL = "Level"

    /** `commandCenter.logSearchPlaceholder`. */
    const val LOG_SEARCH_PLACEHOLDER = "Filter log lines..."

    /** `LOG_FILES` (`command-center/index.tsx:46` @ the pin), in its order. */
    val LOG_FILES: List<String> = listOf("agent", "errors", "gateway", "desktop")

    /**
     * The level tabs as Desktop *renders* them: the ids are `ALL/INFO/WARNING/
     * ERROR` (`command-center/index.tsx:47`) and every label is lowercased on
     * the way out, `ALL` to `all` included (`:484-487`).
     */
    val LOG_LEVELS: List<String> = listOf("all", "info", "warning", "error")

    // ── Updates sheet: stages (`en.ts:2600-2611`) ──────────────────────────
    /**
     * `updates.stages`, for the six stages a backend apply reaches. The four
     * this app never renders — `fetch`, `pydeps`, `rebuild`, `guiSkew` — are
     * Desktop's own client-side updater's and have no backend path.
     */
    fun stageTitle(stage: GatewayUpdateStage): String = when (stage) {
        // `idle` and `prepare` are the same sentence upstream (`:2599-2600`).
        GatewayUpdateStage.Idle, GatewayUpdateStage.Prepare -> "Getting ready…"
        GatewayUpdateStage.Pull -> "Almost there…"
        GatewayUpdateStage.Restart -> "Restarting Hermes…"
        GatewayUpdateStage.Done -> "Update complete"
        GatewayUpdateStage.Manual -> "Update from your terminal"
        GatewayUpdateStage.Error -> "Update paused"
    }

    // ── Updates sheet: idle branches (`en.ts:2612-2628`) ───────────────────
    /** `updates.checking`. */
    const val CHECKING = "Looking for updates…"

    /** `updates.checkFailedTitle`. */
    const val CHECK_FAILED_TITLE = "Couldn’t check for updates"

    /** `updates.tryAgain`. */
    const val TRY_AGAIN = "Try again"

    /** `updates.notAvailableTitle`. */
    const val NOT_AVAILABLE_TITLE = "Update not available"

    /** `updates.unsupportedMessage`. */
    const val UNSUPPORTED_MESSAGE = "This version of Hermes can’t update itself from inside the app."

    /** `updates.connectionRetry`. */
    const val CONNECTION_RETRY = "Check your connection and try again."

    /** `updates.allSetTitle`. */
    const val ALL_SET_TITLE = "You’re all set"

    /** `updates.latestBodyBackend`. */
    const val LATEST_BODY_BACKEND = "The backend is running the latest version."

    /** `updates.availableTitleBackend`. */
    const val AVAILABLE_TITLE_BACKEND = "Backend update available"

    /** `updates.availableBodyBackend`. */
    const val AVAILABLE_BODY_BACKEND =
        "A newer version of the connected Hermes backend is ready to install."

    /** `updates.availableBodyNoChangelog`. */
    const val AVAILABLE_BODY_NO_CHANGELOG =
        "A newer version is ready. Release notes aren’t available for this install type."

    /** `updates.updateNow`. */
    const val UPDATE_NOW = "Update now"

    /** `updates.maybeLater`. */
    const val MAYBE_LATER = "Maybe later"

    /** `updates.done`. */
    const val DONE = "Done"

    /** `updates.errorTitle`. */
    const val ERROR_TITLE = "Update didn’t finish"

    /** `updates.errorBody`. */
    const val ERROR_BODY = "No worries — nothing was lost. You can try again now."

    /** `updates.notNow`. */
    const val NOT_NOW = "Not now"

    /** `updates.moreChanges(count)`. */
    fun moreChanges(count: Int): String =
        "+ $count more change${if (count == 1) "" else "s"} included."

    // ── Updates sheet: applying and terminal (`en.ts:2640-2641, 2668-2675`) ─
    /** `updates.applyingBodyBackend`. */
    const val APPLYING_BODY_BACKEND =
        "The remote backend is applying the update and will restart. " +
            "Hermes reconnects automatically when it’s back."

    /** `updates.applyStatus.*`, the six a backend apply can reach. */
    fun applyStatus(status: GatewayUpdateStatusKey): String = when (status) {
        GatewayUpdateStatusKey.Preparing -> "Updating backend…"
        GatewayUpdateStatusKey.Pulling -> "Backend updating…"
        GatewayUpdateStatusKey.Restarting -> "Backend restarting to load the update…"
        GatewayUpdateStatusKey.NotAvailable -> "Update not available for this backend."
        GatewayUpdateStatusKey.Failed -> "Backend update failed."
        GatewayUpdateStatusKey.NoReturn -> NO_RETURN
    }

    /**
     * `updates.applyStatus.noReturn`. Split from [applyStatus] only because the
     * product-copy gate measures one literal at a time and this is the longest
     * string Desktop has for this surface; the words are unchanged.
     */
    const val NO_RETURN = "Backend didn’t come back online. " +
        "The update may not have completed — check the backend host."

    // ── Android-only chrome, where Desktop has no string ───────────────────
    /**
     * What a screen reader calls the status dot. Desktop's dot is a bare
     * `<span>` with no accessible name (`command-center/index.tsx:430-435`)
     * because the sentence beside it says the same thing — which is the same
     * reason this is a description on a decorative shape rather than a second
     * visible label. It is not new copy: it is [GATEWAY_RUNNING] and
     * [GATEWAY_STOPPED], spoken.
     */
    fun statusDotDescription(running: Boolean): String =
        if (running) GATEWAY_RUNNING else GATEWAY_STOPPED
}
