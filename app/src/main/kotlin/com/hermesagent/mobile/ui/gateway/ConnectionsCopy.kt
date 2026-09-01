package com.hermesagent.mobile.ui.gateway

import com.hermesagent.mobile.data.connections.ConnectionAttentionAction
import com.hermesagent.mobile.data.connections.ConnectionKind
import com.hermesagent.mobile.data.connections.SavedConnection

/**
 * The Connections vocabulary, taken from Desktop's `i18n/en.ts` at pinned SHA
 * `3ca096de5f8183cb2e0ec23673f294d5978656a3` (`settings.connections`,
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
     * The switcher trigger's accessible name on the Gateways screen.
     *
     * Desktop names that trigger with the registry's own section title
     * (`connection-switcher.tsx:154,248,264` passes `t.settings.connections.title`
     * @ `3ca096de`), and the rail's mount still does — there the two never meet,
     * because the switcher is in the statusbar and the registry is a settings
     * page. On this app's Gateways screen they are on one surface, so that name
     * reaches a screen reader twice: once as this control, once as the heading
     * over the list below it. The verb is Desktop's own — `stagedNote` calls
     * the act "Switch gateways" (`en.ts:706`) — so the surfaces still name one
     * thing, and the control now says what it does rather than repeating what
     * it sits above.
     */
    const val SWITCHER_LABEL = "Switch gateways"

    /**
     * `en.ts:705`, minus the Cloud route Android has no sign-in for. Desktop's
     * "this device and" is back now that a Local row can be created here.
     */
    const val INTRO = "Manage this device and every Hermes gateway it can reach over remote or SSH connections."

    /**
     * `en.ts:706-707`, minus the profile rail and cron jobs Android does not
     * ship yet, and widened by one word: Desktop can only switch from its
     * sidebar, and this list can switch too (S-C1). Saying "from Sessions"
     * alone would now name the longer of the two routes as the only one.
     */
    const val STAGED_NOTE =
        "Switch gateways here or from Sessions. Chats and messaging stay with their gateway; " +
            "work on other gateways keeps running."

    /** `en.ts:710`. */
    const val SEARCH_PLACEHOLDER = "Search gateways…"

    /** `en.ts:711`. */
    const val NO_SEARCH_RESULTS = "No gateways match your search."

    /** `en.ts:713`. */
    const val CURRENT_PILL = "Current"

    /**
     * The act of re-homing this device to another saved row.
     *
     * Desktop's registry has no such action — its rows offer Test, Make
     * primary, Edit and Remove, and switching is the sidebar's radio group
     * (`connection-switcher.tsx:212-227`). The word is still Desktop's: it is
     * the verb `stagedNote` uses for exactly this act (`en.ts:706`), so the
     * two surfaces name the same thing rather than inventing a second term.
     */
    const val SWITCH_CONNECTION = "Switch"

    /**
     * The one word both switch surfaces use while a switch is in flight.
     *
     * Shared rather than duplicated: the session rail says it on its trigger
     * and this list says it on the row, and two spellings of one state is how
     * a person starts wondering whether they are two different states.
     */
    const val CONNECTING = "Connecting…"

    /**
     * `en.ts:723`. Rendered disabled behind
     * [com.hermesagent.mobile.ui.common.WIP_PILL]: this app has no
     * route-independent reachability probe to run.
     */
    const val TEST_CONNECTION = "Test"

    /**
     * `en.ts:722`. Rendered disabled for the same reason: `primary` is the
     * launch-mode default (`registry.primary`), and Android persists no such
     * field — with one active connection it would not differ from `Current`.
     */
    const val MAKE_PRIMARY = "Make primary"

    /**
     * Why activating a Managed SSH row does not dial it.
     *
     * The SSH credential is in memory for the life of the screen and died with
     * the connection the switch just closed, so nothing can bring this row up
     * unattended (`ConnectionSwitchController.restorable`). The next action is
     * the Connect button on the Managed SSH pane this switch just revealed,
     * directly above this list.
     */
    const val SSH_NEEDS_CREDENTIAL =
        "Managed SSH signs in on this device, so it cannot reconnect on its own. " +
            "Enter this host's credential above, then Connect."

    /**
     * The same landing for any other row that cannot come up unattended.
     *
     * Reached when a saved row no longer names an address this app can use, so
     * there is no stored sign-in to restore it with. Rare — the editor refuses
     * an unaddressable row at save — but [SSH_NEEDS_CREDENTIAL] would be a lie
     * about a route that has no host credential to enter.
     */
    const val NEEDS_CONNECT = "This gateway did not reconnect on its own. Check its address above, then Connect."

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

    /**
     * `en.ts:818` (`settings.gateway.signIn`), on the registry row rather than
     * on the pane.
     *
     * Desktop's registry has no per-row status at all: its rows are Test, Make
     * primary, Edit and Remove, and whether the connection is up is the
     * statusbar's business. This app switches from the registry, so the row is
     * where the person is standing when the switch fails — and a row that only
     * says `Current` about a gateway that never came up is the lie S-U2 is
     * about. The word is Desktop's own for the act it starts.
     */
    const val SIGN_IN = "Sign in"

    /**
     * The same slot on a Local row, whose credential is a token this app
     * already holds — there is nothing to sign in to, only a server to reach.
     * Desktop's own word for the act (`en.ts:18`, `common.connect`), and the
     * word the pane directly above this list uses on its button.
     */
    const val CONNECT = "Connect"

    /**
     * Puts the switch-failure line away.
     *
     * Desktop's failure is a toast that dismisses itself
     * (`connection-switcher.tsx:127` @ `3ca096de`). This app has no
     * notification stack to toast into (#73), so the sentence is an inline line
     * that stays until it is answered or dismissed — which means it needs a
     * way to be dismissed. Desktop's own word for that control
     * (`en.ts` `notifications.dismiss`), minus the noun that names a surface
     * this app does not have.
     */
    const val DISMISS = "Dismiss"

    /** `en.ts:1772`. */
    const val MANAGE_GATEWAYS = "Manage gateways…"

    /** `en.ts:734`. */
    const val KIND_REMOTE = "Remote gateway"

    /** `en.ts:736`. */
    const val KIND_SSH = "SSH"

    /** `en.ts:733`. */
    const val KIND_LOCAL = "Local"

    /**
     * `en.ts:735`. The kind itself is not offered — there is no Android Hermes
     * Cloud sign-in — but the chooser still renders it, disabled, so the form
     * teaches the same four kinds Desktop's does.
     */
    const val KIND_CLOUD = "Hermes Cloud"

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

    /**
     * The word for what a row is asking for. Policy — which kinds ask at all —
     * is [ConnectionKind.attentionAction]'s; this is only the wording, and
     * both words are Desktop's.
     */
    fun attentionActionLabel(action: ConnectionAttentionAction): String = when (action) {
        ConnectionAttentionAction.SignIn -> SIGN_IN
        ConnectionAttentionAction.Connect -> CONNECT
    }

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

/**
 * The **Connection mode** cards' vocabulary, from Desktop's `settings.gateway`
 * i18n block at pinned SHA `3ca096de5f8183cb2e0ec23673f294d5978656a3`
 * (`apps/desktop/src/i18n/en.ts:777-784`, `:865-868`), as rendered by
 * `apps/desktop/src/app/settings/gateway-settings.tsx:1044-1084`.
 *
 * Separate from [ConnectionsCopy], which is the `settings.connections` block
 * below it on the same page. Two Desktop blocks, two objects, so a line here
 * can be checked against the line it cites without guessing which block it
 * came from.
 *
 * Titles and hints are verbatim. Two descriptions are not, and both say
 * "desktop" or "start" about an app that is neither and does not — the
 * deviation is per-constant below and in `docs/parity/gateway-connections.md`.
 */
internal object GatewayModeCopy {
    /** `en.ts:776`. */
    const val MODE_TITLE = "Connection mode"

    /** `en.ts:777`. */
    const val LOCAL_TITLE = "Local gateway"

    /**
     * Deviates from `en.ts:778` ("Start a private Hermes backend on localhost.
     * This is the default and works offline."). Two of its three clauses are
     * false here: this app starts nothing — the person runs `hermes serve` in
     * Termux and this app only dials it — and Local is not the default, since
     * ADR-0002 makes the host-owned Remote Gateway the preferred topology on
     * mobile. Desktop's "private", "backend" and "works offline" survive
     * because they are still true.
     */
    const val LOCAL_DESC = "Connect to a private Hermes backend you run on this phone. Works offline."

    /** `en.ts:782`. */
    const val CLOUD_TITLE = "Hermes Cloud"

    /** `en.ts:783`. */
    const val CLOUD_DESC =
        "Sign in once to Hermes Cloud and pick from the agents on your account — no URL to paste."

    /** `en.ts:779`. */
    const val REMOTE_TITLE = "Remote gateway"

    /**
     * `en.ts:780` ("Connect this desktop shell to a remote Hermes backend."),
     * with the one word that names the wrong client replaced. Nothing else
     * about the sentence changes.
     */
    const val REMOTE_DESC = "Connect this app to a remote Hermes backend."

    /** `en.ts:781`. */
    const val REMOTE_AUTH_HINT =
        "Hosted gateways use OAuth or a username and password; self-hosted ones may use a session token."

    /** `en.ts:865`. */
    const val SSH_TITLE = "Connect via SSH"

    /**
     * `en.ts:866-867`, minus one adjective. Desktop's sentence ends "Requires
     * working **key-based** SSH access to the host", which is false here and
     * not harmlessly so: this route offers three methods
     * (`AuthMethod.TailscaleSsh`, `Password`, `PrivateKey` —
     * `data/ssh/SshModel.kt:80`), and only one of them is a key. Left verbatim,
     * the line tells a person whose host takes a password, or who is on a
     * tailnet, that the route will not work for them — a deterrent, not a
     * cosmetic difference. Everything else is Desktop's, word for word.
     */
    const val SSH_DESC =
        "Hermes is launched on the remote over SSH and tunneled to this app — nothing to start " +
            "or expose yourself. Requires working SSH access to the host."

    /** `en.ts:868`. */
    const val SSH_TRUST_HINT =
        "The first presented host key is trusted and pinned; later changes fail closed."
}
