# Reviewing Desktop parity

The review-time gate for any change under
`app/src/main/kotlin/com/hermesagent/mobile/ui/**`. Desktop is the spec: the
same UX, the same menu treatment, the same words. This checklist is what
[`.chalk/skills/review-desktop-parity/SKILL.md`](../../.chalk/skills/review-desktop-parity/SKILL.md)
enforces.

Porting a surface for the first time is
[`port-desktop-surface.md`](port-desktop-surface.md); it hands off to this page
at review. Theme values are [`sync-desktop-themes.md`](sync-desktop-themes.md).
The words themselves are [`review-product-copy.md`](review-product-copy.md).

## 0. Pin

**Two repo-wide pins, plus a per-surface pin where a page declares one.** A
citation is worthless without saying which one it is against.

| Surface | Pin | Where it is recorded |
|---|---|---|
| UI structure, behaviour and copy — everything this checklist reviews | `f82f2dbabd9e66b714f2b4f8a40447fe0c13e732` | `AGENTS.md` |
| Theme values: presets, palettes, colour tokens | `45fcaaa54aae2d03ab816fb61c6ba312d3ac67b8` | `DesktopThemeLedger.PINNED_SHA`, enforced by `ThemeParityTest` |
| A per-surface pin of its own, where a page declares one | that page's `## Pin` table | e.g. `docs/parity/relay-channels-surface.md` pins the Relay plugin at `563a8c8` |

Use the UI pin for this checklist, and the theme pin only when the question is
a colour value. The divergence is tracked in
[#103](https://github.com/donovan-yohan/hermes-agent-android/issues/103); until
it closes, write the SHA you actually read next to every `path:line`.

The reference checkout `~/.hermes/hermes-agent` is read-only and its `HEAD` is
neither pin. Never write to it, never fetch, and never check it out to a pin —
make the disposable export in step 2 and read *that*. A citation without the
SHA cites nothing.

## 1. Find the Desktop original

For the surface under review, name:

- the component, `path:line` at the pin;
- its i18n keys in `apps/desktop/src/i18n/en.ts`, by line;
- its upstream tests, which state the invariants the prose omits.

If Desktop genuinely has no equivalent, write that down. An Android-only
surface is allowed; an Android-only surface nobody noticed was Android-only is
how a design language forks.

## 2. Render both sides

Desktop needs a dev renderer with CDP, run from a **disposable pinned export**
so no real config, `.env` or auth is in reach:

```bash
pin=f82f2dbabd9e66b714f2b4f8a40447fe0c13e732
export=/tmp/hermes-desktop-$pin
git clone --no-hardlinks --quiet --no-checkout \
  "${HERMES_AGENT_UPSTREAM:-$HOME/.hermes/hermes-agent}" "$export"
git -C "$export" checkout --quiet "$pin"
git -C "$export" rev-parse HEAD   # must print $pin
```

These commands prompt for approval rather than running unattended: `git clone`
executes an arbitrary command through `--upload-pack` or an `ext::` transport
and its destination is unconstrained, so `.chalk/permissions.yaml` lists it
under `ask`. Approve it once per review. The clone reads the reference checkout and never writes to it, and
`--no-hardlinks` keeps the export's object store from reaching back into it, so
the checkout that moves is the disposable one. That also gives
`capture-desktop-reference.mjs` a real repository for its clean-tree and
`--expect-sha` guards to check. Point `--upstream` at the export, never at
`~/.hermes/hermes-agent`. Delete the export when the capture is done. Seed the surface with
synthetic state: no host, fingerprint, credential, filesystem path or private
session text may appear in a capture. Never run bare `npm run perf:serve`, and
never relaunch or kill the user's own Hermes to free a port.

```bash
node .chalk/skills/port-hermes-desktop-surface/scripts/capture-desktop-reference.mjs \
  --name session-actions-open \
  --selector '[data-slot="dropdown-menu-content"]' \
  --upstream "$export" --expect-sha "$pin" --match 5174

python3 .chalk/skills/port-hermes-desktop-surface/scripts/capture-android-reference.py \
  --name session-actions-open

python3 .chalk/skills/port-hermes-desktop-surface/scripts/build-visual-report.py \
  --name session-actions-open
```

Everything lands under the untracked `build/visual-parity/<name>/`. Open
`report.html` and judge both surfaces together.

### When a renderer is not available

In descending order of strength, and you must say which one you used:

| Fallback | What it is | Recorded as |
|---|---|---|
| Stored reference packet | A previous run's `desktop/contract.json` + `reference.png`, attached to the PR or the issue | `report: <url>` + `commit: <sha it was captured at>` |
| Android side only | An emulator capture, or the surface's `@Preview` renders in phone light and dark | `report: <url>` + `commit:`, and the copy/order diff below carries the Desktop half from source |
| Nothing | No renderer, no device | `pending: #<issue>` |

`pending:` is honest and it is also a ceiling: an unrendered UI change reviews
at **Concern** at best. It is never a reason to describe a screenshot that was
not taken.

## 3. Diff the copy verbatim

```bash
grep -n '<the key>' "$export/apps/desktop/src/i18n/en.ts"
```

`$export` is the pinned export from step 2, already checked out at the pin.
Running `git show "$pin":…` from this repo cannot work — the Desktop commit is
not an object here — and running it against `~/.hermes/hermes-agent` is refused
by `.chalk/permissions.yaml`. If you have not made the export yet, make it now;
it is the only sanctioned way to read Desktop at a pin.

Every rendered Android string that has a Desktop counterpart keeps Desktop's
words, sentence and capitalization. Shortening for a phone is allowed and gets
written down; re-phrasing because a port preferred its own wording is drift.
Run the copy gate too — the two checks are different questions:

```bash
python3 scripts/check-product-copy.py --self-test
python3 scripts/check-product-copy.py
```

## 4. Compare structure

Walk this list against the report, item by item. Skipping one is how a menu
quietly reorders.

| Contract | What passes |
|---|---|
| Action and menu **order** | Identical sequence, identical group boundaries |
| Group separators | Present where Desktop has them, absent where it does not |
| Glyph family and glyph | Same family, same glyph, same visual size — the 48 dp hit box grows around it, not the icon |
| Label casing and type treatment | Same words, same casing, same weight/tracking category |
| Alignment and edge placement | Same side, same anchor |
| Spacing rhythm, radii, strokes | Same rhythm, scaled only where touch or readability forces it |
| Colour roles | The same semantic token, never a raw colour or a lookalike |
| Visible states | Default, selected/open, loading, empty, error and disabled — each one Desktop has, rendered on both sides |

## 5. Classify every divergence

Exactly three classes. A row that does not fit one of them is not yet
understood.

| Class | Means | Obligation |
|---|---|---|
| `mobile-adaptation` | A deliberate change with a real reason: touch mechanics, viewport space, accessibility, or an explicit mobile priority | The reason, written down. "Material does this", "by default", "not implemented yet" and a blank cell all fail the gate |
| `drift` | An unintended difference. A finding | An issue number. Every drift row is at least a **Concern** on the review |
| `omission` | Something Desktop renders that this app does not | The Evidence cell must **begin** with one of the five markers below. It is matched at the start of the cell on purpose: "this is not a non-goal, we just have not built it" passes a substring test and is exactly the row the gate exists to catch |

The five omission markers, and what each one commits you to:

| Marker | Means | Do not use it for |
|---|---|---|
| `non-goal: <reason>` | This platform will never have it, and here is why | Anything that might return. The reason is mandatory — a bare `non-goal` fails |
| `coming soon` | The disabled pill ships **today** | A control that is simply absent |
| `pill-owed: #<issue>` | A control or mode Desktop renders, absent here, owing the disabled pill | A detail that is not a control |
| `out-of-scope: #<issue>` | That issue deliberately excluded it. Not a platform judgement, and it may return | A platform non-goal; say `non-goal:` and mean it |
| `deferred: #<issue>` | An omitted *detail* — text, a field, a value — with a named owner | A control or a mode. That is `pill-owed:` |

**Unsupported means disabled, not absent.** Where Desktop renders a mode or
control this app does not support yet, the control stays visible and disabled
behind the marker chip, so the surface teaches the same shape Desktop does. The
chip renders `WIP`; the marker in the table above stays `coming soon`, because
one names what is drawn and the other names the class.
Removing it is only correct for a non-goal. This reverses the older reasoning in
`docs/parity/session-actions-menu.md`; see
[#101](https://github.com/donovan-yohan/hermes-agent-android/issues/101).

The gate cannot tell a control from a text field. `pill-owed:` versus
`deferred:` is a reviewer's call, and mislabelling a control as a detail to
dodge the pill is itself a finding.

## 6. Land it

Each surface owns `docs/parity/<surface>.md`, which must carry:

```markdown
## Divergences

| Desktop | Class | Android | Evidence |
|---|---|---|---|
| Hover-revealed kebab | mobile-adaptation | Always visible in a 48 dp target | Touch has no hover; weight and placement unchanged |

## Visual report

- report: build/visual-parity/session-actions-open/report.html
- commit: 86d9742
```

or, when the report is genuinely owed:

```markdown
## Visual report

- pending: #72
```

A page with no divergences writes the single line `None.` under
`## Divergences`. Then:

```bash
python3 scripts/check-parity-evidence.py --self-test
python3 scripts/check-parity-evidence.py
./scripts/check-repo-invariants.sh
```

`check-parity-evidence.py` runs inside `./gradlew check`. It is a structure
gate: it proves a report was named and every row was classified. It cannot see
the pixels — that is what this checklist is for.

## 7. Post the verdict

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

**Block** on invented copy where Desktop has a string, a reordered menu or
group, a changed glyph family, or a control that is absent rather than disabled.
**Concern** on any drift row, and on any UI change reviewed without a render.
Update this page with what each review taught; delete a step that proved
useless rather than appending to it.
