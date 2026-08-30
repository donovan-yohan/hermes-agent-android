---
name: sync-hermes-desktop-themes
description: Use when Hermes Desktop adds, changes or removes a built-in theme, renames a colour token, or changes what a token means. Enforces an exact inventory diff against a pinned upstream SHA, semantic token mapping, provenance updates, parity tests, and visual checks across phone/wide and light/dark.
---

# Sync Desktop themes into Android

Themes are the one place where Android and Desktop must agree on *values*, not
just behaviour. Drift here is invisible in review and obvious on a device.

The full checklist is [`docs/workflows/sync-desktop-themes.md`](../../../docs/workflows/sync-desktop-themes.md).
A sync that moves a rendered surface is reviewed by `review-desktop-parity`,
which owns the side-by-side and the divergence classes.

## The executable half

Run the diff before you edit anything:

```bash
python3 .chalk/skills/sync-hermes-desktop-themes/scripts/check-theme-parity.py \
  --upstream "${HERMES_AGENT_UPSTREAM:-$HOME/.hermes/hermes-agent}"
```

Exit codes: `0` parity, `1` drift (it prints exactly what), `2` the upstream
checkout is missing or unreadable. It compares upstream
`apps/desktop/src/themes/presets.ts` against
`app/src/main/kotlin/com/hermesagent/mobile/ui/theme/BuiltinThemes.kt` and
`app/src/test/kotlin/.../DesktopThemeLedger.kt` on name, label, description,
registry order, default skin, and which presets ship a hand-tuned dark palette.

The offline counterpart is `ThemeParityTest`, which runs in `./gradlew check`
with no upstream checkout. Both exist on purpose: the script catches "Desktop
moved", the test catches "Android regressed".

## Non-negotiables

1. **Pin, then diff.** Record the upstream SHA. Run the script. Do not start
   from the rendered app.
2. **Port expressions, not screenshots.** A Desktop `color-mix(...)` stays an
   expression on Android (`mixPremultiplied`, `mix`, `readableOn` in
   `ColorMath.kt`, ported from `apps/desktop/src/themes/color.ts`). Resolving it
   to a hex literal silently forks the palette the next time the seed changes.
3. **Dark-first presets stay synthesised.** Only presets that ship `darkColors`
   upstream get a hand-tuned light half. The rest run `synthLightColors`, the
   line-for-line port of `apps/desktop/src/themes/context.tsx:84-118`. Never
   hand-author a light palette for a preset Desktop synthesises.
4. **Semantics live in `HermesTokens`.** A new upstream colour key becomes a
   semantic token with its provenance comment, not a component-level literal.
   If Desktop changes what a key *means*, change the token derivation once.
5. **Fonts are substituted, never fetched.** Android bundles no webfont and
   makes no runtime font request. Record every substitution in the preset
   comment and in `docs/phase-1-architecture.md`. `cyberpunk` is the reminder
   that a font choice can be load-bearing: it sets sans *and* mono to a
   monospace face, which changes the whole UI.
6. **Update the ledger with the sync**, not later:
   `app/src/test/kotlin/com/hermesagent/mobile/ui/theme/DesktopThemeLedger.kt`
   carries `PINNED_SHA` and the per-preset entries, and
   `verifyRepoInvariants` fails if the SHA is not a real 40-char commit.
7. **Look at it.** Run the previews for phone light, phone dark and wide, plus
   the changed preset in both modes, before claiming parity.

## Done means

- [ ] Script exits 0 against the new upstream SHA.
- [ ] `ThemeParityTest` green, including the contrast floor for every preset/mode.
- [ ] Ledger SHA and entries updated in the same commit as `BuiltinThemes.kt`.
- [ ] Typography substitutions recorded.
- [ ] Previews checked: phone light, phone dark, wide, plus each changed preset.
- [ ] Workflow doc updated with anything this sync taught.
