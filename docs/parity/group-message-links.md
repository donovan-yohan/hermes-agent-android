# Group message web links

## Pin

Desktop rendering authority is `36922ad064d65dcf25f8f48df81e1ccf9a55de67`.
This narrow message-body review does not repin the hosted-room protocol or
certify the rest of the Group Chats surface against a newer revision.

- `apps/desktop/src/plugins/hermes-bots/group-chat-view.tsx:111-118,1322-1334`
  selects the shared `MessageTextContent` renderer, falling back to Streamdown.
- `apps/desktop/src/sdk/index.ts:1779` exports that production renderer.
- `apps/desktop/src/components/assistant-ui/markdown-text.tsx:833-848` exposes
  the shared message-text and markdown entry points.

Android previously passed every group event body to plain `Text`. An authored
`[download the latest apk](https://github.com/donovan-yohan/hermes-agent-android/actions/runs/39120359182/artifacts/12134162983)`
therefore had no actionable range, even after ordinary and canonical Bot Chat
messages acquired web-link annotations in #382 and #383.

## Contract

Only `message.user` and `message.member` are Markdown message bodies. These
are the production hosted event kinds, not `message.assistant`. Group message
text stays redacted before parsing and uses the existing transcript block
renderer and guarded HTTP/HTTPS browser handoff. Parsing is remembered per
message text. Activity, rename, authority and other status rows remain literal.

The group caller supplies no transcript directive contributions. Directive-like
text cannot instantiate plugin UI. HTML blocks and inline tags remain literal
text rather than executable HTML, including when mixed with supported Markdown;
their visible web URLs use the existing guarded annotations. An empty parsed
body falls back to the existing literal display. No attachment fetching,
filesystem access, Gateway mutation or automatic download is introduced.

The example is a web link, not a file attachment. GitHub Actions artifacts
require a GitHub sign-in and contain a ZIP with `app-debug.apk`; opening a link
is not proof of a successful browser download or an Android installation.

## Reply capability discovery

The requested regular-chat quote/reply feature was not found at the rendering
pin or current inspected Desktop main,
`9e01b44dd23978f9ff40b7837467f3a9f1582130`. At the latter revision:

- `apps/desktop/src/app/context-menu/app-context-menu.tsx:349-357` offers Copy
  for selected text, not insertion of a quote into the composer.
- `apps/desktop/src/components/assistant-ui/thread/assistant-message.tsx:1046-1133`
  provides branch, copy, read-aloud, refresh and reaction actions, not a
  message-specific Reply action.
- `apps/desktop/src/plugins/hermes-bots/group-chat-view.tsx:1266-1294,1379-1390`
  provides a separate agent-only group action that seeds an `@member` mention
  into a draft. It does not quote a message or reference a message identifier.

No new reply wire field or regular-chat quote UI is invented by this fix.
Hosted-group send/thread actions remain disabled as documented in
[the group surface ledger](bot-group-chat.md).

## Divergences

| Desktop | Class | Android | Evidence |
|---|---|---|---|
| Group bodies reuse the shared message renderer | mobile-adaptation | Hosted user/member bodies reuse the native transcript Markdown blocks and web-link actions | `ApkArtifactLinkDiagnosticTest` exercises the exact reported public link through ordinary, Bot Chat and hosted-group message surfaces |
| Pointer activation of web links | mobile-adaptation | Character-position taps activate the exact authored HTTP/HTTPS destination | Production Compose link tests capture the URI handoff; browser download and phone installation are not claimed |
| Inline and structured fenced code remain literal at the pin | mobile-adaptation | Web URLs inside code remain tappable, using the same native behavior as ordinary chat | User-requested web-link availability; existing `TranscriptLinkTest` and the group-link regressions |
| Browser Markdown applies its HTML sanitization policy | mobile-adaptation | HTML-shaped blocks, tags and comments remain visible literal text; safe visible web URLs retain native annotations | `MarkdownTest` and `GroupMessageLinkTest` cover mixed paragraphs, nested quotes/lists, refused schemes and redaction without an HTML renderer |
| Desktop media/directive-capable message surface | drift | Group bodies render supported Markdown only, with no directive contributions or attachment transport | #192 / #193 retain the read-only group boundary; this change adds only existing web actions |
| Desktop group composer, attachments, thread reply and member mutations | drift | Existing disabled WIP controls remain unchanged | #192 / #193; `GroupsJourneyTest` and link regressions retain the read-only surface |

## Visual report

- report: docs/parity/visual/group-message-links/report.html
- commit: 36922ad064d65dcf25f8f48df81e1ccf9a55de67

The report pairs the actual production Desktop group view, mounted through its
workspace callback with public synthetic data, with production Android
GroupsScreen native Robolectric draws. Desktop source and capture digests, the
capture-only harness, Android source hashes and the full published inventory
are retained beside it. No physical-device, real-browser download or
installation acceptance is claimed. Theme settings differ and the report is
limited to message-body behavior, not whole-surface or theme parity.
