# Visual-parity capture lane

`visual-parity-capture.yml` is a manual, artifact-only lane. Dispatch one
catalogued surface/state/theme at a specific Android ref; it builds that exact
head and captures the debug-only synthetic Compose activity on the same pinned
Pixel 6/KVM emulator shape as the instrumented lane. The Android receipt records
its source SHA, installed APK SHA-256, fixture/state, theme, viewport and focused
application proof. It rejects packets that contain secrets, home/private paths,
serials, or uncatalogued fixture identifiers.

```text
ref: <branch name or immutable SHA>
surface: composer-status-stack | composer-url-chip
state: one key in docs/parity/visual-capture-surfaces.json
theme: dark | light
```

No workflow job has a write permission, bot token, auto-commit step, or mutation
of the existing `android-exact-head.yml` lane. Artifacts expire after 30 days.

## Desktop boundary at the declared pins

The Desktop capture script still captures real Chrome/Electron pixels and now
requires an exact clean disposable export, pinned SHA, synthetic fixture/state,
and theme. Its receipt retains only the upstream SHA and computed DOM/style
contract, never the temporary export path or local renderer URL.

A real **Desktop status-stack or URL-chip** packet cannot yet be automated from
the supplied pins. At `564aef2946c436500a5e80ee117b66b789b3f99a`, Desktop's
public E2E fixture API only launches a mock backend (`apps/desktop/e2e/fixtures.ts`
`setupMockBackend`); it exposes no state-seeder or renderer injection for the
composer-status stores. The actual status stack consumes renderer-local stores,
so setting those stores from a new Playwright process would not mount the pinned
component. The older URL-chip pin has the same absence of a public captured-state
fixture. Handwritten HTML or a copied screenshot would evade this boundary and is
not evidence, so the workflow uploads a `BLOCKED.md` boundary packet rather than
fabricating Desktop pixels.

Unblock Desktop capture by adding a **test-only** export-local E2E scenario at the
upstream pin that mounts the real composer/status components through their real
providers and writes `reference.png`; then call
`.chalk/skills/port-hermes-desktop-surface/scripts/capture-desktop-reference.mjs`
against its CDP page with the catalog's selector, fixture ID, state and theme.
Do not run `perf:serve`, use a real profile, or edit `~/.hermes/hermes-agent`.
