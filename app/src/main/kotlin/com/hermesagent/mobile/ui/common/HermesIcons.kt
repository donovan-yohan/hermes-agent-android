package com.hermesagent.mobile.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
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
    /** Desktop's `remote` connection kind glyph (`connections-registry.tsx:29` @ `3ca096de`). */
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
    CloudDownload("\uEAC2"),
    Folder("\uEA83"),
    Archive("\uEA98"),
}

private val CodiconFont = FontFamily(Font(R.font.codicon))

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
 */
@Composable
fun HermesIconButton(
    icon: HermesIcon,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    active: Boolean = false,
    tint: Color = HermesTheme.tokens.textTertiary,
) {
    val tokens = HermesTheme.tokens
    Box(
        modifier = modifier
            .size(HermesTheme.spacing.touchTarget)
            .background(
                if (active) tokens.widgetSurface else Color.Transparent,
                RoundedCornerShape(4.dp),
            )
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
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
) {
    Canvas(modifier.size(size).clearAndSetSemantics {}) {
        val unit = this.size.minDimension / VIEWPORT
        val left = (this.size.width - VIEWPORT * unit) / 2f
        val top = (this.size.height - VIEWPORT * unit) / 2f
        val bolt = Path()
        BOLT_POINTS.forEachIndexed { index, (px, py) ->
            val dx = left + px * unit
            val dy = top + py * unit
            if (index == 0) bolt.moveTo(dx, dy) else bolt.lineTo(dx, dy)
        }
        bolt.close()
        drawPath(
            path = bolt,
            color = color,
            style = if (filled) Fill else Stroke(width = 2f * unit, join = StrokeJoin.Round),
        )
    }
}

/** Tabler's icon viewport; every coordinate below is on this square. */
private const val VIEWPORT = 24f

/** Tabler `bolt`, `M13 3l0 7l6 0l-8 11l0 -7l-6 0l8 -11`, as absolute points. */
private val BOLT_POINTS = listOf(
    13f to 3f,
    13f to 10f,
    19f to 10f,
    11f to 21f,
    11f to 14f,
    5f to 14f,
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
