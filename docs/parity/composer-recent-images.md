# Composer recent images: source and divergence ledger

The composer add sheet's **Recent images** section: a labelled, horizontally scrolling rail
of this device's newest images above **Files**, and the **Choose photos** row that opens
Android's system photo picker. Implemented per
[`docs/workflows/port-desktop-surface.md`](../workflows/port-desktop-surface.md) across
`data/attachments/RecentImages.kt`, `data/attachments/RecentImagePermissions.kt`,
`data/attachments/PickerGenerationFence.kt`, `ui/chat/composer/RecentImagesRail.kt`,
`ui/chat/composer/ComposerAddSheet.kt`, `ui/chat/ChatViewModel.kt` and `MainActivity.kt`.

A tap adds that image to the current prompt through the attachment pipeline that already
staged files: bytes acquired locally, bounded, memory-only, uploaded at submit, and only the
Gateway's own receipt allowed into prompt context. No path, `content://` URI, cache name or
base64 leaves the process, and the shelf's read is re-fenced to
`(connection generation, durable session, occurrence)` like every other attachment.

Desktop's composer carries the same acquisition purpose behind a different model: an
`Attach` menu that reads a filesystem through its own dialogs, with no rail of recent media.
The rows below record that difference where it is a divergence and classify it honestly.

## Pin

| Source | Pin | Read via |
|---|---|---|
| Desktop renderer (composer context menu, i18n) | `hermes-agent` @ `437116f9497c80d242ce034ff7f5d81dc277a337` | read-only checkout; every citation taken with `git show <sha>:<path>` |

Every `path:line` below is against that SHA.

## Paths that settled the port

| Question | Path |
|---|---|
| The menu the rail sits in: an `Attach` label over Files / Folder / Images / Paste image / URL / Prompt snippets | `apps/desktop/src/app/chat/composer/context-menu.tsx:69-95` |
| Desktop's verbatim row copy, including the ellipsis each dialog-opening row carries | `apps/desktop/src/i18n/en.ts:2924-2930` |
| The image row Desktop does have: `Images…`, opening Desktop's own file dialog | `apps/desktop/src/app/chat/composer/context-menu.tsx:77-79` |
| There is no rail of recent media in the Desktop composer to copy | `apps/desktop/src/app/chat/composer/` — all 136 files at the pin, none mentioning a recent-media affordance (`git grep -in recent`) |
| Android's acquisition boundary the rail must stay inside: bytes, never a path or URI, staged before submit | `docs/parity/composer-capabilities.json` (`attachments`, `completions-context-contrib`) |

## Divergences

| Desktop | Class | Android | Evidence |
|---|---|---|---|
| `Attach → Images…` opens Desktop's own image dialog (`context-menu.tsx:77-79`, copy `en.ts:2927`) | mobile-adaptation | The sheet's `Choose photos` row opens the system photo picker (`ActivityResultContracts.PickMultipleVisualMedia`, `MainActivity.kt:229-233`) | Android has no file dialog to open; the system photo picker is the platform surface for images, and it can only return the images the person picked, so this route needs no library grant at all. Its result is fenced to the connection generation *and* durable session that opened it, and re-checked after the picker's metadata read, so a pick that outlives a reconnect or a session switch is refused rather than attached to whatever composer is on screen |
| Desktop has no rail of recent media; its Files and Folder rows read a filesystem tree (`context-menu.tsx:71-76`) | mobile-adaptation | A labelled horizontal `Recent images` rail above Files lists the newest images a media grant covers, each tap adding one through the staged pipeline (`RecentImagesRail.kt:140-196`, `RecentImages.kt:60-95`) | A phone's screenshots live in a shared media library with no tree to browse, so the touch affordance for the same acquisition is a rail; it is bounded to 12 rows, re-read on every sheet open, fenced to the session the sheet was opened for, and a read the device refuses says so instead of claiming the library is empty |
| `Folder…` and `Paste image` rows (`context-menu.tsx:74-76`, `:80-86`, copy `en.ts:2926`, `:2928`) | omission | Neither row exists on Android; the sheet keeps its "Folders aren't available yet." notice | deferred: #278 |

## Visual report

- pending: #277

Android half only, owed its Desktop side by #277:
`docs/parity/visual/composer-add-sheet/recent-images-populated-dark/android/reference.png`,
`docs/parity/visual/composer-add-sheet/recent-images-added-dark/android/reference.png` and
`docs/parity/visual/composer-add-sheet/recent-images-permission-dark/android/reference.png`, each
with its `contract.json`. All three are real emulator pixels from the debug-only
`ComposerAddSheetParityActivity`, captured through the `visual-parity-capture` lane at the
branch head with synthetic fixture images; the receipts record the APK, git SHA and retained
accessibility tree, and the populated receipt's tree carries the rail's own content
descriptions. The Desktop half is the `Attach` menu rendered from a disposable pinned export,
which at this pin has no state-seeder that mounts that menu for a capture process — #277 owns
that boundary, and the rail itself has no Desktop counterpart to render beside.

## Executable evidence

| Claim | Test |
|---|---|
| The device read is bounded, newest-first, and fail-closed on a malformed or over-long row | `RecentImagesTest` |
| Per-platform permission sets and what a granted/partial/denied answer means | `RecentImagesTest` |
| A picker result from a previous connection generation or session is refused, and a launched pick stops holding once its session changes | `RecentImagesTest` |
| A refused library read reports the refusal instead of an empty device, and one undecodable preview does not fail the rail | `RecentImagesTest`, `ChatViewModelTest` |
| A tap adds through the existing pipeline, a second tap is not a second copy, the count cap marks the rail full, removal clears the mark, and an endpoint switch wipes the rail | `ChatViewModelTest` |
| A grant that outlived its session is refused with a recovery notice rather than attached | `ChatViewModelTest` |
| Loading, permission, empty, refused and populated states; horizontal scrolling; the 48dp target; multi-pick; accessibility descriptions | `RecentImagesRailTest` |
| The rail sits above Files and the existing Files / URL / Prompt snippets rows survive | `RecentImagesRailTest` |
