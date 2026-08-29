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
> Gateways entry that creates a Local connection is slice S-A2
> ([#96](https://github.com/donovan-yohan/hermes-agent-android/pull/96)), and the
> launch restore is S-A5
> ([#97](https://github.com/donovan-yohan/hermes-agent-android/pull/97)).
>
> **What is verified.** Everything below marked *Verified* was run end to end on
> 2026-08-29 against app head `fe67796`, on a Pixel 10 Pro emulator — Android 17
> (API 37), arm64, `getconf PAGESIZE` = 16384 — installing `hermes-agent 0.20.4`
> from `f82f2db` in Termux and connecting the app to it. That pass covers the
> install, the token gating, connecting, the session list and the negative cases.
> It does **not** cover a live turn (no provider key was on the device) or
> keep-alive on a physical phone, which is where step 5 is still unproven — see
> [Known limitations](../../status/ROADMAP.md#known-limitations). Anything called
> community-documented here — step 5's Android settings, and the entries under
> [Sources](#sources) — is somebody else's evidence, not this repository's.

## 1. Install Termux

Install Termux from **F-Droid** or from the project's **GitHub releases**. Do
not install the Google Play build: it was deprecated in 2020 and no longer
receives updates, and the two are signed with different keys, so neither can be
installed over the other.

- <https://f-droid.org/packages/com.termux/>
- <https://github.com/termux/termux-app/releases>

### Check the page size before you trust the install

Android 15 brought 16 KB memory pages, and Pixel 8 and newer devices — plus the
current Pixel emulator images — run that way. Termux `v0.118.3` bundles a
bootstrap linked for 4 KB pages, so on a 16 KB device its very first launch dies
before it reaches a prompt, leaving an app that opens to nothing:

```
E Termux:TermuxInstaller: (-1) Termux Bootstrap Second Stage Command:
E Termux:TermuxInstaller: Exit Code: `139`
```

139 is SIGSEGV. Ask the device which size it uses — in Termux if it runs, or
over `adb` if it does not:

```bash
getconf PAGESIZE
```

`4096` and there is nothing to do. `16384` and you need a Termux whose bootstrap
is built for 16 KB pages: a release later than `v0.118.3` whose notes say so, or
a `termux-packages` bootstrap of `bootstrap-2026.08.23-r1` (aarch64) or later
installed over the failed one. Check any bootstrap archive against the
`_sha256sums` file published beside it before extracting it.

> **Verified.** On the Pixel 10 Pro emulator, `v0.118.3+github-debug_arm64-v8a`
> installed and its bundled bootstrap segfaulted exactly as above; `readelf -lW`
> on `bin/bash` from the APK's `libtermux-bootstrap.so` shows every `LOAD`
> segment aligned to `0x1000`. Extracting `bootstrap-2026.08.23-r1` (aarch64)
> over `$PREFIX` gave a working `bash 5.3.15`, and everything below then ran on
> it.

Replacing a bootstrap by hand needs `adb` and a debug-signed Termux build, so it
is a developer workaround rather than a user path. What the device pass ran, as
the `com.termux` user with the archive already pushed to
`/data/data/com.termux/files/bs.zip`:

```bash
cd /data/data/com.termux/files
rm -rf usr && mkdir -p usr
unzip -q -o bs.zip -d usr
# The archive ships its symlinks as a manifest: `target←linkpath`, one per line.
cd usr
while read -r line; do
  [ -z "$line" ] && continue
  ln -sf "${line%%←*}" "${line##*←}"
done < SYMLINKS.txt
chmod -R 700 bin libexec
./bin/bash -c 'echo $BASH_VERSION'
```

### Update the packages

Open Termux and update its packages:

```bash
pkg update
```

## 2. Install Hermes in Termux

Upstream documents two tested Android bundles, `.[termux]` and
`.[termux-all]` (`website/docs/getting-started/termux.md:277` @ `f82f2db`).
The steps below install the smaller `.[termux]` one along upstream's explicit
manual path (`termux.md:103-162`); the one-line installer in the note below
tries `.[termux-all]` first and falls back to it (`termux.md:93`).

```bash
pkg install -y git python clang rust make pkg-config libffi openssl nodejs ripgrep ffmpeg libjpeg-turbo

# Hermes pins `requires-python = ">=3.11,<3.14"`, and Termux's `python` is past
# that. 3.11 comes from the Termux User Repository.
pkg install -y tur-repo
pkg install -y python3.11

git clone https://github.com/NousResearch/hermes-agent.git
cd hermes-agent

python3.11 -m venv venv
source venv/bin/activate
export ANDROID_API_LEVEL="$(getprop ro.build.version.sdk)"
export CARGO_BUILD_JOBS=1
python -m pip install --upgrade pip setuptools wheel

python -m pip install -e '.[termux]' -c constraints-termux.txt

ln -sf "$PWD/venv/bin/hermes" "$PREFIX/bin/hermes"
hermes version
hermes doctor
```

> **Verified.** That sequence is what the device pass ran to get
> `hermes-agent 0.20.4` installed and serving. It installed the package list
> without `nodejs` and `ffmpeg`, which `serve` itself does not need, and the
> `pip install` takes a long time on a phone: it builds Rust and C extensions
> from source.

Three of those lines are deviations from upstream's manual path, and each one is
a build that fails without it:

- **`python3.11`, not `python`.** Termux now ships Python 3.14, and Hermes pins
  `requires-python = ">=3.11,<3.14"` (`pyproject.toml:15` @ `f82f2db`), so the
  default interpreter is refused outright. 3.13 does not work either: PEP 738
  makes `sys.platform` report `android` from 3.13 onwards, and the pinned
  `psutil==7.2.2` (`pyproject.toml:108`) answers that with `platform android is
  not supported`. On 3.11 `sys.platform` is still `linux` and the build
  completes.
- **`libjpeg-turbo`.** Pillow is a core dependency (`pyproject.toml:134`), and
  without the JPEG headers it stops with *"The headers or library files could
  not be found for jpeg"*.
- **`CARGO_BUILD_JOBS=1`.** Parallel cargo builds of `jiter` fail in Termux with
  `pyo3-ffi`'s `Text file busy (os error 26)`. One job at a time is slower and
  it finishes. The verified run also set `MAKEFLAGS=-j1`, which is not known to
  be required.

The fourth, `ANDROID_API_LEVEL`, is upstream's own requirement: Rust/maturin
packages such as `jiter` fail to build without it (`termux.md:135`).

`$PREFIX/bin` is already on Termux's `PATH`, so the symlink keeps `hermes`
available in new shells without re-activating the virtualenv
(`termux.md:149-155`).

Upstream also ships a Termux-aware one-line installer that does the same work
and picks the right extra automatically (`termux.md:81-97`):

```bash
curl -fsSL https://hermes-agent.nousresearch.com/install.sh | bash
```

The device pass did not use it. It builds against whatever `python` Termux
supplies, which is the interpreter the pin above refuses, so on a current Termux
expect it to stop at the same place — use the manual path.

Then configure a model, once:

```bash
hermes model
```

A model needs a provider key. `hermes doctor` names what is missing — an absent
`~/.hermes/.env` and an unconfigured provider are what it reports on a fresh
install — and without one, connecting works but every turn ends in an error.

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

> **Verified.** With the server up, `/api/health` answered
> `200 {"ok":true,"version":"0.20.4","auth_required":false}` with no token — it
> is public at the pin, which is why the app's readiness check cannot be the
> place a wrong token is caught. `/api/sessions` answered `401` with no token,
> `200` with the right one and `401` with a wrong one.

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
   not in the UI; it needs `adb` from a computer, or wireless debugging. The
   flags below are community-documented and are not verified by this
   repository — see [Sources](#sources).

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

> **Not verified.** This step is the one part of this guide the device pass could
> not exercise: an emulator with the app in the foreground is not a phone in a
> pocket. Treat the three settings as community advice until a physical-device
> pass says otherwise.

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

> **Verified.** The saved row read `Local · 127.0.0.1:9119 · Session token`, the
> chat surface showed the repository and branch only the Termux Gateway knows,
> and force-stopping and relaunching the app came back **Connected** with the
> session restored and without opening Gateways. A wrong token was refused and
> stayed refused — nothing retried it — and re-saving the right one reconnected,
> as did restarting a stopped `hermes serve`. The session token appeared nowhere
> in `logcat`.

## Troubleshooting

| Symptom | What is happening | Fix |
|---|---|---|
| The app says *"Session token was refused. Save the token Hermes is running with, then connect."* | The token the app holds is not the token the running server has — usually because `hermes serve` restarted without `HERMES_DASHBOARD_SESSION_TOKEN` set and minted a new random one (`web_server.py:499-500` @ `f82f2db`). The refusal comes from the WebSocket upgrade, not the readiness check: `/api/health` needs no token at the pinned Hermes (`dashboard_auth/public_paths.py:33-38`), so the socket is where a wrong token is caught. The app does not retry it. | Export the token as in step 3, restart `hermes serve`, then re-save the token on the connection. |
| The app says *"Hermes is not answering on this device. Start it, then connect."* | Nothing is answering on that port: `hermes serve` exited, or Android suspended or killed it in the background. This is the sentence for both halves of that — pressing **Connect** when the server is not running, and a connection that was live until the server went away — and the app offers **Connect** again rather than retrying by itself, because the only thing that starts that process is you. | Run `hermes serve --status` in Termux. If it lists nothing, start it again, then press **Connect**. A server that dies minutes after you switch apps is the phantom-process killer or battery optimisation, not Hermes — work through step 5. |
| The app says *"Save this Gateway's session token, then connect."* | The row has no token saved. `hermes serve` is headless and serves no web UI, so there is no page for the app to read one from (`dashboard.py:166-170` @ `f82f2db`). | Edit the connection and paste the token from step 3. |
| The app connects, but every turn ends with *"That turn failed — Hermes ended this turn unexpectedly. Check the Gateway, then try again."* | The Gateway is running and answering; the turn is what failed. On a fresh Termux install that is usually a missing provider key, so Hermes has no model to run the turn with. | Run `hermes doctor` in Termux. If it reports a missing `~/.hermes/.env` or an unconfigured provider, add the key and run `hermes model`, then restart `hermes serve`. |
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
- `termux-wake-lock` / `termux-wake-unlock` — shipped by the `termux-tools`
  package ([termux/termux-tools](https://github.com/termux/termux-tools)); the
  same wake lock is the **Acquire wakelock** action on the Termux notification
- phantom-process killing and the flags that disable it —
  [agnostic-apollo/Android-Docs](https://github.com/agnostic-apollo/Android-Docs/blob/master/en/docs/apps/processes/phantom-cached-and-empty-processes.md),
  [termux/termux-app#3506](https://github.com/termux/termux-app/issues/3506)
- 16 KB memory pages and which devices use them —
  [Android developer documentation](https://developer.android.com/guide/practices/page-sizes)
- Termux bootstrap archives and their checksums —
  [termux/termux-packages releases](https://github.com/termux/termux-packages/releases)
- `sys.platform == "android"` from Python 3.13 —
  [PEP 738](https://peps.python.org/pep-0738/)

The 16 KB bootstrap failure, the interpreter and build deviations in step 2, and
every note marked *Verified* were observed by this repository's device pass for
[#93](https://github.com/donovan-yohan/hermes-agent-android/issues/93) on the
hardware named at the top of this guide. They are one device's evidence, not a
support matrix.
