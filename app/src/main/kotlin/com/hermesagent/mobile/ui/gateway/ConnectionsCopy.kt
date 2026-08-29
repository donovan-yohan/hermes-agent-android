package com.hermesagent.mobile.ui.gateway

import com.hermesagent.mobile.data.connections.ConnectionKind
import com.hermesagent.mobile.data.connections.SavedConnection

/**
 * The Connections vocabulary, taken from Desktop's `i18n/en.ts` at pinned SHA
 * `f82f2dbabd9e66b714f2b4f8a40447fe0c13e732` (`settings.connections`,
 * lines 703-764; `profiles.switchConnectionFailed:1770` and
 * `profiles.connectGateway:1772`).
 *
 * Kept in one object so the parity of this surface's words can be audited
 * against those lines without reading five composables. Where a sentence names
 * something Android does not ship — Hermes Cloud, a Local runtime, cron jobs —
 * it is shortened rather than reworded, and the deviation is recorded in
 * `docs/parity/gateway-connections.md`.
 */
internal object ConnectionsCopy {
    /** `en.ts:704`. */
    const val TITLE = "Registered gateways"

    /**
     * `en.ts:705`, minus the Cloud route Android has no sign-in for. Desktop's
     * "this device and" is back now that a Local row can be created here.
     */
    const val INTRO = "Manage this device and every Hermes gateway it can reach over remote or SSH connections."

    /** `en.ts:706-707`, minus the profile rail and cron jobs Android does not ship yet. */
    const val STAGED_NOTE =
        "Switch gateways from Sessions. Chats and messaging stay with their gateway; " +
            "work on other gateways keeps running."

    /** `en.ts:710`. */
    const val SEARCH_PLACEHOLDER = "Search gateways…"

    /** `en.ts:711`. */
    const val NO_SEARCH_RESULTS = "No gateways match your search."

    /** `en.ts:713`. */
    const val CURRENT_PILL = "Current"

    /** `en.ts:716`. */
    const val ADD_CONNECTION = "Add connection"

    /** `en.ts:717`. */
    const val EDIT_CONNECTION = "Edit"

    /** `en.ts:718`. */
    const val REMOVE_CONNECTION = "Remove"

    /** `en.ts:719`. */
    const val REMOVE_CONFIRM_TITLE = "Remove this connection?"

    /** `en.ts:741`. */
    const val LABEL_TITLE = "Name"

    /** `en.ts:742`, minus the uniqueness clause Android does not enforce. */
    const val LABEL_DESC = "Required. Shown everywhere this gateway appears."

    /** `en.ts:743`. */
    const val LABEL_PLACEHOLDER = "Homelab"

    /** `en.ts:744`. */
    const val URL_TITLE = "Gateway URL"

    /** `en.ts:745`. */
    const val SSH_HOST_TITLE = "SSH host"

    /**
     * Desktop has no equivalent: its main process accepts any string and fails
     * later. This app refuses a URL it cannot address, because an unreachable
     * row is one whose sign-in nothing can erase. Same sentence the route form
     * above the list already uses.
     */
    const val INVALID_URL = "Enter an HTTPS Gateway URL."

    /**
     * `en.ts:827`, and the same words a Local row reports as its auth mode.
     * One string with two readers: a field whose label disagreed with the row
     * summary would read as two different credentials.
     */
    const val TOKEN_TITLE = SavedConnection.SESSION_TOKEN

    /** `en.ts:831`. */
    const val TOKEN_PLACEHOLDER = "Paste session token"

    /**
     * `en.ts:828`, rewritten for where the token comes from here. Desktop
     * names a remote gateway's `.env`; on this route it is what `hermes serve`
     * prints in Termux when it starts.
     */
    const val TOKEN_DESC = "The token Hermes shows when it starts. On a saved gateway, leave this blank to keep the one you saved."

    /**
     * Desktop never requires a token: its Local connection is the runtime its
     * own app manages and needs no credential at all. On loopback here the
     * token is the whole boundary, so a Local row cannot be saved without one.
     */
    const val TOKEN_REQUIRED = "Paste this gateway's session token, then save."

    /**
     * The re-address rule, said where it happens. The Keystore slot is bound to
     * the address that minted it (S-A1), so a row that now names a different
     * address cannot use the token it had — and the person is the only one who
     * can supply the new one.
     */
    const val TOKEN_READDRESSED =
        "This is a different address, so the saved token no longer applies. Paste the token this Hermes is running with."

    /**
     * The one limitation worth stating beside Save. Everything else about
     * running Hermes in Termux belongs in the guide, not on this form.
     */
    const val LOCAL_LIMITATION = "Runs only while Termux keeps hermes serve alive."

    /**
     * A token the Gateway could never match, refused before it is stored.
     *
     * The field never reads back, so a stray character pasted out of a terminal
     * — a newline, a non-breaking space, a smart quote — would otherwise be an
     * unexplainable 401 with nothing on screen to look at. Refusing beats
     * mangling it into `?` on the way to ASCII.
     */
    const val TOKEN_UNREADABLE = "That is not a session token. Paste the value Hermes shows, with no quotes or spaces."

    /**
     * The Keystore refused the write. Nothing was saved — not the token and not
     * the row — so the next action is the same one that just failed.
     */
    const val TOKEN_NOT_STORED = "Could not save this gateway's token on this device. Try again."

    /** The Local pane, when no saved row names an address it could dial. */
    const val LOCAL_NO_ADDRESS = "Add a Local gateway below, then connect."

    /**
     * What the Local route is, on the pane that dials it. Says whose the
     * runtime is, because that is the whole difference from Desktop's Local
     * connection, and that the route does not leave the phone.
     */
    const val LOCAL_INTRO =
        "Connect to a Hermes you run on this phone in Termux. This app never starts or stops it, and nothing on this route leaves the device."

    /** `en.ts:760`. */
    const val SAVE = "Save connection"

    /** `en.ts:762`. */
    const val CANCEL = "Cancel"

    /** `en.ts:763`. */
    const val EMPTY = "No connections registered yet."

    /**
     * Desktop can hold an empty registry and offers no such rule. This app is
     * always configured for exactly one connection, so the last row cannot go.
     */
    const val LAST_CONNECTION_HINT = "Add another gateway before removing this one."

    /**
     * Desktop has no equivalent: its registry lives in the main process and is
     * never read by an older renderer. This app can be downgraded, and the
     * saved document is left untouched rather than overwritten, so the surface
     * has to say why nothing can be changed.
     */
    const val REGISTRY_LOCKED = "Saved gateways can’t be changed on this version. Update the app."

    /** `en.ts:1772`. */
    const val MANAGE_GATEWAYS = "Manage gateways…"

    /** `en.ts:734`. */
    const val KIND_REMOTE = "Remote gateway"

    /** `en.ts:736`. */
    const val KIND_SSH = "SSH"

    /** `en.ts:733`. */
    const val KIND_LOCAL = "Local"

    /** `en.ts:738`, narrowed to HTTPS: this app refuses a plain-HTTP Gateway URL. */
    const val KIND_REMOTE_DESC = "A Hermes gateway reachable over HTTPS — LAN, Tailscale, or the internet."

    /** `en.ts:740`. */
    const val KIND_SSH_DESC = "A Hermes install reached over SSH."

    /**
     * Deviates from `en.ts:737` (“The Hermes runtime managed by this app.”),
     * which is true of Desktop and false here: this app hosts nothing. The
     * runtime is one the person starts on this phone, and the description has
     * to say whose it is.
     */
    const val KIND_LOCAL_DESC = "A Hermes running on this device."

    /** `en.ts:720-721`. */
    fun removeConfirmDesc(label: String): String =
        "“$label” will be removed from this app. The instance itself is not touched — " +
            "you can add it again any time."

    /** `en.ts:754`. */
    fun duplicateUrl(label: String): String = "A connection to this gateway URL already exists (“$label”)."

    /** `en.ts:755`. */
    fun duplicateSsh(label: String): String = "A connection to this SSH host already exists (“$label”)."

    /** `en.ts:1770`. */
    fun switchConnectionFailed(label: String): String = "Could not connect to $label"

    fun kindLabel(kind: ConnectionKind): String = when (kind) {
        ConnectionKind.Remote -> KIND_REMOTE
        ConnectionKind.Ssh -> KIND_SSH
        ConnectionKind.Local -> KIND_LOCAL
    }

    fun kindDescription(kind: ConnectionKind): String = when (kind) {
        ConnectionKind.Remote -> KIND_REMOTE_DESC
        ConnectionKind.Ssh -> KIND_SSH_DESC
        ConnectionKind.Local -> KIND_LOCAL_DESC
    }
}
