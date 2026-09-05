# Third-party notices

## Visual Studio Code Codicons

Hermes Mobile includes the Codicons 0.0.45 icon font so Desktop and Android use
the same glyph language.

- Creator: Microsoft Corporation
- Source: https://github.com/microsoft/vscode-codicons
- Package: `@vscode/codicons@0.0.45`
- License: Creative Commons Attribution 4.0 International (CC BY 4.0)
- Local license copy: `app/src/main/res/raw/codicons_license.txt`
- Modification: the font file is unmodified; Android code selects individual
  glyphs and renders them inside native touch targets.

## Collapse

Hermes Mobile includes the Collapse Bold typeface so the empty-chat wordmark is
set in the face Hermes Desktop sets it in.

- Foundry: Blaze Type (designed by Axel Andre)
- Source: `web/public/fonts/Collapse-Bold.woff2` in the pinned hermes-agent
  checkout `3ca096de5f8183cb2e0ec23673f294d5978656a3`, the same file Desktop
  loads from `@nous-research/ui`
- Notice in the font: © 2023 Keussel, Blaze Type; licence pointer
  <https://blazetype.eu/eula>
- Licence: Collapse is a commercial Blaze Type face; the repo owner states
  permission to use it in this app.
- Modification: the woff2 container was removed so Android's `res/font` can
  read the file. Outlines, metrics, cmap and name table are untouched.
  `docs/fonts.md` records the command and both digests.
