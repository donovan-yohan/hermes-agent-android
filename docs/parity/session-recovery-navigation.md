# Session recovery, composer joins, and feature navigation

## Scope and diagnosis

A physical-device reproduction showed `session.history` rejecting the mobile client's speculative `include_row_ids` request field. The shared request builder now sends only the session selector; returned authoritative row IDs remain preserved. A diagnostic working-tree APK confirmed that the affected conversation opens after the correction.

Session-open failures use session-scoped state and a retry action rather than composer notices. The error reserves space below the transcript viewport; it does not overlay messages or the return-to-latest control. Retry preserves the draft and late failure results cannot replace another session's state.

All visible status groups, coding status, and composer form one joined stack. Feature contributions move above project/session content, while the Gateway selector moves below the profile rail. Settings retains plugin management, not feature launchers. Bot loading uses a fixed-center arc with lifecycle and reduced-motion guards. Bot startup failures retain categories without claiming that the specific physical startup incident has been diagnosed.

## Reference

Desktop source reference: `437116f9497c80d242ce034ff7f5d81dc277a337`. See the existing transcript-backfill, bots-roster, and profile-switcher parity ledgers for their separately pinned contracts. This change does not claim a new complete Desktop parity audit.

## Visual report

- pending: #71

Clean rendered Desktop/Android comparison remains outstanding. Private diagnostic captures are not repository evidence. Physical confirmation of the affected conversation opening applies to a working-tree APK, not a merged main artifact. Navigation and Bot start/motion physical acceptance remain pending.

## Divergences

| Desktop | Class | Android | Evidence |
| --- | --- | --- | --- |
| Session-open failure | mobile-adaptation | User-requested error below transcript, with retry | `SessionOpenFailureLayoutTest` asserts separation and draft retention; pending: #71. |
| Status and composer surfaces | mobile-adaptation | User-requested continuous outer boundary | `ComposerChromeStackTest` geometry helpers; mounted combination/visual evidence pending: #71. |
| Feature navigation and Gateway selector | mobile-adaptation | Features above projects, Gateway below profiles | `SessionSidebarNavigationBoundsTest` ordering, cramped reachability and callbacks; full inventory and physical acceptance pending: #71. |
| Bot loading arc and startup copy | drift | Centered arc with classified failures | `BotsRowSpinnerGeometryTest`; physical motion/start acceptance pending: #71. |

## Verification boundary

A forced local `check assembleDebug --rerun-tasks` passed after integration: debug 2,996 tests, release 2,388 tests, no failures/errors, one skipped in each variant. Exact-head CI, independent review, clean visual evidence, and latest-main deployment must be recorded separately; this document is not a shipping receipt.
