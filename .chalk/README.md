# .chalk

chalkbag source of truth for this repo: skills, permissions, provider config.

## Layout

| Path | What it is |
|---|---|
| `skills/port-hermes-desktop-surface/` | How to translate a Hermes Desktop surface into this app |
| `skills/sync-hermes-desktop-themes/` | How to keep the theme registry in step with Desktop; ships an executable parity diff under `scripts/` |
| `permissions.yaml` | Per-provider permissions. Encodes the two hard rules: upstream is read-only, and nothing reads a credential |
| `providers.yaml` | Which providers get rendered |

## Rules

- Repo-specific instructions live in the tracked `AGENTS.md` at the root
  (`CLAUDE.md` is a symlink to it). Keep chalkbag workflow notes here.
- Edit `skills/`, `permissions.yaml`, `providers.yaml`. Never hand-edit the
  generated `.agents/`, `.claude/`, `.codex/`, `.opencode/` or `opencode.json` —
  they are gitignored, and `./gradlew check` fails if one gets committed.
- Long checklists belong in `docs/workflows/`, not in a `SKILL.md`. A skill
  states the contract and links the checklist; that way the checklist can grow
  without the skill becoming unreadable.
- After editing this tree:

  ```bash
  chalkbag validate
  chalkbag build --yes
  chalkbag doctor
  ```

- `chalkbag build` defaults to every enabled provider (or the last rendered
  set). Use `--provider <ids>` only for a deliberate one-off.
- While iterating, `chalkbag watch` re-renders on save. On macOS,
  `chalkbag daemon install --provider claude,codex` plus `chalkbag daemon status`.
