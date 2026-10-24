# All-user disclosure: synthetic rendered proof

Related to #403. [Open the report](report.html).

## Source and route boundaries

- Baseline: `072df3eec91ca75624f2dea67122cd346ff0e1a9` plus the source/test hashes in `capture-manifest.json`; the checkout is deliberately uncommitted for parent review.
- Android images are real synchronous `decorView.draw(Canvas(bitmap))` production-Compose native Robolectric renders, not generated mockups.
- `BotsBotChatJourneyTest` walks roster tap -> `BotsViewModel` -> `BotsPluginRepository` -> `ChatViewModel.openBotChat` -> `LiveGatewaySessionRepository` -> correlated RPC history parsing -> production ChatScreen. The wire supplies synthetic canonical/profile/history responses only. Existing loading/failure/ordinary-composer controls remain.
- `UserPromptDisclosureTest` mounts the actual ordinary ChatScreen with synthetic models, not the Bot repository route. It records collapsed, expanded historical, middle-reading and expanded sticky states in dark/light. Numbered passages and repeated assistant text are intentional fixtures.
- Stored Desktop fallback is `../chat-readability/desktop/capture-manifest.json` and its original production-component images at `36922ad064d65dcf25f8f48df81e1ccf9a55de67`. Their hashes/pins are not modified. The new source inspection at `41dfcf2c1bd7146d4ad46aea08b7dd007900602e` is not a new Desktop render.
- No live Gateway/account, emulator/device, IME, platform accessibility-tree or physical latency claim. Physical paired acceptance remains pending #72.

## Contract and causal evidence

Default clipping uses three actual rendered lines and overflow-only third-line fade for every user body. Explicit inline expansion is complete and selectable, sticky expansion alone is bounded/inner-scrollable/nonselectable. The single UI-only saveable owner follows durable row identity across render re-projection; fallback migration is limited to exactly the same render id. Endpoint/session scopes do not inherit state. There is no text/time/index matching or backend/model change.

Identical final `BotsBotChatJourneyTest.kt` bytes were copied into a clean baseline source archive and executed with Robolectric. Both dark/light defect cases reached canonical admission and measured **both** historical and current inline heights at 351px versus the independent three-rendered-line 79px expectation. The existing two controls passed. The same final bytes pass repaired source; hashes and XML counts are recorded in the manifest. Harness setup errors before the actual behavioral RED are not counted as RED.

An intermediate candidate keyed the entire screen composition by session. The existing cached-project-preview family caught drawer remounts (six cases). The key was narrowed to disclosure allocation only; the same seven cached-preview tests now pass. This is intermediate-regression evidence, not an original-baseline failure. Parent independent review and the full gate remain separate.

## Focused acceptance

The focused command selects prompt/final-user/disclosure tests, selection/Copy, Bot route, transcript follow, Show earlier, cached preview and conversation controls: **153 tests, zero failures/errors/skips**. The original unchanged baseline controls separately passed **130 tests, zero failures/errors/skips**. These are separate source identities, not additive coverage. XML counts, skips and command are in `capture-manifest.json`. Tests preserve actual TextLayoutResult constraints/style/density, full offscreen semantic size/position, historical/current middle reading and measured forward/reverse handoff, native third-line ink, overflow/short/exact-three, wide scaled monospace, separate Return, non-consuming outside taps, multi-touch/long press/drag, lazy remount/new final/prepend, save/restore, durable row re-projection and scope isolation. Assistant/tool/attachment controls are retained.

No full `check`, package/assemble, device action or publication was run. Current labels, theme-token roles, glyph and action order are unchanged; existing jump-to-latest reply overlap and loading-model fixture state are not claimed repaired.

## Reproduction

Reproduction template: configure `JAVA_HOME` for JDK 17 and `ANDROID_HOME` for
the installed Android SDK. Choose a private scratch capture directory. Exact
machine-specific execution commands remain in the private verification receipt;
the public templates preserve the executed task and test selection.

```sh
NATIVE_UI_CAPTURE_DIR=<scratch-capture-output> \
./gradlew -Dorg.gradle.java.home="$JAVA_HOME" \
  :app:testDebugUnitTest --tests '*Prompt*Test*' --tests '*FinalUser*' \
  --tests '*TranscriptSelectionTest*' --tests '*BotsBotChatJourneyTest*' \
  --tests '*TranscriptFollowTest*' --tests '*ShowEarlierJourneyTest*' \
  --tests '*CachedProjectPreviewJourneyTest*' --tests '*Conversation*' \
  --no-daemon --console=plain
```

`report.html` pairs the unchanged stored Desktop reference with new Android captures. `capture-manifest.json` records the source/test inputs and image digests; `hashes.json` inventories the new packet excluding itself. These files are review artifacts, not product copy.
