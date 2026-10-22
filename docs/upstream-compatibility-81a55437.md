# Existing-client audit at 81a55437

Desktop source target: `81a55437f3f31a9f12a30c53b6c5d49d420898aa`.
Android comparison baseline: `65080e8a37e8d546869528e43c162503015b94c7`.
Original executed stock-backend target remains
`046094df28b712f62a5e3c728a9e59da05dcbf1e`. Historical network and visual
receipts are not restamped. This report distinguishes source assessment,
implemented remediations and exact-candidate execution.

## Changed existing contracts

All upstream paths/ranges below refer to the Desktop source target above.

| Consumer | Finding at Android baseline | Remediation / boundary | Owner |
|---|---|---|---|
| Submit and busy correction | `tui_gateway/contracts/canonical_sessions.py:233-245,269-283` permits a consumed voice stop without an admission. The old adapter rejects it as uncertain; correction falls back into a ready queue. | Exact literal `voice_stopped:true` becomes a no-admission outcome. Its own optimistic submit rolls back without stopping another authoritative execution; correction settles without replay. Applies to canonical and legacy owner-forward replies. Correlated RPC/repository and ViewModel regressions cover this shape. | #266 |
| Busy model selection | `gateway/session_deferred_pick.py:16-43,92-136` defers selection and can ask for confirmation at the next boundary. | Isolated follow-up is being implemented and tested. No claim of new-backend compatibility until explicit consent and effective-state fencing are verified. | #266 |
| Observing transferred queued work | `gateway/session_queued_controls.py:65-78,175-214` can return unconsumed steering work to FIFO. | Accept a newer authoritative pending return only for a known `started,steering:true` row. Pending publication revisions fence delayed submit receipts; terminal remains non-resurrectable. This is observer support, not new queued-control UI. | #266 |
| Uncertain correction rejection | `gateway/session_busy_controls.py:202-217` distinguishes proven `delivered:false` from rejection that may follow delivery. | Only explicit false permits definite-negative recovery. Plain rejected becomes Ambiguous; ready fallback never follows uncertain delivery. Existing typed outcomes retain pre-dispatch refusal behavior. | #266 |
| Legacy owner forwarding | `tui_gateway/methods_prompt.py:709-762` may queue work at the live owner or answer `SESSION_OWNER_UNCONFIRMED` after a possibly durable handoff. | Unconfirmed is potentially accepted throughout repository and composer. A successful forwarded queued receipt removes only its phantom local running turn and uses the existing Gateway queue projection, never a fabricated canonical admission. | #266 |

The field-level comparison also inspected REST fenced session edits,
owner-qualified identity/replay/snapshots, staged attachment/download routes,
profiles, host logs/update receipts, hosted group reads and bundled plugin
transport boundaries. No incompatible request-shape change was found in those
selected retained paths. That is a bounded source result, not an exhaustive
network certification.

## Optional additions are not parity

New queued producer controls, hosted member activity, catalog/settings/update
management, pending connection restoration, streaming voice leases and Desktop
window/terminal bridges are separate capabilities. Unknown optional fields do
not license an enabled control. The source/control inventory records omissions
and owners, and pending rendered comparisons remain Concern.

## Execution status

- Theme registry source comparison is byte-identical from the previous UI target.
- ThemeParityTest ran 13 tests with zero failures/errors/skips before production
  remediations were added. It is not compatibility evidence for those changes.
- Focused regression and full check/APK verification for the remediated candidate
  are pending. Do not read this working audit as a green new-backend result.
- No live provider, update, restart, installed app or physical-device operation
  was performed by this audit.
