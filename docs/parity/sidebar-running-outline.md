# Sidebar running outline parity

## Pin and source contract

Desktop authority is `NousResearch/hermes-agent` at
`45fcaaa54aae2d03ab816fb61c6ba312d3ac67b8` (read-only checkout).

| Contract | Desktop source | Android port |
|---|---|---|
| Owner | `apps/desktop/src/store/session-dot-state.ts:70-80` | `SessionStatus.Working` and `SessionStatus.Stalled` only |
| Rendering | `apps/desktop/src/app/chat/sidebar/session-row.tsx:254-258,404` | `SessionRow` paint sibling, including project preview rows |
| Geometry and bright stop | `apps/desktop/src/app/chat/sidebar/chrome.tsx:21-42,84-108`; `src/styles.css:1011-1040,1129-1144` | Flush 6dp rounded host; 1.25dp inset stroke; semantic `sessionRunningOutline` is foreground in dark mode and accent in light mode |
| Motion | `apps/desktop/src/styles.css:994-1008,1011-1040,1085-1113` | 300% 160-degree gradient, -10% to -50% travel, 2.23s linear infinite |
| Reduced motion | `apps/desktop/src/styles.css:1157-1161` | `ValueAnimator.areAnimatorsEnabled()` omits the Compose infinite clock; phase zero remains a visible static ring |

## State and mobile deviation ledger

State is backend-authoritative `SessionSummary.status` from `SessionCache`; no
ViewModel or animation state determines whether a ring exists.

Desktop: 1.25px stroke on a compact 26px row. Android: 1.25dp stroke on the
existing 48dp minimum touch-target row. Reason: Android's touch target is a
mobile accessibility requirement; the ring remains flush, rounded, and
non-interactive. The decoration is canvas-only and clears its semantics, so the
row keeps its existing one clickable target and one spoken label.

## Executable evidence

- `SessionRunningOutlineTest` proves the exact Working/Stalled predicate and
  excludes NeedsInput, Background, Idle, and Unread.
- `ChatAccessibilityLayoutTest` proves a running row has one, not duplicate,
  accessible label.

Capture state: pending physical/emulator visual capture of a synthetic running
row in dark and light themes. The fixture must contain no user session text,
host, path, token, credential, or fingerprint.
