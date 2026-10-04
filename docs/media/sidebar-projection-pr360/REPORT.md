# PR360 actual paired visual observations

**Verdict: Concern — partial genuine observations, not complete paired acceptance.**

Android source: `7e7af545571c504274e88fd6b56a0882e28c08df`. Desktop immutable pin: `36922ad064d65dcf25f8f48df81e1ccf9a55de67`, with capture-only `desktop-fixture.spec.ts` in a disposable export. All images were opened and inspected. No pixels were redrawn or restamped. Common synthetic renderer inputs: mono/dark, en-US, UTC, semantic clock `1789654800000`. Android SystemUI clock is not changed and is outside semantic time normalization.

## Observed pairs

| State | Android | Desktop | Inspection / classification |
|---|---|---|---|
| Overview | [actual PNG](projection-overview-android.png) | [actual PNG](projection-overview-desktop.png) | Same project and two session titles, design before release, matching 12m/20m ages. Mobile uses 48dp/two-line rows and compact drawer; Desktop uses a dense persistent rail with truncated release preview. Android already paints design as selected; Desktop remains a fresh chat. This selection context is not normalized, so no blanket visual pass. |
| Selected project | [actual PNG](projection-selected-android.png) | [actual PNG](projection-selected-desktop.png) | Same project and row order/ages. Desktop exposes its `main` lane and visible `All projects`; Android has a direct flat project list and an accessibility-labelled back control. Mobile adaptation, subject to parent approval; not pixel equivalence. |
| Search match `design` | [actual PNG](projection-search-match-android.png) | [actual PNG](projection-search-match-desktop.png) | **Unapproved behavioral difference:** Android keeps the matching project with both preview sessions; Desktop renders a flat Results section containing only design. Same query/seed does not establish the same visible result model. |
| Query `no synthetic match` | [actual empty PNG](projection-search-miss-android.png) | [actual failed-journey PNG](desktop-query-miss-observed-results-failed.png) | **Unapproved behavioral difference:** Android displays `Nothing matches`; genuine Desktop FTS OR-relaxes to `no OR synthetic OR match`, returning both seeded synthetic messages. Desktop image is a failure observation, not a accepted miss fixture. Its global project-absence assertion also sees the retained statusbar project label. |
| 20 draft edits | No accepted PNG | Not reached | Installed Android runtime reports ready=true, 20 edits, overview/preview identity reuse=true; capture fails before screenshot at focused-Activity verification. Native focused owner was not retained by this lane, so the exact owner is unknown. Desktop stopped at previous failure. Runtime-only proof is not paired acceptance. |

Named/unified profile, archive and compression-rehome journeys are still missing on both rendered paths. No readiness, merge or issue-completion recommendation.

## Receipt and artifact identities

`android-index.json` includes all five exact-head workflow URLs, GitHub artifact IDs/zip SHA-256 digests, local file hashes and actual installed APK hashes. The four successful Android receipts were revalidated with `scripts/visual_parity_contract.py check-receipt`; the failed draft artifact has only real runtime samples, no invented screenshot/contract/APK identity.

APK SHA-256 (independent CI debug builds/signing, so hashes differ by state):

- Overview: `05d5316480d91131bd2b83015c00823535f843d83512774c93866562edc7c80a`
- Selected: `c992bc31436452b948c164a96d8770e4e14aebe0c1502bd94f775a0560c0f9e7`
- Search match: `52df95e73cef91d42f5fd7acb3b25e7e4983ac612922fd15df6e14021665281e`
- Search miss: `2944f0cf7480cb0b73a3cf66caaaa4e32bb965d2eadd022985f679e617ca0ffd`

`desktop-index.json` includes the fixture hash and three original PNG hashes. Three Desktop observational receipts passed the canonical schema; schema acceptance does not certify the failed journey or resolve the differences above.

Desktop original PNG SHA-256:

- Overview: `bbffebe5e746d8c90ee9d65e3be2f3cb462d7f125ea0d5fdb42639e3456e46a2`
- Selected: `59521eec9913d9635ce70a98cdb00f5615a63d4120e48b07552d4a9c2028f4ed`
- Search match: `7423d877e735aac7755d7be83cc5a57fa71dffeae3801e2a326195f6b70b72d7`

The Desktop fixture responses use the real project wire tree (`repos[].groups[]`) and actual workspace-grouping preference. Session/search REST goes through the real Electron IPC backend, seeded solely with disposable SessionDB inputs. `desktop-rpc-trace.json` records the synthetic project/profile request routing. No upstream production source modification or mock HTML mount was used.
