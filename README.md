# TmuxTV

A native Android TV app for driving remote **tmux** sessions from the couch with
a plain D-pad remote. Pick any device on your **Tailscale** tailnet, list its
tmux sessions, and attach — rendered in a riced (Tokyo Night + JetBrains Mono)
terminal.

Built for TV boxes with no keyboard: the D-pad navigates panes, OK zooms, media
keys change font size, and text entry (token, username, password) is done from
your phone or an on-screen keyboard — never thumb-typed on a remote.

---

## Features

- **Phone pairing for the token** — on first run the TV shows a QR + URL; open it
  on your phone and paste your Tailscale API token there (no typing on the TV).
- **Live device list** — enumerated via the Tailscale API (`/tailnet/-/devices`),
  with online indicators. Works on any tailnet; no hard-coded hub host.
- **Session picker** — runs `tmux ls` on the chosen host and lists sessions, plus
  *New session* and *Plain shell*.
- **Manual host entry** — connect to anything as `user@ip[:port]`.
- **Auth** — SSH key (ed25519, via BouncyCastle) where available, with a password
  prompt fallback. Username is remembered per host.
- **Riced terminal** — xterm.js, Tokyo Night palette, JetBrains Mono, adjustable
  margins for TV overscan.
- **Remote-first controls** and clear loading / error feedback throughout.

## Remote controls

| Button            | In the terminal            | In a menu           |
|-------------------|----------------------------|---------------------|
| D-pad ▲▼◀▶        | move active tmux pane      | move selection      |
| OK / Center       | zoom pane (`prefix z`)     | select              |
| Media ▶▶ / ◀◀     | font zoom in / out         | font zoom           |
| BACK / MENU       | open popup (Detach / Sessions / Devices / Resume) | back / resume |

> The tmux **prefix** is `Ctrl-a` (see `PREFIX` in `MainActivity.kt`).

## First-run setup

1. Install the APK (see Build) and open **TmuxTV** from the TV launcher.
2. The TV shows **Connect your tailnet** with a QR code.
3. On your phone, scan it (or open the shown `http://<tv-ip>:8765`) and paste a
   **Tailscale API access token** — create one at
   *login.tailscale.com › Settings › Keys › Generate access token*.
4. The TV loads your **Devices**. Pick one → enter the SSH **username** (remembered
   after the first time) → key auth, or enter a **password** if prompted.
5. Choose a tmux session (or *New* / *Plain shell*) and you're in.

To change the token later: **Devices → Change Tailscale token**.

## Build

Requires the Android SDK (platform 35, build-tools 35), JDK 17.

```sh
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Providing the SSH key

The app authenticates with an ed25519 private key at its private
`files/id_ed25519`. Authorize the matching public key on your hosts
(`~/.ssh/authorized_keys`). For a debug build you can provision the key over adb:

```sh
adb shell "run-as com.hk1.tmuxtv sh -c 'mkdir -p files; cat > files/id_ed25519'" < your_key
adb shell "run-as com.hk1.tmuxtv sh -c 'chmod 600 files/id_ed25519'"
```

Hosts without the key simply prompt for a password.

## Architecture

| File                | Role |
|---------------------|------|
| `MainActivity.kt`   | Phase machine (pair → hosts → sessions → terminal/popup), D-pad handling, native input dialogs, WebView host |
| `SshSession.kt`     | JSch SSH: interactive shell + `runCommand()` for `tmux ls`; key and/or password auth |
| `TailscaleApi.kt`   | Device enumeration via the Tailscale HTTP API |
| `PairServer.kt`     | NanoHTTPD server for phone-based token entry |
| `QrGen.kt`          | QR code (ZXing) for the pairing screen |
| `assets/terminal.html` | xterm.js terminal, menus, pairing & busy overlays |

Terminal rendering runs in a WebView (xterm.js); native code owns all input and
pumps SSH bytes to the terminal, so the remote drives everything.

## Notes / limitations

- Online status is approximated from the device's `lastSeen` (< 5 min).
- Pairing serves the token form over HTTP on the LAN; keep it to a trusted network.
- The tmux prefix and default font size are constants in `MainActivity.kt`.
