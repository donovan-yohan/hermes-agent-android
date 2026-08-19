# hermes-mobile

<!-- Write this file as a MAP, not a README. Keep it ~60-120 lines: point at the
     real docs instead of duplicating them. Doctrine, with good/bad examples:
     https://github.com/donovan-yohan/chalk-bag/blob/master/chalkbag/docs/authoring-agents-md.md -->

One line: what this repository is and does.

## Directory map

| Path | What lives there | When to read it |
|---|---|---|
| `src/` | Application source | Changing behavior |
| `tests/` | Test suites | Adding or fixing tests |
| `.chalk/` | chalkbag source (skills, permissions, provider config) | Editing agent config; see `.chalk/README.md` |

## Commands

- Install: `<install command>`
- Build: `<build command>`
- Test: `<test command>` — single file: `<single-test command>`
- Lint: `<lint command>`

## Working rules

- Preserve the repo's established file organization.
- Keep thin entrypoints thin; move substantial logic into libraries or focused helper modules.
- When behavior changes, update the nearest spec/plan/docs that explain it.

## Scoped guides

| Path | Covers |
|---|---|
| _(add scoped AGENTS.md files here as the repo grows)_ | |
