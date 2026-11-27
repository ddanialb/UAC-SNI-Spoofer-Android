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
    )

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
        val DEFAULT = FoxyEngineSettings()

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
