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

# ── 5b. Every parity page names a visual report and classifies its divergences ─
# Desktop is the spec, and a parity page that only argues in prose can claim
# anything. This is the structural half: the report is named (or explicitly
# owed), and every divergence is mobile-adaptation, drift or omission with the
# obligation that class carries. Judging the pixels is the review-desktop-parity
# skill's job; this makes an unclassified or unevidenced page fail the build.
python3 scripts/check-parity-evidence.py --self-test || fail=1
if ! python3 scripts/check-parity-evidence.py; then
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
# 3ca096de5f8183cb2e0ec23673f294d5978656a3).
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
# Sorted by codepoint, which is what LC_ALL=C below guarantees on any machine.
expected_domains=$'127.0.0.1\n::1\nlocalhost'

# How many times the file grants cleartext at all. Exactly one grant, on the
# loopback domain-config, is the whole claim; counting is what stops a second
# grant elsewhere in the file from hiding behind the first one being correct.
grants=$(grep -c 'cleartextTrafficPermitted="true"' "$nsc" 2>/dev/null || true)

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
elif grep -q '<debug-overrides' "$nsc"; then
  # A debug-overrides block applies to every host in a debuggable build, and a
  # debug build is the one people run against a real Gateway by hand.
  problem "$nsc has a debug-overrides block; cleartext must not be re-opened for debug builds."
  note "fix: remove it; the loopback domain-config already covers every address this app needs."
elif [[ "$grants" != "1" ]]; then
  problem "$nsc grants cleartext $grants times; exactly one loopback domain-config may."
  note "fix: keep one <domain-config cleartextTrafficPermitted=\"true\"> holding only the loopback names."
elif ! grep -qE '<domain-config[^>]*cleartextTrafficPermitted="true"' "$nsc"; then
  problem "$nsc grants cleartext somewhere other than a domain-config element."
  note "fix: the single grant belongs on <domain-config>, so it is scoped to named hosts."
else
  # Every <domain> in the file, because the single grant above is already known
  # to be the loopback domain-config and nothing else may hold one.
  actual_domains="$(
    grep -oE '<domain[^>]*>[^<]+</domain>' "$nsc" |
      sed -E 's|.*<domain[^>]*>([^<]+)</domain>.*|\1|' |
      sed -E 's/[[:space:]]+//g' |
      LC_ALL=C sort -u
  )"
  if [[ "$actual_domains" != "$expected_domains" ]]; then
    problem "$nsc permits cleartext to something other than loopback:"
    note "$(echo "$actual_domains" | tr '\n' ' ')"
    note "fix: the cleartext domain-config lists exactly 127.0.0.1, localhost and ::1."
  else
    ok "cleartext is refused by default and permitted for loopback only"
  fi
fi

# A bottom sheet is its own window, created with SOFT_INPUT_ADJUST_NOTHING from
# API 30 up (material3 1.4.0's ModalBottomSheetDialogWrapper picks it over
# ADJUST_RESIZE when SDK_INT >= 30), so it inherits nothing from OverlayScaffold
# and the keyboard draws straight over it. Every route already gets this from
# that one scaffold; a sheet is the one surface that has to ask, and asking is a
# single modifier that is trivially forgotten. The rule is written on
# OverlayScaffold in HermesApp.kt; this is the part of it that can fail.
#
# Two things are checked per file that calls ModalBottomSheet: that it holds at
# least as many real imePadding() calls as sheets, and that none of them sits
# after verticalScroll in the same modifier chain — padding inside the scroll
# pads the scrolled content instead of shrinking the viewport, which looks
# right and reaches nothing.
#
# Chains are found by awk as either one line, or a run of lines beginning with
# "." (comment lines inside a run are neutral, since this repo's chains carry
# them). Stated so it is not mistaken for more: a chain written some third way
# is counted but not order-checked, and the whole check only ever looks at
# files that already call ModalBottomSheet. It is a floor, not a proof.
sheet_files="$(grep -rl 'ModalBottomSheet(' app/src/main/kotlin --include='*.kt' | LC_ALL=C sort || true)"
if [[ -z "$sheet_files" ]]; then
  problem "no ModalBottomSheet call sites found; this invariant is watching nothing."
  note "fix: if sheets really are gone, delete this check rather than leaving it green by vacancy."
else
  sheet_gap=""
  sheet_misplaced=""
  while IFS= read -r sheet_file; do
    read -r sheets padded misplaced < <(awk '
      function isComment(l) { return (l ~ /^[[:space:]]*(\/\/|\*|\/\*)/) }
      {
        if (isComment($0)) next
        if ($0 ~ /ModalBottomSheet\(/) sheets++
        hasIme = ($0 ~ /\.imePadding\(\)/)
        hasScroll = ($0 ~ /\.verticalScroll\(/)
        if (hasIme) padded++
        if (hasIme && hasScroll && index($0, ".imePadding()") > index($0, ".verticalScroll(")) {
          bad = bad NR ","
        }
        if ($0 ~ /^[[:space:]]*\./) {
          if (hasIme && chainScroll) bad = bad NR ","
          if (hasScroll) chainScroll = 1
        } else {
          chainScroll = (hasScroll ? 1 : 0)
        }
      }
      END { printf "%d %d %s\n", sheets, padded, (bad == "" ? "-" : bad) }
    ' "$sheet_file")
    if (( padded < sheets )); then
      sheet_gap+="  $sheet_file: $sheets sheets, $padded imePadding()"$'\n'
    fi
    if [[ "$misplaced" != "-" ]]; then
      sheet_misplaced+="  $sheet_file: imePadding() after verticalScroll at line(s) ${misplaced%,}"$'\n'
    fi
  done <<< "$sheet_files"
  if [[ -n "$sheet_gap" ]]; then
    problem "a bottom sheet does not pad its content root for the keyboard:"
    note "$(printf '%s' "$sheet_gap")"
    note "fix: add imePadding() to the sheet's content root, above navigationBarsPadding()"
    note "     and outside any scroll modifier, or whatever the sheet's own covered part"
    note "     is will be unreachable rather than merely hidden."
  fi
  if [[ -n "$sheet_misplaced" ]]; then
    problem "a bottom sheet pads for the keyboard inside its own scroll:"
    note "$(printf '%s' "$sheet_misplaced")"
    note "fix: move imePadding() above verticalScroll. Inside it the padding travels"
    note "     with the scrolled content and the viewport keeps its full height, so the"
    note "     covered part still cannot be reached."
  fi
  if [[ -z "$sheet_gap" && -z "$sheet_misplaced" ]]; then
    ok "every bottom sheet pads its content root for the keyboard, outside its scroll"
  fi
fi

exit $fail
