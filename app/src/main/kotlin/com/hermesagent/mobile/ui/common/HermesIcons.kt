package com.hermesagent.mobile.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hermesagent.mobile.R
import com.hermesagent.mobile.ui.theme.HermesTheme

/**
 * The Desktop glyph language. Values are Codicons 0.0.45 code points, matching
 * the pinned Desktop dependency rather than substituting Material symbols.
 */
enum class HermesIcon(val glyph: String) {
    Add("\uEA60"),
    Edit("\uEA73"),
    File("\uEA7B"),
    /** Desktop's `remote` connection kind glyph (`connections-registry.tsx:29` @ `72a3277cd7`). */
    Globe("\uEB01"),
    /**
     * Desktop's `local` kind and Local-gateway mode glyph
     * (`connections-registry.tsx:28`, `gateway-settings.tsx:1053` @ `3ca096de`),
     * which is lucide `Monitor`.
     *
     * This used to be `device-mobile`, on the argument that Android's local
     * runtime lives on the phone rather than on a desktop. That reasoning
     * changed the glyph to make a point the words already make, and a changed
     * glyph is what the parity gate calls drift
     * (`docs/workflows/review-desktop-parity.md`, "Compare structure"). The
     * glyph is Desktop's again; the *ownership* difference stays in the
     * description, where it always belonged.
     *
     * Codicons 0.0.45 ships no `device-desktop`, so this is the family's own
     * monitor, `vm` — same shape, same family, verified against the shipped
     * font by [HermesIconFontTest].
     */
    Monitor("\uEA7A"),
    /**
     * Desktop's `cloud` connection kind and Hermes Cloud mode glyph
     * (`connections-registry.tsx:27`, `gateway-settings.tsx:1061` @ `3ca096de`).
     * This app has no Hermes Cloud sign-in yet, so the control it marks ships
     * visible and disabled behind a `WIP` pill rather than absent.
     */
    Cloud("\uEBAA"),
    Trash("\uEA81"),
    SettingsGear("\uEB51"),
    Organization("\uEA7E"),
    ChevronUp("\uEAB7"),
    Attach("\uEC34"),
    /**
     * The search field's leading glyph. Desktop's is Tabler `IconSearch`
     * (`components/ui/search-field.tsx:69` renders `Search` from
     * `apps/desktop/src/lib/icons.ts:102` @ `3ca096de`), not a Codicon — only
     * that field's clear button is one (`close`, `:98`). This is the Codicon
     * of the same shape, the same substitution [Globe] and [Monitor] already
     * record, and it is ledgered in `docs/parity/session-search.md`.
     */
    Search("\uEA6D"),
    Clock("\uEA82"),
    Terminal("\uEA85"),
    Error("\uEA87"),
    Warning("\uEA6C"),
    // The tool-tone glyph set (`components/ui/tool-icon.tsx` @ the pinned SHA).
    // Desktop draws these as filled Phosphor paths keyed by Codicon names and
    // falls back to the Codicon font for anything it has no path for; here the
    // font is the whole set. `brain` is the one name Codicon 0.0.45 does not
    // ship, so a memory row takes [Database] — the nearest "this was stored"
    // glyph in the same family, recorded in docs/parity/tool-output-fidelity.md.
    Eye("\uEA70"),
    FileMedia("\uEAEA"),
    Files("\uEAF0"),
    Question("\uEB32"),
    Tools("\uEB6D"),
    Robot("\uEC20"),
    SymbolMisc("\uEB63"),
    Comment("\uEA6B"),
    /**
     * Desktop's schedule-pill glyph. Its routines row draws the schedule in a
     * pill led by Codicon `calendar` (`cron.tsx:581-584`); this is that glyph in
     * the shipped Codicons 0.0.45 font, verified against the bundled
     * `codicon.ttf` cmap by [com.hermesagent.mobile.ui.common.HermesIconFontTest].
     */
    Calendar("\uEAB0"),

    /**
     * Desktop's idle next-run glyph. `watch` is the icon Desktop's own empty
     * state for this pane uses (`cron.tsx:1307`, `icon="watch"`), which is what
     * this app draws on the empty card.
     */
    Watch("\uEB7C"),
    Database("\uEACE"),
    SymbolMethod("\uEA8C"),
    Check("\uEAB2"),
    Checklist("\uEAB3"),
    ArrowDown("\uEA9A"),
    ArrowUp("\uEAA1"),
    ChevronDown("\uEAB4"),
    ChevronRight("\uEAB6"),
    Diff("\uEAE1"),
    RootFolder("\uEB46"),
    ListUnordered("\uEB17"),
    ListFilter("\uEB83"),
    Thinking("\uEC59"),
    Link("\uEB15"),
    Mic("\uEC12"),
    StopCircle("\uEC1F"),
    GitBranch("\uEA68"),
    CircleSlash("\uEABD"),
    KebabVertical("\uEB10"),
    PassFilled("\uEBB3"),
    Copy("\uEBCC"),
    Close("\uEA76"),
    Home("\uEB06"),
    Layers("\uEBD2"),
    Ellipsis("\uEA7C"),

    // The per-session actions menu's glyph vocabulary, fixed by Desktop at
    // `apps/desktop/src/app/chat/sidebar/session-actions-menu.tsx:292,304,317,
    // 345,357,435,444` @ `3ca096de5f8183cb2e0ec23673f294d5978656a3`. Rename
    // (`edit`) and Delete (`trash`) already have entries above. Every code
    // point here is asserted against the shipped font by `HermesIconFontTest`.
    Pin("\uEB2B"),
    /** Closed envelope: the session is unread. Codicon has no `mail-unread`. */
    Mail("\uEB1C"),
    /** Open envelope: the session is read. */
    MailRead("\uEB1B"),
    /** Desktop's fork glyph — this font has no `git-fork`, only `repo-forked`. */
    RepoForked("\uEA63"),

    /**
     * The lead glyph an auto-discovered repo lane wears in Desktop's project
     * overview (`app/chat/sidebar/project-row.tsx` `projectIcon`, @
     * `564aef2946`): a repo Desktop found by scanning disk, rather than a row
     * somebody created in `projects.db`.
     */
    Repo("\uEA62"),
    CloudDownload("\uEAC2"),
    Folder("\uEA83"),
    Archive("\uEA98"),
    /** Desktop's inactive activity-toast control (`roster-pane-toolbar.tsx` @ `72a3277cd7`). */
    BellSlash("\uEC08"),

    /**
     * Desktop's `symbol-color`, the Appearance submenu's trigger glyph
     * (`apps/desktop/src/app/chat/sidebar/session-actions-menu.tsx:469` @
     * `3ca096de5f8183cb2e0ec23673f294d5978656a3`).
     */
    SymbolColor("\uEB5C"),

    /**
     * The refresh verb, in the three places Desktop draws it: the registry's
     * `Update all instances` button (`RefreshCw`,
     * `app/settings/connections-registry.tsx:984`), the assistant action bar's
     * reload (`RefreshCwIcon`,
     * `components/assistant-ui/thread/assistant-message.tsx:640`) and the tab
     * menu's own reload, which names the Codicon directly
     * (`session-actions-menu.tsx:370`, `icon: 'refresh'`) — all @ `3ca096de`.
     * Two lucide draws and one Codicon name, one glyph in this font.
     */
    Refresh("\uEB37"),

    /**
     * Read aloud. Desktop's `disabled={isPreparing}` renders lucide `Loader2Icon`
     * (`assistant-message.tsx:697` @ `3ca096de`), which is `loading` here.
     */
    Loading("\uEB19"),

    /**
     * Read aloud. Desktop's speaking glyph is lucide `VolumeXIcon`
     * (`assistant-message.tsx:697` @ `3ca096de`), which is `mute` here.
     */
    Mute("\uEB24"),

    /**
     * Read aloud. Desktop's idle glyph is lucide `AudioLines`
     * (`assistant-message.tsx:697` @ `3ca096de`), a waveform Codicons 0.0.45
     * does not ship at all.
     *
     * `unmute` — a speaker with sound leaving it — is this family's nearest
     * "audio is playing", and the substitution is the one [Database] and
     * [Monitor] already record: stay inside the shipped font rather than mix a
     * second glyph family into one row. Ledgered in
     * `docs/parity/transcript-selection-copy.md`.
     */
    Unmute("\uEB75"),
}

/**
 * Tabler 3.44.0 outlines — the family Desktop actually draws these controls
 * from (`apps/desktop/src/lib/icons.ts`, `@tabler/icons-react` 3.44.0 @
 * `564aef2946c436500a5e80ee117b66b789b3f99a`). Codicons 0.0.45 ships no
 * steering wheel, and a near-miss glyph in a different family is not the same
 * icon, so these arrive as shared vector data instead of a font substitution.
 *
 * Path data is copied verbatim from the published icon sources: 24px viewBox,
 * 2px round stroke.
 */
enum class TablerIcon(vararg paths: String) {
    Pencil(
        "M4 20h4l10.5 -10.5a2.828 2.828 0 1 0 -4 -4l-10.5 10.5v4",
        "M13.5 6.5l4 4",
    ),
    SteeringWheel(
        "M3 12a9 9 0 1 0 18 0a9 9 0 1 0 -18 0",
        "M10 12a2 2 0 1 0 4 0a2 2 0 1 0 -4 0",
        "M12 14l0 7",
        "M10 12l-6.75 -2",
        "M14 12l6.75 -2",
    ),
    CornerDownLeft("M18 6v6a3 3 0 0 1 -3 3h-10l4 -4m0 8l-4 -4"),
    Trash(
        "M4 7l16 0",
        "M10 11l0 6",
        "M14 11l0 6",
        "M5 7l1 12a2 2 0 0 0 2 2h8a2 2 0 0 0 2 -2l1 -12",
        "M9 7v-3a1 1 0 0 1 1 -1h4a1 1 0 0 1 1 1v3",
    ),
    Bolt("M13 3l0 7l6 0l-8 11l0 -7l-6 0l8 -11"),
    Check("M5 12l5 5l10 -10"),

    ;

    internal val pathData = paths.toList()
}

/** Paints Desktop's 24px Tabler path data inside an Android-sized visual box. */
@Composable
fun TablerIconGlyph(
    icon: TablerIcon,
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 16.dp,
    filled: Boolean = false,
) {
    val paths = remember(icon) { icon.pathData.map { PathParser().parsePathString(it).toPath() } }
    Canvas(modifier.size(size).clearAndSetSemantics {}) {
        val factor = this.size.width / 24f
        scale(factor, factor, pivot = Offset.Zero) {
            paths.forEach { path ->
                drawPath(
                    path = path,
                    color = color,
                    style = if (filled) {
                        Fill
                    } else {
                        Stroke(width = 2f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                    },
                )
            }
        }
    }
}

internal val CodiconFont = FontFamily(Font(R.font.codicon))

/** A decorative Codicon. The owning control supplies its spoken label. */
@Composable
fun HermesIconGlyph(
    icon: HermesIcon,
    modifier: Modifier = Modifier,
    color: Color = HermesTheme.tokens.textTertiary,
    size: TextUnit = 14.sp,
) {
    Text(
        text = icon.glyph,
        style = TextStyle(fontFamily = CodiconFont, fontSize = size, lineHeight = size),
        color = color,
        modifier = modifier.clearAndSetSemantics {},
    )
}

/**
 * Desktop-sized Codicon inside Android's 48dp touch floor. Growing the hit box
 * must not make a quiet 12-14px sidebar glyph look like a Material toolbar icon.
 *
 * @param contentDescription the spoken name, or `null` where a merging host
 *   already says the whole phrase. `SemanticsProperties.ContentDescription`
 *   *concatenates* on merge rather than replacing, so a name kept here inside a
 *   `semantics(mergeDescendants = true)` parent is a second name on one node —
 *   which is how a marked control starts announcing its label twice. See
 *   [com.hermesagent.mobile.ui.common.ComingSoonIconAction] for what that costs
 *   and what today's merge actually does with it.
 */
@Composable
fun HermesIconButton(
    icon: HermesIcon,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    active: Boolean = false,
    tint: Color = HermesTheme.tokens.textTertiary,
) {
    val tokens = HermesTheme.tokens
    val spoken = contentDescription
    Box(
        modifier = modifier
            .size(HermesTheme.spacing.touchTarget)
            .background(
                if (active) tokens.widgetSurface else Color.Transparent,
                RoundedCornerShape(4.dp),
            )
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .then(
                if (spoken == null) {
                    Modifier
                } else {
                    Modifier.semantics { this.contentDescription = spoken }
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        HermesIconGlyph(
            icon = icon,
            color = if (enabled) tint else tokens.textQuaternary,
        )
    }
}

/**
 * Desktop's approval-mode bolt.
 *
 * Desktop draws it from Tabler Icons (MIT) — `IconBolt` and `IconBoltFilled`,
 * re-exported as `Zap` / `ZapFilled` (`apps/desktop/src/lib/icons.ts:125-126` @
 * `3ca096de5f8183cb2e0ec23673f294d5978656a3`) and used at `size-3.5`
 * (`apps/desktop/src/app/shell/approval-mode-menu.tsx:47`). Codicons 0.0.45,
 * the font every other glyph in this app comes from, ships no bolt at all, so
 * this is the one glyph drawn rather than typed.
 *
 * The path is Tabler's own `bolt` outline, `M13 3l0 7l6 0l-8 11l0 -7l-6 0l8
 * -11` on a 24×24 viewport — a closed six-point polygon. Tabler's filled
 * variant is a separately drawn path with rounded joins; filling this same
 * polygon keeps outline and filled provably one silhouette, which is what the
 * two states have to share (`docs/parity/approval-mode.md`).
 *
 * @param filled the `off` state: Desktop swaps in `ZapFilled` and drops the
 *   70% opacity the other two modes carry (`approval-mode-menu.tsx:47`).
 */
@Composable
fun ZapGlyph(
    color: Color,
    modifier: Modifier = Modifier,
    filled: Boolean = false,
    size: Dp = 14.dp,
) = TablerIconGlyph(
    icon = TablerIcon.Bolt,
    color = color,
    modifier = modifier,
    size = size,
    filled = filled,
)

/** Desktop's 8px two-tone checker mark from `.dither`. */
@Composable
fun DitherMark(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(8.dp).clearAndSetSemantics {}) {
        val cell = size.width / 4f
        for (row in 0 until 4) {
            for (column in 0 until 4) {
                if ((row + column) % 2 == 0) {
                    drawRect(
                        color = color,
                        topLeft = androidx.compose.ui.geometry.Offset(column * cell, row * cell),
                        size = androidx.compose.ui.geometry.Size(cell, cell),
                    )
                }
            }
        }
    }
}
