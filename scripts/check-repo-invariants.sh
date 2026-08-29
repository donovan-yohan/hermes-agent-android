#!/usr/bin/env bash
# Repo invariants that a Kotlin test cannot see, checked as part of `./gradlew check`.
#
# Each check fails loudly with the exact fix. Add one only when it protects
# something a reviewer would otherwise have to remember.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

fail=0
note() { printf '  %s\n' "$1"; }
problem() { printf 'FAIL  %s\n' "$1"; fail=1; }
ok() { printf 'ok    %s\n' "$1"; }

# ── 1. CLAUDE.md must stay a symlink to AGENTS.md ─────────────────────────────
# chalkbag renders provider files from .chalk/; AGENTS.md is the one tracked,
# hand-written map. If CLAUDE.md becomes a regular file the two drift silently
# and Claude reads a stale copy.
if [[ ! -L CLAUDE.md ]]; then
  problem "CLAUDE.md is not a symlink."
  note "fix: rm -f CLAUDE.md && ln -s AGENTS.md CLAUDE.md"
elif [[ "$(readlink CLAUDE.md)" != "AGENTS.md" ]]; then
  problem "CLAUDE.md points at '$(readlink CLAUDE.md)', not AGENTS.md."
  note "fix: ln -sfn AGENTS.md CLAUDE.md"
elif [[ ! -f AGENTS.md ]]; then
  problem "CLAUDE.md -> AGENTS.md but AGENTS.md does not exist."
else
  ok "CLAUDE.md -> AGENTS.md"
fi

# Git must agree: a symlink committed as a regular file is the failure mode
# that survives a fresh clone.
mode="$(git ls-files --stage CLAUDE.md | awk '{print $1}' || true)"
if [[ -n "$mode" && "$mode" != "120000" ]]; then
  problem "git has CLAUDE.md staged as mode $mode; a symlink is mode 120000."
  note "fix: git rm --cached CLAUDE.md && ln -sfn AGENTS.md CLAUDE.md && git add CLAUDE.md"
elif [[ -n "$mode" ]]; then
  ok "git tracks CLAUDE.md as a symlink (mode 120000)"
fi

# ── 2. Generated agent trees must never be committed ─────────────────────────
for generated in .agents .claude .codex .opencode opencode.json; do
  if git ls-files --error-unmatch "$generated" >/dev/null 2>&1; then
    problem "$generated is tracked; chalkbag generates it and .gitignore excludes it."
    note "fix: git rm -r --cached $generated"
  fi
done
[[ $fail -eq 0 ]] && ok "no generated chalkbag output is tracked"

# ── 3. No private-key material in tracked files ──────────────────────────────
# The repo must stay safe to publish. A bare PEM *header* is legitimate — the
# redaction tests need one as a fixture — so this looks for a header followed
# by an actual base64 body, which is the thing that would be a leak.
#
# `git grep -A 1` prints the match as `path:line:text` and the context line as
# `path-line-text`; a body directly under a header is therefore a context line
# whose text is a long base64 run.
leaked="$(
  git grep -In -A 1 -e '-----BEGIN [A-Z ]*PRIVATE KEY-----' -- . 2>/dev/null |
    grep -E -- '-[0-9]+-[A-Za-z0-9+/=]{40,}[[:space:]]*$' |
    sed -E 's/-[0-9]+-.*$//' |
    sort -u || true
)"
if [[ -n "$leaked" ]]; then
  problem "private-key material in tracked file(s): $(echo "$leaked" | tr '\n' ' ')"
  note "fix: remove it and rotate the key; credentials never belong in this repo."
else
  ok "no private-key material in tracked files"
fi

# ── 4. The theme ledger records a real upstream pin ──────────────────────────
ledger="app/src/test/kotlin/com/hermesagent/mobile/ui/theme/DesktopThemeLedger.kt"
if [[ ! -f "$ledger" ]]; then
  problem "$ledger is missing; the offline theme-parity gate depends on it."
elif ! grep -qE 'PINNED_SHA = "[0-9a-f]{40}"' "$ledger"; then
  problem "$ledger does not record a 40-character upstream SHA."
  note "fix: set PINNED_SHA to the hermes-agent commit the presets were read from."
else
  ok "theme ledger pins $(grep -oE '[0-9a-f]{40}' "$ledger" | head -1)"
fi

# ── 5. Primary product copy stays concise ───────────────────────────────────
python3 scripts/check-product-copy.py --self-test || fail=1
if ! python3 scripts/check-product-copy.py; then
  fail=1
fi

# ── 6. Composer parity checker keeps rejecting broken contracts ──────────────
if ! python3 -m unittest discover -s scripts/tests -p 'test_*.py'; then
  problem "composer parity checker tests failed."
fi

# ── 7. Composer parity contract remains pinned and classified ────────────────
# This writes only a compact ignored build report; it never writes to Desktop.
if ! python3 scripts/check-composer-parity.py --report build/composer-parity/report.md; then
  problem "composer parity manifest, citations, inventory, or capture matrix is invalid."
fi

# ── 8. Exact-head Android CI keeps the build and APK evidence together ───────
if ! python3 scripts/check-ci-workflow.py; then
  problem "Android exact-head GitHub Actions contract is invalid."
fi

# ── 9. Kotlin sources must contain no NUL, so their diffs stay reviewable ────
# A NUL byte is Git's own binary heuristic: one is enough for Git to classify a
# source file as binary, and it then shows no diff on a pull request and
# `git diff --check` skips it, so a change to the most security-sensitive file
# in the tree can land unread. Write the escape instead.
binary_kotlin="$(git ls-files -z 'app/src/**/*.kt' 'app/src/*.kt' \
  | xargs -0 -r perl -ne 'if (/\x00/) { print "$ARGV\n"; close ARGV; }' 2>/dev/null || true)"
if [[ -n "$binary_kotlin" ]]; then
  problem "Kotlin sources contain a NUL byte, so Git treats them as binary:"
  printf '%s\n' "$binary_kotlin" | sed 's/^/        /'
  note "fix: write it as a Kotlin escape (the slot separator is the one we hit) so the diff stays readable."
else
  ok "no tracked Kotlin source contains a NUL, so every diff is reviewable"
fi

# ── 10. Production must never fall back to Phase 1 demo data ─────────────────
if grep -R -nE 'data\.demo|DemoSessions|DemoTurnEngine' app/src/main/kotlin >/dev/null 2>&1; then
  problem "production source still references the Phase 1 demo session/turn path."
  note "fix: route production startup, sessions and turns through the live Gateway repository."
else
  ok "production startup has no demo session or turn source"
fi

# ── 11. Inline diffs read the diff tokens, not lookalike status colours ──────
# ThemeSemanticParityTest proves the diff tokens hold Desktop's values; only a
# source check can prove the diff *panel* is the thing reading them, and reading
# them the right way round. The panel shipped tinting with `statusUnread` (the
# unread-session dot) and `destructive` (the destructive-action red) — a
# different semantic that merely happened to be green and red, and that moves
# with the palette. Desktop derives every diff surface from `--ui-green` /
# `--ui-red` instead (`styles.css:222-227` @
# f82f2dbabd9e66b714f2b4f8a40447fe0c13e732).
transcript="app/src/main/kotlin/com/hermesagent/mobile/ui/chat/Transcript.kt"
panel="$(sed -n '/fun InlineDiffPanel(/,/^}$/p' "$transcript" 2>/dev/null || true)"

# Presence is not enough: a transposed pair would still mention all four names,
# so each marker must be paired with its own tint and its own ink.
unpaired=""
for pair in "+:Added" "-:Removed"; do
  for role in Background Foreground; do
    token="diff${pair#*:}$role"
    grep -qF "startsWith(\"${pair%%:*}\") -> tokens.$token" <<<"$panel" || unpaired="$unpaired $token"
  done
done

if [[ -z "$panel" ]]; then
  problem "InlineDiffPanel was not found in $transcript; the diff-token invariant lost its subject."
  note "fix: keep the panel here, or move this check to wherever inline diffs are painted now."
elif grep -qE 'tokens\.(statusUnread|destructive)' <<<"$panel"; then
  problem "InlineDiffPanel tints a diff line with statusUnread or destructive."
  note "fix: read tokens.diffAdded/diffRemoved and their derived Background/Foreground tokens."
elif [[ -n "$unpaired" ]]; then
  problem "InlineDiffPanel does not pair each diff marker with its own tint and ink:$unpaired."
  note "fix: a '+' line takes diffAddedBackground behind diffAddedForeground; a '-' line takes the remove pair."
else
  ok "inline diffs read the diff tokens, each marker paired with its own tint and ink"
fi


# ── 12. The instrumented emulator lane stays offline ─────────────────────────
# S38's contract is that no test in `app/src/androidTest/` needs a real Gateway,
# a credential, or a host name. A URL literal or a PEM header is the shape that
# breaks it: the moment one appears, the lane has stopped being reproducible on
# any runner and has started depending on somebody's machine.
#
# A URL is not the only shape a leak takes, and the others are the ones a
# reviewer skims past: an SSH destination (`user@host`), a literal address, and
# an accepted host-key fingerprint are each enough to name somebody's machine
# without a scheme in front of them.
lane="app/src/androidTest"
if [[ ! -d "$lane" ]]; then
  problem "$lane is missing; the instrumented lane is what CI's emulator job runs."
else
  lane_targets="$(
    grep -rInE \
      -e 'https?://[A-Za-z0-9]' \
      -e '-----BEGIN' \
      -e '[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+\.[A-Za-z]{2,}' \
      -e '(^|[^0-9.])([0-9]{1,3}\.){3}[0-9]{1,3}([^0-9.]|$)' \
      -e 'SHA256:[A-Za-z0-9+/=]{10,}' \
      "$lane" 2>/dev/null || true
  )"
  if [[ -n "$lane_targets" ]]; then
    problem "the instrumented lane names a network target, an account or key material:"
    note "$(echo "$lane_targets" | head -5)"
    note "fix: construct the state locally; the lane must run with no Gateway reachable."
  else
    ok "the instrumented lane names no Gateway, host, account or key material"
  fi
fi

# ── 13. Cleartext HTTP stays loopback-only ──────────────────────────────────
# The Local route talks to a Hermes on this same phone, so cleartext has to be
# permitted somewhere. The whole security of that decision is *where*: exactly
# the three loopback names, and nothing else. `usesCleartextTraffic="true"` is
# the one-word version of the same permission granted to every host on the
# internet, so it is refused outright, and the config's domain list is compared
# against the loopback set rather than merely inspected for a base rule.
manifest="app/src/main/AndroidManifest.xml"
nsc="app/src/main/res/xml/network_security_config.xml"
expected_domains=$'127.0.0.1\n::1\nlocalhost'

if grep -qE 'usesCleartextTraffic[[:space:]]*=[[:space:]]*"true"' "$manifest" 2>/dev/null; then
  problem "$manifest sets usesCleartextTraffic=\"true\"."
  note "fix: permit cleartext per domain in $nsc; loopback is the only address that needs it."
elif ! grep -qF 'android:networkSecurityConfig="@xml/network_security_config"' "$manifest" 2>/dev/null; then
  problem "$manifest does not point at the network security config."
  note "fix: add android:networkSecurityConfig=\"@xml/network_security_config\" to <application>."
elif [[ ! -f "$nsc" ]]; then
  problem "$nsc is missing; without it targetSdk 36 blocks the loopback Gateway outright."
elif ! grep -qE '<base-config[^>]*cleartextTrafficPermitted="false"' "$nsc"; then
  problem "$nsc does not set base cleartextTrafficPermitted=\"false\"."
  note "fix: the base config must refuse cleartext; only the loopback domain-config permits it."
else
  actual_domains="$(
    sed -n '/<domain-config[^>]*cleartextTrafficPermitted="true"/,/<\/domain-config>/p' "$nsc" |
      grep -oE '<domain[^>]*>[^<]+</domain>' |
      sed -E 's|.*<domain[^>]*>([^<]+)</domain>.*|\1|' |
      sed -E 's/[[:space:]]+//g' |
      sort -u
  )"
  if [[ "$actual_domains" != "$expected_domains" ]]; then
    problem "$nsc permits cleartext to something other than loopback:"
    note "$(echo "$actual_domains" | tr '\n' ' ')"
    note "fix: the cleartext domain-config lists exactly 127.0.0.1, localhost and ::1."
  else
    ok "cleartext is refused by default and permitted for loopback only"
  fi
fi

exit $fail
