# Chat readability rendered review

[Open the rendered comparison](report.html).

## Authority and scope

Desktop production components were rendered from a disposable export at
`36922ad064d65dcf25f8f48df81e1ccf9a55de67`. The parent independently compared
all 12 source digests with Git blobs and all four captured screenshot digests
with the manifest. `desktop/capture-manifest.json` records source hashes,
component inputs, computed geometry, browser versions and network assertions;
`desktop/capture-only.patch` contains the ignored fixture additions. No
production Desktop files were modified. Existing installed dependencies were
reused, not claimed to be a fresh lockfile installation.

Android images are synthetic production-Compose native window draws under
Robolectric. They are not emulator, physical-device, actual IME, or TalkBack
acceptance. Public example domains and synthetic messages replace private
conversation content. The fixtures exercise corresponding contracts, not
identical text or full-shell geometry.

## Reviewed states

- Desktop authored Markdown labels and bare URL text; inline code, URL-only
  fence preprocessing and structured code URLs are separate cases.
- Newest Android sticky prompt collapsed to three rendered lines, expanded,
  and native ink comparison against an unfaded three-line reference.
- Prior pinned request approaching an incoming user message, with a quiet
  intervening-replies cue.
- Expanded Thinking at the same animation phase before and after correction.
  Before, header and body shared the transcript item's origin. After, body
  starts below the measured header. Native tests also cover narrow widths,
  font scale 2, monospace/light and multiple animation phases.

## Copy, order and divergences

Authored link labels remain unchanged; bare URLs retain the full address.
No URL-title fetch or synthetic replacement label is added. `Thinking`,
`Thought briefly`, and `Thought for …` retain their existing copy.

The three-line sticky cap, third-line-only fade, complete inline original,
code-URL activation, and "Replies in between" cue are requested
mobile adaptations. Desktop has no intervening-replies cue in the inspected
path. Existing Android metadata-before-caret order differs from Desktop's
caret-before-metadata; this patch does not reorder the header. It remains
explicit drift under #72, so the global parity verdict is Concern, not an
unqualified parity approval.

See [the contract ledger](../../chat-readability.md) and
[sticky prompt ledger](../../sticky-user-prompt-port.md). Physical acceptance
remains pending under #72.

## Reproduction

The unfiltered local `check assembleDebug --no-build-cache` completed successfully
on implementation head `36e488b152056dcbbde265840841f99d1d4712fa` after the
review follow-ups: 3,633 Debug and 2,847 Release tests, zero failures/errors,
one opt-in live-Gateway skip in each variant, plus lint and repository gates.
Commit-specific CI remains the shipping gate. The installed local emulator
refused the APK because its existing package has another signing identity. No
package or app data was removed to bypass that refusal.

Android captures use the existing `saveNativeCapture` helper and native
Activity decor-view drawing. Run focused tests with `NATIVE_UI_CAPTURE_DIR`
and `THINKING_CAPTURE_DIR` pointing at ignored build output. Desktop's exact
capture command and ignored fixture content are in its manifest and patch.
`hashes.json` records every published evidence file's SHA-256 digest.
