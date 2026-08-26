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

exit $fail
