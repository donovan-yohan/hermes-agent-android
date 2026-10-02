# Installed Skills native acceptance — historical blocked attempt

This attempt remains unchanged below. A subsequent authorized separate-emulator
[recovery packet](captures/installed-skills-4df25613/REPORT.md) contains 24 Android
state/theme receipts and 14 Desktop semantic pairings without replacing this
emulator's shared package. It does not retroactively change this attempt's result.

## Visual report

- Historical attempt: blocked before capture; no visual acceptance is claimed.
- Subsequent [recovery report](captures/installed-skills-4df25613/REPORT.md) and
  [rendered comparison](captures/installed-skills-4df25613/comparison.html) retain
  their separate source and artifact identities and evidence limitations.
- pending: #194

## Divergences

| Desktop | Class | Android | Evidence |
|---|---|---|---|
| Installed Skills visual acceptance | omission | This historical attempt produced no Android receipts because installed APK bytes did not match the requested artifact | deferred: #194 — Artifact identity gate and evidence accounting below; subsequent recovery is separate, not retroactive acceptance |

## Artifact identity gate

- Requested Android source: `4df256139b8d2bf03f903516152e299cd9949aa5`.
- Local approved debug APK SHA-256: `1b080b6b6325b451bb8fdeaff3b98b19a804c8e87c722f9b9b855ac590b4705b`.
- Installed base APK SHA-256: `5e8584f1d2398931e7c5bd7b5aa4f9629cd70415b75169959967aaa61bad8758`.
- The installed APK was independently read through the package manager's base APK path and hashed after transfer; an on-device SHA-256 command confirmed the mismatch.
- Target was an API 37 emulator, with the designated synthetic secondary user foreground. Both owner and synthetic users were running. Initial focus belonged to the synthetic user's application.

**Result: blocked.** The capture worker rejected the installed bytes before fixture launch, XML export, or screenshot. No install, app-data clearing, user switch, owner launch, global settings change, or Gradle invocation was performed. No owner-state capture was taken.

## Evidence accounting

The unchanged catalog contains twelve states, each requiring light and dark acceptance:

- `skills-loaded`
- `skills-disabled`
- `skills-loading`
- `skills-pending`
- `skills-saved`
- `skills-reopened`
- `skills-error`
- `skills-unavailable`
- `skills-refused`
- `skills-essential`
- `skills-empty`
- `skills-unconfirmed`

Required Android state/theme receipts: **24**. Captured: **0**. Validated native XML: **0**. This report is not a parity receipt or native acceptance.

The pre-existing Desktop scratch report describes **14** genuine images at `587e673e2a2fae0616d8b750bb189217080f621a`. That historical source pin is unchanged. Its observational JSON remains explicitly noncanonical; this attempt neither restamps nor publishes it as canonical acceptance. No Desktop pixels were recaptured, altered, or newly certified. No paired packet was completed.

## Safe continuation

Android package code is shared across users: a user-targeted replacement of this application cannot be assumed to preserve the owner's installed code. Installing the requested APK on this emulator therefore requires a separately authorized package-replacement decision, or a separate disposable emulator already provisioned with the approved APK. Do not silently replace the package merely because the synthetic secondary user is foreground.

After that gate is resolved, verify installed bytes again before launching the production-state fixture. Preserve the real 20-second loading/pending deadline, validate original XML and receipts, inspect light/dark pixels, and report staged fixture actions separately from human interaction. Autosave, refusal, essential normalization and lost-readback uncertainty remain unobserved in native pixels in this attempt.

This capture attempt has ended and relinquished the device lane. It did not remove or stop the synthetic user, restore the owner as foreground, or otherwise alter user lifecycle state. Historical build/test totals supplied with the assignment were not rerun or independently certified here. No push was performed.
