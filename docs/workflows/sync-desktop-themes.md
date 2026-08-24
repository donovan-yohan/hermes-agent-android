# Syncing Desktop themes

The durable checklist behind the `sync-hermes-desktop-themes` skill.

Themes are values, not behaviour, so drift is invisible in review. This is the
one place in the port where "looks about right" is not good enough.

---

## 1. Pin and diff

```bash
HERMES_AGENT_UPSTREAM="${HERMES_AGENT_UPSTREAM:-$HOME/.hermes/hermes-agent}"
git -C "$HERMES_AGENT_UPSTREAM" rev-parse HEAD

python3 .chalk/skills/sync-hermes-desktop-themes/scripts/check-theme-parity.py \
  --upstream "$HERMES_AGENT_UPSTREAM"
```

`0` parity · `1` drift, printed · `2` no upstream checkout.

The script covers identity (name, label, description, order, default skin, and
whether a preset ships a hand-tuned dark palette). It deliberately does **not**
diff colour values: Desktop writes them as `color-mix` expressions and Android
ports the expressions, so a value diff would be permanent noise. Values are
covered by reading the diff and by `ColorMathTest`.

## 2. Map the change

| What changed upstream | What to do here |
|---|---|
| New preset | Insert it in the exact Desktop `BUILTIN_THEMES` order in `BuiltinThemes.ALL` + add its matching row to `DesktopThemeLedger.ENTRIES`. Nothing else. |
| Preset removed | Remove from both. Check nothing persisted references it — `BuiltinThemes.resolve` already falls back to the default for unknown names, and `ThemeParityTest` asserts that. |
| Label / description edited | Update both files; the parity test compares them exactly. |
| Colour value edited | Update `BuiltinThemes.kt`, keeping expressions as expressions. |
| New colour key | Add the field to `HermesPalette` (nullable only if it is optional upstream), resolve it in `HermesTokens.from` with the same fallback Desktop uses, and add the key to `DesktopThemeLedger.REQUIRED_COLOR_KEYS` or `OPTIONAL_COLOR_KEYS`. |
| A key's *meaning* changed | Change the derivation in `HermesTokens` once. Never at a call site. |
| Font changed | Update the preset's `HermesFontChoice` and its comment, and the substitution table in `docs/phase-1-architecture.md`. |
| `synthLightColors` changed | Re-port it line for line from `apps/desktop/src/themes/context.tsx`. It is the light half of every dark-first preset. |

## 3. The three files that move together

1. `app/src/main/kotlin/com/hermesagent/mobile/ui/theme/BuiltinThemes.kt` — the data.
2. `app/src/main/kotlin/com/hermesagent/mobile/ui/theme/HermesTokens.kt` — the meaning.
3. `app/src/test/kotlin/com/hermesagent/mobile/ui/theme/DesktopThemeLedger.kt` — the offline record, including `PINNED_SHA`.

A sync that touches 1 without 3 fails `ThemeParityTest`; a sync that touches 3
without 1 fails it too. That is the point.

## 4. Colour expressions

Ported maths lives in `ui/theme/ColorMath.kt`:

| Desktop | Android | Note |
|---|---|---|
| `color-mix(in srgb, X n%, Y)` | `mixPremultiplied(X, n, Y)` | Premultiplied — this is why mixing with `transparent` only lowers alpha |
| `mix(a, b, amount)` (`color.ts:29`) | `mix(a, b, amount)` | Opaque lerp, alpha untouched. **Not** interchangeable with the above |
| `readableOn(hex)` (`color.ts:63`) | `readableOn(color)` | 0.58 luminance split |
| `relativeLuminance` / `contrastRatio` | same names | WCAG, gamma-corrected |

`nousTint(pct)` / `nousTintTransparent(pct)` stay as functions in
`BuiltinThemes.kt`, mirroring `presets.ts:26-27`. Resolving them to hex forks
the palette the next time `NOUS_BLUE` moves.

## 5. Fonts

Android bundles no webfont and makes **no runtime font request**. Every
`fontUrl` upstream becomes a platform-family substitution, recorded in the
preset comment and in `docs/phase-1-architecture.md`.

The trap: a font choice can be load-bearing. `cyberpunk` sets `fontSans` *and*
`fontMono` to Courier, which makes the entire UI monospace. That behaviour is
preserved through `HermesFontChoice.sans`, and `ThemeParityTest` asserts that
cyberpunk is the only preset with a monospace body.

## 6. Verify

```bash
./gradlew :app:testDebugUnitTest --tests '*ThemeParityTest*' --tests '*ColorMathTest*'
./gradlew check
```

Then look at it. The previews to open, all in `ChatScreen.kt` and
`AppearanceScreen.kt`:

- `Chat · nous light`, `Chat · nous dark` — the hand-tuned pair
- `Chat · cyberpunk` — the monospace-everything case
- `Chat · wide slate` — the rail layout
- `Appearance · nous light`, `Appearance · ember dark` — every preset's own
  palette swatch, rendered in its own tokens

Any preset you touched, in both modes. The Appearance list renders each row in
its own resolved tokens, so a broken preset shows up there first.

## 7. Record

Update `DesktopThemeLedger.PINNED_SHA` and the transcription date in the same
commit, and add anything this sync taught to this file. Delete steps that
stopped being true.
