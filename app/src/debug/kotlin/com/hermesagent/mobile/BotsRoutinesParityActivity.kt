package com.hermesagent.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.hermesagent.mobile.plugins.PluginHost
import com.hermesagent.mobile.plugins.PluginHostEvent
import com.hermesagent.mobile.plugins.PluginHostResult
import com.hermesagent.mobile.plugins.bots.BotsPluginRepository
import com.hermesagent.mobile.plugins.bots.BotsRoutinesScreen
import com.hermesagent.mobile.plugins.bots.BotsRoutinesViewModel
import com.hermesagent.mobile.ui.LocalPluginNavigation
import com.hermesagent.mobile.ui.PluginNavigation
import com.hermesagent.mobile.ui.theme.AppearanceSelection
import com.hermesagent.mobile.ui.theme.HermesTheme
import com.hermesagent.mobile.ui.theme.HermesThemeMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Debug-only, synthetic fixture for the `bot-routines` capture surface.
 *
 * It renders the **production** surface: the real `BotsRoutinesViewModel` over
 * the real `BotsPluginRepository` and the real `BotsRoutinesScreen`, driven by
 * a fake host door. Only the wiring is fixture-local — the same shape the
 * sibling `session-list-sections` fixture takes with `SessionList` — so the
 * pixels are the shipped screen rather than a stand-in composed for a
 * screenshot.
 *
 * The owner is selected through the ViewModel's own `selectOwner`, which is the
 * call the roster row's Routines control makes; the destination is then on
 * screen without an interaction, because an icon-only control has no `text` for
 * the capture lane to tap and the subject here is the destination, not the
 * entry.
 *
 * Every job, title and schedule below is invented, and no device, Gateway,
 * profile or real cron store is read. The intent extras are capture metadata
 * only.
 */
class BotsRoutinesParityActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val state = BotsRoutinesFixtureState.parse(intent.getStringExtra(EXTRA_STATE))
        val theme = if (intent.getStringExtra(EXTRA_THEME) == "light") {
            HermesThemeMode.Light
        } else {
            HermesThemeMode.Dark
        }
        // The plugin-scoped reading scope a real activation gets, cancelled with
        // this Activity: a fixture that leaked it would outlive the capture.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        lifecycle.addObserver(
            object : androidx.lifecycle.DefaultLifecycleObserver {
                override fun onDestroy(owner: androidx.lifecycle.LifecycleOwner) {
                    scope.cancel()
                }
            },
        )
        setContent {
            HermesTheme(AppearanceSelection("mono", theme)) {
                BotsRoutinesParityFixture(state = state, scope = scope)
            }
        }
    }

    companion object {
        const val EXTRA_STATE = "visual_parity_state"
        const val EXTRA_THEME = "visual_parity_theme"
    }
}

/** Every bot-routines state in the capture catalog. Unknown values fail before rendering. */
internal enum class BotsRoutinesFixtureState(val wireValue: String) {
    /**
     * One bot's store as the Gateway serves it: an active routine, a paused one,
     * a schedule the label passes through, and a legacy delegated routine — so
     * one capture shows the title stripping, the schedule labels, the paused row
     * and the legacy notice together.
     */
    Populated("populated"),

    /** The Gateway answered, and the read failed while the connection was up. */
    ReadFailure("read-failure"),
    ;

    companion object {
        fun parse(value: String?): BotsRoutinesFixtureState = entries.firstOrNull { it.wireValue == value }
            ?: throw IllegalArgumentException("unsupported bot routines parity state: $value")
    }
}

/** The fixture's host door: one answer per `cron.manage` request. */
private class FixtureHost(
    private val state: BotsRoutinesFixtureState,
) : PluginHost {
    override suspend fun request(method: String, params: JsonObject): PluginHostResult = answer(method)

    override suspend fun requestAtEndpoint(
        expectedGeneration: Long,
        method: String,
        params: JsonObject,
    ): PluginHostResult = answer(method)

    override fun onEvent(type: String, listener: (PluginHostEvent) -> Unit): () -> Unit = {}

    private fun answer(method: String): PluginHostResult = when (method) {
        // The bot's own store. A `scoped` echo is present because the pinned
        // Gateway echoes the profile it was asked for, and the surface compares
        // it: a fixture that omitted it would capture the legacy tag filter
        // instead of the scoped read.
        "cron.manage" -> when (state) {
            BotsRoutinesFixtureState.Populated ->
                PluginHostResult.Success(Json.parseToJsonElement(POPULATED))

            BotsRoutinesFixtureState.ReadFailure ->
                PluginHostResult.Refused(5023, "Hermes refused that Gateway request.")
        }

        else -> error("unexpected method $method")
    }

    private companion object {
        /**
         * One store, as `cron.manage {action:"list", include_disabled:true}`
         * sends it. Two of these belong to the selected bot, one is a legacy
         * delegated routine, and one is another bot's — which the scope echo
         * makes visible here, exactly as it would be on the real Gateway.
         */
        const val POPULATED = """{"success":true,"count":4,"scoped":"ops","jobs":[
            {"job_id":"syn-1","name":"[bot:ops] Morning digest","schedule":"every 1440m","repeat":"forever",
             "deliver":"local","enabled":true,"state":"scheduled","next_run_at":"2026-09-18T09:00:00+00:00",
             "prompt_preview":"Synthetic parity fixture, no real routine text"},
            {"job_id":"syn-2","name":"[bot:ops] Nightly sweep","schedule":"30m","repeat":"3 times",
             "enabled":false,"state":"paused","paused_reason":"synthetic fixture reason"},
            {"job_id":"syn-3","name":"[bot:ops] Weekday standup","schedule":"0 9 * * 1-5","repeat":"forever",
             "enabled":true,"state":"scheduled","next_run_at":"2026-09-18T11:30:00+00:00"},
            {"job_id":"syn-4","name":"[bot:ops] Audit trail","schedule":"every 2h","repeat":"forever",
             "enabled":true,"state":"scheduled","next_run_at":"2026-09-18T12:00:00+00:00",
             "prompt_preview":"You are running the scheduled routine \"Audit trail\" for agent 'ops'. "}
        ]}"""
    }
}

@Composable
internal fun BotsRoutinesParityFixture(
    state: BotsRoutinesFixtureState,
    scope: CoroutineScope,
    modifier: Modifier = Modifier,
) {
    val host = remember(state) { FixtureHost(state) }
    val viewModel = remember(state) {
        BotsRoutinesViewModel(
            repository = BotsPluginRepository(host),
            scope = scope,
            // The fixture is a connected Gateway: the read is what is being
            // captured, not the cold-start wait.
            connected = MutableStateFlow(true),
            endpointGeneration = MutableStateFlow(0L),
        )
    }
    // The destination, selected the way the roster row's own control selects it.
    remember(state) { viewModel.selectOwner(profile = "ops", label = "Ops") }

    val uiState by viewModel.uiState.collectAsState()
    val navigation = remember { PluginNavigation() }

    Box(modifier.fillMaxSize()) {
        CompositionLocalProvider(LocalPluginNavigation provides navigation) {
            BotsRoutinesScreen(
                state = uiState,
                onBack = {},
                // The fixture's own immutable clock, so the captured next-run
                // line reads the same on every device on every day: the pixels
                // must be the fixture's, not the day the capture ran.
                nowMillis = CAPTURE_CLOCK_MILLIS,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * `2026-09-18T08:00:00Z`, one hour before the fixtures' `next_run_at`: the
 * captured next-run line reads "in 1 hr" whatever the date of the capture.
 */
internal const val CAPTURE_CLOCK_MILLIS: Long = 1_789_660_800_000L
