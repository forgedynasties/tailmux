package com.hk1.tmuxtv

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** A tailnet device we can offer to connect to. */
data class Host(
    val name: String,
    val ip: String,
    val os: String,
    val online: Boolean
)

object TailscaleApi {

    /**
     * List tailnet devices via the Tailscale API. [token] is a personal API
     * access token (tskey-api-…) or OAuth token. Uses the "-" tailnet alias so
     * it works for whatever tailnet the token belongs to — no hard-coded hub.
     *
     * The devices endpoint has no live "online" flag, so we approximate it from
     * lastSeen (seen within the last 5 minutes). Blocking; call off the UI thread.
     */
    fun listDevices(token: String): List<Host> {
        val url = URL("https://api.tailscale.com/api/v2/tailnet/-/devices")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Authorization", "Bearer $token")
            connectTimeout = 12000
            readTimeout = 12000
        }
        try {
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                throw RuntimeException("Tailscale API $code: ${body.take(160)}")
            }
            return parse(body)
        } finally {
            conn.disconnect()
        }
    }

    private fun parse(json: String): List<Host> {
        val out = ArrayList<Host>()
        val devices = JSONObject(json).optJSONArray("devices") ?: return out
        val now = System.currentTimeMillis()
        for (i in 0 until devices.length()) {
            val d = devices.optJSONObject(i) ?: continue
            val ip = firstV4(d) ?: continue
            val name = d.optString("hostname").ifBlank { d.optString("name") }
            val os = d.optString("os", "")
            val lastSeenMs = parseInstant(d.optString("lastSeen"))
            val online = lastSeenMs > 0 && (now - lastSeenMs) < 5 * 60 * 1000
            out.add(Host(name = name, ip = ip, os = os, online = online))
        }
        // online first, then alphabetical
        return out.sortedWith(compareByDescending<Host> { it.online }.thenBy { it.name.lowercase() })
    }

    private fun firstV4(d: JSONObject): String? {
        val addrs = d.optJSONArray("addresses") ?: return null
        for (i in 0 until addrs.length()) {
            val a = addrs.optString(i)
            if (a.startsWith("100.") || (a.count { it == '.' } == 3 && !a.contains(":"))) return a
        }
        return null
    }

    /** Parse the leading "yyyy-MM-dd'T'HH:mm:ss" of an ISO instant as UTC. */
    private fun parseInstant(s: String): Long {
        if (s.length < 19) return 0
        return try {
            val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
            fmt.timeZone = TimeZone.getTimeZone("UTC")
            (fmt.parse(s.substring(0, 19)) ?: Date(0)).time
        } catch (_: Exception) {
            0
        }
    }
}
