# Project inline creation and sticky push-off regression evidence

## Result and provenance

- Production/test source: `f9e970e4059dd61e6c45d427579efb4ffb51cc8a`.
- Reproduced production baseline: `bb1693abc2b6ce924b2f6ebfd8bfcb24ee3e6d36`.
- Built debug APK SHA-256: `ec262dfa24b54085a009210825d9f32dfc49ebb7b196ec604300d00c483c39eb`.
- Graphics: Robolectric SDK 34 native graphics, 411dp × 800dp. Real production Compose surfaces drawn synchronously from the Activity window; not manually illustrated pixels.
- Fixture: `app/src/testDebug/kotlin/com/hermesagent/mobile/ui/PromptPushOffRenderTest.kt`; invented request text/project labels, fixed timestamp, nous dark. Chat transport is disconnected; these pixels do not prove a Gateway RPC.

## Runtime regression

Running the revised `PromptCollapseTest` and `SessionCreateAffordancesTest` against unchanged main production sources yielded **23 tests, 5 failures**:

1. Full-height long prompt retention.
2. Incoming prompt physically pushing outgoing prompt.
3. Full-height prompt geometry with the earlier-message row.
4. Entered-project inline creation control.
5. Project-row inline creation controls.

With corrected sources, focused prompt/create/follow coverage passed. The final unfiltered `./gradlew check assembleDebug` passed: debug **3,450 tests**, release **2,740 tests**, zero failures/errors, one skipped in each variant. The previous full `--rerun-tasks` gate also passed. Repository invariants, parity evidence and diff whitespace checks passed. All Gradle invocations used the shared lock sequentially; no wall-clock test delays were introduced.

PR #333's bounded collapse and PR #334's global header controls did not meet these requested behaviors. Their merge/CI status was not visible acceptance.

## Inspect the unchanged synthetic images

| Image | Observation |
|---|---|
| [project-inline-plus.png](project-inline-plus.png) | HOME and Example project each have their own visible plus; the global project-header plus is separate. |
| [handoff-before.png](handoff-before.png) | Outgoing bubble is already leaving through the transcript viewport top; incoming bubble is fully below it. |
| [handoff-pushing.png](handoff-pushing.png) | Both bubbles moved upward by the same scroll displacement, retaining their spacing. No incoming-user underlay and no duplicate source body. |
| [handoff-complete.png](handoff-complete.png) | Outgoing bubble has disappeared; the incoming bubble alone owns the pin. |

Viewport-edge occlusion as the outgoing bubble scrolls out is intentional. There is no interior four-line cut or separately duplicated visible user bubble. Underlying assistant text may pass behind the opaque settled prompt; incoming user bubbles may not.

## Evidence boundaries

These are **native-render regression captures**, not canonical visual-parity receipts, pinned-KVM emulator screenshots, Desktop comparisons, or physical-device acceptance. This host has no attached adb device, no emulator executable at the configured SDK, and no accessible `/dev/kvm`. No emulator/device acceptance is claimed. Existing Desktop/device obligations remain pending under #71/#72. Private user screenshots were inspected only; no private screenshot or session content is included here.

Reproduce the native packet with `PROMPT_CAPTURE_DIR=<output-directory> ./gradlew :app:testDebugUnitTest --tests '*PromptPushOffRenderTest*'`. After a filtered run, rerun the unfiltered test task before reporting full-suite counts. Image/source hashes are retained in `hashes.json`; only allowlisted synthetic PNGs and this report are published.
