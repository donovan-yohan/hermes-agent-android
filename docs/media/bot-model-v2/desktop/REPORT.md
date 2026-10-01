# Desktop bot-model-config v2: real captures, partial contract acceptance

- Real pinned Electron: 14 state/theme executions, 16 unchanged PNGs (14 primary + two refusal reopens).
- 12 receipts accepted; both loading receipts rejected. The run exit status is 1, not success.
- Synthetic Planner / synthetic-provider / synthetic-planner-v1→v2; mono, light/dark, en-US, UTC, fixed wall clock and live timers.
- Shared confirmation uses the actual named dialog. Saved performs initial warning, explicit confirmation, authoritative apply, close, roster reopen and named v2 read.
- Initial refusal is one unconfirmed model-only write, null response and RPC error, native `[role="alert"]` notice: “Advanced configuration failed” / “Synthetic model save refused”. Editor closes; separate reopened capture shows original pair. The notice is captured first, then dismissed through its real close button (retained nodes and RPC timeline marker) before reopening, so it cannot occlude the original pair. No confirmed retry.

## Precise loading blocker

`apps/desktop/src/plugins/hermes-bots/model-picker.tsx:148–153` returns only GlyphSpinner while `isLoading`. No provider/model fields are displayed. `scripts/visual_parity_contract.py:272–275` nevertheless requires the original visible pair for every state except confirmation and refusal. Loading receipts truthfully contain `fields:null`; validator rejects with “visible fields must match the editor phase; hidden fields must not be claimed”. Production and catalog were not modified. Do not certify these two receipts unless a separately authorized future contract expresses hidden loading fields.

Both request/PNG brackets independently validate and finish before 20 seconds: light 1957→2499 ms; dark 1789→2350 ms, monotonic since interception. Pending, response:null and error:null on both sides. Authoritative describe remains v1; zero model writes. These are genuine loading evidence, not accepted receipts or timeout fallback.

## Evidence and reproduction

`accepted/` and `rejected/` separate validation outcomes. Each contains actual PNG, receipt, resolved contract, raw safe RPC/action trace and normalization getters. Refusal has independently hashed reopened PNG. `validation.json` records every fresh validator exit code. `provenance.json` records immutable archive comparison, build and fixture hashes. Apply `capture-only.patch` with `git apply` to a standalone immutable 587e673e2a2fae0616d8b750bb189217080f621a export; the fixture is a new E2E spec, not production code. Set PARITY_CONTRACT to the read-only mobile worktree and PARITY_OUT to a new scratch directory, then run from apps/desktop with a dedicated Xvfb DISPLAY: `npx playwright test e2e/model-contract-v2.spec.ts --workers=1`.

The export reuses an existing matching dist/build and dependencies; no new Desktop build occurred in this task. A separate reconstruction verifies fixture hash. Prior model-editor-reference.spec.ts and historical packets remain untouched.

## Scope and retained limitations

Create UI prefixes description with title; scratch setup uses the real editor to seed exactly “Reviews release plans” before opening the recorded contract scenario. Runtime scoped describe supplies exact SOUL and model pair. Native capabilities catalog remains visible in the editor; its incidental descriptions are not synthetic model assertions.

Receipt nodes are a safe observed subset: broad ancestor aggregates ≥500 characters and nodes containing privacy-denylisted words (notably incidental catalog “Auth” tags) are omitted. Required model/dialog labels, action nodes, exact values and raw safe RPCs are preserved; no pixels were changed. Full uncurated node trees, failed candidates and logs remain in local attempt directories, excluded from this allowlisted packet.

The editor is scrolled to Advanced for model captures; its header may be above the visible scroll area. Confirmation and refusal are independent native crops, never full-shell stand-ins. Playwright traces remain local because they contain sandbox paths. No Android edits, Gradle, GitHub calls, or protected worktree writes were performed.
