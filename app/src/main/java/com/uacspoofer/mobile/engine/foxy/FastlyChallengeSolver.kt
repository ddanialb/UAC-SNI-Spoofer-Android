/*
 * Ported from Se7en Pro (MIT) — Se7enPro/Services/Foxy/FastlyChallengeSolver.cs
 * Copyright (c) 2026 kng7-p (King Network). MIT License.
 * Adapted for UAC SNI Spoofer Android (AGPLv3).
 * Uses HttpURLConnection + manual cookie jar (no new dependency).
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
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.regex.Pattern

/**
 * [org.json.JSONObject.optString] only accepts a [String] fallback, so the common Fastly pattern of
 * `optString("hmac", JSONObject.NULL)` does not type-check. This returns null when the key is absent
 * or explicitly JSON null, letting callers forward `JSONObject.NULL` themselves.
 */
private fun JSONObject.optStringOrNull(key: String): String? {
    if (!has(key) || isNull(key)) return null
    return optString(key)
}

class FastlyChallengeSolver(
    private val cookieJar: FoxyCookieJar,
    private val proxy: java.net.Proxy = java.net.Proxy.NO_PROXY,
    private val proxyAuthHeader: String? = null,
) {
    private val gate = Mutex()

    suspend fun solveAndInstall(challengedBody: String? = null, challengedOrigin: String? = null) =
        withContext(Dispatchers.IO) {
            gate.withLock {
                var lastError: Exception? = null
                if (!challengedBody.isNullOrBlank() && isChallengePage(challengedBody)) {
                    val origin = if (challengedOrigin.isNullOrBlank()) FXA_API_HOST else challengedOrigin.trimEnd('/')
                    try {
                        solveChallengeBody(origin, challengedBody)
                        return@withLock
                    } catch (e: Exception) {
                        lastError = e
                    }
                }
                for (baseUrl in listOf(FXA_SITE_URL, FXA_API_HOST)) {
                    try {
                        solveOnHost(baseUrl)
                        return@withLock
                    } catch (e: NoChallengePageException) {
                    } catch (e: Exception) {
                        lastError = lastError ?: e
                    }
                }
                throw lastError ?: NoChallengePageException()
            }
        }

    /** [PREFIX_REGEX] is a java.util.regex.Pattern, so it yields a Matcher, not a Kotlin MatchResult. */
    private fun findChallengePrefix(page: String): String? {
        val matcher = PREFIX_REGEX.matcher(page)
        return if (matcher.find()) matcher.group() else null
    }

    private fun solveChallengeBody(baseUrl: String, page: String) {
        val prefix = findChallengePrefix(page)
            ?: throw IllegalStateException("Fastly challenge prefix not found on $baseUrl.")
        val prefixUrl = baseUrl.trimEnd('/') + prefix
        val script = getText(prefixUrl + "/script.js?reload=true")
        completePostBackRounds(baseUrl.trimEnd('/'), prefixUrl, script, verifyProbe = false)
    }

    private fun solveOnHost(baseUrl: String) {
        val pageUrl = baseUrl + "/"
        var prefixUrl: String? = null
        var script: String? = null
        var fetchError: Exception? = null
        for (attempt in 1..3) {
            clearStateCookies()
            val page = getText(pageUrl)
            if (!isChallengePage(page)) throw NoChallengePageException()
            val prefix = findChallengePrefix(page)
            ?: throw IllegalStateException("Fastly challenge prefix not found on $baseUrl.")
            prefixUrl = baseUrl + prefix
            try {
                script = getText(prefixUrl + "/script.js?reload=true")
                fetchError = null
                break
            } catch (e: IllegalStateException) {
                if (attempt < 3 && e.message?.contains("HTTP 400") == true) fetchError = e else throw e
            }
        }
        if (fetchError != null || prefixUrl.isNullOrEmpty() || script.isNullOrEmpty()) {
            throw fetchError ?: IllegalStateException("Failed to load Fastly challenge script from $baseUrl.")
        }
        completePostBackRounds(baseUrl, prefixUrl, script, verifyProbe = true)
    }

    private fun completePostBackRounds(baseUrl: String, prefixUrl: String, script: String, verifyProbe: Boolean) {
        val pageUrl = baseUrl + "/"
        var (challenges, token) = parseChallengeInit(script)
        for (round in 0 until MAX_POST_BACK_ROUNDS) {
            val answers = JSONArray()
            for (challenge in challenges) answers.put(answerChallenge(prefixUrl, token, challenge))
            val payload = JSONObject().put("token", token).put("data", answers).toString()
            val (status, body) = post(prefixUrl + "/fst-post-back", payload)
            if (status !in 200..299) throw IllegalStateException("Fastly challenge post-back returned HTTP $status.")
            val doc = JSONObject(body)
            val statusStr = doc.optString("status", "")
            if (statusStr == "success") {
                if (verifyProbe) {
                    val probe = getText(pageUrl)
                    if (isChallengePage(probe)) throw IllegalStateException("Fastly challenge cookie was not accepted on this exit IP.")
                }
                copyCookiesToApiHost(pageUrl)
                return
            }
            if (!doc.has("ch") || !doc.has("tok") || doc.optString("tok", "").isEmpty()) {
                throw IllegalStateException("Unexpected Fastly challenge post-back response.")
            }
            challenges = parseChallenges(doc.getJSONArray("ch"))
            token = doc.getString("tok")
        }
        throw IllegalStateException("Fastly challenge did not complete within $MAX_POST_BACK_ROUNDS rounds.")
    }

    private fun clearStateCookies() {
        for (host in listOf(FXA_API_HOST, FXA_SITE_URL, "https://oauth.accounts.firefox.com")) {
            cookieJar.clearFastlyCookies(URL(host + "/"))
        }
    }

    private fun getText(url: String): String {
        val conn = open(url)
        conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        conn.setRequestProperty("User-Agent", SOLVER_USER_AGENT)
        conn.setRequestProperty("Accept-Encoding", "identity")
        val status = conn.responseCode
        val body = readBody(conn)
        cookieJar.captureCookies(conn, URL(url))
        if (status !in 200..299) {
            if (status == 406 && isChallengePage(body)) return body
            throw IllegalStateException("GET $url returned HTTP $status.")
        }
        return body
    }

    private fun post(url: String, json: String): Pair<Int, String> {
        val conn = open(url)
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Accept", "application/json")
        conn.setRequestProperty("User-Agent", SOLVER_USER_AGENT)
        conn.setRequestProperty("Origin", originOf(url))
        conn.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
        val status = conn.responseCode
        val body = readBody(conn)
        cookieJar.captureCookies(conn, URL(url))
        return status to body
    }

    private fun open(url: String): HttpURLConnection {
        val conn = URL(url).openConnection(proxy) as HttpURLConnection
        FoxyUpstreamProxy.applyAuth(conn, proxyAuthHeader)
        conn.connectTimeout = 30_000
        conn.readTimeout = 180_000
        conn.useCaches = false
        conn.instanceFollowRedirects = true
        cookieJar.applyCookies(conn, URL(url))
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

    private fun answerChallenge(prefixUrl: String, token: String, challenge: JSONObject): JSONObject {
        val type = challenge.optString("ty", "")
        val data = challenge.optJSONObject("data") ?: JSONObject()
        return when (type) {
            "pow" -> {
                val baseStr = data.optString("base", "")
                val hash = data.optString("hash", "")
                val answer = solvePow(baseStr, hash) ?: throw IllegalStateException("No proof-of-work solution found.")
                JSONObject().put("ty", "pow").put("base", baseStr).put("answer", answer)
                    .put("hmac", data.optStringOrNull("hmac") ?: JSONObject.NULL)
                    .put("expires", data.optStringOrNull("expires") ?: JSONObject.NULL)
            }
            "pat" -> JSONObject().put("ty", "pat").put("auth", fetchPat(prefixUrl, token))
            "clientmetrics" -> clientMetricsAnswer()
            "captcha" -> throw IllegalStateException(
                "Mozilla's sign-in now asks for an image CAPTCHA. Sign in once at accounts.firefox.com, then retry here.",
            )
            else -> throw IllegalStateException("Unsupported Fastly challenge type \"$type\".")
        }
    }

    private fun fetchPat(prefixUrl: String, token: String): String {
        val url = prefixUrl + "/pat?token=" + URLEncoder.encode(token, "UTF-8")
        val conn = open(url)
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Accept", "text/plain")
        conn.setRequestProperty("Origin", originOf(prefixUrl))
        conn.setRequestProperty("Content-Type", "application/json")
        conn.outputStream.use { it.write(ByteArray(0)) }
        val status = conn.responseCode
        val body = readBody(conn)
        if (status == 401 || status == 400) return ""
        if (status !in 200..299) throw IllegalStateException("Fastly PAT request returned HTTP $status.")
        val doc = JSONObject(body.ifBlank { "{}" })
        val auth = doc.optString("auth", "")
        if (auth.isEmpty()) throw IllegalStateException("Fastly PAT response carried no auth token.")
        return auth
    }

    private fun clientMetricsAnswer(): JSONObject = JSONObject()
        .put("ty", "clientmetrics")
        .put("webdriver", false)
        .put("bot_detection_result", JSONObject().put("bot_detected", false).put("bot_kind", JSONObject.NULL))
        .put("browser_metrics", JSONObject().put("client_data", "{}").put("error_trace", JSONObject.NULL))
        .put("detector_results", JSONObject())
        .put("v", 2)

    private fun copyCookiesToApiHost(pageUrl: String) {
        val pageUri = URL(pageUrl)
        val pageCookies = cookieJar.getCookies(pageUri)
        if (pageCookies.isEmpty()) throw IllegalStateException("Fastly challenge completed but no cookies were issued.")
        for (target in listOf(FXA_SITE_URL + "/", FXA_API_HOST + "/", "https://oauth.accounts.firefox.com/")) {
            val targetUri = URL(target)
            if (targetUri.host.equals(pageUri.host, ignoreCase = true)) continue
            for (c in pageCookies) {
                if (c.expired) continue
                cookieJar.addCookie(targetUri, c.name, c.value, "/", targetUri.host, secure = true, httpOnly = c.httpOnly)
            }
        }
    }

    companion object {
        const val SOLVER_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:158.0) Gecko/20100101 Firefox/158.0"
        private const val POW_ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        private const val FXA_API_HOST = "https://api.accounts.firefox.com"
        private const val FXA_SITE_URL = "https://accounts.firefox.com"
        private const val MAX_POST_BACK_ROUNDS = 8
        private val PREFIX_REGEX = Pattern.compile("/_fs-ch-[A-Za-z0-9]+")
        private val INIT_CALL_REGEX = Pattern.compile("init\\((\\[[^\\]]*\\]),\\s*\"([^\"]+)\",\\s*\"([^\"]+)\"")

        fun isChallengePage(body: String): Boolean =
            body.contains("/_fs-ch-") && body.contains("Client Challenge")

        fun originOf(url: String): String {
            val idx = url.indexOf("/_fs-ch-")
            return if (idx > 0) url.substring(0, idx) else url
        }

        fun parseChallengeInit(script: String): Pair<List<JSONObject>, String> {
            val matches = INIT_CALL_REGEX.matcher(script)
            var last: java.util.regex.Matcher? = null
            while (matches.find()) last = matches
            val m = last ?: throw IllegalStateException("Fastly challenge init call not found in the challenge script.")
            val challenges = parseChallenges(JSONArray(m.group(1)))
            if (challenges.isEmpty()) throw IllegalStateException("Fastly challenge list is empty.")
            return challenges to (m.group(2) ?: "")
        }

        fun parseChallenges(array: JSONArray): List<JSONObject> {
            val list = mutableListOf<JSONObject>()
            for (i in 0 until array.length()) list.add(array.getJSONObject(i))
            return list
        }

        fun solvePow(baseStr: String, targetHex: String): String? {
            val target = try {
                targetHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            } catch (_: Exception) {
                return null
            }
            if (target.size != 32) return null
            val baseBytes = baseStr.toByteArray(Charsets.UTF_8)
            val buffer = ByteArray(baseBytes.size + 2)
            System.arraycopy(baseBytes, 0, buffer, 0, baseBytes.size)
            val prefixLength = baseBytes.size
            val digest = MessageDigest.getInstance("SHA-256")
            for (a in POW_ALPHABET) {
                buffer[prefixLength] = a.code.toByte()
                for (b in POW_ALPHABET) {
                    buffer[prefixLength + 1] = b.code.toByte()
                    val hash = digest.digest(buffer)
                    if (hash.contentEquals(target)) return "$a$b"
                }
            }
            return null
        }
    }

    private class NoChallengePageException : Exception("Host did not serve a Fastly challenge page.")
}

/** Minimal cookie jar mirroring Windows CookieContainer behavior for FxA/Fastly. */
class FoxyCookieJar {
    internal data class StoredCookie(
        val name: String, val value: String, val path: String, val domain: String,
        val secure: Boolean, val httpOnly: Boolean, val expiresAt: Long,
    ) {
        val expired: Boolean get() = expiresAt < System.currentTimeMillis()
    }

    private val cookies = mutableListOf<StoredCookie>()

    @Synchronized
    fun applyCookies(conn: HttpURLConnection, url: URL) {
        val now = System.currentTimeMillis()
        cookies.removeAll { it.expired }
        val matching = cookies.filter {
            (url.host.equals(it.domain, ignoreCase = true) || url.host.endsWith("." + it.domain, ignoreCase = true)) &&
                url.path.startsWith(it.path)
        }
        if (matching.isNotEmpty()) {
            conn.setRequestProperty("Cookie", matching.joinToString("; ") { "${it.name}=${it.value}" })
        }
    }

    @Synchronized
    fun captureCookies(conn: HttpURLConnection, url: URL) {
        val headers = conn.headerFields["Set-Cookie"] ?: return
        for (raw in headers) {
            if (raw.isBlank()) continue
            val parts = raw.split(";")
            val first = parts[0].trim()
            val eq = first.indexOf('=')
            if (eq <= 0) continue
            val name = first.substring(0, eq).trim()
            val value = first.substring(eq + 1).trim()
            var path = "/"
            var domain = url.host
            var httpOnly = false
            var maxAge: Long? = null
            for (i in 1 until parts.size) {
                val attr = parts[i].trim()
                when {
                    attr.equals("httponly", ignoreCase = true) -> httpOnly = true
                    attr.startsWith("path=", ignoreCase = true) -> path = attr.substring(5).trim().ifEmpty { "/" }
                    attr.startsWith("domain=", ignoreCase = true) -> domain = attr.substring(7).trim().trimStart('.').ifEmpty { url.host }
                    attr.startsWith("max-age=", ignoreCase = true) -> maxAge = attr.substring(8).trim().toLongOrNull()
                }
            }
            if (!url.host.equals(domain, ignoreCase = true) && !url.host.endsWith("." + domain, ignoreCase = true)) {
                domain = url.host
            }
            val isFastly = name.startsWith("_fs_ch_", ignoreCase = true)
            val mirrors = if (isFastly) {
                listOf("accounts.firefox.com", "api.accounts.firefox.com", "oauth.accounts.firefox.com")
            } else listOf(url.host)
            val expires = if (maxAge != null && maxAge > 600) System.currentTimeMillis() + maxAge * 1000 else System.currentTimeMillis() + 10 * 60 * 1000
            if (maxAge != null && maxAge <= 0 && value.isEmpty()) {
                cookies.removeAll { it.name.equals(name, ignoreCase = true) && it.domain.equals(domain, ignoreCase = true) }
                continue
            }
            cookies.removeAll { it.name.equals(name, ignoreCase = true) && it.domain.equals(domain, ignoreCase = true) }
            for (mirror in mirrors) {
                cookies.add(StoredCookie(name, value, "/", mirror, secure = true, httpOnly = httpOnly, expiresAt = expires))
            }
        }
    }

    @Synchronized
    internal fun getCookies(url: URL): List<StoredCookie> {
        val now = System.currentTimeMillis()
        cookies.removeAll { it.expired }
        return cookies.filter {
            (url.host.equals(it.domain, ignoreCase = true) || url.host.endsWith("." + it.domain, ignoreCase = true))
        }.map { StoredCookie(it.name, it.value, it.path, it.domain, it.secure, it.httpOnly, it.expiresAt) }
    }

    @Synchronized
    fun addCookie(url: URL, name: String, value: String, path: String, domain: String, secure: Boolean, httpOnly: Boolean) {
        cookies.removeAll { it.name.equals(name, ignoreCase = true) && it.domain.equals(domain, ignoreCase = true) }
        cookies.add(StoredCookie(name, value, path, domain, secure, httpOnly, System.currentTimeMillis() + 30 * 60 * 1000))
    }

    @Synchronized
    fun clearFastlyCookies(url: URL) {
        cookies.removeAll { it.name.startsWith("_fs_ch_", ignoreCase = true) }
    }

    // Expose for tests.
    internal data class CookieView(val name: String, val value: String, val httpOnly: Boolean, val expired: Boolean)
    internal fun viewCookies(url: URL): List<CookieView> =
        getCookies(url).map { CookieView(it.name, it.value, it.httpOnly, it.expired) }
}
