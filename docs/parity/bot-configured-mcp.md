# Configured Bot MCP — bounded autosave selection

## Pin and scope

Desktop/Gateway authority: `36922ad064d65dcf25f8f48df81e1ccf9a55de67`.
Approved mobile foundation: `6b9e2133e3694901356c58e7d5ba1af56050884e`.
This existing-Bot editor consumes the unchanged [ADR 0006](../adr/0006-scoped-core-mcp-http.md)
`PluginHost.mcp` door. No new RPC, HTTP capability, CLI, SSH or config-file access.

Inspected actual target source, not the historical staged checklist:

- `apps/desktop/src/plugins/hermes-bots/profile-config.tsx:33-43` feature-detects
  current `CapabilitiesView` with explicit connection routing.
- `apps/desktop/src/app/capabilities/connectors/connectors-tab.tsx:229-271` mounts
  the directory, suppresses plugin-owned toggles and routes local server actions.
- `apps/desktop/src/app/capabilities/connectors/local-dialog.tsx:60-90` wires
  server toggle, authentication, refresh tools and advanced removal.
- `apps/desktop/src/app/capabilities/mcp/use-mcp-servers.ts:146-160,231-276,315-319`
  persists the whole map, calls `reload.mcp`, and probes enabled servers.
- `apps/desktop/src/api/mcp.ts:38-53` explicitly replaces the whole server map.
- `apps/desktop/src/i18n/en.ts:2821` supplies `MCP servers`.
- `hermes_cli/web_routers/mcp.py:372-395` instead provides the bounded named
  enable delta, refuses plugin ownership, preserves other configuration, and
  explicitly applies to the next session/Gateway. Android uses this route.

## Behavioral contract

- Only source `Config` is writable; Plugin and unknown provenance are visibly
  read-only. An unknown **name** with known Config provenance remains writable.
- Autosave submits one exact named delta. Outer Save is unrelated. Cancel closes
  the editor but does not undo an admitted change; the UI says so explicitly.
- Pending switches retain observed values. Success requires the typed host's
  receipt and matching fresh named readback, including Config provenance. The
  success copy says selection applies to future sessions or Gateway starts,
  never that the MCP process is healthy or immediately connected.
- Dialog close revokes future enqueue, not the plugin-owned coroutine. Pending
  ownership survives close/reopen and profile A→B→A; fresh endpoint generations
  never inherit old completion. Reopened controls remain disabled until the
  original mutation completes and a fresh read reconciles. Never replay a write.
- Dialog revision plus read sequence fence late inventory responses. Refused,
  cancelled, stale, unchanged and unconfirmed outcomes do not say saved. Failed
  reconciliation removes rows/edit authority while retaining uncertainty.
- Both registered Bots routes wire the editor. Identity/model/avatar/Toolsets
  drafts and installed Skills state are not rewritten or refreshed by MCP.
- Names pass through `redact()` only for visible text and accessible labels;
  callbacks retain the exact wire identifier. The foundation already rejects
  redaction-changing names. This is defense in depth, **not** a claim that every
  possible secret-looking name is detected or that HTTP memory is secret-free.
  Raw transport metadata still enters host memory before typed projection.

## Later owned-MCP nested-control inventory

A separate source audit at Desktop `81a55437f3f31a9f12a30c53b6c5d49d420898aa`
and Android baseline `65080e8a37e8d546869528e43c162503015b94c7` preserves the
historical Pin and packets. Local Advanced is actually supplied only for an
installed, non-plugin local server, not every connector
(`apps/desktop/src/app/capabilities/connectors/local-dialog.tsx:45-83`;
`apps/desktop/src/app/capabilities/connectors/connector-dialog.tsx:371-408`, at
that Desktop SHA). It mounts JSON with an imported Format button, Save, log
stdio/agent tabs/search and Remove confirmation. Log children also add Clear,
previous/next match, Copy and top/bottom actions. Exact mount/handler citations
are in [the bounded control reconciliation](desktop-control-reconciliation.md).

Whole-map save/removal is not the named-toggle API
(`apps/desktop/src/app/capabilities/mcp/use-mcp-servers.ts:228-287,316-396`;
`apps/desktop/src/api/mcp.ts:36-49`, same Desktop SHA). This page's approved
ADR 0006 selection adaptation remains deliberate, not accidental drift. No
broader JSON/log management implementation follows from the native toggle.
The frozen native surface still only has list/config-source toggles and six WIP
parents (`app/src/main/kotlin/com/hermesagent/mobile/plugins/bots/BotMcpEditor.kt:20-65`
at the Android baseline). Missing JSON, Format, Save and log navigation are
controls owing pills under #73, not just omitted descriptive details.

An authority concern remains upstream: the imported log hook observes
`activeGatewayProfile` and calls `getLogs` without the selected capability scope
(`apps/desktop/src/app/capabilities/mcp/mcp-logs.tsx:34-53` at the later Desktop
SHA). Reading a fixed-profile config therefore does not certify its displayed
logs belong to that same owner. Hosted account/OAuth/probe/per-tool/Add flows
remain unverified omissions under #73/#266, not certified by reading controller
method definitions. No installed config, server, credential or log was read;
current rendering remains Concern under existing #194.

## Divergences

| Desktop | Class | Android | Evidence |
|---|---|---|---|
| Whole-map persistence followed by reload/probe | mobile-adaptation | One named enable delta plus authoritative readback; reduced mobile mutation authority avoids clobbering unrelated configuration and promising live health | ADR 0006; source at the pin above; typed host tests |
| Directory/detail layout and richer status | mobile-adaptation | Inline Configured MCP servers section in the phone's existing editor, 48dp Switch rows; no fabricated health/status | `BotMcpEditor`; Compose semantics journeys |
| Immediate persistence with outer editor controls | mobile-adaptation | Explicit Cancel-does-not-undo copy and future-session success wording to distinguish independent autosave from drafts | Registered route Cancel/reopen journey |
| Catalog, add, OAuth/credentials, remove, probe, per-tool controls | omission | Six visible disabled WIP actions; no new host doors | coming soon — bounded selection only |
| Raw JSON editor, Format/Save and MCP log controls (later source inventory, not a repin) | omission | No nested controls behind the bounded selection surface | pill-owed: #73; exact owned mount, callbacks and imported log actions in the source reconciliation |
| MCP usage/cost descriptive detail | omission | Not included in this bounded existing-Bot selection section | deferred: #194 — broader Desktop management acceptance |
| Plugin-owned or unknown provenance | mobile-adaptation | Explicit read-only source labels rather than implying every returned row is a mutable config entry | VM refusal and Compose disabled-row tests |

## Visual report

- pending: #194 — independent acceptance review remains outstanding.

[Historical candidate capture](../media/bot-configured-mcp-aac3177b/REPORT.md)
now provides 26 Android state/theme receipts from candidate `aac3177b` and 14
actual current Desktop Connectors observations at the exact pin above, with
[paired originals](../media/bot-configured-mcp-aac3177b/pairs.md), before/after
runtime telemetry, source manifests and APK hashes. Android canonical-schema
validation passes; Desktop observations remain noncanonical. The bounded named
write/readback adaptation is explicitly distinguished from Desktop whole-map
persistence, reload send and synthetic probe. No live Gateway, physical phone,
TalkBack speech, full on-device registered-route journey, or cross-platform
visual equivalence is certified. Main Classic integration was deliberately
excluded from that historical capture. The branch now includes main
`c5a5524c60b551e21cd3a2041a4fd29d67b97611` through a history-preserving merge;
this does not change the captured source/APK pins or certify new pixels.
The current ceiling remains Concern until independent review. Draft publication
is for review only, not evidence acceptance.

## Deterministic fixture and evidence boundary

`bot-configured-mcp-synthetic-v1` registers **13** states in the capture catalog
and workflow: loaded, disabled, plugin, unknown, loading, pending, saved,
reopened, error, unavailable, refused, empty and unconfirmed (all `mcp-` prefixed).
These are discovered Android behavioral boundaries, not 13 asserted Desktop
counterparts. There is no MCP essential-skill rule; unchanged readback is covered,
and existing Skills essential normalization remains a separate regression.

`BotMcpParityActivity` renders the production editor and ViewModel through the
production typed host over an allowlisted synthetic transport. State staging
uses actual reads/toggles/reopen, not painted success/loading flags. Loading and
pending use the unchanged 20-second request deadline. The worker requires a
fresh measured launch and brackets screenshots below that deadline.

The debug-only, DUMP-protected `.mcp-runtime` provider exports actual request
sequence, method, path, query, mutation body, returned synthetic response/status,
pending/completed/cancelled outcome and monotonic times, alongside authoritative
selection and current VM projection. It accepts no arbitrary input and is cleared
on fixture disposal. The worker reads it before/after screencap, verifies fixture,
state/theme identity and pending operations, and retains the samples in the
screenshot bracket. These are synthetic observations, not a live-server proof or
human gesture receipt. Preserve APK/source identity when captures are produced.

## Verification

Strict RED/GREEN slices observed missing VM, UI wiring and fixture, then passed
focused JVM/Compose runs. Transport regression additionally exercises synthetic
socket HTTP 408 alongside 302/307/503 and requires exactly one PUT admission.
Post-admission cancellation and close/reopen regressions exercise the production
typed host consumer, without treating cancellation as rollback. Full verification
results: focused **111 tests**, zero failures/errors/skips; full debug **3,440**
tests and release **2,733**, each with zero failures/errors and one pre-existing
skip. Serial `check assembleDebug` passed with one worker, no parallel execution,
in-process Kotlin and Xmx6g. The unfiltered rerun completed debug before the tool
execution deadline interrupted release; after verifying no surviving Gradle
process, a sequential resume completed release, lint/check and assemble. Existing
compiler/deprecation warnings and synthetic image-decoder diagnostics remain.
Five MCP capture-contract Python tests also passed. No push or deployment.
