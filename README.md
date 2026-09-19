# tailmux

**Tailscale + tmux.** Attach remote **tmux** sessions on any device in your
tailnet — from the couch or from your pocket — in a riced (Tokyo Night +
JetBrains Mono) terminal.

Two Android apps sharing one backend:

| Module | What |
|--------|------|
| **`:core`** | Shared backend — SSH (JSch), Tailscale device API, encrypted secret store, QR + pairing server, key generation. No UI. |
| **`:tv`** | Android **TV** app (`com.cd4li.tmuxtv`) — D-pad driven, leanback launcher. |
| **`:mobile`** | **Phone/tablet** app (`com.cd4li.tmuxmobile`) — touch UI, input bar, voice. |

## Highlights

- **First-run pairing** — the app enumerates your tailnet via the Tailscale API
  (token entered by phone QR on TV, or pasted on mobile). No hard-coded hub.
- **Pick device → `tmux ls` → attach.** Manual `user@ip[:port]` entry too.
- **Auth that gets out of your way** — auto-generates an SSH key on first run,
  `ssh-copy-id`s it on the first password login, remembers username + (encrypted)
  password per host. Key auth thereafter.
- **Server down?** A *Start tmux* action boots the server so tmux-continuum
  auto-restore brings your sessions back, then lists them.
- **Text input** — voice dictation and keyboard, straight into the active pane.

## Getting started

1. **Install Tailscale** on every device you'll use — the TV box, your phone, and
   the machines you want to reach — and sign in so they're all on the same tailnet.
2. **Generate a Tailscale API token** — [login.tailscale.com](https://login.tailscale.com/admin/settings/keys)
   → Settings → Keys → *Generate access token* (read-only is enough).
3. **Install and open tailmux** (the TV or mobile app).
4. **Give it the token** — on the **TV**, scan the on-screen QR with your phone and
   paste the token there; on **mobile**, paste it straight into the prompt.
5. **Pick a device** from the list (online ones have a green dot), then enter the
   **SSH username** — remembered per host after the first time.
6. **Authenticate** — key auth if the host already has your key; otherwise enter the
   **password once**. tailmux runs `ssh-copy-id` for you, so it's key-based next time.
7. **Choose a tmux session** (or *New session* / *Plain shell*) and you're attached.
   If the host's tmux server is down, pick **Start tmux** to boot it and let
   tmux-continuum restore your sessions.

## Controls

**TV (D-pad):** arrows = move active pane · OK = zoom pane · media ◀▶ = font zoom ·
BACK/MENU = popup (Detach / Sessions / Devices / Voice / Type).

**Mobile (touch):** tap cards to navigate · bottom input bar (type + ⏎) writes to
the active pane · 🎤 voice · quick-keys row (Esc/Tab/^C/arrows) · ☰ actions.

> tmux prefix is `Ctrl-a` (constant in each app's `MainActivity.kt`).

## Build

Android SDK (platform 35, build-tools 35), JDK 17.

```sh
./gradlew :tv:assembleDebug        # TV apk
./gradlew :mobile:assembleDebug    # phone/tablet apk
adb install -r tv/build/outputs/apk/debug/tv-debug.apk
```

Both apps install side by side (distinct applicationIds). Each generates its own
SSH key on first launch; authorize it by connecting once with a password
(auto ssh-copy-id) or drop a key into the app's `files/id_ed25519`.

## Layout

```
core/     shared backend (com.cd4li.tmuxcore) + vendored xterm.js/fonts
tv/       TV app (com.cd4li.tmuxtv)
mobile/   phone/tablet app (com.cd4li.tmuxmobile)
```
