# Sidebar attention and navigation observations

These are synthetic captures from the production `ChatScreen`, `SessionList`
and `ChatViewModel`, using isolated in-memory authorities on an API 34 emulator.
The project-row click holds its owner-aware response until the fixture releases
it. No personal conversation, credential or network service is used.

| State | Light | Dark |
| --- | --- | --- |
| Waiting for an answer | [Light](light-attention.png) | [Dark](dark-attention.png) |
| Selected conversation loading | [Light](light-loading.png) | [Dark](dark-loading.png) |

The captures belong to the candidate based on
`9ccd0108e8dfd5acd01c5799a12705085453d7d1`, APK SHA-256
`40baf9a3c5529fb80511d9edf035b01f8af79bfaa7c118ddcf22d8b32ad67d22`.
The publication branch additionally integrates the notification changes from
main. Its feature component source is unchanged; these images are retained
observations, not relabelled release captures. Image hashes are in `index.json`.

The visible attention label uses readable text ink, retaining the amber status
dot. Loading shows the captured destination title and static skeleton without
the previous transcript or composer. Separate captured success/failure states
and Compose journeys verify completion and restoration; only the compact
attention/loading gallery is published here.

The Desktop reference capture timed out before rendering. Paired review remains
pending under #72. These images prove neither physical-device latency nor a
faster Gateway response. The backend resume/history path is unchanged.
