# Run Hermes on this phone with Termux

This guide sets up a Hermes Agent inside [Termux](https://termux.dev/) on the
same Android device as Hermes Mobile, and adds it to the app as a saved
connection on the **Local** route. The app reaches it at
`http://127.0.0.1:9119`, so the traffic never leaves the phone.

**You own that process, not the app.** Hermes never runs inside the APK. You
start and stop `hermes serve` in Termux; the app is one more client of it, and
disconnecting closes a socket and touches nothing else. This is the same
host-owned boundary [ADR 0002](../adr/0002-shared-remote-gateway.md) draws for
the Remote route, with the host happening to be this device.

Use another route instead when:

- **you want one Hermes shared between Desktop and phone** — use a Remote
  Gateway, which is the recommended topology
  ([ADR 0002](../adr/0002-shared-remote-gateway.md));
- **Hermes lives on a machine you reach over SSH** — use Managed SSH
  ([ADR 0001](../adr/0001-ssh-probe-to-tunnel.md)).

> **Support tier.** Upstream maintains Android/Termux on a best-effort basis:
> "Termux (Android) is a Tier 2 platform … Commits to `main` may break these
> packages at any point in time"
> (`website/docs/getting-started/termux.md:9-11` @ `f82f2db`). Android may also
> suspend Termux background jobs, so a phone-hosted Gateway is a workstation you
> tend, not a service.

> **Status.** The Local route's transport and token slot shipped in slice S-A1
> of [#93](https://github.com/donovan-yohan/hermes-agent-android/issues/93); the
> Gateways entry that creates a Local connection is slice S-A2. No physical
> device pass has been run yet — see
> [Known limitations](../../status/ROADMAP.md#known-limitations).

## 1. Install Termux

Install Termux from **F-Droid** or from the project's **GitHub releases**. Do
not install the Google Play build: it was deprecated in 2020 and no longer
receives updates, and the two are signed with different keys, so neither can be
installed over the other.

- <https://f-droid.org/packages/com.termux/>
- <https://github.com/termux/termux-app/releases>

Open Termux and update its packages:

```bash
pkg update
```

## 2. Install Hermes in Termux

The tested Android bundle is the `termux` extra with the Termux constraints
file. The steps below are upstream's explicit manual path
(`website/docs/getting-started/termux.md:103-162` @ `f82f2db`).

```bash
pkg install -y git python clang rust make pkg-config libffi openssl nodejs ripgrep ffmpeg

git clone https://github.com/NousResearch/hermes-agent.git
cd hermes-agent

python -m venv venv
source venv/bin/activate
export ANDROID_API_LEVEL="$(getprop ro.build.version.sdk)"
python -m pip install --upgrade pip setuptools wheel

python -m pip install -e '.[termux]' -c constraints-termux.txt

ln -sf "$PWD/venv/bin/hermes" "$PREFIX/bin/hermes"
hermes version
hermes doctor
```

`ANDROID_API_LEVEL` matters: Rust/maturin packages such as `jiter` fail to build
without it (`termux.md:135`). `$PREFIX/bin` is already on Termux's `PATH`, so
the symlink keeps `hermes` available in new shells without re-activating the
virtualenv (`termux.md:149-155`).

Upstream also ships a Termux-aware one-line installer that does the same work
and picks the right extra automatically (`termux.md:81-97`):

```bash
curl -fsSL https://hermes-agent.nousresearch.com/install.sh | bash
```

Then configure a model, once:

```bash
hermes model
```

## 3. Choose the session token yourself

`hermes serve` authenticates every request against a single static token. It
reads `HERMES_DASHBOARD_SESSION_TOKEN` if the environment supplies one, and
otherwise mints a fresh random token on **every start**
(`hermes_cli/web_server.py:499-500` @ `f82f2db`). A token you did not choose
changes each time you restart the server, and the one saved in the app stops
working — so set it yourself.

Generate one and keep it in a file only you can read:

```bash
mkdir -p ~/.hermes
head -c 32 /dev/urandom | base64 | tr -d '=+/' > ~/.hermes/session-token
chmod 600 ~/.hermes/session-token
export HERMES_DASHBOARD_SESSION_TOKEN="$(cat ~/.hermes/session-token)"
```

Add that last line to `~/.bashrc` so new Termux shells inherit it.

**On loopback this token is the whole boundary:** there is no TLS, no sign-in
and no host key, and any app on the phone can open a loopback socket without
asking for a permission, so the token is the only thing standing between them
and your agent. Treat it like a password — it is compared against the
`X-Hermes-Session-Token` header (or `Authorization: Bearer`) on every gated
request (`hermes_cli/web_server.py:567-584` @ `f82f2db`). Do not paste it into
a chat, an issue or a screenshot.

## 4. Start the Gateway

```bash
hermes serve --host 127.0.0.1 --port 9119
```

Both values are already the defaults
(`hermes_cli/subcommands/dashboard.py:26-31` @ `f82f2db`); writing them out
makes it obvious what the app is dialling. `--host 127.0.0.1` is the part worth
keeping: it binds the server to this device only, so nothing else on your Wi-Fi
can reach it.

`serve` is the headless backend — it boots the same JSON-RPC/WebSocket gateway
as `hermes dashboard` but never opens or serves the web UI
(`dashboard.py:136-170` @ `f82f2db`). That is what the app wants, and it is also
why the app cannot read the token off a dashboard page: you have to save it.

To see what is running, from any Termux shell:

```bash
hermes serve --status
```

## 5. Keep it running

Android will suspend or kill background processes unless you tell it not to.
Three settings, in the order worth trying:

1. **Hold a wake lock.** In Termux, run `termux-wake-lock` (release it later
   with `termux-wake-unlock`), or use the **Acquire wakelock** button on the
   Termux notification. This stops the device entering deep sleep, and costs
   battery while it is held.
2. **Take Termux off battery optimisation.** Android Settings → Apps → Termux →
   Battery → **Unrestricted**. The exact path varies by manufacturer.
3. **Disable phantom-process killing (Android 12 and newer).** Android kills
   background child processes once all apps together exceed 32 of them, which
   is exactly what a shell running a Python server looks like. The switch is
   not in the UI; it needs `adb` from a computer, or wireless debugging.

   | Android version | Command |
   |---|---|
   | 12 (API 31) | `adb shell "/system/bin/device_config set_sync_disabled_for_tests persistent"` then `adb shell "/system/bin/device_config put activity_manager max_phantom_processes 2147483647"` |
   | 12L (API 32) and newer | `adb shell settings put global settings_enable_monitor_phantom_procs false` |

   Some Android 13+ builds do not kill Termux's children at all, so try without
   this first; apply it if `hermes serve` keeps dying a few minutes after you
   leave the app. Both settings survive a reboot, but re-check them after a
   system update.

Even with all three, upstream calls gateway persistence on Android
"best-effort rather than a normal managed service" (`termux.md:43` @ `f82f2db`).
Expect to restart `hermes serve` sometimes.

## 6. Add the connection in the app

In Hermes Mobile: **Gateways → Add connection → Local**.

- **Address** is prefilled with `http://127.0.0.1:9119`. Change it only if you
  started `hermes serve` on another port.
- **Session token** is the value from step 3. Paste it, then save.
- Save, then connect. The saved row reads
  `Local · 127.0.0.1:9119 · Session token`.

Two rules worth knowing before you type:

- **The address is checked, not guessed.** Only `http://`, only `127.0.0.1`,
  `localhost` or `::1`, no username, query or fragment. Anything else is
  refused rather than silently corrected — which port a row names decides which
  process on this phone receives your token.
- **The token is bound to the address that saved it.** Re-address the row and
  the stored token is refused for the new address, and kept on disk, so a
  mistyped port is recoverable by fixing the address. Save the token again once
  the address is right.

## Troubleshooting

| Symptom | What is happening | Fix |
|---|---|---|
| Connecting fails after a restart — the app either says *"Session token was refused. Save the token Hermes is running with, then connect."* or reports a plain connection failure | The token the app holds is not the token the running server has, usually because `hermes serve` restarted without `HERMES_DASHBOARD_SESSION_TOKEN` set and minted a new random one (`web_server.py:499-500` @ `f82f2db`). Both messages mean the same thing here: at the pinned Hermes `/api/health` needs no token (`dashboard_auth/public_paths.py:33-38`), so a wrong token is often only caught when the authenticated socket is refused. | Export the token as in step 3, restart `hermes serve`, then re-save the token on the connection. |
| The app says *"Hermes is not answering on this device. Start it, then connect."* | Nothing is listening on that port: `hermes serve` exited, or Android killed it in the background. | Run `hermes serve --status` in Termux. If it lists nothing, start it again, and work through step 5 — a server that dies minutes after you switch apps is the phantom-process killer or battery optimisation, not Hermes. |
| The app says *"Save this Gateway's session token, then connect."* | The row has no token saved. `hermes serve` is headless and serves no web UI, so there is no page for the app to read one from (`dashboard.py:166-170` @ `f82f2db`). | Edit the connection and paste the token from step 3. |
| `hermes serve` exits at startup complaining the address is in use | Another Hermes — or another app — already holds port 9119. | `hermes serve --status` lists running Hermes servers and `hermes serve --stop` stops them (`dashboard.py:75-84` @ `f82f2db`). If something else owns the port, start Hermes on another one (`--port 9130`), then change the address on the saved row and save the token again. |

## What Termux does not give you

Upstream's tested Android bundle is deliberately narrower than the
desktop/server install (`termux.md:35-45`, `:272-277` @ `f82f2db`):

- `.[all]` is not supported on Android;
- local voice transcription is unavailable — the `voice` extra needs
  `faster-whisper` → `ctranslate2`, which publishes no Android wheels;
- the browser/Playwright bootstrap is skipped by the Termux installer;
- Docker-based terminal isolation is unavailable inside Termux;
- background persistence is best-effort, as covered in step 5.

Cloud browser providers still work with Node.js alone, and local browser
automation needs an explicit `agent-browser` install (`termux.md:188-207`).

## Sources

Hermes claims above are read from `NousResearch/hermes-agent` at
`f82f2dbabd9e66b714f2b4f8a40447fe0c13e732`, cited as `path:line`.

Android and Termux behaviour is community-documented rather than upstream, and
is not verified by this repository's gates:

- Termux install sources and the deprecated Play build —
  [termux/termux-app discussion #4000](https://github.com/termux/termux-app/discussions/4000)
- `termux-wake-lock` — [Termux wiki](https://wiki.termux.com/wiki/Termux:Boot)
- phantom-process killing and the flags that disable it —
  [agnostic-apollo/Android-Docs](https://github.com/agnostic-apollo/Android-Docs/blob/master/en/docs/apps/processes/phantom-cached-and-empty-processes.md),
  [termux/termux-app#3506](https://github.com/termux/termux-app/issues/3506)
