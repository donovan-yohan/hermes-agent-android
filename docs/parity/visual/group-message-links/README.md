# Group message link evidence

The report is `report.html`. It compares the exact public APK-link message
before/after Android's group-renderer fix with the pinned production Desktop
group surface. No private conversation or backend configuration was captured.

## Desktop

`desktop/README.md` gives the command and capture-only harness. The production
`GroupChatMainView` is obtained through `openGroupChat()`'s real workspace
callback, with synthetic in-memory room data and narrow fixture window-host
seams. Tracked source hashes are checked against Git blobs at
`36922ad064d65dcf25f8f48df81e1ccf9a55de67`. No profile, Electron app or Hermes
backend is started. External browser requests are refused. The actual
production touch handler is checked through the preview store without mounting
or fetching a preview.

The renderer uses Mono light/dark at 412 CSS pixels. The Android default Nous
light capture is a message-body comparison, not a matching-theme claim.

## Android

`android/source-manifest.json` identifies the baseline, RED commit, final source
hashes and reproduction command. `ApkArtifactLinkDiagnosticTest` hydrates the
exact public paragraph through history and the real hosted `message.member`
wire parser. `GroupMessageLinkTest` covers group actions and guardrails. Text
annotations and coordinate taps are asserted, rather than relying on screenshot
styling as proof of clickability.

Captures are production Compose window draws using Robolectric's native
Canvas at API 34, 411 × 891. They are not emulator or physical-device acceptance,
TalkBack validation, network downloads or APK installation evidence. The
baseline capture uses the failing regression before the production change.

## Inventory

`hashes.json` covers every published file other than itself. The source and
capture manifests are included in the digest inventory. The script that
publishes and checks this packet lives only in the task scratch directory;
the committed harness and native tests provide reproducible evidence.

The Desktop manifest discloses an execution mistake in two failed capture
attempts: Vite's default bundled-config loader created and removed transient
`.vite-temp` files through reused dependency symlinks in the read-only upstream
checkout. No tracked source changed. The final successful harness uses the
runner config loader and an isolated writable cache. This is not represented
as a wholly write-free investigation.

Opening the public GitHub Actions link requires GitHub sign-in. The artifact
is a ZIP containing an APK, not an attachment delivered by this chat. Artifact
availability and successful phone installation are outside this report.
