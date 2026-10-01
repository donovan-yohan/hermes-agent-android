# Routine inspector v2 capture handoff

This is a **future capture recipe**, not a new visual acceptance packet. The
`bot-routine-inspector-synthetic-v2` catalog targets Desktop
`587e673e2a2fae0616d8b750bb189217080f621a`. Historical receipts, PNGs, fixture
patches and `REPORT.md` in the scratch `routine-inspector-587e/packet` remain
unchanged. Do not replace their old Android source/APK identities or change the
old failed catalog-validation result because a newer catalog now exists.

## Inputs and boundaries

- Both platforms: synthetic `ops` owner; five jobs `syn-1` through `syn-5`;
  **mono** skin; **en-US / UTC**; dark and light captured separately.
- Ordinary clock: `2026-09-17T16:00:00Z`; overdue clock:
  `2026-09-18T16:00:00Z`. Neither platform may read the capture day's clock.
- Android's fixture supplies its own timestamp formatter through a composition
  local. It does **not** set the device timezone, locale, or process defaults.
  The fixture Activity supplies mono and the requested light/dark mode.
- Desktop must normalize its disposable synthetic profile and renderer only,
  using the production theme provider. Do not alter a real user's profile,
  host timezone, emulator timezone, or CSS to manufacture matching pixels.
- `inspector`, `sparse`, `paused`, `completed`, `overdue-inspector` open the
  production inspector via held-list selection. `overdue` is list context;
  `read-failure` is the refused initial list with Retry, **not** a dialog crop.
- Android now uses Desktop's exact `delivery_failed` copy:
  **Ran, but delivery failed**. A populated inspector is failure-bearing detail,
  distinct from the initial read refusal.
- Preserve the completed safety adaptation: `syn-5` has `schedule: "30m"`,
  `repeat: "1 time"`, `state: "completed"`, `enabled: false`,
  `last_status: "ok"`, `last_run_at: "2026-09-16T09:00:00Z"`.
  Android says **Completed**, omits next run/overdue and cannot resume it.
  Desktop at the target says **Paused / Succeeded**. Do not weaken terminal
  state protection to make that status label match.

## Android dispatch (after parent review and publication)

Use the reviewed immutable Android SHA, not the uncommitted worktree base SHA:

```sh
ANDROID_SHA='<reviewed published 40-character SHA>'
for theme in dark light; do
  for state in inspector sparse paused completed overdue-inspector overdue read-failure; do
    python3 scripts/visual_parity_contract.py describe \
      --surface bot-routine-inspector --state "$state" --theme "$theme"
    gh workflow run visual-parity-capture.yml --ref '<branch containing this workflow>' \
      -f ref="$ANDROID_SHA" -f surface=bot-routine-inspector \
      -f state="$state" -f theme="$theme"
  done
done
```

The manual workflow builds **assembleDebug**, uses the pinned Pixel 6 x86/KVM
lane and records installed APK bytes and source identity. The workflow remains
artifact-only (`contents: read`). Its Desktop job still reports a harness
boundary, not a screenshot. No dispatch or publication is performed by this
source change.

## Desktop normalization for a new immutable export

Start from a fresh `git archive` of the exact target and apply the preserved
capture-only patch with `git apply -p1`. **Copy** the original fixture specs into
the new export; never edit the historical packet. The packet's REPORT documents
the dependency/bootstrap commands. Before Bots navigation in **both** copied
specs (after `waitForAppReady`), configure the renderer using these inputs:

```ts
// Renderer-local emulation, not host/device-global settings.
const cdp = await page.context().newCDPSession(page)
await cdp.send('Emulation.setTimezoneOverride', { timezoneId: 'UTC' })
await cdp.send('Emulation.setLocaleOverride', { locale: 'en-US' })
await page.evaluate(() => {
  // These are the real theme-provider storage keys at 587e673e.
  localStorage.setItem('hermes-desktop-theme-v2', 'mono')
  localStorage.setItem('hermes-desktop-mode-v1', 'system')
  localStorage.setItem('hermes-desktop-profile-themes-v1', '{}')
  localStorage.setItem('hermes-desktop-profile-modes-v1', '{}')
  window.dispatchEvent(new StorageEvent('storage', {
    key: 'hermes-desktop-theme-v2', newValue: 'mono',
  }))
})
await expect(page.locator('html')).toHaveAttribute('data-hermes-theme', 'mono')
expect(await page.evaluate(() => Intl.DateTimeFormat().resolvedOptions().timeZone)).toBe('UTC')
expect(await page.evaluate(() => Intl.DateTimeFormat().resolvedOptions().locale)).toBe('en-US')
```

Retain the existing `page.emulateMedia` and fixed clock calls. Before **each**
screenshot assert `data-hermes-theme=mono`, `data-hermes-mode=<requested mode>`,
and the expected real dialog/pane contents. Keep initial refusal request-count
assertions. A locale/CDP/theme assertion failure is a capture blocker, not
permission to rewrite pixels or waive the input match. This normalization recipe
has source-backed keys but has **not** been exercised in a new Electron capture
by this change; runtime assertions are required.

From the new export's `apps/desktop`, with an owned, verified Xvfb display:

```sh
DISPLAY=:193 PARITY_OUT="$NEW_PACKET/desktop" npx playwright test \
  e2e/routine-inspector-reference.spec.ts --workers=1 --reporter=list
DISPLAY=:193 PARITY_OUT="$NEW_PACKET/desktop" npx playwright test \
  e2e/routine-inspector-failure.spec.ts --workers=1 --reporter=list
```

Store the **new** additive fixture patch and hashes, actual Electron results,
original PNG hashes, explicit normalized inputs, exact upstream SHA and new
Android source/APK receipts in a new packet. Validate each new receipt using
`scripts/visual_parity_contract.py check-receipt --platform android|desktop
--receipt <receipt.json>`. Never claim generic workflow success is proof of a
Desktop capture or that format/layout differences have disappeared. The phone
sheet and Desktop dialog remain different layouts; relative wording is still a
separate formatter difference.
