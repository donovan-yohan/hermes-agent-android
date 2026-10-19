# Desktop cron synthetic reference packet

## Publication status

This is a curated public derivative of the original capture packet. All canonical PNGs and screenshot receipts, upstream source snapshots, font assets and final execution evidence retain their original bytes. `SHA256SUMS.json` is the **unchanged original packet manifest**, not a checksum list for this smaller publication. `publication-manifest.json` inventories current files, maps every transformed auxiliary file from original to published SHA-256, and lists excluded originals by relative filename and digest only. `publication-verification.json` records the separate read-only publication check plus executed helper preflight, additive-patch application/byte checks, root-redaction equivalence and source-packet immutability checks. No browser capture or visual assessment was repeated for publication.

## Historical capture outcome

- Upstream source pin: `5fc490e2b34123212d73ef316086f939567a725a`.
- **40 canonical screenshots: 20 states × mono dark/light**, 1280×960, device scale 1, `en-US`, UTC, fixed clock `2026-10-19T09:00:00Z`.
- **340 real Playwright assertions passed; zero final browser page/console errors.** `capture-index.json` is the canonical screenshot enumeration; `gallery.html` shows paired modes.
- **The original capture audit recorded 20,366 identical tracked-file hashes** and `git diff --exit-code HEAD --` returned 0, with three new untracked fixture/harness files. `verification.json` is that unchanged historical receipt, not a fresh checkout audit. The before/after inventories are excluded from publication because unrelated upstream filenames contain contributor identities and private hostnames; their identical original digests remain in both manifests.

## What is genuine / what is synthetic

The actual unchanged upstream `CronView`, private editor via its genuine UI, `SidebarCronJobsSection`, overlay/panel/dialog/menu/notification primitives, `styles.css`, `ThemeProvider`, `I18nProvider`, and QueryClient execute in Chromium served by the existing Vite config. No component or production source is copied/reimplemented to manufacture the screen. Fixtures mount the modules by dynamic import after installing the synthetic `window.hermesDesktop.api` boundary. Public Courier Prime font CSS/TTFs, required by the real mono theme, are hash-pinned in `fonts/` and fulfilled from that cache during capture; normal bundled icon/fonts/styles load from Vite.

**This is not a live service, native Electron window, full Desktop shell, production-data test, Android parity result, or backend/scheduler correctness proof.** Labels on every capture state “SYNTHETIC FIXTURE — no live backend” and distinguish fixture framing from Desktop shell. Outer header/footer and 320px sidebar host are fixture framing. No real profile/config/auth file, job store, or runtime connection was accessed; all mutation responses are synthetic in-memory data.

## Canonical paired states

1. Populated manager + Status report detail + closed and scheduler-owned run rows
2. Manager menu (`Edit cron`, `Delete`)
3. Edit dialog
4. All seven frequency choices
5. Custom schedule editor
6. Synthetic provider/model catalog choices
7. Paused job detail
8. Create dialog
9. Blank-prompt validation
10. Multiple delivery selections
11. Delete confirmation
12. Script-only detail + inert output document
13. Script-only editor (blank prompt, no model selector)
14. Synthetic Recipe typed blueprint editor
15. Sidebar expanded run peek
16. Sidebar context actions (`Trigger now`, `Pause cron`, `Manage`, `Delete`)
17. Empty state
18. Job last-error state
19. List-load failure notification (real component also displays empty copy)
20. Held-trigger queued row + busy/disabled action

## Interaction coverage

Recorded clicks, fills and assertions live in `interaction-events.json`. The harness opens existing and new editors through actual controls, tests seven schedule choices, uses custom cron, selects then resets provider/model together, pauses/resumes and reads the synthetic store after UI refresh, validates an empty prompt, creates with multiple delivery targets, cancels then confirms deletion, saves script-only without introducing an AI prompt, instantiates a typed recipe with changed time/topic and local delivery, opens run callbacks with owning profile/liveness rows from manager and sidebar, uses sidebar Manage to focus the manager, and holds one trigger promise to expose the genuine optimistic queued UI with duplicate dispatch guarded. Creates/updates/deletes are followed by exact synthetic-store checks and rendered readbacks, not assumed from a successful call.

Receipt JSON per capture contains screenshot SHA-256, exact selector + target geometry, visible copy, controls/values, viewport, skin/mode, fixture state, fixed clock, browser version, all key component/style/fixture source hashes, font source hashes, and complete synthetic request/state snapshot. Each screenshot receipt explicitly states `liveBackend:false`. The published read-only `verify-packet.py` validates all canonical screenshot/receipt bytes and the included original source/font bytes. Historical receipts retain the original capture-harness hash: its published environment-only derivative is explicitly mapped in `publication-manifest.json`, not misrepresented as the original bytes. The excluded full tracked inventories and excluded machine logs are not revalidated by the published verifier.

## Dataset contract

`report-daily`, profile `default`, name `Status report`, prompt `Prepare a status report`, schedule `*/15 * * * *`, display `Every 15 minutes`, delivery `local`, next `2026-10-20T09:00:00Z`. Completed row `local-cron-completed` has source `cron`, title `Status report`, started `1792342800`, ended/last-active `1792342860`; active row `local-cron-active` explicitly has `scheduler_owned:true`. Script job `script-check` has `no_agent:true`, blank prompt, script `printf scheduled`. Its `cron_output` history record is not a navigation button.

The schedule/next-date mismatch is intentional supplied comparison data, not inferred live scheduling. Job-level `last_run_at` remains null independently from supplied run history. Do not interpret this fixture as evidence of scheduler timestamps.

`Synthetic Recipe` is explicitly synthetic, not an alleged built-in/backend recipe. Its real Desktop-supported field vocabulary is `time`, `enum`, `weekdays`, `text`, and the delivery special case. It exercises defaults and changed slot submission. Synthetic model/provider and delivery discovery are labeled in source and receipts, not claims about installed configuration.

## Observations / limitations

- Actual CronView list-load failure calls `notifyError` and then ends loading without a distinct error body. With no cached jobs/blueprints the real UI shows **“No scheduled jobs yet” alongside “Failed to load cron jobs”**. Both themes capture that behavior rather than inventing an error screen. Mobile should retain its stronger explicit failure-vs-empty contract.
- The pending row is genuine UI optimism while the synthetic trigger promise is deliberately held. It does **not** prove backend acceptance, run materialization, success, trigger timeout, or a live scheduler.
- Opening a run verifies the real `openCronRun` callback row/liveness path, not a full transcript navigation or send/permission flow.
- Synthetic delivery choices demonstrate multi-selection, not a connected Telegram/Slack account. The real component's Slack “set a home channel first” hint is displayed; the hint itself does not prove a disabled control.
- Exact stored unknown delivery/model retention, endpoint/profile ABA, real backend reconciliation, loading/reconnection, mobile layouts, and full shell/native controls are outside this packet.
- Some editor captures retain genuine synthetic notifications from earlier actions in that scenario. Their underlying form fields are captured in receipts; visible placeholder strings are **not** entered values.
- Reduced motion is enabled for stable screenshots; Vite reports the expected motion-library reduced-motion warning, not an error.

## Reproduction

Use a new disposable checkout at the documented pin with dependencies installed; never use a personal or protected checkout. The historical capture used existing dependencies and unchanged manifests, locks and Vite config; native Electron scripts were not needed. Set `ROOT` to the disposable checkout, `PACKET` to this publication, `CRON_CHROMIUM` to an explicitly discovered executable, `CRON_EVIDENCE_DIR` to a separate new or empty scratch output directory, and `TMPDIR` to a writable scratch directory. Do not point capture output at this packet or the original evidence.

1. In `ROOT`, apply `git apply "$PACKET/fixture-additions.patch"`. It adds only the three published fixtures. The capture harness now requires `CRON_EVIDENCE_DIR`; this publication change does not restamp historical receipt hashes. Set `CRON_REFERENCE_URL=http://127.0.0.1:<free-port>` using a discovered free port (`53979` was the historical run).
2. Start the existing Vite CLI from `$ROOT/apps/desktop` via `node ../../node_modules/vite/bin/vite.js --host 127.0.0.1 --port <free-port> --strictPort`, using a fresh scratch `HOME`, a writable scratch `TMPDIR`, and **all `HERMES*` environment variables removed**. Stop only this task-owned server afterward. Historical shutdown verification remains in the original packet; its machine-specific process receipt is excluded here.
3. Export `ROOT`, `CRON_CHROMIUM`, `CRON_EVIDENCE_DIR`, and `CRON_REFERENCE_URL`, then run `python3 "$PACKET/run-capture.py" --preflight` followed by `python3 "$PACKET/run-capture.py"`. The helper checks the pin and deployed fixtures, refuses nonempty capture output, copies verified font assets and creates a fresh tracked baseline, then runs with isolated HOME/TMPDIR and cleared HERMES env. Non-loopback requests are denied except exact public font URLs fulfilled from hash-verified local bytes. The API boundary is synthetic. New capture logs can contain local paths; curate them separately before publication.
4. `python3 "$PACKET/verify-packet.py"` performs **read-only publication integrity and heuristic text privacy checks**, independent of an upstream checkout. It does not verify a new capture, reproduce the browser run, or rerun the historical full tracked-file audit.

`commands.jsonl` is a **redacted historical derivative** retaining all failed and passing commands, actual exit codes and output except machine-root substitutions. `<UPSTREAM_ROOT>`, `<SOURCE_PACKET>` and `<HOME>` replace private roots; these are disclosure placeholders, not executable shell commands. No failed attempt or exit status was removed. Initial expected fixture-absent assertion is the red preflight. Subsequent harness failures were exact copy-case expectations, not production changes. Initial default Playwright launch expected cache revision 1234, absent locally; explicit installed 1243 solved it without installation. An isolation-first run blocked the theme's Google font, producing logged resource errors; public source files were then cached/hashed and final capture rerun with the exact real font loaded. Preliminary diagnostics remain only in the original packet and are excluded from this publication, not silently promoted to canonical evidence.

## Files

- `screenshots/`, `receipts/`, `capture-index.json`, `gallery.html`
- `fixtures/cron-reference.{html,tsx}`, `fixtures/cron-reference.capture.mjs`
- `fixture-additions.patch` (additive, regenerated from the published fixture copies; never applied to a protected checkout)
- `source/` exact unchanged key upstream source excerpts as full files
- `verification.json` (unchanged historical audit; full tracked inventories excluded)
- `interaction-events.json`, `execution-summary.json`, `browser-errors.json`, `blocked-requests.json`
- `commands.jsonl` (redacted historical commands), `run-capture.py`, `verify-packet.py`, `fonts/`
- `SHA256SUMS.json` (unchanged original manifest), `publication-manifest.json`, `publication-verification.json`

Excluded auxiliary groups: preliminary `diagnostics/`, the Vite log, the task process/shutdown receipt, and the full before/after tracked inventories. Their exact relative filenames and original SHA-256 values are in the publication manifest. Exclusions preserve disclosure without publishing private roots, process identifiers or unrelated contributor/host inventories. The original packet remains the authority for excluded bytes. Privacy validation is a text-pattern scan, not a guarantee about rendered image content; this publication pass makes no screenshot visual judgment.

This packet is an upstream **core cron component reference** for later side-by-side Android review. It must not replace the mobile project's older per-surface evidence pins or be presented as full Desktop screenshot-shell parity.
