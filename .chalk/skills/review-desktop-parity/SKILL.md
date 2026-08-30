---
name: review-desktop-parity
description: Use when reviewing any change under app/src/main/kotlin/**/ui/**. Enforces a rendered Desktop-versus-Android comparison at the pinned SHA, verbatim copy and menu-order diffs, and a classified divergence ledger before a UI change can be approved.
---

# Review Desktop parity

Hermes Desktop is the spec, not a mood board. A UI review that never looked at
the Desktop surface is an opinion. Render both, put them side by side, and say
what differs and why.

The full checklist is [`docs/workflows/review-desktop-parity.md`](../../../docs/workflows/review-desktop-parity.md).
Follow it; this file is the contract it enforces.

## When this gate applies

Any PR touching `app/src/main/kotlin/com/hermesagent/mobile/ui/**`, or any copy
string those surfaces render. Theme-only value changes go to
`sync-hermes-desktop-themes`; new surfaces go to `port-hermes-desktop-surface`
first and come back here for review.

## Non-negotiables

1. **Find the Desktop original at the pin.** Read it out of the disposable
   export from step 2, not out of `~/.hermes/hermes-agent` — that checkout is
   read-only and its `HEAD` is neither pin. Name the `path:line` of the component, its
   i18n keys, and its tests, each against the SHA you actually read: the UI pin
   `f82f2db` for structure and copy, the theme ledger's `45fcaaa` for colour
   values, or a per-surface pin where the page declares one (#103). If Desktop
   has no equivalent, say so explicitly — that is a finding, not an absence
   of one.
2. **Render it.** Capture Desktop from a **disposable pinned export** with CDP
   (`git clone --no-hardlinks --no-checkout <upstream> <export>` then
   `git -C <export> checkout <pin>`, so the checkout that moves is the throwaway
   one), capture the same state on Android, and build the side-by-side. Making
   the export prompts for approval — `git clone` runs an arbitrary command
   through `--upload-pack` or an `ext::` transport, so it is an `ask`, not an
   allow — and so do the reads against the export. Approve them once per review:

   ```bash
   node .chalk/skills/port-hermes-desktop-surface/scripts/capture-desktop-reference.mjs \
     --name <surface-state> --selector '<root-selector>' \
     --upstream /tmp/hermes-desktop-<pin> --expect-sha <pin> --match <dev-port>
   python3 .chalk/skills/port-hermes-desktop-surface/scripts/capture-android-reference.py --name <surface-state>
   python3 .chalk/skills/port-hermes-desktop-surface/scripts/build-visual-report.py --name <surface-state>
   ```

   Never relaunch or kill the user's own app for a port, and never run bare
   `npm run perf:serve` — it copies real config, `.env` and auth. Seed synthetic
   state only: no host, fingerprint, credential, path or private session text
   may enter a capture.
3. **State the fallback, do not fabricate.** No dev renderer: cite a stored
   reference packet (a previous run's `desktop/contract.json` + `reference.png`,
   attached to the PR) with the commit it was captured at. No device: an
   emulator capture, or `@Preview` renders, and say which. Nothing at all: the
   page records `pending: #<issue>` and the review's ceiling is **Concern** — an
   unrendered UI change is not an approved one.
4. **Diff the copy verbatim.** `git show <pin>:apps/desktop/src/i18n/en.ts` and
   compare every rendered string against its key: same words, same
   capitalization, same sentence, unless a truthful Android reason is written
   down. A word this port invented where Desktop already had one is drift.
5. **Compare structure, not vibes.** Menu and action **order**, group
   boundaries and separators, glyph family and glyph choice, visual icon size,
   label casing, and every visible state Desktop has — default, selected/open,
   loading, empty, error, disabled.
6. **Unsupported is disabled, not absent.** A Desktop mode or control this app
   does not support **yet** ships visible and disabled with a "coming soon"
   pill. Only a non-goal — something this platform will never have — is omitted.
   A silently missing control is a finding.
7. **Classify every divergence** as exactly one of `mobile-adaptation` (a real
   touch/space/accessibility reason), `drift` (a finding, with an issue) or
   `omission`. An omission's Evidence cell must *begin* with `non-goal: <reason>`
   (never returning), `coming soon` (the pill ships today), `pill-owed: #<issue>`
   (a control that owes one), `out-of-scope: #<issue>` (that issue excluded it,
   and it may return) or `deferred: #<issue>` (a detail, never a control).
   "Material does this" and "not implemented yet" are not reasons.
8. **Land it in the ledger.** The surface's `docs/parity/<surface>.md` carries a
   `## Visual report` and a `## Divergences` table; `scripts/check-parity-evidence.py`
   fails the build on a missing section or an unclassified row.

## Reviewer output

Post, in this order:

```text
Parity: <surface> @ <pin>
Report: <path to report.html, stored packet, or pending: #<issue>>
Desktop: <path:line>, i18n <en.ts:line>
Copy:    <verbatim | N diffs, listed>
Order:   <unchanged | what moved>
States:  <which Desktop states were rendered on both sides>
Divergences: <n> mobile-adaptation, <n> drift, <n> omission
Verdict: Approve | Concern | Block
```

Every `drift` row is at least a **Concern**. Invented copy where Desktop has a
string, a reordered menu, a changed glyph family, or a control that is absent
rather than disabled is a **Block** until it is reclassified with a reason.

## Done means

- [ ] Desktop original located at the pin, with `path:line` and i18n keys.
- [ ] Side-by-side report built, or the fallback named with its commit, or `pending: #<issue>`.
- [ ] Copy diffed verbatim against `en.ts` at the pin.
- [ ] Menu/action order, glyphs, casing and every visible state compared.
- [ ] Each divergence classified; no omission is a silently missing control.
- [ ] `docs/parity/<surface>.md` updated and `./scripts/check-repo-invariants.sh` green.
