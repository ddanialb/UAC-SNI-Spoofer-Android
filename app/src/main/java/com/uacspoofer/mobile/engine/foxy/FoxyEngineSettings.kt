/*
 * Ported from Se7en Pro (MIT) — Se7enPro/Models/UserSettings.cs (Firefox* fields)
 * Copyright (c) 2026 kng7-p (King Network). MIT License.
 * Adapted for UAC SNI Spoofer Android (AGPLv3).
 */
package com.uacspoofer.mobile.engine.foxy

data class FoxyEngineSettings(
    val serverCountry: String = "REC",
    val serverCity: String = "",
    val lastEdgeHost: String = "",
    val lastEdgePort: Int = 0,
    val customProxyEnabled: Boolean = false,
    val customProxyType: String = "socks5",
    val customProxyHost: String = "",
    val customProxyPort: Int = 1080,
    val customProxyUsername: String = "",
    val customProxyPassword: String = "",
    val dualSessionPool: Boolean = false,
    val socksPort: Int = 19_060,
    /** Off / sni / firstByte / record — how the TLS ClientHello is fragmented. */
    val helloSplit: String = HELLO_SPLIT_SNI,
    /**
     * Which address family the edge is dialled over: `auto` (app decides, IPv4 first),
     * `v4`, `v6` (IPv6 first, IPv4 as the fallback on networks without IPv6) or `both`.
     */
    val ipMode: String = IP_AUTO,
    val keepAlive: Boolean = true,
    val autoReconnect: Boolean = true,
    val edgeAttempts: Int = 6,
    /** Extra edge ports to try after the primary one (comma separated, empty = none). */
    val fallbackPorts: String = "",
) {
    fun validated(): FoxyEngineSettings = copy(
        serverCountry = serverCountry.trim().uppercase().ifBlank { "REC" },
        serverCity = serverCity.trim().uppercase(),
        lastEdgeHost = lastEdgeHost.trim(),
        lastEdgePort = lastEdgePort.coerceIn(0, 65_535),
        customProxyType = normalizeScheme(customProxyType),
        customProxyHost = customProxyHost.trim(),
        customProxyPort = if (customProxyPort in 1..65_535) customProxyPort else 1080,
        socksPort = if (socksPort in 1024..65_535) socksPort else 19_060,
        helloSplit = normalizeHelloSplit(helloSplit),
        ipMode = normalizeIpMode(ipMode),
        edgeAttempts = edgeAttempts.coerceIn(1, 12),
        fallbackPorts = fallbackPorts.split(',')
            .mapNotNull { it.trim().toIntOrNull()?.takeIf { port -> port in 1..65_535 } }
            .distinct()
            .joinToString(","),
    )

    fun helloSplitMode(): HelloSplitMode = when (helloSplit) {
        HELLO_SPLIT_FIRST_BYTE -> HelloSplitMode.FIRST_BYTE
        HELLO_SPLIT_RECORD -> HelloSplitMode.RECORD
        HELLO_SPLIT_OFF -> HelloSplitMode.OFF
        else -> HelloSplitMode.SNI
    }

    /** Edge ports for one host, primary first. */
    fun edgePorts(primary: Int): List<Int> =
        (listOf(primary) + fallbackPorts.split(',').mapNotNull { it.trim().toIntOrNull() })
            .filter { it in 1..65_535 }
            .distinct()

    fun upstreamProxy(): UpstreamProxySettings? {
        if (!customProxyEnabled || customProxyHost.isBlank()) return null
        return UpstreamProxySettings(
            enabled = true,
            type = normalizeScheme(customProxyType),
            host = customProxyHost.trim(),
            port = if (customProxyPort in 1..65_535) customProxyPort else 1080,
            username = customProxyUsername.ifBlank { null },
            password = customProxyPassword.ifBlank { null },
        )
    }

    companion object {
        const val HELLO_SPLIT_OFF = "off"
        const val HELLO_SPLIT_SNI = "sni"
        const val HELLO_SPLIT_FIRST_BYTE = "firstByte"
        const val HELLO_SPLIT_RECORD = "record"

        const val IP_AUTO = "auto"
        const val IP_V4 = "v4"
        const val IP_V6 = "v6"
        const val IP_BOTH = "both"
        val IP_MODES = setOf(IP_AUTO, IP_V4, IP_V6, IP_BOTH)

        val DEFAULT = FoxyEngineSettings()

        fun normalizeHelloSplit(raw: String?): String = when (raw?.trim()?.lowercase()) {
            HELLO_SPLIT_OFF, "none", "false" -> HELLO_SPLIT_OFF
            HELLO_SPLIT_FIRST_BYTE, "first_byte", "first-byte", "firstbyte" -> HELLO_SPLIT_FIRST_BYTE
            HELLO_SPLIT_RECORD -> HELLO_SPLIT_RECORD
            else -> HELLO_SPLIT_SNI
        }

        fun normalizeIpMode(raw: String?): String {
            val value = raw?.trim()?.lowercase().orEmpty()
            return when (value) {
                IP_V4, "ipv4", "4" -> IP_V4
                IP_V6, "ipv6", "6" -> IP_V6
                IP_BOTH, "any" -> IP_BOTH
                else -> IP_AUTO
            }
        }

        fun normalizeScheme(scheme: String?): String =
            if (scheme?.trim().equals("http", ignoreCase = true) || scheme?.trim().equals("https", ignoreCase = true)) "http" else "socks5"

        fun parsePortOrDefault(raw: String?): Int {
            val port = raw?.trim()?.toIntOrNull()
            return if (port != null && port in 1..65_535) port else 1080
        }

        /** Parses `socks5://user:pass@host:port` style input (Windows parity). */
        fun tryParseProxyInput(raw: String): ParsedProxyInput? {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return null
            val hasScheme = trimmed.contains("://")
            val hasCreds = trimmed.contains('@')
            val lastColon = trimmed.lastIndexOf(':')
            val hasPort = lastColon > 0 && trimmed.substring(lastColon + 1).toIntOrNull() in 1..65_535
            if (!hasScheme && !hasCreds && !hasPort) return null
            if (!hasScheme && trimmed.count { it == ':' } > 1 && !trimmed.startsWith("[")) return null
            val normalized = if (hasScheme) trimmed else "socks5://$trimmed"
            val uri = try {
                java.net.URI(normalized)
            } catch (_: Exception) {
                return null
            }
            val host = uri.host ?: return null
            val scheme = if (hasScheme) uri.scheme ?: "" else ""
            val port = if (uri.port > 0) uri.port else 0
            var user = ""
            var pass = ""
            val userInfo = uri.userInfo
            if (!userInfo.isNullOrEmpty()) {
                val idx = userInfo.indexOf(':')
                if (idx >= 0) {
                    user = java.net.URLDecoder.decode(userInfo.substring(0, idx), "UTF-8")
                    pass = java.net.URLDecoder.decode(userInfo.substring(idx + 1), "UTF-8")
                } else {
                    user = java.net.URLDecoder.decode(userInfo, "UTF-8")
                }
            }
            return ParsedProxyInput(scheme, host, port, user, pass)
        }
    }

    data class ParsedProxyInput(
        val scheme: String,
        val host: String,
        val port: Int,
        val username: String,
        val password: String,
    )
}
