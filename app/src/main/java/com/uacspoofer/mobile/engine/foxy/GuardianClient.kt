/*
 * Ported from Se7en Pro (MIT) — Se7enPro/Services/Foxy/GuardianClient.cs
 * Copyright (c) 2026 kng7-p (King Network). MIT License.
 * Adapted for UAC SNI Spoofer Android (AGPLv3).
 */
package com.uacspoofer.mobile.engine.foxy

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64

class GuardianClient(
    private val proxy: java.net.Proxy = java.net.Proxy.NO_PROXY,
    seedCountries: List<VpnCountry>? = null,
    private val proxyAuthHeader: String? = null,
) {
    private val serverListLock = Mutex()
    @Volatile private var cachedCountries: List<VpnCountry>? = seedCountries

    val cachedCountriesOrFallback: List<VpnCountry>
        get() = cachedCountries ?: getBuiltInFallbackCountries()

    suspend fun activate(accessToken: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val conn = open(URL(GUARDIAN_BASE + "/api/v1/fpn/activate"))
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $accessToken")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(ByteArray(0)) }
            val status = conn.responseCode
            readBody(conn)
            status in 200..299
        } catch (_: Exception) {
            false
        }
    }

    suspend fun fetchProxyPass(accessToken: String): ProxyPassResult = withContext(Dispatchers.IO) {
        fetchProxyPassCore(accessToken, allowAutoActivate = true)
    }

    private fun fetchProxyPassCore(accessToken: String, allowAutoActivate: Boolean): ProxyPassResult {
        val conn = open(URL(GUARDIAN_BASE + "/api/v1/fpn/token"))
        conn.setRequestProperty("User-Agent", USER_AGENT)
        conn.setRequestProperty("Accept", "application/json")
        conn.setRequestProperty("Authorization", "Bearer $accessToken")
        val status = conn.responseCode
        if (status == 429) throw FoxyQuotaExceededException("Account monthly traffic quota exceeded.")
        if (status == 401 || status == 403) {
            if (allowAutoActivate) {
                // Activate is suspend; run blocking (called from IO dispatcher).
                val activated = kotlinx.coroutines.runBlocking(Dispatchers.IO) { activate(accessToken) }
                if (activated) return fetchProxyPassCore(accessToken, allowAutoActivate = false)
            }
            throw FoxyAuthTokenInvalidException("Access token rejected by Mozilla Guardian.")
        }
        val json = readBody(conn)
        if (status !in 200..299) throw IllegalStateException("Guardian token request failed (HTTP $status).")
        val doc = JSONObject(json)
        val token = doc.getString("token")
        val expiresAt = if (doc.has("expires_at") && doc.optLong("expires_at", 0L) > 0) {
            doc.getLong("expires_at")
        } else {
            extractJwtExpiry(token) ?: (System.currentTimeMillis() / 1000 + 900)
        }
        return ProxyPassResult(
            token = token,
            expiresAtEpochSeconds = expiresAt,
            quotaLimit = readQuotaHeader(conn, "X-Quota-Limit"),
            quotaRemaining = readQuotaHeader(conn, "X-Quota-Remaining"),
            quotaReset = readQuotaHeader(conn, "X-Quota-Reset"),
        )
    }

    private fun readQuotaHeader(conn: HttpURLConnection, name: String): Long? =
        conn.getHeaderField(name)?.toLongOrNull()

    suspend fun fetchCountries(): List<VpnCountry> = withContext(Dispatchers.IO) {
        cachedCountries?.takeIf { it.isNotEmpty() }?.let { return@withContext it }
        serverListLock.withLock {
            cachedCountries?.takeIf { it.isNotEmpty() }?.let { return@withLock it }
            try {
                val conn = open(URL(REMOTE_SETTINGS_URL))
                conn.setRequestProperty("User-Agent", USER_AGENT)
                conn.setRequestProperty("Accept", "application/json")
                val status = conn.responseCode
                val json = readBody(conn)
                if (status !in 200..299) throw IllegalStateException("Remote Settings HTTP $status")
                val list = parseServerListJson(json)
                cachedCountries = list
                list
            } catch (_: Exception) {
                val fallback = getBuiltInFallbackCountries()
                cachedCountries = fallback
                fallback
            }
        }
    }

    private fun open(url: URL): HttpURLConnection {
        val conn = url.openConnection(proxy) as HttpURLConnection
        FoxyUpstreamProxy.applyAuth(conn, proxyAuthHeader)
        conn.connectTimeout = 15_000
        conn.readTimeout = 20_000
        conn.useCaches = false
        conn.instanceFollowRedirects = true
        return conn
    }

    private fun readBody(conn: HttpURLConnection): String {
        val stream = try {
            conn.inputStream
        } catch (_: Exception) {
            conn.errorStream ?: return ""
        }
        return stream.bufferedReader().use { it.readText() }
    }

    companion object {
        private const val GUARDIAN_BASE = "https://vpn.mozilla.org"
        private const val REMOTE_SETTINGS_URL =
            "https://firefox.settings.services.mozilla.com/v1/buckets/main/collections/vpn-serverlist/records"
        private const val USER_AGENT = "MozillaVPN/2.35.0 (sys:linux; iap:true)"

        fun extractJwtExpiry(token: String): Long? {
            return try {
                val parts = token.split(".")
                if (parts.size < 2) return null
                var payload = parts[1].replace('-', '+').replace('_', '/')
                payload += when (payload.length % 4) {
                    2 -> "=="
                    3 -> "="
                    else -> ""
                }
                val doc = JSONObject(String(Base64.getDecoder().decode(payload), Charsets.UTF_8))
                if (doc.has("exp") && doc.optLong("exp", 0L) > 0) doc.getLong("exp") else null
            } catch (_: Exception) {
                null
            }
        }

        fun parseServerListJson(json: String): List<VpnCountry> {
            val countries = mutableListOf<VpnCountry>()
            val doc = JSONObject(json)
            if (!doc.has("data")) return countries
            val data = doc.getJSONArray("data")
            for (r in 0 until data.length()) {
                val record = data.getJSONObject(r)
                val name = record.optString("name", "")
                val code = record.optString("code", "")
                if (name.equals("CatchAll Anycast", ignoreCase = true)) continue
                val cities = mutableListOf<VpnCity>()
                if (record.has("cities")) {
                    val cityArray = record.getJSONArray("cities")
                    for (c in 0 until cityArray.length()) {
                        val city = cityArray.getJSONObject(c)
                        val cityName = city.optString("name", "")
                        val cityCode = city.optString("code", "")
                        val servers = mutableListOf<VpnServerNode>()
                        if (city.has("servers")) {
                            val serverArray = city.getJSONArray("servers")
                            for (s in 0 until serverArray.length()) {
                                val server = serverArray.getJSONObject(s)
                                val hostname = server.optString("hostname", "")
                                val port = if (server.has("port")) server.optInt("port", 2499) else 2499
                                val quarantined = server.optBoolean("quarantined", false)
                                if (hostname.isNotEmpty() && !quarantined) {
                                    servers.add(VpnServerNode(hostname, if (port > 0) port else 2499, quarantined))
                                }
                            }
                        }
                        if (servers.isNotEmpty()) cities.add(VpnCity(cityName, cityCode, servers))
                    }
                }
                if (code.isNotBlank() && cities.isNotEmpty()) countries.add(VpnCountry(name, code, cities))
            }
            return countries
        }

        fun getCandidates(countries: List<VpnCountry>, countryCode: String? = null, cityCode: String? = null): List<ProxyCandidate> {
            val candidates = mutableListOf<ProxyCandidate>()
            for (country in countries) {
                val wantsAll = countryCode.isNullOrEmpty() ||
                    countryCode.equals("REC", ignoreCase = true) ||
                    countryCode.equals("AUTO", ignoreCase = true)
                if (!wantsAll && !country.code.equals(countryCode, ignoreCase = true)) continue
                for (city in country.cities) {
                    if (!cityCode.isNullOrEmpty() && !city.code.equals(cityCode, ignoreCase = true)) continue
                    for (server in city.servers) {
                        if (!server.quarantined) {
                            candidates.add(ProxyCandidate(server.hostname, server.port, country.code, country.name, city.code))
                        }
                    }
                }
            }
            if (candidates.isEmpty() && !cityCode.isNullOrEmpty()) return getCandidates(countries, countryCode)
            if (candidates.isEmpty()) {
                candidates.add(ProxyCandidate("p.m1.fastly-masque.net", 2499, "REC", "Recommended", ""))
            }
            return candidates
        }

        fun orderCandidates(
            candidates: List<ProxyCandidate>,
            preferredHost: String?,
            preferredPort: Int,
            avoidHosts: Collection<String>? = null,
        ): List<ProxyCandidate> {
            val ordered = mutableListOf<ProxyCandidate>()
            val seen = mutableSetOf<String>()
            fun avoided(c: ProxyCandidate): Boolean =
                avoidHosts != null && avoidHosts.isNotEmpty() && avoidHosts.any { it.equals(c.host, ignoreCase = true) }
            if (!preferredHost.isNullOrBlank()) {
                for (candidate in candidates) {
                    if (candidate.host.equals(preferredHost, ignoreCase = true) &&
                        (preferredPort <= 0 || candidate.port == preferredPort) &&
                        !avoided(candidate) && seen.add(candidate.host)
                    ) {
                        ordered.add(candidate)
                    }
                }
            }
            for (candidate in candidates) {
                if (!avoided(candidate) && seen.add(candidate.host)) ordered.add(candidate)
            }
            if (!avoidHosts.isNullOrEmpty()) {
                for (candidate in candidates) {
                    if (avoided(candidate) && seen.add(candidate.host)) ordered.add(candidate)
                }
            }
            return ordered
        }

        /**
         * Offline copy of Mozilla's vpn-serverlist (Remote Settings), regenerated from
         * https://firefox.settings.services.mozilla.com/v1/buckets/main/collections/vpn-serverlist/records
         * so every country Foxy can use is selectable even without a network round-trip.
         */
        fun getBuiltInFallbackCountries(): List<VpnCountry> = listOf(
            country("Recommended", "REC", "Fastest Edge", "REC", "p.m1.fastly-masque.net", 2499),
            country("Argentina", "AR", "Argentina", "EZE", "eze223.m1.fastly-masque.net", 2499),
            country("Australia", "AU", "Australia", "YSBK", "ysbk106.m1.fastly-masque.net", 2499),
            country("Austria", "AT", "Austria", "VIE", "vie63.m1.fastly-masque.net", 2499),
            country("Belgium", "BE", "Belgium", "BRU", "bru148.m1.fastly-masque.net", 2499),
            country("Brazil", "BR", "Brazil", "SBSP", "sbsp209.m1.fastly-masque.net", 2499),
            country("Bulgaria", "BG", "Bulgaria", "SOF", "sof151.m1.fastly-masque.net", 2499),
            country("Canada", "CA", "Canada", "CA", "ca.m1.fastly-masque.net", 2499),
            country("CatchAll Anycast", "US", "USA", "Q30", "p.m1.fastly-masque.net", 2499),
            country("Chile", "CL", "Chile", "SCL", "scl222.m1.fastly-masque.net", 2499),
            country("Colombia", "CO", "Colombia", "SKBO", "skbo234.m1.fastly-masque.net", 2499),
            country("Denmark", "DK", "Denmark", "CPH", "cph232.m1.fastly-masque.net", 2499),
            country("Finland", "FI", "Finland", "HEL", "hel141.m1.fastly-masque.net", 2499),
            country("France", "FR", "France", "LFPB", "lfpb115.m1.fastly-masque.net", 2499),
            country("Germany", "DE", "Germany", "MUC", "muc139.m1.fastly-masque.net", 2499),
            country("Ghana", "GH", "Ghana", "ACC", "acc96.m1.fastly-masque.net", 2499),
            country("Ireland", "IE", "Ireland", "DUB", "dub43.m1.fastly-masque.net", 2499),
            country("Italy", "IT", "Italy", "LIME", "lime121.m1.fastly-masque.net", 2499),
            country("Japan", "JP", "Japan", "RJTF", "rjtf770.m1.fastly-masque.net", 2499),
            country("Korea", "KR", "Korea", "ICN", "icn145.m1.fastly-masque.net", 2499),
            country("Malaysia", "MY", "Malaysia", "KUL", "kul98.m1.fastly-masque.net", 2499),
            country("Mexico", "MX", "Mexico", "MMQT", "mmqt107.m1.fastly-masque.net", 2499),
            country("Netherlands", "NL", "Netherlands", "EHRD", "ehrd229.m1.fastly-masque.net", 2499),
            country("New Zealand", "NZ", "New Zealand", "AKL", "akl103.m1.fastly-masque.net", 2499),
            country("Norway", "NO", "Norway", "OSL", "osl65.m1.fastly-masque.net", 2499),
            country("Peru", "PE", "Peru", "SPJC", "spjc128.m1.fastly-masque.net", 2499),
            country("Philippines", "PH", "Philippines", "MNL", "mnl215.m1.fastly-masque.net", 2499),
            country("Portugal", "PT", "Portugal", "LIS", "lis149.m1.fastly-masque.net", 2499),
            country("Recomended Location", "REC", "Recomended City", "REC", "p.m1.fastly-masque.net", 2499),
            country("Singapore", "SG", "Singapore", "WSAT", "wsat188.m1.fastly-masque.net", 2499),
            country("South Africa", "ZA", "South Africa", "FAOR", "faor73.m1.fastly-masque.net", 2499),
            country("Spain", "ES", "Spain", "LETO", "leto235.m1.fastly-masque.net", 2499),
            country("Sweden", "SE", "Sweden", "ESSB", "essb127.m1.fastly-masque.net", 2499),
            country("Thailand", "TH", "Thailand", "BKK", "bkk228.m1.fastly-masque.net", 2499),
            country("United Kingdom", "GB", "United Kingdom", "EGLC", "eglc860.m1.fastly-masque.net", 2499),
            country("United States", "US", "United States", "US", "us.m1.fastly-masque.net", 2499),
        )

        private fun country(
            name: String,
            code: String,
            cityName: String,
            cityCode: String,
            host: String,
            port: Int,
        ) = VpnCountry(name, code, listOf(VpnCity(cityName, cityCode, listOf(VpnServerNode(host, port)))))
    }
}
