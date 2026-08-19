# Reviewing product copy

Use this checklist for any visible string, state label, error, empty state,
content description, or `product-copy-allow` exception.

## Standard

- Say the user's task, current state, outcome, and next action.
- Reuse Hermes Desktop terminology/copy where it is truthful for Android.
- Keep implementation status, architecture, threat-model detail, hedging, and
  developer caveats out of primary UI.
- Put at most one concise platform-limitation sentence beside the affected
  action. Put deeper material in help, diagnostics, workflow docs, or ADRs.
- Map failures to what happened and a safe next action. Never render raw
  exceptions, tokens, credentials, endpoints, host names, or fingerprints as
  error prose.

## Review the rendered surface

1. Inventory strings from the Kotlin branches that actually render. Include
   concatenated literals, state-derived labels, error mappers, disabled states,
   and content descriptions; do not review comments as if they were UI.
2. For a Desktop-derived surface, compare the pinned SHA-scoped authority for
   terminology and behavior. Record when a frozen authority ledger replaces
   direct upstream inspection.
3. Render phone and wide layouts plus relevant light/dark states. Check line
   wrapping, hierarchy, the action beside each limitation, and that details do
   not crowd the primary path.
4. Inspect merged and unmerged Compose semantics. Confirm each control has one
   useful spoken label, errors announce an action, and visible copy is not read
   twice.
5. Run:

   ```bash
   python3 scripts/check-product-copy.py --self-test
   python3 scripts/check-product-copy.py
   ```

## Executable threshold

`scripts/check-product-copy.py` scans every production Kotlin file below
`app/src/main/kotlin/com/hermesagent/mobile/ui/` plus an explicit list of data
sources whose exceptions or results render transitively. That list includes the
Gateway connection, RPC, session terminal-error and remote-lifecycle sources,
and the SSH destination, key import, session opener and probe sources. Embedded
remote executable scripts are not product copy and are ignored. The gate joins
adjacent string literals connected with `+`; a primary literal group fails
above 36 words or 240 visible characters. The explicit list keeps unrelated
data/protocol constants, tests, and documentation out of scope.

For rare legitimate long copy, place this directly above the string:

```kotlin
// product-copy-allow: legal text must remain verbatim in this confirmation
```

The reason is mandatory and specific. Do not allowlist a whole file or use an
exception to retain architecture/security exposition in primary UI.

## Evidence to hand off

- changed rendered strings and the state/action each serves;
- Desktop authority paths or the frozen SHA-scoped contract used;
- screenshot/preview states and semantics checked;
- copy-gate commands and results;
- every exception marker and why shortening would damage the user task.
