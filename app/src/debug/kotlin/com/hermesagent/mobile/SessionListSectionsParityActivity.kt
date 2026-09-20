package com.hermesagent.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.hermesagent.mobile.data.prefs.SidebarGrouping
import com.hermesagent.mobile.data.session.IcuSessionBucketLabel
import com.hermesagent.mobile.data.session.SessionStatus
import com.hermesagent.mobile.data.session.SessionSummary
import com.hermesagent.mobile.data.session.buildSessionRows
import com.hermesagent.mobile.ui.sessions.SessionList
import com.hermesagent.mobile.ui.theme.AppearanceSelection
import com.hermesagent.mobile.ui.theme.HermesTheme
import com.hermesagent.mobile.ui.theme.HermesThemeMode
import java.util.Locale
import java.util.TimeZone

/**
 * Debug-only, synthetic session-list fixture for the `session-list-sections`
 * capture surface.
 *
 * The rows are the **same fourteen synthetic rows** the Desktop packet for
 * #141/#299 was seeded with, at the same immutable clock
 * (`1789654800000` = 2026-09-17T14:20Z), bucketed in `en-US`/UTC by the shipped
 * [IcuSessionBucketLabel] — so both sides cut the same instants into the same
 * buckets and the divider words are comparable byte for byte. Nothing is read
 * from a device, Gateway, profile or real session: the intent extras are capture
 * metadata only.
 *
 * The list is longer than a phone, so the states whose subject is at its end are
 * reached by a real drag on the real scroller (`swipe:list-up` in
 * `docs/parity/visual-capture-surfaces.json`), never by a grown pane, a
 * shortened seed or stitched pixels.
 */
class SessionListSectionsParityActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val state = SessionListSectionsFixtureState.parse(intent.getStringExtra(EXTRA_STATE))
        val theme = if (intent.getStringExtra(EXTRA_THEME) == "light") HermesThemeMode.Light else HermesThemeMode.Dark
        setContent { HermesTheme(AppearanceSelection("mono", theme)) { SessionListSectionsParityFixture(state) } }
    }

    companion object {
        const val EXTRA_STATE = "visual_parity_state"
        const val EXTRA_THEME = "visual_parity_theme"
    }
}

/** Every session-list state in the capture catalog. Unknown values fail before rendering. */
internal enum class SessionListSectionsFixtureState(val wireValue: String) {
    /**
     * The seeded list from its head — `PINNED`, `SESSIONS`, the unlabelled head
     * run, `EARLIER TODAY`. The state name is the one the Desktop packet for
     * #141/#299 was captured under.
     */
    PinnedSessionsMonthDividers("pinned-sessions-month-dividers"),

    /** The same list, dragged to its own end, where the month tail lives. */
    MonthsScrolled("months-scrolled"),

    /** A live query: one `RESULTS` section over the same rows. */
    Results("results"),

    /** The same rows, every one of them pinned, so Desktop's all-pinned sentence shows. */
    AllPinned("all-pinned"),

    /**
     * The archived view (#146): the archived seed under `PINNED`, its `SESSIONS`
     * caption and no date dividers. The pinned archived row is the state's
     * subject, because the pin's visibility in this view is the fix.
     */
    ArchivedPinned("archived-pinned"),
    ;

    companion object {
        fun parse(value: String?): SessionListSectionsFixtureState = entries.firstOrNull { it.wireValue == value }
            ?: throw IllegalArgumentException("unsupported session list sections parity state: $value")
    }
}

@Composable
internal fun SessionListSectionsParityFixture(state: SessionListSectionsFixtureState) {
    val sessions = when (state) {
        SessionListSectionsFixtureState.AllPinned ->
            SessionListSectionsSeed.sessions.map { it.copy(pinned = true) }
        SessionListSectionsFixtureState.ArchivedPinned ->
            SessionListSectionsSeed.sessions.map { it.copy(archived = true) }
        else -> SessionListSectionsSeed.sessions
    }
    val query = if (state == SessionListSectionsFixtureState.Results) SessionListSectionsSeed.QUERY else ""
    val rows = buildSessionRows(
        sessions = sessions,
        nowMillis = SessionListSectionsSeed.CLOCK_MILLIS,
        query = query,
        timeZone = SessionListSectionsSeed.TIME_ZONE,
        locale = SessionListSectionsSeed.LOCALE,
        archivedView = state == SessionListSectionsFixtureState.ArchivedPinned,
        // Bound to the same zone the buckets were cut in: `Intl` and ICU resolve
        // a month name in a zone, and a device in another zone would name the
        // wrong month near a boundary.
        bucketLabel = IcuSessionBucketLabel(SessionListSectionsSeed.LOCALE, SessionListSectionsSeed.TIME_ZONE),
    )

    Box(Modifier.fillMaxSize().background(HermesTheme.tokens.sidebarSurface)) {
        SessionList(
            rows = rows,
            projects = emptyList(),
            projectsAvailable = null,
            sidebarGrouping = SidebarGrouping.Date,
            selectedProject = null,
            projectLoading = false,
            activeSessionId = null,
            query = query,
            canCreate = true,
            archivedVisible = state == SessionListSectionsFixtureState.ArchivedPinned,
            onQueryChange = {},
            onSidebarGroupingChange = {},
            onSelectProject = {},
            onExitProject = {},
            onCreateProject = { _, _ -> },
            onSelect = {},
            onCreate = {},
            nowMillis = SessionListSectionsSeed.CLOCK_MILLIS,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * The one synthetic seed, shared by every state.
 *
 * Every instant is the Desktop seed's own `last_active` second value and every
 * title is that seed's title, so a divider on one side can be read against the
 * same divider on the other. `last_active` is a Unix **second** count
 * (`1789653600` → `2026-09-17T14:00:00Z` at the pinned clock).
 */
private object SessionListSectionsSeed {
    /** `2026-09-17T14:20:00.000Z`, immutable on both sides. */
    const val CLOCK_MILLIS = 1_789_654_800_000L

    /** The locale and zone both sides bucket in. */
    val TIME_ZONE: TimeZone = TimeZone.getTimeZone("UTC")
    val LOCALE: Locale = Locale.US

    /** The one query the `results` state is a live search for. */
    const val QUERY = "synthetic"

    private const val PREVIEW = "Synthetic parity fixture, no real session text"

    val sessions: List<SessionSummary> = listOf(
        session("synthetic-pin-a", "Pinned design review", 1_789_653_600_000L, pinned = true),
        session("synthetic-pin-b", "Pinned release checklist", 1_788_771_600_000L, pinned = true),
        session("synthetic-r1", "Head row alpha", 1_789_654_080_000L),
        session("synthetic-r2", "Head row bravo", 1_789_651_200_000L),
        session("synthetic-r3", "Head row charlie", 1_789_646_700_000L),
        session("synthetic-r4", "Head row delta", 1_789_645_200_000L),
        session("synthetic-r5", "Head row echo", 1_789_640_400_000L),
        session("synthetic-r6", "Earlier today foxtrot", 1_789_633_200_000L),
        session("synthetic-r7", "Yesterday golf", 1_789_546_800_000L),
        session("synthetic-r8", "Earlier this week hotel", 1_789_300_800_000L),
        session("synthetic-r9", "Last week india", 1_789_041_600_000L),
        session("synthetic-r10", "Earlier this month juliett", 1_788_436_800_000L),
        session("synthetic-r11", "July kilo", 1_784_116_800_000L),
        session("synthetic-r12", "November 2025 lima", 1_762_948_800_000L),
    )

    private fun session(
        id: String,
        title: String,
        lastActiveAtMillis: Long,
        pinned: Boolean = false,
    ) = SessionSummary(
        id = id,
        title = title,
        preview = PREVIEW,
        lastActiveAtMillis = lastActiveAtMillis,
        status = SessionStatus.Idle,
        source = "desktop",
        pinned = pinned,
        unread = false,
        archived = false,
    )
}
