# Existing-Bot Toolsets selection

## Pin and scope

Desktop/Gateway `587e673e2a2fae0616d8b750bb189217080f621a`.
Android capture source `0d08856c6dcd720b62fa2aa97e6bf3ac82503902` includes
main avatar integration `4797bf539d9b754f93965b4ec57a2f00846339b8`.
This is a bounded **selection adaptation**, not current Desktop Tools equivalence.

At this pin, `apps/desktop/src/plugins/hermes-bots/profile-config.tsx:247-299`
mounts the live capabilities panel. The historical checkbox fallback at `:395-427`
is not the actual reachable current Tools contract. Current
`apps/desktop/src/app/capabilities/toolsets/toolsets-tab.tsx:52-101,130-140`
autosaves per-toolset switches and bulk All; All does not restore inheritance.

Android uses `tui_gateway/methods_profiles.py:345-405,567-588,637-727`:
authoritative ordered rows and literal pin state, Toolsets-only configure followed
by named readback. All-selected remains a pin. Empty selection disables Save;
only separately confirmed Restore defaults sends an empty selection. Preflight
baseline comparison is not CAS. Pending authority survives reopen and endpoint/
profile/dialog fences prevent stale callbacks or uncertain-write replay.

## Divergences

| Desktop | Class | Android | Evidence |
|---|---|---|---|
| Immediate switch autosave | mobile-adaptation | Explicit independent Save toolsets and named readback | Prevent accidental touch writes and keep partial-section outcomes clear; [actual platform map](../media/bot-toolsets-v1/platform-map.json) distinguishes draft from already-dispatched PUT |
| Tools master/detail and inline Terminal inspector | mobile-adaptation | Focused ordered selection rows on a narrow screen | Bounded phone selection editor, not a replacement configuration inspector; native tool configuration is outside this slice |
| No current default-pin badge or restore-defaults action | mobile-adaptation | Using defaults / Custom selection and separately confirmed Restore defaults | Distinguish inherited defaults from an explicit pin and prevent empty selection from silently disabling or resetting tools |
| Silent autosave commit, then separately reopened readback | mobile-adaptation | In-place saved/restored results after production actions | Keep phone section outcome visible without dismissing other unsaved sections; Android actions are fixture-staged, Desktop switch/reopen are real gestures |
| Current Skills / Tools / Connectors / Plugins navigation | omission | Skills and MCP servers remain visible disabled WIP | coming soon — only existing-Bot Toolsets selection is implemented here; current capability tabs and plugin configuration are not claimed |
| New / Duplicate tool configuration | omission | Toolsets remains WIP outside existing-Bot Edit | coming soon — this write requires an existing authoritative named profile |

## Visual report

- report: ../media/bot-toolsets-v1/report.html
- commit: 0d08856c6dcd720b62fa2aa97e6bf3ac82503902

[Capture report](../media/bot-toolsets-v1/REPORT.md) and
[media index](../media/CAPTURE.md#toolsets-selection-supplement) retain 24 Android
canonical receipts/PNGs and 14 unchanged genuine Desktop observational references.
Twelve Android states were discovered from the catalog; no assumed eight-state
count was used. No Desktop reset/restored/refusal counterpart was fabricated.

**Review pending, not parity-approved.** Android native checkable rows expose
checked/enabled state and ≥48dp bounds, but their platform class is
`android.view.View`, not `android.widget.CheckBox`; TalkBack role announcement is
unverified. Full-sheet layout, real Gateway mutation, native tool configuration and
strict cross-platform equivalence remain unverified/outside this bounded packet.
The generic two-platform capture workflow lacks a current Tools dispatch seam;
this supplemental capture did not claim or exercise it.
