# Project context actions and hidden-project recovery

## Pin

| Source | Revision |
|---|---|
| Desktop behavior, copy and rendered component reference | `41dfcf2c1bd7146d4ad46aea08b7dd007900602e` |
| Newer recovery inspection | `cb757fac59c92b49e9b8f2910e5cd7ab75efffdd` |

The relevant project menu, layout, sidebar and palette source is unchanged between these inspected Desktop revisions. Desktop has an eight-second Undo after hiding an auto-discovered project, but no permanent hidden-project restore screen. Android's persistent finder is an intentional addition.

## Contract

At `41dfcf2c1bd7146d4ad46aea08b7dd007900602e`:

- `apps/desktop/src/app/chat/sidebar/projects/project-menu.tsx:91-155,331-354` defines the context action order and explicit/auto distinction. The kebab's different Appearance placement is at `apps/desktop/src/app/chat/sidebar/projects/project-menu.tsx:254-274`; Android long-press follows the context menu.
- `apps/desktop/src/i18n/en_projects.ts:25-48` defines Actions, Rename…, Appearance, No color, Add folder, Set active, Copy path, Delete, Hide from sidebar, Hidden from sidebar, Undo and the deletion confirmation.
- `apps/desktop/src/store/projects.ts:1204-1325,1343-1387` defines update, appearance adoption, folder addition, deletion and active-project RPCs. Clearing appearance sends an empty string, not JSON null. Auto-project appearance adopts through `projects.create` with `use:false` and retires the old picker.
- `apps/desktop/src/app/chat/sidebar/projects/project-appearance.tsx:10-39,54-79` and `apps/desktop/src/lib/profile-color.ts:45-50` define the ordered 28 icons and 12 swatches.
- `apps/desktop/src/store/layout.ts:497-529` defines local hide/filter/restore; `apps/desktop/src/app/chat/sidebar/projects/project-menu.tsx:62-80` is its eight-second Undo caller.
- Home has no project actions. Remote Desktop omits Reveal in folder because the host path is not a path on the client.

## Android authority

Project identity, appearance, active selection and memberships remain Gateway truth in `SessionCache`. The existing repository issues an editor bound to the connection, endpoint generation and exact nullable profile route/revision. Every mutation checks that owner and the originating surface again at actual dispatch. An accepted write and a failed subsequent catalog refresh are distinct outcomes; no automatic write retry is introduced. Set active does not change the foreground conversation. Removing a saved project does not delete conversations or files.

Hidden auto-project IDs are device-local preferences scoped to the saved connection/address identity and exact nullable profile route. They never remove backend catalog entries or infer membership from paths. The sidebar filters only auto rows. Hidden projects searches the unfiltered catalog's hidden rows by name or path and restores one by removing its local dismissal. The finder remains available after Undo expires. Preference-instance and ViewModel restart tests establish persistence; the debug fixture deliberately uses transient synthetic storage and does not establish process-death persistence.

An owner-keyed lane serializes preference reads with admitted writes so a profile round trip cannot read ahead of its pending hide/restore. Editor incarnation retirement blocks old adoption handles without permanently blacklisting a repository path that resurfaces after saved-project deletion. Pending-dialog cleanup retires only its own token even when the original surface has been disposed. These were independent review findings, reproduced against the predecessor and covered by identical-byte successor regressions before re-review acceptance.

Auto-project appearance adoption prepares a durable local attempt before sending `projects.create`. An unconfirmed sent request remains guarded across reconnects and preference/repository restarts; ordinary actions cannot automatically submit another create. Review project changes remains reachable independently of hidden or missing rows. It reads the actual saved projects, including archived rows, and requires a separate checked review and explicit Allow another attempt action. That acknowledgement changes only local authorization, retires previous appearance choices and submits no RPC. A new appearance choice is required to send another create. A refresh, missing row or matching path never implies that the original request failed or succeeded.

This is duplicate-submission protection within one saved connection/address and exact profile, not backend idempotency: the earlier request may still finish after explicit authorization, and renewed intent can create a duplicate. Connection aliases and other devices do not share guards. Storage failure fails closed for adoption without removing Copy, Hide or ordinary saved-project actions.

## Divergences

| Desktop | Class | Android | Evidence |
|---|---|---|---|
| Pointer right-click menu | mobile-adaptation | Long-press opens the shared modal action list from overview rows and entered-project headers. | Touch has no right-click; native targets preserve context order and glyphs. |
| Hover-revealed kebab | mobile-adaptation | Long-press plus a named accessibility action, without a separate hover trigger. | The requested gesture is the touch entrypoint; Home remains excluded. |
| Local-only Reveal in folder | omission | No host-file-manager action. | non-goal: Android cannot reveal a remote Gateway path in its own filesystem; Desktop remote mode also omits this action. |
| Desktop filesystem picker for Add folder | mobile-adaptation | Enter a folder on the connected Gateway, with optional label/primary choice. | Android document URIs name phone files, not folders on the host. |
| Eight-second Undo is the only restore control | mobile-adaptation | Undo plus a permanent searchable Hidden projects finder. | Recovery remains available after the transient action expires, as requested in #400. |
| Global Desktop dismissal storage | mobile-adaptation | Dismissals are scoped to saved row/address and exact profile route. | Multiple saved Gateways can reuse IDs; phone-local preferences must not hide another host's project. |
| Compact desktop appearance popover | mobile-adaptation | Scrollable modal picker with native touch targets and explicit Cancel. | The phone needs touch-sized controls and constrained-height content. Swatch/clear/icon order, filled circles, selection ring and selected-icon tint remain Desktop's. |
| Incremental explicit Appearance picker | mobile-adaptation | A successful choice closes the modal; reopen to change the other field. | One touch transaction owns one write and its readback; the saved appearance remains visible on the row/header. |
| Delete may reset Desktop's open project conversation to a fresh draft | mobile-adaptation | Exit the removed sidebar scope while preserving the foreground conversation. | Deleting organization metadata must not discard the phone's current reading/composing context; sessions and transcripts remain intact. |
| No durable unconfirmed-adoption review at the inspected Desktop pin | mobile-adaptation | Prepare locally before create; expose fresh saved-project inventory and explicit reviewed authorization independently of row visibility. | Timeout/cancellation can lose a response without cancelling host work; renewed user intent is separate from replay. The supplemental report records the new Android-only surface. |
| Entered-project header's pointer actions | mobile-adaptation | Tap or long-press an entered non-Home project header opens Actions; overview/Home headings expose no dead click actions. | Named click and long-click semantics are regression-tested; source correction is not a restamp of older header pixels. |

## Verification

Implementation tests cover exact requests, appearance clear/adoption, dispatch-time endpoint/profile/surface revocation, accepted writes with failed readback, duplicate admission, scoped preference loading/writes, restart, Undo expiry, restore, failed persistence, stale dialog callbacks, Home exclusion, and real repository/ViewModel/ChatScreen Compose journeys. The instrumented lane and installed feature journeys are separate evidence, not inferred from Robolectric.

Independent source review accepted the repaired production candidate `d49fb464da2bb2b99b5c4f5d39a3999f428dc6a2`. The original three defects have causal predecessor/successor tests; fresh independent compilation passed all 29 repository/ViewModel tests, and eight adapted boundary scenarios passed. Focused Compose picker tests also verify actual swatch/ring/icon-tint pixels and nonoverlapping 48dp targets.

Final bounded combined review accepted `6a656667615f5336e69b6a60f766a3c7c6806464`, including durable adoption recovery and the later scope-snapshot, authored-folder trimming and header-semantics fixes. All 22 changed-file hashes were independently verified before and after review. Fresh isolated production-seam compilation passed 56 tests, including actual correlated wire, DataStore file close/reopen, compare-and-set, archived-inclusive producer shape and commit-then-exception probes. The reviewer independently reproduced the two duplicate-request failures against `48cc6300` using identical final dispatch-test bytes, and the scope-snapshot failure against `313f20f7`. Focused Gradle evidence has 110 passing tests, including all 82 prior named cases; these overlap the fresh tests and are not additive totals. A composed journey covers hidden-row recovery, failed inventory, local-only acknowledgement and a fresh deliberate appearance choice. Neither producer-shape inspection nor synthetic RPC proves live-server commit behavior.

The six separate native-graphics Robolectric evidence journeys passed with zero failures/errors/skips. They exercised actual long touch and production callbacks for rename, Add folder, set-active, delete/cancel, appearance select/clear, auto adoption, hide/Undo, searched recovery after a virtual 8,100 ms, restore, and Home exclusion. Synthetic RPC/storage is not a live Gateway. No device keyboard was exercised.

The earlier installed-emulator attempt was refused for insufficient storage before a fixture launched. No uninstall, data clearing, user switch or replacement device was performed. Installed feature/IME acceptance remains open under #400; the shared hosted instrumented lane has its own narrower scope.

## Visual report

- report: visual/project-actions/report.html
- commit: d49fb464da2bb2b99b5c4f5d39a3999f428dc6a2

- supplemental report: visual/project-actions/recovery/report.html
- supplemental commit: 6a656667615f5336e69b6a60f766a3c7c6806464

The supplemental dark-theme report contains three actual recovery-composable window draws: before inventory refresh, inventory failure and reviewed archived inventory with its duplicate warning and authorization control. It is Robolectric SDK34 native graphics with synthetic state and callback boundaries, not a full Activity journey, light-theme qualification, installed device or IME proof. Its own receipts hash the final composable and test fixture. The original report below retains its earlier provenance unchanged. Parent and independent visual inspection found the selected reviewed-inventory state readable and unclipped.

The report selects eight Desktop and twelve Android images in twelve rows. Four state pairs per theme compare real Desktop components against Android native-graphics modal/root draws. The two recovery states per theme have no Desktop equivalent and say so rather than fabricating a peer. Canonical JSON receipts retain original names; `index.json` maps byte-identical selected images to publication names and checksums.

Desktop renders unchanged production components with synthetic Gateway/picker/clipboard boundaries, not the full Electron shell. Android is the documented Robolectric native-graphics fallback, not installed-device acceptance. Modal decor draws are authoritative for dialog pixels; the Undo composite omits OS compositor dim/system windows. The additive capture harness is preserved beside the report and is not part of the instrumented lane.

Parent visual inspection covered the dark context menu, light selected Appearance picker, dark searched recovery finder and light Add folder modal. Action order/separators, filled swatches/ring, selected icon tint and label legibility were visible without clipping in those selected states. Other selected images have provenance and geometry checks, not a claim of individual visual inspection. Copy is verbatim for Desktop counterparts; Android-specific remote-path fields and persistent recovery labels are recorded adaptations above.
