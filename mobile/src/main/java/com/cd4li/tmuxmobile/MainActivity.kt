package com.cd4li.tmuxmobile

import com.cd4li.tmuxcore.Host
import com.cd4li.tmuxcore.Net
import com.cd4li.tmuxcore.SecretStore
import com.cd4li.tmuxcore.SshAuthException
import com.cd4li.tmuxcore.SshSession
import com.cd4li.tmuxcore.TailscaleApi

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognizerIntent
import android.text.InputType
import android.util.Base64
import android.util.Log
import android.view.ContextThemeWrapper
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.EditText
import android.widget.FrameLayout
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

private const val TAG = "TmuxMobile"

/** Touch-first phone/tablet client. Shares the backend with the TV app but drives
 * everything through taps, an input bar and voice — no D-pad. */
class MainActivity : Activity() {

    private val port = 22
    private lateinit var web: WebView
    private var ssh: SshSession? = null
    private val ui = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("tmuxmobile", MODE_PRIVATE) }

    // which list the WebView is currently showing (routes taps)
    private var menuMode = ""   // "hosts" | "sessions" | "actions"
    private var hostsByIp = HashMap<String, Host>()
    private var curUser = ""; private var curIp = ""; private var curPassword: String? = null; private var curName = ""
    private var paneIds = listOf<String>()
    private var activePaneId = ""
    private var curSession = ""          // the tmux session name (not the host name)
    @Volatile private var copyMode = false

    private val ESC = 0x1b.toByte()
    private fun arrow(c: Char) = byteArrayOf(ESC, '['.code.toByte(), c.code.toByte())

    private fun keyPath(): String? =
        File(filesDir, "id_ed25519").let { if (it.exists() && it.length() > 0) it.absolutePath else null }

    private fun pwKey(ip: String, user: String) = "pw_${ip}_$user"
    private fun savePw(ip: String, user: String, pw: String) {
        SecretStore.encrypt(pw)?.let { prefs.edit().putString(pwKey(ip, user), it).apply() }
    }
    private fun loadPw(ip: String, user: String): String? =
        prefs.getString(pwKey(ip, user), null)?.let { SecretStore.decrypt(it) }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        web.setBackgroundColor(0xFF16161E.toInt())
        with(web.settings) { javaScriptEnabled = true; domStorageEnabled = true }
        WebView.setWebContentsDebuggingEnabled(true)
        web.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                Log.i(TAG, "JS[${m.lineNumber()}]: ${m.message()}"); return true
            }
        }
        web.addJavascriptInterface(Bridge(), "Bridge")
        setContentView(web)
        Thread { SshSession.ensureKey(File(filesDir, "id_ed25519").absolutePath) }.start()
        web.loadUrl("file:///android_asset/terminal.html")
    }

    inner class Bridge {
        @JavascriptInterface fun ready() = ui.post { onReady() }
        @JavascriptInterface fun input(d: String) { ssh?.send(d.toByteArray(Charsets.UTF_8)) }
        @JavascriptInterface fun resize(cols: Int, rows: Int) { ssh?.resize(cols, rows) }
        @JavascriptInterface fun pick(id: String) = ui.post { onPick(id) }
        @JavascriptInterface fun sendLine(t: String) = ui.post { sendText(t, true) }
        @JavascriptInterface fun key(name: String) = ui.post { onKey(name) }
        @JavascriptInterface fun voice() = ui.post { startVoiceInput() }
        @JavascriptInterface fun openActions() = ui.post { showActions() }
        @JavascriptInterface fun openDevices() = ui.post { loadHosts() }
        @JavascriptInterface fun selectPane(id: String) = ui.post { doSelectPane(id) }
        @JavascriptInterface fun paneStep(delta: Int) = ui.post { doPaneStep(delta) }
        @JavascriptInterface fun scroll(lines: Int) = ui.post { doScroll(lines) }
        @JavascriptInterface fun scrollExit() = ui.post { doScrollExit() }
        @JavascriptInterface fun showKeyboard() = ui.post { showKb() }
    }

    private fun showKb() {
        web.requestFocus()
        val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.showSoftInput(web, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
    }

    // ---------- panes (one at a time + finder) ----------
    // All tmux control goes over a separate exec channel — never through the
    // interactive pane — so it's independent of the tmux prefix and can't leak.

    private fun doSelectPane(id: String) {
        if (copyMode) doScrollExit()
        // select-pane unzooms if the window was zoomed; then zoom the target
        ssh?.exec("tmux select-pane -t $id \\; resize-pane -Z -t $id")
        activePaneId = id
        js("API.setActivePane(${q(id)})")
        ui.postDelayed({ refreshPanes(false) }, 400)
    }

    private fun doPaneStep(delta: Int) {
        if (paneIds.isEmpty()) return
        val pos = paneIds.indexOf(activePaneId).let { if (it < 0) 0 else it }
        val next = paneIds[((pos + delta) % paneIds.size + paneIds.size) % paneIds.size]
        doSelectPane(next)
    }

    // scrolling drives tmux copy-mode server-side, debounced so rapid drags
    // coalesce into one exec instead of flooding the connection
    private var pendingScroll = 0
    private val scrollFlush = Runnable {
        val n = pendingScroll; pendingScroll = 0
        if (n == 0 || activePaneId.isEmpty()) return@Runnable
        copyMode = true
        val dir = if (n > 0) "scroll-up" else "scroll-down"
        ssh?.exec("tmux copy-mode -t $activePaneId \\; send-keys -t $activePaneId -X -N ${minOf(kotlin.math.abs(n), 300)} $dir")
    }
    private fun doScroll(lines: Int) {
        if (lines == 0) return
        pendingScroll += lines
        ui.removeCallbacks(scrollFlush)
        ui.postDelayed(scrollFlush, 55)
    }
    private fun doScrollExit() {
        ui.removeCallbacks(scrollFlush); pendingScroll = 0
        if (copyMode && activePaneId.isNotEmpty()) ssh?.exec("tmux send-keys -t $activePaneId -X cancel")
        copyMode = false
    }

    private fun refreshPanes(zoomIfNeeded: Boolean) {
        val s = curSession; if (s.isEmpty() || curIp.isEmpty()) { Log.i(TAG, "refreshPanes skip session='$s' ip='$curIp'"); return }
        Thread {
            try {
                // window_layout carries the tiled geometry (unaffected by zoom); list-panes gives id/active/cmd
                val out = SshSession.runCommand(
                    curIp, port, curUser, keyPath(), curPassword,
                    "tmux display-message -p -t ${shq(s)} '#{window_zoomed_flag}|#{window_layout}'; echo '::'; " +
                        "tmux list-panes -t ${shq(s)} -F '#{pane_id}|#{pane_index}|#{pane_active}|#{pane_current_command}'"
                )
                Log.i(TAG, "panes out=[${out.replace("\n", "\\n")}]")
                val chunks = out.split("::")
                val head = chunks.getOrNull(0)?.trim().orEmpty()
                val zoomed = head.substringBefore("|") == "1"
                val layout = head.substringAfter("|", "")
                val W = Regex("""^[0-9a-f]+,(\d+)x(\d+)""").find(layout)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                val H = Regex("""^[0-9a-f]+,(\d+)x(\d+)""").find(layout)?.groupValues?.get(2)?.toIntOrNull() ?: 0
                // leaves in window_layout: WxH,X,Y,<paneNum>
                val geo = HashMap<String, IntArray>()
                Regex("""(\d+)x(\d+),(\d+),(\d+),(\d+)""").findAll(layout).forEach { m ->
                    val (w, h, x, y, pn) = m.destructured
                    geo[pn] = intArrayOf(x.toInt(), y.toInt(), w.toInt(), h.toInt())
                }
                val panes = JSONArray(); val ids = ArrayList<String>(); var active = ""
                for (line in (chunks.getOrNull(1) ?: "").lines()) {
                    val p = line.trim().split("|"); if (p.size < 4) continue
                    val id = p[0]; if (id.isEmpty()) continue
                    val g = geo[id.removePrefix("%")] ?: continue
                    ids.add(id); if (p[2] == "1") active = id
                    panes.put(JSONObject().apply {
                        put("id", id); put("label", p[1]); put("cmd", p[3]); put("active", p[2] == "1")
                        put("x", g[0]); put("y", g[1]); put("pw", g[2]); put("ph", g[3])
                    })
                }
                val obj = JSONObject().apply { put("w", W); put("h", H); put("panes", panes) }
                paneIds = ids; if (active.isNotEmpty()) activePaneId = active
                ui.post {
                    js("API.setPanes(${q(obj.toString())})")
                    if (zoomIfNeeded && !zoomed && ids.size > 1 && active.isNotEmpty())
                        ssh?.exec("tmux resize-pane -Z -t $active")
                }
            } catch (e: Throwable) { Log.e(TAG, "refreshPanes failed", e) }
        }.start()
    }

    private fun shq(s: String) = "'" + s.replace("'", "'\\''") + "'"

    private fun js(code: String) { ui.post { web.evaluateJavascript(code, null) } }

    // ---------- startup / token ----------
    private fun onReady() {
        val t = token()
        if (t.isNullOrBlank()) promptToken(true) else loadHosts()
    }

    private fun token(): String? {
        prefs.getString("token", null)?.let { if (it.isNotBlank()) return it }
        val f = File(filesDir, "ts_token")
        if (f.exists()) { val t = f.readText().trim(); if (t.isNotBlank()) { prefs.edit().putString("token", t).apply(); return t } }
        return null
    }

    private fun promptToken(first: Boolean) {
        promptText(
            if (first) "Add your Tailscale token" else "Tailscale API token",
            "tskey-api-…  (paste from login.tailscale.com › Keys)",
            prefs.getString("token", "") ?: ""
        ) { t ->
            if (t.isBlank()) { if (first) promptToken(true); return@promptText }
            prefs.edit().putString("token", t.trim()).apply()
            loadHosts()
        }
    }

    // ---------- hosts ----------
    private fun loadHosts() {
        val token = token() ?: return promptToken(true)
        js("API.busy(${q("Loading devices …")})")
        Thread {
            try {
                val hosts = TailscaleApi.listDevices(token)
                ui.post { js("API.idle()"); showHosts(hosts) }
            } catch (e: Throwable) {
                Log.e(TAG, "device list failed", e)
                ui.post {
                    js("API.error(${q("Tailscale error: " + (e.message ?: "").take(70))})")
                    ui.postDelayed({ js("API.idle()"); promptToken(false) }, 3000)
                }
            }
        }.start()
    }

    private fun showHosts(hosts: List<Host>) {
        menuMode = "hosts"; hostsByIp = HashMap()
        val arr = JSONArray()
        arr.put(obj("manual", "Enter host manually", "user@ip[:port]", "⌨", null))
        for (h in hosts) {
            hostsByIp[h.ip] = h
            arr.put(obj("host:${h.ip}", h.name, "${h.os} · ${h.ip}" + if (h.online) "" else " · offline",
                "▸", if (h.online) "on" else "off"))
        }
        arr.put(obj("token", "Change Tailscale token", null, "⚙", null))
        js("API.showMenu(${q("<b>Devices</b>")}, ${q("tap to connect")}, ${q(arr.toString())})")
    }

    private fun onHostChosen(id: String) {
        when {
            id == "manual" -> promptText("Add host", "user@ip[:port]", "") { v ->
                parseTarget(v)?.let { listSessions(it.first, it.second, it.second, null, it.third) }
                    ?: toast("use user@ip")
            }
            id == "token" -> promptToken(false)
            id.startsWith("host:") -> {
                val ip = id.removePrefix("host:"); val h = hostsByIp[ip]
                if (h != null && !h.online) { toast("${h.name} is offline"); return }
                val name = h?.name ?: ip
                val remembered = prefs.getString("user_$ip", null)
                if (remembered != null) listSessions(remembered, ip, name, loadPw(ip, remembered))
                else {
                    val guess = prefs.getString("last_user", "") ?: ""
                    promptText("Username for $name", "username", guess) { v ->
                        val u = v.trim(); if (u.isEmpty()) return@promptText toast("need a username")
                        listSessions(u, ip, name, null)
                    }
                }
            }
        }
    }

    // ---------- sessions ----------
    private fun listSessions(user: String, ip: String, name: String, password: String?, p: Int = port) {
        if (!tailscaleReady(ip)) return
        js("API.busy(${q("Connecting to $user@$ip …")})")
        Thread {
            try {
                val out = SshSession.runCommand(ip, p, user, keyPath(), password, "tmux ls 2>/dev/null || true")
                prefs.edit().putString("user_$ip", user).putString("last_user", user).apply()
                if (password != null) { savePw(ip, user, password); copyKeyToHost(ip, p, user, password) }
                ui.post { js("API.idle()"); showSessions(user, ip, name, password, out) }
            } catch (e: SshAuthException) {
                ui.post {
                    js("API.idle()")
                    val wrong = password != null
                    promptSecret(if (wrong) "Wrong password — $user@$ip" else "Password for $user@$ip") { pw ->
                        if (pw.isNotBlank()) listSessions(user, ip, name, pw, p)
                    }
                }
            } catch (e: Throwable) {
                Log.e(TAG, "tmux ls failed", e)
                ui.post { js("API.error(${q("Connect failed: " + (e.message ?: "").take(70))})"); ui.postDelayed({ js("API.idle()") }, 3000) }
            }
        }.start()
    }

    private fun showSessions(user: String, ip: String, name: String, password: String?, tmuxLs: String) {
        menuMode = "sessions"
        curUser = user; curIp = ip; curPassword = password; curName = name
        val arr = JSONArray(); var count = 0
        for (line in tmuxLs.lines()) {
            val s = line.trim(); if (s.isEmpty() || !s.contains(":")) continue
            count++; arr.put(obj("sess:${s.substringBefore(":")}", s.substringBefore(":"), s.substringAfter(":").trim(), "▪", null))
        }
        if (count == 0) arr.put(obj("start", "Start tmux", "server down — boot & restore", "⏻", null))
        arr.put(obj("new", "New session", null, "＋", null))
        arr.put(obj("shell", "Plain shell", "no tmux", "▸", null))
        js("API.showMenu(${q("<b>$name</b>")}, ${q("tap a session")}, ${q(arr.toString())})")
    }

    private fun onSessionChosen(id: String) {
        if (id == "start") return startTmux()
        val cmd = when {
            id == "shell" -> null
            id == "new" -> "exec tmux new"
            id.startsWith("sess:") -> { val n = id.removePrefix("sess:"); "tmux attach -d -t $n || tmux new -s $n" }
            else -> return
        }
        curSession = if (id.startsWith("sess:")) id.removePrefix("sess:") else ""   // tmux session for the finder
        attach(cmd, if (id.startsWith("sess:")) id.removePrefix("sess:") else curName)
    }

    private fun tailscaleReady(ip: String): Boolean {
        if (!Net.isTailscaleIp(ip) || Net.tailscaleUp()) return true
        val installed = try { packageManager.getPackageInfo("com.tailscale.ipn", 0); true } catch (_: Exception) { false }
        val ctx = ContextThemeWrapper(this, android.R.style.Theme_Material_Dialog_Alert)
        AlertDialog.Builder(ctx)
            .setTitle("Tailscale not connected")
            .setMessage(
                "This phone isn't on your tailnet, so it can't reach $ip.\n\n" +
                    if (installed) "Open Tailscale and connect, then try again."
                    else "Install Tailscale and sign in, then try again."
            )
            .setPositiveButton(if (installed) "Open Tailscale" else "Get Tailscale") { _, _ ->
                try {
                    val i = if (installed) packageManager.getLaunchIntentForPackage("com.tailscale.ipn")
                    else Intent(Intent.ACTION_VIEW, android.net.Uri.parse("market://details?id=com.tailscale.ipn"))
                    if (i != null) startActivity(i)
                } catch (_: Exception) {}
            }
            .setNegativeButton("Cancel", null)
            .show()
        return false
    }

    private fun startTmux() {
        js("API.busy(${q("Starting tmux — restoring …")})")
        Thread {
            try {
                val out = SshSession.runCommand(curIp, port, curUser, keyPath(), curPassword,
                    "tmux new-session -d 2>/dev/null; sleep 3; tmux ls 2>/dev/null || true", 15000)
                ui.post { js("API.idle()"); showSessions(curUser, curIp, curName, curPassword, out) }
            } catch (e: Throwable) {
                ui.post { js("API.error(${q("Couldn't start tmux")})"); ui.postDelayed({ js("API.idle()") }, 2500) }
            }
        }.start()
    }

    private fun copyKeyToHost(ip: String, p: Int, user: String, password: String) {
        val kp = keyPath() ?: return
        val pub = SshSession.publicKeyLine(kp) ?: return
        Thread {
            try {
                SshSession.runCommand(ip, p, user, kp, password,
                    "umask 077; mkdir -p ~/.ssh; grep -qxF '$pub' ~/.ssh/authorized_keys 2>/dev/null || echo '$pub' >> ~/.ssh/authorized_keys")
                Log.i(TAG, "installed key on $user@$ip")
            } catch (e: Throwable) { Log.e(TAG, "ssh-copy-id failed", e) }
        }.start()
    }

    // ---------- attach ----------
    @Volatile private var awaitingFirstBytes = false
    private fun attach(initialCommand: String?, pillName: String) {
        ssh?.close()
        menuMode = ""; copyMode = false; paneIds = emptyList(); activePaneId = ""
        js("API.hideMenu()"); js("API.setSession(${q(pillName)})"); js("API.busy(${q("Attaching $pillName …")})")
        js("API.setPanes('[]')")
        awaitingFirstBytes = true
        ssh = SshSession(
            curIp, port, curUser, keyPath(), curPassword, initialCommand,
            onBytes = { b, n -> if (awaitingFirstBytes) { awaitingFirstBytes = false; ui.post { js("API.idle()") } }; enqueueOutput(b, n) },
            onStatus = { m -> if (m != "AUTH_FAIL") ui.post { if (awaitingFirstBytes) js("API.busy(${q(m)})") } },
            onClosed = { ui.post { js("API.error(${q("disconnected — tap Devices")})") } }
        ).also { it.connect() }
        ui.postDelayed({ js("API.refit()") }, 400)
        ui.postDelayed({ js("API.refit()") }, 1600)
        ui.postDelayed({ refreshPanes(true) }, 2000)   // populate finder + zoom to one pane
    }

    // ---------- actions sheet ----------
    private fun showActions() {
        if (curIp.isEmpty()) return
        menuMode = "actions"
        val arr = JSONArray()
        arr.put(obj("resume", "Resume", null, "▸", null))
        arr.put(obj("apane", "Next pane", "cycle panes", "⿴", null))
        arr.put(obj("azoom", "Zoom pane", null, "⛶", null))
        arr.put(obj("awnext", "Next window", null, "▷", null))
        arr.put(obj("awprev", "Prev window", null, "◁", null))
        arr.put(obj("sessions", "Sessions", null, "▪", null))
        arr.put(obj("detach", "Detach", "leave tmux running", "⏏", null))
        js("API.showMenu(${q("<b>$curName</b>")}, ${q("tap an action")}, ${q(arr.toString())})")
    }

    private fun onPick(id: String) = when (menuMode) {
        "hosts" -> onHostChosen(id)
        "sessions" -> onSessionChosen(id)
        "actions" -> onActionChosen(id)
        else -> {}
    }

    private fun onActionChosen(id: String) {
        when (id) {
            "resume" -> resume()
            "apane" -> { resume(); doPaneStep(1) }
            "azoom" -> { resume(); if (activePaneId.isNotEmpty()) ssh?.exec("tmux resize-pane -Z -t $activePaneId") }
            "awnext" -> { resume(); ssh?.exec("tmux next-window -t ${shq(curSession)}"); ui.postDelayed({ refreshPanes(true) }, 300) }
            "awprev" -> { resume(); ssh?.exec("tmux previous-window -t ${shq(curSession)}"); ui.postDelayed({ refreshPanes(true) }, 300) }
            "sessions" -> { js("API.hideMenu()"); listSessions(curUser, curIp, curName, curPassword) }
            "detach" -> { js("API.hideMenu()"); ssh?.close(); ssh = null; listSessions(curUser, curIp, curName, curPassword) }
        }
    }
    private fun resume() { menuMode = ""; js("API.hideMenu()") }

    // ---------- input ----------
    private fun sendText(text: String, enter: Boolean) {
        if (copyMode) doScrollExit()
        ssh?.send(text.toByteArray(Charsets.UTF_8))
        if (enter) ssh?.send(byteArrayOf(0x0d))
    }

    private fun onKey(name: String) {
        val b = when (name) {
            "esc" -> byteArrayOf(ESC)
            "tab" -> byteArrayOf(0x09)
            "stab" -> byteArrayOf(ESC, '['.code.toByte(), 'Z'.code.toByte())  // Shift+Tab (CSI Z)
            "ctrlc" -> byteArrayOf(0x03)
            "up" -> arrow('A'); "down" -> arrow('B'); "right" -> arrow('C'); "left" -> arrow('D')
            "enter" -> byteArrayOf(0x0d)
            else -> return
        }
        Log.i(TAG, "key=$name bytes=${b.joinToString(",") { (it.toInt() and 0xff).toString() }}")
        ssh?.send(b)
    }

    private val reqVoice = 1001
    private fun startVoiceInput() {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak — sends to the active pane")
        }
        try { startActivityForResult(i, reqVoice) } catch (e: Exception) { toast("No voice recognizer") }
    }
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == reqVoice && resultCode == RESULT_OK) {
            data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { sendText(it, true) }
        }
    }

    override fun onBackPressed() {
        when {
            menuMode == "actions" || menuMode == "sessions" -> { if (curIp.isNotEmpty()) { menuMode = ""; js("API.hideMenu()") } else super.onBackPressed() }
            menuMode == "hosts" -> if (curIp.isNotEmpty()) { menuMode = ""; js("API.hideMenu()") } else super.onBackPressed()
            curIp.isNotEmpty() -> showActions()
            else -> super.onBackPressed()
        }
    }

    // ---------- output batching ----------
    private val outBuf = java.io.ByteArrayOutputStream(64 * 1024)
    private var flushScheduled = false
    private fun enqueueOutput(bytes: ByteArray, n: Int) {
        synchronized(outBuf) { outBuf.write(bytes, 0, n); if (!flushScheduled) { flushScheduled = true; ui.postDelayed(::flushOutput, 16) } }
    }
    private fun flushOutput() {
        val data: ByteArray
        synchronized(outBuf) { data = outBuf.toByteArray(); outBuf.reset(); flushScheduled = false }
        if (data.isNotEmpty()) web.evaluateJavascript("API.write('${Base64.encodeToString(data, Base64.NO_WRAP)}')", null)
    }

    // ---------- dialogs / helpers ----------
    private fun promptText(title: String, hint: String, prefill: String, onOk: (String) -> Unit) =
        dialog(title, hint, prefill, false, onOk)
    private fun promptSecret(title: String, onOk: (String) -> Unit) = dialog(title, "password", "", true, onOk)

    private fun dialog(title: String, hint: String, prefill: String, secret: Boolean, onOk: (String) -> Unit) {
        val ctx = ContextThemeWrapper(this, android.R.style.Theme_Material_Dialog_Alert)
        val input = EditText(ctx).apply {
            setText(prefill); setHint(hint); setSingleLine()
            inputType = if (secret) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                        else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setSelection(text.length)
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val holder = FrameLayout(ctx).apply { setPadding(pad, pad / 2, pad, 0); addView(input) }
        AlertDialog.Builder(ctx).setTitle(title).setView(holder)
            .setPositiveButton("OK") { _, _ -> onOk(input.text.toString()) }
            .setNegativeButton("Cancel", null)
            .create().apply { setOnShowListener { input.requestFocus() }; show() }
    }

    private fun toast(m: String) = android.widget.Toast.makeText(this, m, android.widget.Toast.LENGTH_SHORT).show()

    private fun parseTarget(s: String): Triple<String, String, Int>? {
        val t = s.trim(); val at = t.indexOf('@'); if (at <= 0 || at == t.length - 1) return null
        val user = t.substring(0, at); var host = t.substring(at + 1); var p = port
        val c = host.lastIndexOf(':'); if (c > 0) host.substring(c + 1).toIntOrNull()?.let { p = it; host = host.substring(0, c) }
        return if (host.isBlank()) null else Triple(user, host, p)
    }

    private fun obj(id: String, label: String, sub: String?, ico: String, dot: String?) = JSONObject().apply {
        put("id", id); put("label", label); if (sub != null) put("sub", sub); put("ico", ico); if (dot != null) put("dot", dot)
    }

    private fun q(s: String) = "'" + s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n").replace("\r", "") + "'"

    override fun onDestroy() { ssh?.close(); super.onDestroy() }
}
