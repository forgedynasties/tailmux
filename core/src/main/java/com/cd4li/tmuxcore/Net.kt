package com.cd4li.tmuxcore

import java.net.Inet4Address
import java.net.NetworkInterface

object Net {
    /** True if [ip] is in Tailscale's 100.64.0.0/10 (CGNAT) range. */
    fun isTailscaleIp(ip: String): Boolean {
        if (!ip.startsWith("100.")) return false
        val second = ip.split(".").getOrNull(1)?.toIntOrNull() ?: return false
        return second in 64..127
    }

    /** True if this device currently has a Tailscale address (i.e. tailnet is up). */
    fun tailscaleUp(): Boolean {
        return try {
            NetworkInterface.getNetworkInterfaces().asSequence().any { nif ->
                nif.inetAddresses.asSequence().any {
                    it is Inet4Address && (it.hostAddress?.let(::isTailscaleIp) == true)
                }
            }
        } catch (_: Exception) { false }
    }
}
