# Bundled fonts

Two font files ship inside the APK. Nothing is fetched at runtime: this app
makes **no** network request for a face, in any build, on any route.

| Resource | Face | Why it is here |
|---|---|---|
| `app/src/main/res/font/codicon.ttf` | Codicons 0.0.45 | Desktop's glyph language, so an icon means the same thing on both |
| `app/src/main/res/font/collapse_bold.otf` | Collapse Bold | Desktop's wordmark face, drawn on the empty-chat splash |

Everything else a Desktop preset names — Courier Prime, JetBrains Mono, IBM
Plex Mono — resolves to a platform family. That substitution table is in
[`phase-1-architecture.md`](phase-1-architecture.md) and the rule behind it is
step 5 of [`workflows/sync-desktop-themes.md`](workflows/sync-desktop-themes.md).

Licences are recorded in [`../THIRD_PARTY_NOTICES.md`](../THIRD_PARTY_NOTICES.md).

## Collapse Bold

Desktop sets `.wordmark` in Collapse
(`apps/desktop/src/styles.css:1629-1635` @
`3ca096de5f8183cb2e0ec23673f294d5978656a3`) and loads it from
`@nous-research/ui`'s `dist/fonts/Collapse-Bold.woff2` (`styles.css:62-68` @ the
same SHA). The same file is in the pinned checkout at
`web/public/fonts/Collapse-Bold.woff2`.

Collapse is a commercial Blaze Type face; the repo owner states permission to
use it in this app.

### Provenance

| | |
|---|---|
| Upstream path | `web/public/fonts/Collapse-Bold.woff2` @ `3ca096de5f8183cb2e0ec23673f294d5978656a3` |
| Source sha256 | `cb1bc6803168cffb3ef7b8113f95f82480a6d0f46d6e37edc854259377c6c00b` (59 144 bytes, woff2) |
| Shipped file | `app/src/main/res/font/collapse_bold.otf` |
| Shipped sha256 | `c0cbb0b86bcfcf7ba5103470944925d1eaaa4576d8fbd068f263cf540fc9821d` (117 164 bytes) |
| Name table | family `Collapse`, subfamily `Bold`, © 2023 Keussel, Blaze Type, licence pointer <https://blazetype.eu/eula> |

### The conversion, and the only thing it changes

Android's `res/font` cannot read woff2, so the container is removed. Nothing
else is: no subsetting, no re-hinting, no outline conversion, no name-table
edit. The outlines stay CFF, which is why the resource is `.otf` and not
`.ttf` — the sfnt tag is `OTTO`, Android reads both, and a name that claimed
otherwise would be the misleading part.

```bash
python -c "
from fontTools.ttLib import TTFont
f = TTFont('\$HERMES_AGENT_UPSTREAM/web/public/fonts/Collapse-Bold.woff2', recalcTimestamp=False)
f.flavor = None
f.save('app/src/main/res/font/collapse_bold.otf')
"
```

`recalcTimestamp=False` is load-bearing for the digest above: without it
fontTools rewrites `head.modified` to the moment of the run and three bytes of
the file move with it, so the same command would produce a different sha256
every time and the pin would be unverifiable.

Only the Bold is bundled, because `.wordmark` is `font-weight: 700` and asks
for nothing else. Nothing may synthesise another weight from it.

### What holds it

- `CollapseBoldFontTest` (JVM) pins the digest and byte count, reads the name
  table, checks every letter of `HERMES AGENT` is mapped, and reproduces the
  pinned Desktop capture's `7.756 em` from the shipped file's own advances plus
  `.wordmark`'s `0.08em` tracking. A re-conversion, a re-subset or a different
  face under the same name fails there rather than in a screenshot.
- `WordmarkFitDeviceTest` (Robolectric, `@GraphicsMode(NATIVE)`) resolves
  `R.font.collapse_bold` through `ResourcesCompat.getFont`, so a truncated or
  unparseable file fails rather than silently falling back to the platform
  sans, and asserts the monospace-everything preset still gets Collapse.
- `docs/parity/empty-states.md` carries the fit table these numbers feed.
