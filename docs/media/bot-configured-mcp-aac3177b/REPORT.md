# Configured MCP — historical candidate capture

## Outcome and exact pins

**26 Android canonical-schema receipts and 14 genuine Desktop observations**, all with original light/dark PNGs. This is a bounded synthetic evidence packet, not a rendered-parity approval or a claim of 13 equivalent Desktop states. Independent review remains pending under #194.

- Android capture source: `aac3177b0cb4b73482d1802d5b407a7bb8f40cfe`.
- Existing debug APK and installed base APK: `ffdd9460d1a5236979fb2b0644da0747e6e86d63a3d4bb77e3da37d4ba43766c`.
- Desktop source: `36922ad064d65dcf25f8f48df81e1ccf9a55de67`.
- No Gradle run, Android source edit, main integration, push, shared-emulator install, or physical-phone action in this task. Later main Classic changes are deliberately absent.
- Android source attribution is the clean candidate supplied with the existing APK. Installed-byte equality is verified; this capture does **not** supply a reproducible rebuild or embedded APK source attestation. Publication commits are not build commits.

[Provenance](provenance.json), [per-file hashes](files.json), [validation results](validation.json), [earlier-attempt history](validation-history.json), [paired gallery](pairs.md).

## Android: actual production VM over synthetic transport

The catalog was read rather than assumed: 13 states × light/dark = 26 unique captures. The unchanged worker verified installed bytes before a fresh launch, focused `BotMcpParityActivity`, real accessibility labels, and `.mcp-runtime` snapshots immediately before/after screencap. All 26 passed `visual_parity_contract.py check-receipt --platform android`.

The fixture mounts production `BotMcpEditor`, `BotsMcpViewModel`, and `GatewayPluginMcp`. Its allowlisted transport is synthetic and performs no network traffic. Toggle/reopen states are staged through actual VM methods, not through Android touch gestures. The heading and standalone Activity wrapper are fixture UI, not proof of opening the full registered Bot dialog or of outer Cancel behavior on-device.

| Catalog state | Observed runtime boundary, both modes |
|---|---|
| `mcp-loaded` | GET 200; Config row enabled |
| `mcp-disabled` | GET 200; Config row disabled |
| `mcp-plugin` | GET 200; Plugin row, read-only copy |
| `mcp-unknown` | GET 200; unknown wire source projected to Other, read-only copy |
| `mcp-loading` | GET genuinely pending, VM loading, no rows |
| `mcp-pending` | One named PUT genuinely pending; observed false retained, VM busy |
| `mcp-saved` | One PUT 200 followed by GET 200 enabled readback; future-session success copy |
| `mcp-reopened` | Same one PUT, additional fresh GET after VM close/open; enabled |
| `mcp-error` | GET 403; refused-access text, no rows |
| `mcp-unavailable` | Scoped and fallback GET 404; unavailable text, no rows |
| `mcp-refused` | PUT 403 then fresh GET 200 false; uncertainty, no saved claim |
| `mcp-empty` | GET 200 with empty inventory |
| `mcp-unconfirmed` | PUT 200; subsequent reads status 0; authoritative synthetic value true but VM removes rows/edit authority and reports uncertainty |

Loading/pending retain the unchanged **20-second** production deadline. All four screenshot/postcheck brackets finished below it; the largest postcheck was **7.3571 seconds** after pre-launch monotonic start. Every state retains both runtime samples in its original `contract.json`; no gestures or successful writes were invented in receipts.

Device: task-owned ARM64 emulator, Android 17/API 37, font scale 1.0. Original PNGs are 1080×1920; a requested taller viewport was constrained by the emulator and is not claimed. This is not Android 13 OS evidence: thirteen refers to catalog states. Mono and requested light/dark are fixture-controlled. SystemUI time is not normalized, and Android locale/timezone have no per-state resolved getter in this packet. TalkBack speech, contrast certification, physical-device behavior, live Gateway operation and on-device registered-route integration remain unverified.

## Desktop: actual current Connectors, not the fallback checklist

A new immutable export was installed from the committed npm lockfile and freshly built with `npm run build --workspace apps/desktop`. `uv sync --frozen --no-dev` prepared the real E2E backend. The actual `setupMockBackend` Electron fixture creates an isolated, credential-stripped sandbox. Only an additive capture spec was introduced; no production components were modified. No `perf:serve`, old staged checklist, manually drawn UI, user profile, or live server configuration was used.

The fixture creates a synthetic Bot, opens its actual Edit profile → Advanced → Connectors surface, and intercepts the real `hermes:api` seam for a credentials-free config. The entire server map contains only `synthetic-research` (synthetic command) and `synthetic-notes` (`example.invalid`). Commands/probes are not executed against real MCP servers. Other panels are backed by the isolated real E2E sandbox.

Four final Playwright scenarios passed (exit 0). Seven observations × light/dark = 14 originals:

| Observation | What is actually proved |
|---|---|
| disabled | Real current Connectors directory, both synthetic Config entries off |
| pending | Native switch click admitted a whole-map PUT; server remains off while the promise is held. The directory switch remains enabled, unlike Android's busy gate |
| saved | Whole-map PUT completes; rendered switch on; actual `reload.mcp` **send** observed; synthetic POST test request/response observed |
| reopened | Actual outer Cancel and editor reopen; fresh config GET, enabled value retained, no second PUT |
| empty | Successful config read with no server entries |
| loading | Config promise held pending; visible native skeleton and accessible reading label |
| read-refused-settled | Four refused config attempts, then the actual directory renders its **empty-state copy**, not an Android-style refusal banner |

`reload.mcp` telemetry proves a send, not successful reload completion or real process health. The enabled entry is represented by **removing `enabled:false`**, not by inventing an `enabled:true` field. The untouched second server remains present in the whole-map payload.

DOM/ARIA and transport samples accompany native PNGs. The final fixture scrolls the real Advanced section into view; headers/identity controls outside the viewport are not certified. Desktop uses Mono, real `data-hermes-mode` assertions, renderer en-US/UTC and fixed synthetic clock. SystemUI/viewport, layout, inventory size, and timestamp normalization differ across platforms.

## Adaptation, not equivalence

Android uses `PUT /api/mcp/servers/synthetic-research/enabled` with the bounded enable delta and explicit profile, then authoritative inventory readback. It neither replaces the complete map nor reloads/probes a live MCP server. Desktop uses `PUT /api/mcp/servers` with the complete config map, followed by reload request and probe. These are deliberately different mutation authorities and success meanings.

Disabled, pending, saved, reopened, empty and loading form useful **behavioral comparisons**, not interchangeable contracts. Android `mcp-error` is paired with Desktop settled read refusal only to show the divergent result. Plugin/unknown provenance, unavailable route, mutation refusal and lost readback have no captured Desktop counterpart here. Android loaded true has no independently staged Desktop loaded-true observation; Desktop saved/reopened true is not relabelled as one.

All 14 Desktop observations were honestly rejected by the canonical validator: `receipt misses required common provenance`. They remain observational, not padded with invented canonical fields. Android schema acceptance likewise does not prove cross-platform visual equality.

## Provenance and reconstruction

- [Android manifest](android/source-manifest.json): 1,604 immutable archive files compared against the clean capture checkout before publication, zero mismatches.
- [Desktop manifest](desktop/source-manifest.json): 17,051 immutable archive files compared against the disposable built export, zero mismatches. Archive bytes, rather than raw Git blob bytes, preserve export transformations such as CRLF. An initialized export can carry a placeholder build stamp; its manifest, not that stamp, is the source authority.
- [Additive fixture](desktop/configured-mcp-reference.spec.ts) and [patch](desktop/fixture.patch) were reconstructed in an independent initialized directory with matching SHA-256 `028458023e15770dc27189fc6da5674b62be2e3d757abeccf8a38da39db9d43b`.
- Apply the patch to an immutable Desktop export after `git init -q`, then install/build the pinned source. From `apps/desktop`, run `PARITY_OUT=<owned-output> DISPLAY=<owned-display> npx playwright test e2e/configured-mcp-reference.spec.ts --workers=1` with the real sandbox prerequisites. This is a reproducible capture fixture, not a promise of identical rasterization or randomized Bot face.
- Android requires the retained APK with the hash above and the candidate's unchanged `.chalk/skills/port-hermes-desktop-surface/scripts/capture-android-reference.py`; invoke the worker directly with an explicit task-owned serial and `--launch-fixture`. Do not use the CI shell wrapper, which rebuilds and assumes a default device.

## Rejections, privacy and cleanup

Earlier raw attempts remain private and unchanged. The first Desktop attempt failed a wire-id locator because displayed names are title-cased. A later read-error snapshot was still in retry/loading phase. Another otherwise-passing run captured loading below the viewport; it was rejected visually and recaptured by scrolling the real component, not by modifying pixels or production code. Their original hashes and reasons are preserved in `validation-history.json`.

Publication allowlists original PNGs, safe receipt/observation JSON, hashes, fixture patch/source and this report. No APK, traces, private host scripts, serials, device paths, sandbox logs or credentials are published. Dependency installation reported 28 audit vulnerabilities (3 low, 8 moderate, 17 high); no remediation changed this historical pin.

The owned Android emulator was stopped; process and serial absence were read back while the pre-existing emulator remained available. Its disposable AVD/data and raw logs are retained privately for provenance, not running. No shared package bytes or physical Pixel were changed. The owned Desktop display was stopped after captures. Source/Gradle trees remain unchanged; this packet is a docs/evidence-only local publication and has not been pushed.
