# Spike: Desktop UX drift since the pin, audited for alignment

Status: audit. No product code in this document; the fixes it justifies land as
their own slices and are named per row.

| | |
|---|---|
| Repo pin | `NousResearch/hermes-agent` @ `72a3277cd7937fd0f0a2a3e3fddbed21d7b1c8bd` (2026-09-08) |
| Upstream head audited | `564aef2946c436500a5e80ee117b66b789b3f99a` (2026-09-10) |
| Range | 501 commits; `apps/desktop/src/` 196 files, +10543 / −2260 |
| Audited | 2026-09-12 |

**Why this exists.** The owner asked whether this app is still aligned with
Desktop after "some updates to their design/UX for core functionality". This
audits the Desktop half of the range only — the gateway and CLI halves are a
separate contract question and are not settled here.

Every citation below was read with `git show <sha>:<path>` against a read-only
checkout. Nothing in that checkout was written, fetched or checked out.

## The two gates that could have failed, and did not

**Themes are unaffected.** `check-theme-parity.py --upstream` against head
reports 11 presets in the same order (`nous, github, catppuccin, everforest,
solarized, nous-alt, midnight, ember, mono, slate, cyberpunk`). `styles.css`
changed by +17/−4 and every hunk is the sticky-user-message masking below; no
colour token moved. `BuiltinThemes.ALL` needs no data edit.

**Notification and approval copy is unchanged.** `i18n/en.ts`'s
`settings.notifications` block and `chat.approval` block are byte-identical at
head, so everything shipped in #215 still quotes Desktop correctly. The copy
that did move is in plugins, skills, tips and scheduled jobs.

## What changed, and where this app stands

Classes are the parity ledger's own: **aligned** (no action), **drift** (this
app does something different and should not), **gap** (Desktop has it and this
app has nothing).

| Desktop change | Upstream | Android today | Class | Owner |
|---|---|---|---|---|
| Live-owner refusals offer `Start new session` and hide Retry, keyed off `error.data.reason = SESSION_NOT_OWNED` on the JSON-RPC 4090 rather than sniffing prose | `6efe3a45c1`, `c80003ff57` | Only the *busy* 4090 is handled, and by matching the English sentence (`ChatViewModel.kt:1928`). A session another surface holds bounces the send with a notice that names no escape | drift | #220 |
| The transcript pages earlier turns automatically when the reader is at the clamped top, through the same `showEarlier` path the button uses | `3ec8042483`, `474143da81` | `ShowEarlierRow` is a manual pill and nothing else loads earlier turns (`Transcript.kt:213-214`) | drift | #221 |
| An auto-discovered repo renders the repo glyph and an `Auto-discovered` accessible name, so it cannot be mistaken for an explicit project | `03b5460ad9`, `fc36288832` | `isAuto` is parsed and used for *sorting* only (`ProjectGrouping.kt:18-19`) — which is exactly the state Desktop just fixed | drift | #222 |
| Vault: `vault.code.request`, `vault.save_login.request` and `vault.unlock.request` park a turn and raise the `input` notification kind, each with its own `.expire` and `.respond` | `input-requests.ts:367-448` @ head | `PendingInputKind` is `{Clarify, Approval, Sudo, Secret}`; the three events are received and dropped, so a session parked on a vault prompt shows nothing at all | gap | #223 |
| A count of messages below the thread viewport | `342f76a7c2` | No affordance | gap | #224 |
| Composer status groups stay collapsed except todos | `5b181e511a` | Needs a read against `CodingStatusRow` before it can be classified | unclassified | #225 |
| Background sessions stop polling when they are not the foreground session | `b4ccbccf9c` | Needs a read against the app's own foreground isolation before it can be classified | unclassified | #225 |
| Sticky user messages mask the thread behind them with a solid surface | `e7c819a7e1`, `styles.css` | `docs/parity/sticky-user-prompt-port.md` predates the change | drift | #226 |
| One Plugins surface: Capabilities → Plugins owns agent plugins, desktop plugins, install and the catalog; `settings.sectionEntries.plugins` is gone from `en.ts` | `61afcde8f9` | Settings → Plugins, bundled-only, quoting a key that no longer exists | drift | #227 |

The two `unclassified` rows are deliberately not guessed. They are named so the
next pass starts from a list rather than from the diff again.

## The pin itself

Not moved here, and the reason is the precedent this repo already set. #195
moved 6016 commits and found that a stamp may follow the pin only where the
citation was re-derived at it; the ~765 `path:line` citations it could not
confirm went *back* to the old SHA with their line numbers untouched, leaving
the tree mixed-pin on purpose.

This range is far kinder — 501 commits over two days, no file decomposition —
but it is still more citations than one unattended pass can honestly re-derive,
and `scripts/check-repo-invariants.sh:147` already names a `styles.css` span
that moved. So the bump is proposed rather than taken, with the theme gate's
evidence attached: **#228**.

What a bump would have to touch: `AGENTS.md`'s pin sentence (CLAUDE.md follows
the symlink), `DesktopThemeLedger.PINNED_SHA`,
`scripts/check-repo-invariants.sh`, `THIRD_PARTY_NOTICES.md`, and the
`styles.css` line span in the invariant script.

## What was shipped from this audit

- **#222** — the auto-discovered project cue, ported in this same pass. Small,
  self-contained, and Desktop's own fix for the state this app is in.
- **#220** — `SESSION_NOT_OWNED` classified off the reason code and given an
  escape, ported in this same pass. The app's existing notice for this refusal
  was already recorded as misleading before upstream shipped an answer for it.

Everything else is filed and unstarted.
