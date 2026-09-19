package com.hk1.tmuxtv

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.Base64
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.EditText
import android.widget.FrameLayout
import com.hk1.tmuxcore.Host
import com.hk1.tmuxcore.PairServer
import com.hk1.tmuxcore.QrGen
import com.hk1.tmuxcore.SecretStore
import com.hk1.tmuxcore.SshAuthException
import com.hk1.tmuxcore.SshSession
import com.hk1.tmuxcore.TailscaleApi
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface

private const val TAG = "TmuxTV"

class MainActivity : Activity() {

    private val port = 22
    private lateinit var web: WebView
    private var ssh: SshSession? = null
    private val ui = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("tmuxtv", MODE_PRIVATE) }

    private var fontPx = 17

    private enum class Phase { PAIR, HOSTS, SESSIONS, TERMINAL, POPUP }
    private var phase = Phase.HOSTS
    private var pairServer: PairServer? = null
    @Volatile private var awaitingFirstBytes = false

    // hosts discovered this session, plus the connection we're currently on
    private var hostsByIp = HashMap<String, Host>()
    private var curUser = ""
    private var curIp = ""
    private var curPassword: String? = null
    private var curName = ""

    // tmux / vt bytes
    private val PREFIX = byteArrayOf(0x01)            // Ctrl-a (tmux prefix)
    private val ESC = 0x1b.toByte()
    private fun arrow(c: Char) = byteArrayOf(ESC, '['.code.toByte(), c.code.toByte())
    private val UP = arrow('A'); private val DOWN = arrow('B')
    private val RIGHT = arrow('C'); private val LEFT = arrow('D')

    private fun keyPath(): String? =
        File(filesDir, "id_ed25519").let { if (it.exists() && it.length() > 0) it.absolutePath else null }

    // remembered SSH passwords (encrypted via the Android Keystore)
    private fun pwKey(ip: String, user: String) = "pw_${ip}_$user"
    private fun savePw(ip: String, user: String, pw: String) {
        SecretStore.encrypt(pw)?.let { prefs.edit().putString(pwKey(ip, user), it).apply() }
    }
    private fun loadPw(ip: String, user: String): String? =
        prefs.getString(pwKey(ip, user), null)?.let { SecretStore.decrypt(it) }

    /** ssh-copy-id: install our public key on the host so later logins use key auth. */
    private fun copyKeyToHost(ip: String, p: Int, user: String, password: String) {
        val kp = keyPath() ?: return
        val pub = SshSession.publicKeyLine(kp) ?: return
        Thread {
            try {
                SshSession.runCommand(
                    ip, p, user, kp, password,
                    "umask 077; mkdir -p ~/.ssh; " +
                        "grep -qxF '$pub' ~/.ssh/authorized_keys 2>/dev/null || echo '$pub' >> ~/.ssh/authorized_keys"
                )
                Log.i(TAG, "installed key on $user@$ip")
            } catch (e: Throwable) {
                Log.e(TAG, "ssh-copy-id failed", e)
            }
        }.start()
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemUi()

        web = WebView(this)
        web.setBackgroundColor(0xFF16161E.toInt())
        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
        }
        WebView.setWebContentsDebuggingEnabled(true)
        web.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                Log.i(TAG, "JS[${m.lineNumber()}]: ${m.message()}")
                return true
            }
        }
        web.addJavascriptInterface(Bridge(), "Bridge")
        setContentView(web)
        // make sure we have a key to offer via ssh-copy-id (generate on first run)
        Thread { SshSession.ensureKey(File(filesDir, "id_ed25519").absolutePath) }.start()
        web.loadUrl("file:///android_asset/terminal.html")
    }

    @Suppress("DEPRECATION")
    private fun hideSystemUi() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            )
    }

    // ---------- JS bridge ----------
    inner class Bridge {
        @JavascriptInterface fun ready() = ui.post { onReady() }
        @JavascriptInterface fun input(d: String) { ssh?.send(d.toByteArray(Charsets.UTF_8)) }
        @JavascriptInterface fun resize(cols: Int, rows: Int) { ssh?.resize(cols, rows) }
    }

    private fun js(code: String) { ui.post { web.evaluateJavascript(code, null) } }
    private fun flash(big: String, small: String = "") { js("API.flash(${q(big)}, ${q(small)})") }

    // ---------- startup: token then hosts ----------
    private fun onReady() {
        val token = token()
        if (token.isNullOrBlank()) startPairing() else loadHosts()
    }

    // ---------- phone-based token pairing ----------
    private fun startPairing() {
        phase = Phase.PAIR
        val prt = 8765
        try {
            stopPairing()
            pairServer = PairServer(prt) { t -> ui.post { onTokenPaired(t) } }.also { it.begin() }
        } catch (e: Throwable) {
            Log.e(TAG, "pair server failed", e)
            return promptToken(firstRun = true)   // fall back to on-TV entry
        }
        val ip = localIp() ?: return promptToken(firstRun = true)
        val url = "http://$ip:$prt"
        val qr = QrGen.dataUri(url)
        js("API.showPair(${q(url)}, ${q(qr)})")
    }

    private fun onTokenPaired(t: String) {
        if (t.isBlank()) return
        prefs.edit().putString("token", t.trim()).apply()
        stopPairing()
        js("API.hidePair()")
        loadHosts()
    }

    private fun stopPairing() {
        try { pairServer?.end() } catch (_: Throwable) {}
        pairServer = null
    }

    private fun localIp(): String? {
        var fallback: String? = null
        for (nif in NetworkInterface.getNetworkInterfaces()) {
            if (!nif.isUp || nif.isLoopback) continue
            for (addr in nif.inetAddresses) {
                if (addr !is Inet4Address || addr.isLoopbackAddress) continue
                val ip = addr.hostAddress ?: continue
                // prefer LAN wifi addresses; keep tailscale 100.x as fallback
                if (ip.startsWith("192.168.") || ip.startsWith("10.") || ip.startsWith("172.")) return ip
                if (ip.startsWith("100.")) fallback = ip
            }
        }
        return fallback
    }

    private fun token(): String? {
        prefs.getString("token", null)?.let { if (it.isNotBlank()) return it }
        // testing convenience: allow provisioning via adb into files/ts_token
        val f = File(filesDir, "ts_token")
        if (f.exists()) {
            val t = f.readText().trim()
            if (t.isNotBlank()) { prefs.edit().putString("token", t).apply(); return t }
        }
        return null
    }

    private fun loadHosts() {
        val token = token() ?: return startPairing()
        js("API.busy(${q("Loading devices …")})")
        Thread {
            try {
                val hosts = TailscaleApi.listDevices(token)
                ui.post { js("API.idle()"); showHosts(hosts) }
            } catch (e: Throwable) {
                Log.e(TAG, "device list failed", e)
                ui.post {
                    js("API.error(${q("Tailscale error: " + (e.message ?: "").take(70))})")
                    ui.postDelayed({ js("API.idle()"); startPairing() }, 3200)
                }
            }
        }.start()
    }

    private fun showHosts(hosts: List<Host>) {
        phase = Phase.HOSTS
        hostsByIp = HashMap()
        val arr = JSONArray()
        arr.put(JSONObject().apply {
            put("id", "manual"); put("label", "Enter host manually")
            put("sub", "user@ip[:port]"); put("ico", "⌨")
        })
        for (h in hosts) {
            hostsByIp[h.ip] = h
            arr.put(JSONObject().apply {
                put("id", "host:${h.ip}")
                put("label", h.name)
                put("sub", "${h.os} · ${h.ip}" + if (h.online) "" else " · offline")
                put("ico", "▸")
                put("dot", if (h.online) "on" else "off")
            })
        }
        arr.put(JSONObject().apply {
            put("id", "token"); put("label", "Change Tailscale token"); put("ico", "⚙")
        })
        js("API.showMenu(${q("<b>Devices</b>")}, ${q("▲▼ choose · OK connect · BACK " + if (curIp.isEmpty()) "exit" else "resume")}, ${q(arr.toString())})")
    }

    private fun onHostChosen(id: String) {
        when {
            id == "manual" -> promptManual()
            id == "token" -> startPairing()
            id.startsWith("host:") -> {
                val ip = id.removePrefix("host:")
                val h = hostsByIp[ip]
                if (h != null && !h.online) { flash("offline", h.name); return }
                val name = h?.name ?: ip
                val remembered = prefs.getString("user_$ip", null)
                if (remembered != null) listSessions(remembered, ip, name, loadPw(ip, remembered))
                else {
                    val guess = prefs.getString("last_user", "") ?: ""
                    // We already know the IP from the device list — ask only for the username.
                    promptText("Username for $name", "username", guess, false) { v ->
                        val user = v.trim()
                        if (user.isEmpty()) return@promptText flash("need a username")
                        listSessions(user, ip, name, null)
                    }
                }
            }
        }
    }

    // ---------- list tmux sessions on a host ----------
    private fun listSessions(user: String, ip: String, name: String, password: String?, p: Int = port) {
        js("API.busy(${q("Connecting to $user@$ip …")})")
        Thread {
            try {
                val out = SshSession.runCommand(
                    ip, p, user, keyPath(), password,
                    "tmux ls 2>/dev/null || true"
                )
                prefs.edit().putString("user_$ip", user).putString("last_user", user).apply()
                if (password != null) {
                    savePw(ip, user, password)          // remember for next time
                    copyKeyToHost(ip, p, user, password) // ssh-copy-id → key auth from now on
                }
                ui.post { js("API.idle()"); showSessions(user, ip, name, password, out) }
            } catch (e: SshAuthException) {
                ui.post {
                    js("API.idle()")
                    val wrong = password != null
                    promptText(
                        if (wrong) "Wrong password — $user@$ip" else "Password for $user@$ip",
                        "password", "", true
                    ) { pw -> if (pw.isNotBlank()) listSessions(user, ip, name, pw, p) }
                }
            } catch (e: Throwable) {
                Log.e(TAG, "tmux ls failed", e)
                ui.post {
                    js("API.error(${q("Connect failed: " + (e.message ?: "").take(70))})")
                    ui.postDelayed({ js("API.idle()") }, 3200)
                }
            }
        }.start()
    }

    private fun showSessions(user: String, ip: String, name: String, password: String?, tmuxLs: String) {
        phase = Phase.SESSIONS
        curUser = user; curIp = ip; curPassword = password; curName = name
        val arr = JSONArray()
        var count = 0
        for (line in tmuxLs.lines()) {
            val s = line.trim()
            if (s.isEmpty() || !s.contains(":")) continue
            val sName = s.substringBefore(":")
            val rest = s.substringAfter(":").trim()
            count++
            arr.put(JSONObject().apply {
                put("id", "sess:$sName"); put("label", sName)
                put("sub", rest); put("ico", "▪")
            })
        }
        // Server not running (e.g. after reboot): offer to boot it so tmux-continuum
        // auto-restore kicks in, then re-list the restored sessions.
        if (count == 0) arr.put(JSONObject().apply {
            put("id", "start"); put("label", "Start tmux")
            put("sub", "server down — boot & restore"); put("ico", "⏻")
        })
        arr.put(JSONObject().apply { put("id", "new"); put("label", "New session"); put("ico", "＋") })
        arr.put(JSONObject().apply {
            put("id", "shell"); put("label", "Plain shell"); put("sub", "no tmux"); put("ico", "▸")
        })
        js("API.showMenu(${q("<b>$name</b>")}, ${q("▲▼ choose · OK attach · BACK devices")}, ${q(arr.toString())})")
    }

    private fun onSessionChosen(id: String) {
        if (id == "start") { startTmux(); return }
        val cmd = when {
            id == "shell" -> null
            id == "new" -> "exec tmux new"
            id.startsWith("sess:") -> {
                val n = id.removePrefix("sess:")
                "tmux attach -d -t $n || tmux new -s $n"
            }
            else -> return
        }
        attach(cmd, if (id.startsWith("sess:")) id.removePrefix("sess:") else curName)
    }

    /** Boot the tmux server so continuum auto-restore runs, then re-list. */
    private fun startTmux() {
        js("API.busy(${q("Starting tmux — restoring sessions …")})")
        Thread {
            try {
                val out = SshSession.runCommand(
                    curIp, port, curUser, keyPath(), curPassword,
                    "tmux new-session -d 2>/dev/null; sleep 3; tmux ls 2>/dev/null || true",
                    timeoutMs = 15000
                )
                ui.post { js("API.idle()"); showSessions(curUser, curIp, curName, curPassword, out) }
            } catch (e: Throwable) {
                Log.e(TAG, "start tmux failed", e)
                ui.post {
                    js("API.error(${q("Couldn't start tmux: " + (e.message ?: "").take(60))})")
                    ui.postDelayed({ js("API.idle()") }, 3000)
                }
            }
        }.start()
    }

    // ---------- attach an interactive session ----------
    private fun attach(initialCommand: String?, pillName: String) {
        ssh?.close()
        js("API.hideMenu()")
        js("API.setSession(${q(pillName)})")
        js("API.busy(${q("Attaching $pillName …")})")
        phase = Phase.TERMINAL
        awaitingFirstBytes = true
        ssh = SshSession(
            curIp, port, curUser, keyPath(), curPassword, initialCommand,
            onBytes = { b, n ->
                if (awaitingFirstBytes) { awaitingFirstBytes = false; ui.post { js("API.idle()") } }
                enqueueOutput(b, n)
            },
            onStatus = { m ->
                Log.i(TAG, "status: $m")
                if (m != "AUTH_FAIL") ui.post { if (awaitingFirstBytes) js("API.busy(${q(m)})") }
            },
            onClosed = { ui.post { js("API.error(${q("disconnected — BACK for menu")})") } }
        ).also { it.connect() }
        ui.postDelayed({ js("API.refit()") }, 400)
        ui.postDelayed({ js("API.refit()") }, 1600)
    }

    // ---------- output batching ----------
    private val outBuf = java.io.ByteArrayOutputStream(64 * 1024)
    private var flushScheduled = false
    private fun enqueueOutput(bytes: ByteArray, n: Int) {
        synchronized(outBuf) {
            outBuf.write(bytes, 0, n)
            if (!flushScheduled) { flushScheduled = true; ui.postDelayed(::flushOutput, 16) }
        }
    }
    private fun flushOutput() {
        val data: ByteArray
        synchronized(outBuf) { data = outBuf.toByteArray(); outBuf.reset(); flushScheduled = false }
        if (data.isNotEmpty()) {
            val b64 = Base64.encodeToString(data, Base64.NO_WRAP)
            web.evaluateJavascript("API.write('$b64')", null)
        }
    }

    // ---------- key handling ----------
    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val code = e.keyCode
        val down = e.action == KeyEvent.ACTION_DOWN
        if (down && e.repeatCount == 0)
            Log.i(TAG, "KEY code=$code name=${KeyEvent.keyCodeToString(code)}")

        // global font zoom (works everywhere)
        if (down) {
            val ctrl = e.isCtrlPressed
            when {
                code == KeyEvent.KEYCODE_MEDIA_NEXT -> { fontInc(); return true }
                code == KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { fontDec(); return true }
                code == KeyEvent.KEYCODE_ZOOM_IN -> { fontInc(); return true }
                code == KeyEvent.KEYCODE_ZOOM_OUT -> { fontDec(); return true }
                ctrl && (code == KeyEvent.KEYCODE_EQUALS || code == KeyEvent.KEYCODE_PLUS ||
                    code == KeyEvent.KEYCODE_NUMPAD_ADD) -> { fontInc(); return true }
                ctrl && (code == KeyEvent.KEYCODE_MINUS ||
                    code == KeyEvent.KEYCODE_NUMPAD_SUBTRACT) -> { fontDec(); return true }
                ctrl && code == KeyEvent.KEYCODE_0 -> { fontReset(); return true }
            }
        }

        return when (phase) {
            Phase.PAIR -> handlePair(code, down)
            Phase.HOSTS, Phase.SESSIONS -> handleMenu(code, down)
            Phase.POPUP -> handlePopup(code, down)
            Phase.TERMINAL -> handleTerminal(code, down, e)
        }
    }

    private fun handlePair(code: Int, down: Boolean): Boolean {
        if (!down) return true
        when (code) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BUTTON_A ->
                promptToken(firstRun = true)      // type on TV instead
            KeyEvent.KEYCODE_BACK -> finish()
        }
        return true
    }

    private fun handleMenu(code: Int, down: Boolean): Boolean {
        if (!down) return true
        when (code) {
            KeyEvent.KEYCODE_DPAD_UP -> js("API.menuMove(-1)")
            KeyEvent.KEYCODE_DPAD_DOWN -> js("API.menuMove(1)")
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BUTTON_A ->
                web.evaluateJavascript("API.menuSel()") { v ->
                    val id = unq(v)
                    if (phase == Phase.HOSTS) onHostChosen(id) else onSessionChosen(id)
                }
            KeyEvent.KEYCODE_BACK -> {
                if (phase == Phase.SESSIONS) showHosts(hostsByIp.values.toList().sortedWith(
                    compareByDescending<Host> { it.online }.thenBy { it.name.lowercase() }))
                else if (curIp.isNotEmpty()) { js("API.hideMenu()"); phase = Phase.TERMINAL }
                else finish()
            }
        }
        return true
    }

    private fun handleTerminal(code: Int, down: Boolean, e: KeyEvent): Boolean {
        when (code) {
            KeyEvent.KEYCODE_DPAD_UP -> { if (down && e.repeatCount == 0) selectPane(UP, "▲"); return true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { if (down && e.repeatCount == 0) selectPane(DOWN, "▼"); return true }
            KeyEvent.KEYCODE_DPAD_LEFT -> { if (down && e.repeatCount == 0) selectPane(LEFT, "◀"); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { if (down && e.repeatCount == 0) selectPane(RIGHT, "▶"); return true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BUTTON_A -> {
                if (down && e.repeatCount == 0) zoomPane(); return true
            }
            KeyEvent.KEYCODE_VOICE_ASSIST, KeyEvent.KEYCODE_ASSIST, KeyEvent.KEYCODE_SEARCH -> {
                if (down && e.repeatCount == 0) startVoiceInput(); return true
            }
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_BACK -> {
                if (down && e.repeatCount == 0) showPopup(); return true
            }
        }
        return super.dispatchKeyEvent(e)
    }

    // ---------- in-terminal popup ----------
    private fun showPopup() {
        phase = Phase.POPUP
        val arr = JSONArray()
        arr.put(JSONObject().apply { put("id", "resume"); put("label", "Resume"); put("ico", "▸") })
        arr.put(JSONObject().apply {
            put("id", "voice"); put("label", "Voice input"); put("sub", "speak → active pane, then Enter"); put("ico", "🎤")
        })
        arr.put(JSONObject().apply {
            put("id", "type"); put("label", "Type text"); put("sub", "keyboard → active pane"); put("ico", "⌨")
        })
        arr.put(JSONObject().apply {
            put("id", "detach"); put("label", "Detach"); put("sub", "leave tmux running"); put("ico", "⏏")
        })
        arr.put(JSONObject().apply { put("id", "sessions"); put("label", "Sessions on $curName"); put("ico", "▪") })
        arr.put(JSONObject().apply { put("id", "devices"); put("label", "Devices"); put("ico", "⌂") })
        js("API.showMenu(${q("<b>$curName</b>")}, ${q("▲▼ · OK select · BACK resume")}, ${q(arr.toString())})")
    }

    private fun handlePopup(code: Int, down: Boolean): Boolean {
        if (!down) return true
        when (code) {
            KeyEvent.KEYCODE_DPAD_UP -> js("API.menuMove(-1)")
            KeyEvent.KEYCODE_DPAD_DOWN -> js("API.menuMove(1)")
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BUTTON_A ->
                web.evaluateJavascript("API.menuSel()") { v -> onPopupChosen(unq(v)) }
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_MENU -> { js("API.hideMenu()"); phase = Phase.TERMINAL }
        }
        return true
    }

    private fun onPopupChosen(id: String) {
        when (id) {
            "resume" -> { js("API.hideMenu()"); phase = Phase.TERMINAL }
            "voice" -> { js("API.hideMenu()"); phase = Phase.TERMINAL; startVoiceInput() }
            "type" -> { js("API.hideMenu()"); phase = Phase.TERMINAL; promptTypeText() }
            "sessions" -> { js("API.hideMenu()"); listSessions(curUser, curIp, curName, curPassword) }
            "devices" -> { js("API.hideMenu()"); loadHosts() }
            "detach" -> {
                ssh?.send(PREFIX + byteArrayOf('d'.code.toByte()))
                ui.postDelayed({ listSessions(curUser, curIp, curName, curPassword) }, 300)
            }
        }
    }

    private fun fontInc() { fontPx = (fontPx + 2).coerceAtMost(40); js("API.setFont($fontPx)") }
    private fun fontDec() { fontPx = (fontPx - 2).coerceAtLeast(8); js("API.setFont($fontPx)") }
    private fun fontReset() { fontPx = 17; js("API.setFont($fontPx)") }

    private fun selectPane(seq: ByteArray, glyph: String) { ssh?.send(PREFIX + seq); flash(glyph, "pane") }
    private fun zoomPane() { ssh?.send(PREFIX + byteArrayOf('z'.code.toByte())); flash("⛶", "zoom") }

    // ---------- text input into the active pane ----------
    private val reqVoice = 1001

    private fun startVoiceInput() {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak — goes to the active pane")
        }
        try { startActivityForResult(i, reqVoice) }
        catch (e: Exception) { flash("No voice recognizer", "install the Google app") }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == reqVoice && resultCode == RESULT_OK) {
            val text = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (!text.isNullOrEmpty()) sendText(text, enter = true)
        }
    }

    private fun promptTypeText() {
        promptText("Type text", "text → active pane", "", false) { t ->
            if (t.isNotEmpty()) sendText(t, enter = true)
        }
    }

    private fun sendText(text: String, enter: Boolean) {
        ssh?.send(text.toByteArray(Charsets.UTF_8))
        if (enter) ssh?.send(byteArrayOf(0x0d))
        flash("sent", text.take(28))
    }

    // ---------- native input dialogs ----------
    private fun promptToken(firstRun: Boolean) {
        promptText(
            if (firstRun) "Welcome — Tailscale API token" else "Tailscale API token",
            "tskey-api-…  (login.tailscale.com › Settings › Keys)",
            prefs.getString("token", "") ?: "", false
        ) { t ->
            if (t.isBlank()) { if (firstRun) startPairing(); return@promptText }
            prefs.edit().putString("token", t.trim()).apply()
            stopPairing()
            js("API.hidePair()")
            loadHosts()
        }
    }

    private fun promptManual() {
        promptText("Add host", "user@ip[:port]", "", false) { v ->
            val t = parseTarget(v) ?: return@promptText flash("bad host", "use user@ip")
            listSessions(t.first, t.second, t.second, null, t.third)
        }
    }

    private fun promptText(
        title: String, hint: String, prefill: String, isPassword: Boolean,
        onOk: (String) -> Unit
    ) {
        val ctx = ContextThemeWrapper(this, android.R.style.Theme_Material_Dialog_Alert)
        val input = EditText(ctx).apply {
            setText(prefill)
            setHint(hint)
            setSingleLine()
            inputType = if (isPassword)
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setSelection(text.length)
        }
        val pad = (24 * resources.displayMetrics.density).toInt()
        val holder = FrameLayout(ctx).apply { setPadding(pad, pad / 2, pad, 0); addView(input) }
        AlertDialog.Builder(ctx)
            .setTitle(title)
            .setView(holder)
            .setPositiveButton("OK") { _, _ -> onOk(input.text.toString()) }
            .setNegativeButton("Cancel", null)
            .create().apply {
                setOnShowListener { input.requestFocus() }
                show()
            }
    }

    /** "user@ip[:port]" -> (user, ip, port). */
    private fun parseTarget(s: String): Triple<String, String, Int>? {
        val t = s.trim()
        val at = t.indexOf('@')
        if (at <= 0 || at == t.length - 1) return null
        val user = t.substring(0, at)
        var hostPart = t.substring(at + 1)
        var p = port
        val colon = hostPart.lastIndexOf(':')
        if (colon > 0) {
            hostPart.substring(colon + 1).toIntOrNull()?.let { p = it; hostPart = hostPart.substring(0, colon) }
        }
        if (hostPart.isBlank()) return null
        return Triple(user, hostPart, p)
    }

    // ---------- helpers ----------
    private fun q(s: String) = "'" + s.replace("\\", "\\\\").replace("'", "\\'")
        .replace("\n", "\\n").replace("\r", "") + "'"
    private fun unq(v: String): String {
        var s = v
        if (s.length >= 2 && s.startsWith("\"") && s.endsWith("\"")) s = s.substring(1, s.length - 1)
        return s.replace("\\\"", "\"").replace("\\\\", "\\")
    }

    override fun onDestroy() {
        ssh?.close()
        stopPairing()
        super.onDestroy()
    }
}
