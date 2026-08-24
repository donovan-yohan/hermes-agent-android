# Gateway resilience: mobile flow map

Authority for Desktop behavior:
`NousResearch/hermes-agent` @ `7a54ab22e648a0d2d7e740fad908dda93f025820`
(`apps/desktop/src/app/gateway/hooks/use-gateway-boot.ts`,
`apps/desktop/src/lib/reconnect-backoff.ts`). Inspected read-only.

Mobile's contract: **optimistically restore connectivity; surface an error
only when failure is certain or device-independent.** A connection loss is
an expected event on a phone, not an error state.

## Scenario matrix

| Scenario | Desktop behavior | Mobile before | Mobile after |
|---|---|---|---|
| App cold start, saved route | Boot connects; transient boot faults retry with backoff (max 5) | One-shot `restoreRemote`; failure = dead until manual tap | Same one-shot attempt, but retryable failures enter the unlimited full-jitter retry loop instead of dying silently |
| Wi-Fi → cellular handoff | `online` event → immediate reconnect, unlimited full-jitter backoff (300ms→15s cap) | 3 attempts (0/1/5s), then permanent "keeps disconnecting" red text | Unlimited full-jitter retries; network recovery resets attempts |
| Tunnel/underground (no radio) | Waits; reconnect nudged by `online` | Burns its 3 attempts while offline, gives up before signal returns | Network-loss path holds "Waiting for network"; first stable-network event restarts fresh retries |
| Screen off / Doze mid-session | n/a (desktop) — mobile analog of macOS sleep | Socket dies; state goes NeedsAttention; no foreground nudge | Foreground resume triggers immediate reconnect when route exists and socket is down |
| Gateway restarted (server side) | Backoff rides it out; fresh ticket minted every attempt | 3 attempts then give up | Same as handoff: keeps trying with jittered backoff |
| Ticket/OAuth expired during outage | Refresh path; reauth surfaced **once** per episode, loop continues | Restore fails non-interactively ("Sign in to this Gateway before reconnecting") | Same message; terminal auth failures stop automatic retries until explicit sign-in |
| Host key changed / gateway gone | Escalated recoverable error overlay | Immediate NeedsAttention | Unchanged — these are true failure states |
| User pressed Disconnect | Stays disconnected | Stays disconnected | Unchanged — explicit user intent is never overridden |

## Design rules carried over from Desktop

1. **Unlimited retries.** Never strand the user behind a manual button for a
   self-healing class of failure.
2. **Full-jitter exponential backoff**, base 300ms, cap 15s
   (`reconnectBackoffDelayMs`): avoids reconnect storms against a restarting
   Gateway and needs no accumulated-delay state.
3. **Attempt reset on stability.** A connection that held ≥30s counts as
   healthy again (`STABLE_REMOTE_CONNECTION_MILLIS`); the next drop starts
   the ladder fresh.
4. **Wake/network nudges.** Online transitions and app foregrounding trigger
   an *immediate* reconnect (attempt counter reset) rather than waiting out
   the current backoff sleep.
5. **Time-based escalation, not attempt-count.** After ~45s of continuous
   failure, surface a calm, actionable state — but keep retrying underneath.
   The error is informational, not terminal.
6. **Honest failure gating.** Only these are surfaced as user-actionable
   states: auth required (interactive sign-in needed), host-key review or
   mismatch, gateway unreachable with network confirmed up *and* escalation
   threshold passed, or explicit disconnect by the user. Everything else is
   `Connecting`.

## Implementation map

- `GatewayConnectionManager` (data/gateway/GatewayConnection.kt):
  - `REMOTE_RECONNECT_DELAYS_MILLIS` replaced by full-jitter backoff with
    unbounded attempts (injected `reconnectJitter` keeps tests deterministic).
  - Escalation after `RECONNECT_ESCALATE_AFTER_MILLIS` of continuous failure;
    `NeedsAttention` copy becomes informational while retries continue.
  - `nudgeRemoteReconnect()` public entry: fast-path reconnect on wake/
    online signals without tearing down a healthy socket.
- `HermesApplication`: process-lifecycle observer nudges on foreground.
- SSH route: network-recovery still requires manual reconnect this slice
  (SSH credentials are interactive by policy); documented gap.
