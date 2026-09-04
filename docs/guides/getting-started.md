# Getting started

This guide takes you from nothing to a phone that is talking to your Hermes
Agent. It has three parts: install the app, choose how the phone reaches your
Hermes, then set that route up.

The app hosts no agent of its own. Every route below connects to a `hermes
serve` process that you run and that you keep running.

---

## 1. Install the app

There is no store listing yet. The project publishes **one rolling debug APK**
from the newest successful `main` build of the **Android exact-head** workflow.

1. Open the
   [Android exact-head workflow](https://github.com/donovan-yohan/hermes-agent-android/actions/workflows/android-exact-head.yml)
   and select the newest successful run on `main`.
2. Download the `hermes-mobile-latest` artifact. GitHub requires you to be
   signed in to download Actions artifacts.
3. Unzip it and install `app-debug.apk`, either by copying it to the phone and
   opening it, or over ADB:

   ```bash
   adb install -r app-debug.apk
   ```

**This is a development channel, not a release.** The APK is *debug-signed*
with a persistent CI identity: a newer rolling build will update an earlier
rolling install in place, but it cannot update an APK you built yourself,
because that one carries a different signing key. There is no versioned
release, no upgrade policy and no rollback story yet. Android 8.0 (API 26) or
newer is required.

You can also build it yourself — see
[Build and verify](../../README.md#build-and-verify).

---

## 2. Pick a route

| Route | Use it when | What it needs |
|---|---|---|
| **Remote gateway** (recommended) | Your Hermes runs on a machine you own — a server, a desktop, a VPS — and you want Desktop and phone to share it | An **HTTPS** URL for the Gateway, and the Gateway's auth gate switched on |
| **Connect via SSH** (fallback) | You want a *private* backend that belongs to this phone alone | SSH access to a host that has Hermes installed |
| **Local gateway** | Your Hermes runs in Termux on this same phone | A `hermes serve` on loopback and its session token |

Remote gateway is the recommended route because the Gateway stays **host-owned**:
the app connects to a process you started and never starts, adopts, stops or
reaps it, which is what makes it safe for Desktop and phone to share one Hermes
profile and one session database.

> One Remote Gateway safely shares its process and session storage, but the
> Gateway does not yet fan events out to several clients. Do not open or
> control the **same running session** from Desktop and the phone at the same
> time. Different sessions are fine.

---

## 3. Remote gateway (recommended)

### What the app requires

The app is strict about two things, and both are worth knowing before you start
so the error messages make sense.

**The URL must be HTTPS.** `normalizeRemoteGatewayUrl`
(`app/src/main/kotlin/com/hermesagent/mobile/data/gateway/RemoteGateway.kt:1341`)
accepts only an `https` scheme, with no username or password in the URL, no
query string and no fragment. Anything else is refused before a request is
made, with `Enter a valid HTTPS Gateway URL.`
(`RemoteGateway.kt:481`, `RemoteGateway.kt:1330`). There is no loopback
exception on this route — a Hermes on the phone itself is the Local route
instead.

**The Gateway must advertise native sign-in.** Before signing in, the app reads
`GET /api/status` (`RemoteGateway.kt:694`) and checks two fields
(`RemoteGateway.kt:488-497`):

| Field | Requirement | Message if it fails |
|---|---|---|
| `auth_required` | must be `true` | `This Gateway is not using remote authentication. Enable the Gateway auth gate before connecting.` |
| `auth_flows` | must contain `native_pkce` | `This Gateway does not support native sign-in. Update Hermes on the remote host.` |

Sign-in itself is RFC 8252 browser PKCE: the app opens a Custom Tab, and the
provider redirects back to a loopback listener the app opened on
`http://127.0.0.1:<random port>/callback`. Nothing is pasted by hand.

### Make `hermes serve` advertise `native_pkce`

On the host, `hermes serve` builds `auth_flows` like this
(`hermes_cli/web_server.py:3960-3990` @
`3ca096de5f8183cb2e0ec23673f294d5978656a3`):

```python
auth_required = bool(getattr(app.state, "auth_required", False))
...
if auth_required:
    auth_flows.append("cookie")
    if _list_session_providers():
        auth_flows.append("native_pkce")
```

So you need **both** of the following.

**(a) The auth gate must engage.** `should_require_auth`
(`hermes_cli/web_server.py:798-816` @ the pin) turns the gate on for any bind
that is not `127.0.0.1`, `localhost` or `::1` — RFC1918 and CGNAT addresses
count as public on purpose. `should_require_dashboard_auth`
(`web_server.py:820-833`) *also* turns it on when `dashboard.public_url` names
a non-loopback host, even though the process itself is bound to loopback. That
second rule is the one the Tailscale setup below relies on: the Gateway stays
on `127.0.0.1`, and declaring its public URL is what engages the gate
(`web_server.py:19642`, and the startup message at `web_server.py:19685-19700`
that names `dashboard.public_url` as the reason when the bind is loopback).

`--insecure` does **not** turn the gate off. It is accepted and ignored
(`web_server.py:798-816`, `hermes_cli/subcommands/dashboard.py:32-40` @ the pin).

**(b) At least one session-capable auth provider must be registered.** With the
gate on and no provider, `hermes serve` refuses to start rather than serving
unauthenticated (`web_server.py:19659`). Pick one:

*Password (no external service).* Add to the host's `config.yaml`:

```yaml
dashboard:
  public_url: "https://gateway.example.ts.net"
  basic_auth:
    username: admin
    # Preferred: a precomputed scrypt hash, so no plaintext sits at rest.
    password_hash: "scrypt$..."
```

Generate the hash on the host with:

```bash
python -c "from plugins.dashboard_auth.basic import hash_password; print(hash_password('your-password'))"
```

(`plugins/dashboard_auth/basic/__init__.py:19-40,115-121` @ the pin. A plaintext
`password:` key is accepted and hashed at load, and the same values can come
from `HERMES_DASHBOARD_BASIC_AUTH_USERNAME` /
`HERMES_DASHBOARD_BASIC_AUTH_PASSWORD_HASH` instead.)

*OAuth.* Run `hermes dashboard register` on the host and follow it; pass
`--redirect-uri` with your public HTTPS callback
(`hermes_cli/subcommands/dashboard.py:170-206` @ the pin).

Either provider supports sessions, which is what puts `native_pkce` in
`auth_flows` (`hermes_cli/dashboard_auth/registry.py:117-122` @ the pin).

**(c) Start the Gateway.** The flags that matter
(`hermes_cli/subcommands/dashboard.py:19-160` @ the pin):

```bash
hermes serve --host 127.0.0.1 --port 9119
```

| Flag | Meaning |
|---|---|
| `--host` | Bind address, default `127.0.0.1` |
| `--port` | Bind port, default `9119`; `0` lets the OS choose |
| `--skip-build` | Serve the prebuilt web assets — useful on a host with no npm |
| `--isolated` | Run a dedicated per-profile server instead of the machine-level one |
| `--status` / `--stop` | List or stop running Gateway processes |

Check what you got:

```bash
curl -s https://gateway.example.ts.net/api/status
```

You are ready when the response has `"auth_required": true` and `"native_pkce"`
inside `auth_flows`.

### Tailscale tips

The recommended way to get an HTTPS URL is **Tailscale**: it gives the host a
stable MagicDNS name with a real certificate, and reaches it only from your own
devices. Nothing is published to the internet.

**Once per tailnet**, in the Tailscale admin console
([enabling HTTPS](https://tailscale.com/kb/1153/enabling-https)):

1. Open the **DNS** page.
2. Enable **MagicDNS** if it is not on already.
3. Under **HTTPS Certificates**, select **Enable HTTPS**.
4. Acknowledge that your machine names and your tailnet DNS name will be
   published on a public certificate-transparency ledger.

You do **not** need to run `tailscale cert` by hand for this: `tailscale serve`
terminates TLS itself and provisions the certificate automatically.

**On the host**, with `hermes serve` listening on `127.0.0.1:9119`:

```bash
# Put HTTPS on the tailnet in front of the plain-HTTP Gateway, persistently.
tailscale serve --bg --https=443 http://127.0.0.1:9119

# Check what is being served.
tailscale serve status

# Stop this one mapping, or clear everything.
tailscale serve --https=443 http://127.0.0.1:9119 off
tailscale serve reset
```

`--bg` is what makes it persist in the background rather than living for as
long as the command does. The result is
`https://<machine>.<tailnet>.ts.net/` — for example
`https://gateway.example.ts.net` — on port 443.

> Tailscale changed the Serve and Funnel CLI syntax in client 1.52. If your
> client is older, or the commands above are rejected, check
> `tailscale serve --help` on the host and the
> [Serve documentation](https://tailscale.com/kb/1242/tailscale-serve).

Set that same URL as `dashboard.public_url` in the host's `config.yaml`
(the previous step). Without it the Gateway is bound to loopback, the auth gate
never engages, `auth_required` stays `false`, and the app refuses to sign in.

**On the phone**, install the Tailscale Android app and sign in to the same
tailnet. The Remote gateway route then works unchanged — the app just sees an
ordinary HTTPS URL. If your tailnet uses non-default ACLs, make sure the policy
permits the phone to reach the host on port 443.

**Do not use Tailscale Funnel here.** `tailscale funnel` exposes the service to
the public internet, which is the one thing this setup exists to avoid; Serve
is the tailnet-only counterpart. See
[Tailscale Funnel](https://tailscale.com/kb/1223/funnel) if you want to know
exactly what it does.

### Add the connection in the app

1. Open **Gateways** and add a connection.
2. Choose **Remote gateway**.
3. Give it a label and paste the HTTPS URL, for example
   `https://gateway.example.ts.net`.
4. Save, then **Connect**. A browser tab opens for sign-in and hands back to
   the app on its own.

Once connected the app fetches the Gateway's sessions and you can open one and
send a turn. The tokens the sign-in produced are encrypted with the Android
Keystore, stored outside backup, and bound to the Gateway that minted them.

---

## 4. Connect via SSH (fallback)

Use this when you want a backend that belongs to this phone rather than a
shared one. The app opens an SSH connection to a host that already has Hermes
installed, starts its **own** `hermes serve` there, forwards it over the
tunnel, and proves it owns that process before reporting a connection. The
details and their consequences are in
[the Managed SSH decision record](../adr/0001-ssh-probe-to-tunnel.md).

What to expect:

- **The destination is one field**: `user@host.example`, port 22 implied, or
  `user@host.example:2222`, or `user@[2001:db8::1]:2222`. It is parsed, not
  guessed — a value that does not parse is refused rather than interpreted.
- **The host key is reviewed on first use, always.** You are shown the
  fingerprint and you accept it explicitly. A key that later *changes* has no
  accept path at all. Changing the host or port on a saved connection drops the
  accepted fingerprint; changing only the username keeps it.
- **One authentication method, one attempt.** Password, Private key or
  Tailscale SSH. There is no fallback chain: if the method you chose is
  refused, that is the answer.
- Passwords, passphrases and imported private-key bytes stay in memory only and
  are wiped after use. Nothing about them reaches disk.
- **Reconnecting starts a fresh backend** rather than reattaching to the
  previous one, and the app never kills a process it cannot positively prove it
  owns — so an abrupt disconnect can leave a backend running on the host that
  you may want to clean up yourself.
- Do not point this at a host whose Hermes profile a Remote Gateway is already
  serving. Two servers against one `HERMES_HOME` is not a supported shape.

### Tailscale SSH

If the host runs [Tailscale SSH](https://tailscale.com/kb/1193/tailscale-ssh),
pick the **Tailscale SSH** method
(`app/src/main/kotlin/com/hermesagent/mobile/ui/ssh/SshScreen.kt:348`). It maps
to SSH auth type `none`
(`AuthMethod.TailscaleSsh -> SshAuthType.None`,
`app/src/main/kotlin/com/hermesagent/mobile/data/ssh/SshModel.kt:80,93-98`) —
a deliberate choice for a host whose tailnet policy does the authenticating,
never a step in a fallback chain.

Two things to expect:

- **You still review the host key.** The SSH library the app uses does not read
  Tailscale's own client-side `known_hosts`, so first-use review applies here
  exactly as it does to the other methods.
- **A refusal is reported as its own thing**, not as a generic auth failure
  (`ProbeFailure.TailscaleSshRefused`,
  `app/src/main/kotlin/com/hermesagent/mobile/data/ssh/SshModel.kt:188`,
  raised at `data/ssh/SshjProbe.kt:156-163`): *"This trusted host refused
  Tailscale SSH. Enable Tailscale SSH on the target and allow this connection
  in your tailnet policy, or choose Password or Private key. Nothing was
  sent."* If you see it, the reachability was fine and the tailnet policy is
  what to fix.

---

## 5. Local gateway (Termux on this phone)

If you want Hermes running on the phone itself, install it under Termux, start
`hermes serve` on loopback, and save it as a **Local gateway** connection. The
app talks to it over `http://127.0.0.1:9119` — cleartext HTTP is permitted for
exactly `127.0.0.1`, `localhost` and `::1` and refused everywhere else — and
authenticates with the Hermes session token, kept in the same encrypted
per-connection slot a Remote sign-in uses.

The URL is checked rather than guessed: `normalizeLocalGatewayUrl` refuses
anything that is not plain `http` to one of those three loopback names, and it
never substitutes a port you did not type, because which port a connection
names decides which process on the phone receives its token.

Like Remote, this route owns no process: the app connects to the Hermes you
started in Termux and never starts, adopts, stops or reaps it. Android will
suspend Termux given the chance, so keeping it alive is your job.

Full instructions, including the deviations from upstream's manual install that
Termux needs, are in
**[Run Hermes on this phone with Termux](termux-local-gateway.md)**.

---

<!-- hermes-cloud:start -->
## Hermes Cloud

Hermes Desktop can sign in to **Hermes Cloud** and pick from the agents on your
account instead of pasting a URL. **The Android app cannot do this yet.** The
card is on screen — it is the fourth option in the connection chooser, in the
same position Desktop puts it, titled `Hermes Cloud` and described as *"Sign in
once to Hermes Cloud and pick from the agents on your account — no URL to
paste."* (`app/src/main/kotlin/com/hermesagent/mobile/ui/gateway/ConnectionsCopy.kt:342,429,432-433`)
— but it is disabled behind the `WIP` marker chip and cannot be selected or
saved. There is no Android Hermes Cloud sign-in, and the connection model has
no member for it (`docs/parity/gateway-connections.md:81,280`).

It is shown rather than hidden on purpose: the chooser teaches the same four
options Desktop's does, and a control this app does not implement yet stays
visible and disabled rather than silently missing.

Use the Remote gateway route in the meantime.

<!-- TODO(cloud-spike): replace this paragraph with the spike's conclusion on what Hermes Cloud can actually do from Android today, and the steps if any. -->
<!-- hermes-cloud:end -->

---

## What the app keeps, and where

Worth knowing before you type a password into anything.

- **In memory only, wiped after use**: SSH passwords, passphrases and imported
  private-key bytes. The whole screen's material is wiped when you leave the
  Gateways surface.
- **Encrypted on disk**: exactly two kinds of credential, sharing one
  mechanism — a Remote connection's OAuth tokens and a Local connection's
  Hermes session token. Android Keystore ciphertext, outside backup, one file
  per saved connection, naming the Gateway that minted it. It is refused if the
  connection later points somewhere else, and it is zeroed and unlinked when
  you remove the connection.
- **Ordinary settings**: a label, the route, the Gateway URL and optional
  sign-in provider, and the SSH host, port, username, auth method and accepted
  fingerprint, plus which connection is active.
- **Never**: a credential, host name or fingerprint in logs, UI status,
  screenshots or crash text. Anything user-visible is redacted first.

---

## Where to go next

- [Status and roadmap](../../status/ROADMAP.md) — what works, what the evidence
  behind it is, and what is deferred
- [Run Hermes on this phone with Termux](termux-local-gateway.md)
- [Remote Gateway decision record](../adr/0002-shared-remote-gateway.md)
- [Managed SSH decision record](../adr/0001-ssh-probe-to-tunnel.md)

Upstream Hermes paths above are cited at the pinned reference commit
`3ca096de5f8183cb2e0ec23673f294d5978656a3`; check your own installation if you
run a different version.
