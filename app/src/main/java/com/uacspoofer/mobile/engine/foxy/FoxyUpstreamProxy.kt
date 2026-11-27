/*
 * Ported from Se7en Pro (MIT) — Se7enPro/Services/Foxy/FoxyModels.cs (UpstreamProxySettings).
 * Copyright (c) 2026 kng7-p (King Network). MIT License.
 * Adapted for UAC SNI Spoofer Android (AGPLv3).
 */
package com.uacspoofer.mobile.engine.foxy

import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy

/**
 * Helpers for the optional user-supplied upstream proxy that sits in front of the Foxy edge
 * (Guardian / FxA / edge dials all honour it).
 *
 * Android's [Proxy] carries the endpoint only, so credentials are applied per-connection as a
 * `Proxy-Authorization` header. Base64 is hand-rolled because `java.util.Base64` needs API 26 and
 * `android.util.Base64` is unavailable to plain JVM unit tests.
 */
object FoxyUpstreamProxy {

    fun toProxy(settings: UpstreamProxySettings?): Proxy {
        if (settings?.enabled != true || settings.host.isBlank() || settings.port !in 1..65_535) {
            return Proxy.NO_PROXY
        }
        val type = if (settings.type.equals("http", ignoreCase = true)) Proxy.Type.HTTP else Proxy.Type.SOCKS
        return Proxy(type, InetSocketAddress(settings.host, settings.port))
    }

    /** Identity of the proxy, used to invalidate cached HTTP clients when settings change. */
    fun fingerprint(settings: UpstreamProxySettings?): String {
        if (settings?.enabled != true || settings.host.isBlank()) return "direct"
        val auth = if (settings.username.isNullOrEmpty()) "" else "|auth"
        return "${settings.type.lowercase()}://${settings.host}:${settings.port}$auth"
    }

    /** `Proxy-Authorization` value for the given proxy, or null when no credentials are set. */
    fun authHeader(settings: UpstreamProxySettings?): String? {
        if (settings?.enabled != true) return null
        val user = settings.username?.takeIf { it.isNotEmpty() } ?: return null
        val pass = settings.password ?: ""
        // Only HTTP proxies accept an explicit header; SOCKS5 does not use one.
        if (!settings.type.equals("http", ignoreCase = true)) return null
        return "Basic " + base64("$user:$pass".toByteArray(Charsets.UTF_8))
    }

    fun applyAuth(conn: HttpURLConnection, header: String?) {
        if (!header.isNullOrEmpty()) conn.setRequestProperty("Proxy-Authorization", header)
    }

    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    fun base64(data: ByteArray): String {
        if (data.isEmpty()) return ""
        val out = StringBuilder((data.size + 2) / 3 * 4)
        var i = 0
        while (i < data.size) {
            val b0 = data[i].toInt() and 0xFF
            val b1 = if (i + 1 < data.size) data[i + 1].toInt() and 0xFF else -1
            val b2 = if (i + 2 < data.size) data[i + 2].toInt() and 0xFF else -1
            out.append(ALPHABET[b0 shr 2])
            out.append(ALPHABET[((b0 and 0x03) shl 4) or (if (b1 >= 0) b1 shr 4 else 0)])
            out.append(
                when {
                    b1 >= 0 && b2 >= 0 -> ALPHABET[((b1 and 0x0F) shl 2) or (b2 shr 6)]
                    b1 >= 0 -> ALPHABET[(b1 and 0x0F) shl 2]
                    else -> '='
                }
            )
            out.append(if (b2 >= 0) ALPHABET[b2 and 0x3F] else '=')
            i += 3
        }
        return out.toString()
    }
}
