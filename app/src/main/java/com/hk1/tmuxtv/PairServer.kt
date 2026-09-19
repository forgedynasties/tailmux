package com.hk1.tmuxtv

import fi.iki.elonen.NanoHTTPD

/**
 * Tiny local web server for phone-based token entry. The TV shows a QR/URL; the
 * user opens it on their phone and pastes the Tailscale token into a form. The
 * POST lands here and is handed to [onToken].
 */
class PairServer(port: Int, private val onToken: (String) -> Unit) : NanoHTTPD(port) {

    override fun serve(session: IHTTPSession): Response {
        if (session.method == Method.POST) {
            return try {
                val body = HashMap<String, String>()
                session.parseBody(body)
                val token = session.parameters["token"]?.firstOrNull()?.trim().orEmpty()
                if (token.isNotEmpty()) {
                    onToken(token)
                    html(DONE)
                } else {
                    html(page("Token was empty — go back and paste it again."))
                }
            } catch (e: Exception) {
                html(page("Error: ${e.message}"))
            }
        }
        return html(page(null))
    }

    private fun html(body: String) =
        newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", body)

    companion object {
        private fun page(msg: String?): String = """
            <!doctype html><html><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>TmuxTV pairing</title>
            <style>
              body{font-family:system-ui,-apple-system,Segoe UI,Roboto,sans-serif;
                   background:#16161e;color:#c0caf5;margin:0;padding:24px;
                   display:flex;min-height:100vh;box-sizing:border-box;align-items:center;justify-content:center}
              .card{background:#1b1d2c;border:1px solid #2a2e46;border-radius:16px;padding:28px;max-width:460px;width:100%}
              h1{font-size:20px;margin:0 0 6px}
              p{color:#8b93bd;font-size:14px;line-height:1.5;margin:0 0 18px}
              input{width:100%;box-sizing:border-box;font-size:16px;padding:14px;border-radius:10px;
                    border:1px solid #33467c;background:#0f1017;color:#c0caf5;margin-bottom:14px}
              button{width:100%;font-size:17px;font-weight:600;padding:14px;border:0;border-radius:10px;
                     background:linear-gradient(90deg,#7aa2f7,#bb9af7);color:#0b0f12}
              .msg{color:#9ece6a;margin-bottom:12px;font-size:14px}
              a{color:#7aa2f7}
            </style></head><body><div class="card">
            <h1>TmuxTV</h1>
            <p>Paste your Tailscale API access token to connect the TV to your tailnet.
               Create one at <a href="https://login.tailscale.com/admin/settings/keys" target="_blank">login.tailscale.com › Settings › Keys</a>.</p>
            ${if (msg != null) "<div class=\"msg\">$msg</div>" else ""}
            <form method="POST" action="/">
              <input name="token" placeholder="tskey-api-…" autofocus autocomplete="off"
                     autocapitalize="off" autocorrect="off" spellcheck="false">
              <button type="submit">Connect the TV</button>
            </form></div></body></html>
        """.trimIndent()

        private val DONE: String = """
            <!doctype html><html><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>Paired</title>
            <style>body{font-family:system-ui,sans-serif;background:#16161e;color:#9ece6a;
              display:flex;min-height:100vh;margin:0;align-items:center;justify-content:center;font-size:20px}</style>
            </head><body>✓ Token sent — check your TV</body></html>
        """.trimIndent()
    }
}
