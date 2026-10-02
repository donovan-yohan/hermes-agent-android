# Upstream target refresh — e05b1634 — 2026-10-01

## Scope and provenance

Implementation target: `e05b16348b1d06a3311237423b0a4fc30d9c5aa1` in
`NousResearch/hermes-agent`. Android base: fetched `origin/main`
`651d659bded4deb7b125e48ef746c899857837e0`, isolated branch `feat/upstream-e05`.
Local `main` differed from fetched main; only the new, clean worktree was based
onto the fetched main tip. Original worktree WIP and other worker branches were
not edited. No push and no Gradle invocation in this lane.

This is a **theme-contract refresh, not a full upstream sync**. Source was read
from the scratch `e05-source` export and `AUDIT.md`; missing shared palettes were
exported from the disposable `upstream-e05-audit.git`, never the installed clone.
The six inspected theme exports were byte-checked against target Git blobs.
A plain export has no Git HEAD: the parity command uses `GIT_CEILING_DIRECTORIES`
to prevent a containing repository's unrelated HEAD from masquerading as its pin.

Historical screenshots, reports, per-surface citations and the previous
[587e673e audit](upstream-bot-refresh-587e673e-2026-10-01.md) retain their original
pins. Updating AGENTS and the built-in ledger does not revalidate those captures.

## Implemented current theme contracts

All source citations in this section are at the target SHA above.

- `apps/desktop/src/themes/backend-sync.ts:94-101`: backend `default` does not
  register a converted Classic Hermes palette. Parser rejection also filters
  legacy cache entries on read/write, without deleting other scoped definitions.
- `backend-sync.ts:134-147`: connect seeds never repaint; explicit activation
  can apply after a seed. Successful repeats/reconnect seeds must not undo a
  manual selection. The guard tracks the **announced name**, so `default` and
  `nous` remain distinct backend changes even though both paint Nous. Android
  acknowledges only successful preference writes, keeping failed writes retryable.
- `apps/desktop/src/themes/context.tsx:51-63,107-112`: `default`, `gold` and
  `nous-light` resolve to Nous even if a stale/custom definition exists. Keep the
  raw stored choice and mode; unresolved custom picks can resolve after cache
  restoration. Android retains its existing connection/profile ownership fences.
- `apps/desktop/src/themes/use-skin-command.ts:5-12`: slash aliases additionally
  include `hermes` -> `nous` and `ares` -> `ember`. These are **not** retired
  persisted names; this change does not add a local Android slash-command handler.
- `backend-sync.ts:114-130`: Desktop preserves default/built-in custom CSS under
  the resolved name. Android still does not execute remote CSS; palette routing
  parity here is not a CSS-support claim.

## Built-in inventory

The executable inventory discovered **11**, in this order:
`nous`, `github`, `catppuccin`, `everforest`, `solarized`, `nous-alt`, `midnight`,
`ember`, `mono`, `slate`, `cyberpunk`. Default remains `nous`.
Names, labels, descriptions, order and hand-tuned-dark flags match Android.
No palette/font/token edits are warranted by this delta. Paired old/target blob
IDs are equal for:

| Path | Blob at both 587e673e and e05b1634 |
|---|---|
| `apps/desktop/src/themes/presets.ts` | `f4f24f9426a4addbb06464e736f766c145c8d298` |
| `apps/shared/src/theme-presets.ts` | `bc0a76b73af45313c21a30e4a81e082f2b3121d9` |
| `apps/desktop/src/themes/types.ts` | `08e187468fd0c19e2e4e0af0e00a03e63e1618c1` |

## Verification and gates

Executed without Gradle:

```bash
python3 /home/donovanyohan/.hermes/profiles/ebi/cache/scratch/e05-theme-check.py
GIT_CEILING_DIRECTORIES=/home/donovanyohan/.hermes/profiles/ebi/cache/scratch \
  python3 .chalk/skills/sync-hermes-desktop-themes/scripts/check-theme-parity.py \
  --upstream /home/donovanyohan/.hermes/profiles/ebi/cache/scratch/e05-source
python3 scripts/check-parity-evidence.py
git diff --check
```

Standalone Kotlin/JUnit: **55 tests passed**, covering parser, cache, sync,
repository, resolver, theme parity and color math. Parser, explicit-default reset,
and retired-name resolution regressions were observed failing before their fixes.
This scratch harness compiles current theme data sources/resolver/tests and reads
unchanged collaborator classes from the existing `hermes-mobile-upstream-refresh`
build. It does **not** compile the Compose integration or run Robolectric preference
coverage, and is not a clean Android build. Inventory: 11 matching presets.
Parity-document gate: 41 pages passed.

**Awaiting build lane**: Skills worker `sa-0-8c2bce7f` owns Gradle. After release,
run from this isolated worktree:

```bash
./gradlew :app:testDebugUnitTest \
  --tests '*GatewayThemeParserTest*' --tests '*GatewayThemeRepositoryTest*' \
  --tests '*BackendSkinCacheTest*' --tests '*BackendSkinSyncTest*' \
  --tests '*AppearanceThemeResolverTest*' --tests '*HermesPreferencesTest*' \
  --tests '*ThemeParityTest*' --tests '*ColorMathTest*' \
  --tests '*BackendSkinRestartJourneyTest*' --tests '*AppearanceThemesJourneyTest*'
./gradlew check :app:assembleDebug
```

Rendered light/dark/phone/wide comparison of the default reset remains pending
under the appearance ledger's existing #292 gate. No new visual parity or live
Gateway compatibility claim is made.

## Explicit pending upstream checklist (not implemented here)

- [ ] Truncated replay: discard incomplete catch-up, advance watermark and resync
  authoritative transcript while retaining newer parked live frames and open-request
  recovery (`apps/shared/src/json-rpc-gateway.ts:538-635`).
- [ ] Steering: authoritative session-liveness check before optimistic echo/RPC;
  preserve endpoint/turn ownership (`use-prompt-actions/index.ts:792-810`).
- [ ] Stop versus steer interruption and persisted interrupted metadata
  (`use-prompt-actions/rewind.ts:417-438`); failed-turn error hydration/reconciliation
  (`chat-messages/hydration.ts:194-211,577-591`, `reconciliation.ts:333-359`).
- [ ] Optional create/branch idempotency keys: stable per logical action, retry reuse,
  process-local/TTL limits, no durable exactly-once claim
  (`tui_gateway/contracts/sessions.py:133-160,396-422`).
- [ ] Cron `scheduler_owned` authority, nullable compatibility policy and pre-send
  detail recheck (`hermes_cli/web_routers/cron.py:447-502,540-546`).
- [ ] Redirect-hop auth/custom-header scoping and canonical native callbacks;
  review Android equivalents, not Electron implementation transplantation
  (`apps/desktop/electron/remote-ws-headers.ts:115-146,174-228`,
  `hermes_cli/dashboard_auth/routes.py:218-258,281`).
- [ ] Skills lane's scoped availability probe and mutation fencing verification;
  existing Skills/model/avatar/toolsets contracts are unchanged across these pins,
  not new migrations. Preserve their evidence and worker ownership.
