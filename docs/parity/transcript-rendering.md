# Transcript rendering

## Scope

Typed timeline items, file-edit diff presentation, and running activity motion.
The source audit uses Desktop `437116f9497c80d242ce034ff7f5d81dc277a337`
and the existing tool-view pin `72a3277cd7937fd0f0a2a3e3fddbed21d7b1c8bd`.
These are surface-specific references, not a claim of a repository-wide retarget.

## Contract

Recognized display metadata licenses a timeline item; a marker-shaped user
message alone does not. REST preserves that metadata for the shared history
parser. A completion report is collapsed initially and discloses on demand.

A usable top-level inline diff takes precedence over decoded result metadata.
Result `inline_diff` and then `diff` supply the fallback, including JSON-string
results. The resolved diff controls the tool presentation and copy payload.
Diffs open initially and keep the reader's collapse choice across recomposition.
The body scrolls within a 192 dp viewport; parsing and highlighting remain
budgeted independently, and Copy retains the complete cleaned diff.

Running tool glyphs breathe, running action and reasoning labels shimmer, and
the quiet working mark pulses. Settled rows do not animate. Motion pauses when
the lifecycle is not resumed and when the platform motion scale is zero.

## Divergences

| Desktop | Class | Android | Evidence |
|---|---|---|---|
| Metadata-classified system timeline disclosure | mobile-adaptation | Compose row with a 48 dp touch target and a bounded expanded report | `TimelineEventProjectionTest`, `RestTranscriptProjectionTest`, `TimelineRowRenderTest` |
| File diff body has a 12 rem scroll cap | mobile-adaptation | 192 dp nested scroll viewport with edge hand-back to the transcript | `InlineDiffBodyTest` |
| Filename-selected Shiki highlighting | drift | Bounded local lexer for supported filename families; plain diff colors for unknown languages or over-budget payloads, not full Shiki grammar parity | `SyntaxHighlight.kt`, `InlineDiffBodyTest`; pending: #71 |
| CSS glyph, text shimmer, and working pulse | mobile-adaptation | Compose frame-clock animations with lifecycle and platform motion-scale guards | `TranscriptMotion.kt`; pending: #71 |
| Reloaded new-file diff depends on saved result/snapshot data | omission | No invented overwrite diff from tool arguments when history does not contain a diff | out-of-scope: #71 — requires a persisted Gateway data contract, not renderer inference |

## Visual report

- pending: #71

The current evidence is source inspection and automated Compose behavior/ink
assertions. No rendered Desktop/Android side-by-side or physical-device motion
acceptance is claimed. Light/dark rendered comparison, touch scroll feel, and
on-device reduced-motion acceptance remain explicit review debt under #71.
