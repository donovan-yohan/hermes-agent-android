package com.hermesagent.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.hermesagent.mobile.data.prefs.SidebarGrouping
import com.hermesagent.mobile.data.session.IcuSessionBucketLabel
import com.hermesagent.mobile.data.session.SessionStatus
import com.hermesagent.mobile.data.session.SessionSummary
import com.hermesagent.mobile.data.session.buildSessionRows
import com.hermesagent.mobile.plugins.*
import com.hermesagent.mobile.plugins.bots.BotsPlugin
import com.hermesagent.mobile.plugins.groups.GroupsPlugin
import com.hermesagent.mobile.plugins.kanban.KanbanPlugin
import com.hermesagent.mobile.ui.LocalPluginNavigation
import com.hermesagent.mobile.ui.PluginNavigation
import com.hermesagent.mobile.ui.sessions.SessionList
import com.hermesagent.mobile.ui.theme.AppearanceSelection
import com.hermesagent.mobile.ui.theme.HermesTheme
import com.hermesagent.mobile.ui.theme.HermesThemeMode
import java.util.Locale
import java.util.TimeZone

/**
 * Sidebar-only capture: production SessionList and real bundled registrations.
 * No saved preferences, sessions, profile roster or application plugin registry
 * are consulted. Run on the capture lane's clean install, as for other fixtures.
 * The synthetic plugin context is disconnected; Bots is the actual typed mode
 * contribution, not a fabricated roster. Bots and route bodies are not evidence
 * for this surface. Route clicks update sidebar selection without mounting them.
 */
class DesktopSidebarParityActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val state = DesktopSidebarFixtureState.parse(intent.getStringExtra("visual_parity_state"))
        val theme = when (intent.getStringExtra("visual_parity_theme")) {
            "dark" -> HermesThemeMode.Dark
            "light" -> HermesThemeMode.Light
            else -> throw IllegalArgumentException("unsupported sidebar parity theme")
        }
        setContent {
            HermesTheme(AppearanceSelection("mono", theme)) {
                DesktopSidebarParityFixture(state, Modifier.fillMaxSize().safeDrawingPadding())
            }
        }
    }
}

internal enum class DesktopSidebarFixtureState(val wireValue: String, val initialRoute: String?) {
    SessionsNavigation("sessions-navigation", null),
    GroupsSelected("sessions-navigation-group-selected", "groups:route");

    companion object {
        fun parse(value: String?): DesktopSidebarFixtureState = entries.firstOrNull { it.wireValue == value }
            ?: throw IllegalArgumentException("unsupported sidebar parity state")
    }
}

/** Registers the actual plugins through the production scoping/registry path. */
internal class DesktopSidebarFixturePlugins : AutoCloseable {
    val registry = ContributionRegistry()
    private val disposers = mutableListOf<() -> Unit>()

    init {
        val locales = PluginLocaleRegistry { "en" }
        val values = mutableMapOf<String, String>()
        val storage = object : PluginKeyValueStore {
            override suspend fun read(scopedKey: String): String? = values[scopedKey]
            override suspend fun write(scopedKey: String, value: String?) {
                if (value == null) values.remove(scopedKey) else values[scopedKey] = value
            }
        }
        val rest = object : PluginRest {
            override suspend fun execute(pluginId: String, path: String, options: PluginRestOptions): PluginRestResult =
                PluginRestResult.UnavailableOnGateway
        }
        val socket = object : PluginSocket {
            override fun connect(pluginId: String, path: String, onMessage: (String) -> Unit): () -> Unit = {}
        }
        val os = object : PluginOs {
            override fun notify(input: PluginNotificationInput) = Unit
            override suspend fun openExternal(url: String): Boolean = false
            override suspend fun writeClipboard(text: String): Boolean = false
            override suspend fun share(text: String, title: String?): Boolean = false
        }
        try {
            listOf(BotsPlugin(), GroupsPlugin(), KanbanPlugin()).forEach { plugin ->
                plugin.register(createPluginContext(
                    pluginId = plugin.id,
                    registry = registry,
                    rest = rest,
                    socket = socket,
                    storage = ScopedPluginStorage(plugin.id, storage),
                    os = os,
                    host = UnavailablePluginHost,
                    locales = locales,
                    onDispose = { disposers.add(it) },
                ))
            }
        } catch (failure: Throwable) {
            close()
            throw failure
        }
    }

    override fun close() {
        disposers.asReversed().forEach { it() }
        disposers.clear()
    }
}

@Composable
internal fun DesktopSidebarParityFixture(state: DesktopSidebarFixtureState, modifier: Modifier = Modifier) {
    val plugins = remember { DesktopSidebarFixturePlugins() }
    DisposableEffect(plugins) { onDispose { plugins.close() } }
    var route by remember(state) { mutableStateOf(state.initialRoute) }
    var selectedSession by remember(state) { mutableStateOf<String?>(null) }
    val rows = remember {
        buildSessionRows(
            sessions = listOf(
                SessionSummary(
                    id = "synthetic-sidebar-design", title = "Synthetic design review",
                    preview = "Synthetic capture session", lastActiveAtMillis = SIDEBAR_CLOCK - 720_000L,
                    status = SessionStatus.Idle, source = "desktop",
                ),
                SessionSummary(
                    id = "synthetic-sidebar-release", title = "Synthetic release checklist",
                    preview = "Synthetic capture session", lastActiveAtMillis = SIDEBAR_CLOCK - 1_200_000L,
                    status = SessionStatus.Idle, source = "desktop",
                ),
            ),
            nowMillis = SIDEBAR_CLOCK,
            query = "",
            locale = Locale.US,
            timeZone = TimeZone.getTimeZone("UTC"),
            bucketLabel = IcuSessionBucketLabel(Locale.US, TimeZone.getTimeZone("UTC")),
        )
    }
    CompositionLocalProvider(LocalPluginNavigation provides PluginNavigation(
        currentRoute = route,
        onNavigate = { target ->
            check(plugins.registry.getArea(PluginAreas.ROUTES_AREA).any { it.id == target })
            route = target
        },
        onBack = { route = null },
    )) {
        SessionList(
            rows = rows,
            projects = emptyList(), projectsAvailable = false,
            sidebarGrouping = SidebarGrouping.Date,
            selectedProject = null, projectLoading = false,
            activeSessionId = if (route == null) selectedSession else null,
            query = "", canCreate = true,
            onQueryChange = {}, onSidebarGroupingChange = {}, onSelectProject = {},
            onExitProject = {}, onCreateProject = { _, _ -> },
            onSelect = { selectedSession = it; route = null },
            onCreate = { selectedSession = null; route = null },
            sidebarNavigation = plugins.registry.getArea(PluginAreas.SIDEBAR_NAV_AREA),
            nowMillis = SIDEBAR_CLOCK,
            modifier = modifier,
        )
    }
}

/** Immutable fixture clock; row labels never depend on capture date or device zone. */
private const val SIDEBAR_CLOCK = 1_789_654_800_000L
