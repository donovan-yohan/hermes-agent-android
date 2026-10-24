# Bot notification verification

Candidate base: `9ccd0108e8dfd5acd01c5799a12705085453d7d1` plus the feature on `feat/bot-message-notifications`.
This receipt records local validation before publication. Existing unrelated work
was preserved. No physical-device deployment was performed.

## Integrated gate

Executed after producer-contract review repairs, with no compile-only shared fixtures:

```
./gradlew check assembleDebug --max-workers=1 --console=plain
```

Environment: macOS, Homebrew OpenJDK 17, Android SDK platform 36. Result: BUILD SUCCESSFUL. Debug suite 4130 tests, 0 failures/errors, 2 opt-in live tests skipped. Release suite 3246 tests, 0 failures/errors, the same 2 opt-in live tests skipped. Lint, repo invariants and instrumented APK assembly passed; the instrumented suite was not run. Existing Gradle/Kotlin deprecation warnings remain. Deliberately invalid image-decoder fixtures printed diagnostics without failures.

Skipped tests are `productionClientManagesRealNamedStoreAndScriptOutput` and `a real Gateway answers an advertised connection and refuses an unadvertised one, per connection`.

Final APK SHA-256: `55abe48cda2fb18f556352c31a5e6437f51f04200a70b520bdbed12a98e27792`.
APK: `app/build/outputs/apk/debug/app-debug.apk`.

The full gate log is retained privately. Moving the candidate worktree after
execution changed no feature bytes. Hosted checks qualify the published head
separately from this local receipt.

## Review regressions

- Unmodified backend descriptor has no profile_name. Exact owner/name resolution now uses the serving-first profiles.list row, with ambiguity/refusal controls.
- Empty hidden-inclusive totals and pinned rows beyond LIMIT use the producer's actual pagination behavior.
- Posted OS cards clear after process restart and history eviction, including differing optional profile projection.
- An unfulfilled cold tap survives saved Activity state and recreation.
- Repeated Application startup preserves the pending periodic job rather than resetting its schedule.
- Cold background startup and an empty saved registry do not initialize live sockets, plugins or pending-send owners.

Each defect-driving repair has a captured failing assertion and passing follow-up. The final loopback regression uses real HTTP/authenticated WebSocket transport with synthetic producer-shaped fixtures, not a fabricated claim of a running pinned backend.

## Emulator acceptance

Installed the exact final APK on disposable `ebi_notification_shade_api34` only. API 34, 1080 × 1920, 420 dpi. Both Planning bot and Research bot are independent native children with P/R image avatars and message previews. `android-expanded.png` was recaptured from this exact final APK and inspected. Preview-off capture used the same unchanged fixture/presentation path before nonvisual review repairs; OS readback confirmed both bodies `New message`.

JobScheduler readback confirmed persisted 30-minute/10-minute-flex connectivity-constrained scheduling. A forced OS job run with no configured Gateway started and stopped with `app called jobFinished`; no application services remained. This proves scheduling/startup/empty-target completion, not radio or actual Gateway delivery. The user's existing emulator and Pixel were not altered.

## Remaining evidence limits

No real Gateway account, avatar download, desktop-origin message delivery, physical Pixel, Doze latency or real end-to-end cold conversation navigation was exercised. Those remain qualification, not inferred from unit tests. A cold background-only process uses fallback artwork rather than starting a second asset socket. Polling is inexact and bounded, not instant push or exhaustive delivery under an unlimited/changing backlog. The feature targets hosted-room messages, not legacy client-local Desktop blob rooms.
