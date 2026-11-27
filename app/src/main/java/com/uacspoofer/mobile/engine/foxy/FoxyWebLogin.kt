/*
 * In-app browser sign-in for Mozilla VPN (Foxy engine).
 *
 * Mozilla's own clients authenticate with the OAuth 2.0 authorisation-code flow and
 * PKCE (RFC 8252 / RFC 7636) instead of posting an email + password to the FxA API:
 *
 *   1. build the Guardian login page for platform "android" with a PKCE challenge,
 *   2. the user signs in there (including "continue with Google/Apple"),
 *   3. Guardian redirects to mozilla-vpn://login/success?code=…,
 *   4. exchange {code, code_verifier} at /api/v2/vpn/login/verify for a Guardian JWT.
 *
 * The JWT is what Guardian expects on its fpn-token and vpn endpoints, so the
 * engine no longer needs the user's password at all.
 */
package com.uacspoofer.mobile.engine.foxy

import com.uacspoofer.mobile.logging.AppLogRepository
import com.uacspoofer.mobile.logging.LogSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom

/** A PKCE verifier and its S256 challenge. */
data class FoxyPkce(val verifier: String, val challenge: String)

/** Outcome of an in-app sign-in attempt. */
data class FoxyWebLoginResult(
    val success: Boolean,
    val account: FoxyAccount? = null,
    val errorMessage: String = "",
)

object FoxyWebLogin {
    const val API_BASE = "https://vpn.mozilla.org"

    const val DEEP_LINK_SCHEME = "mozilla-vpn"
    const val DEEP_LINK_HOST = "login"
    const val DEEP_LINK_PATH = "/success"

    /** Sent by the app itself when talking to Guardian (matches Mozilla's Android client). */
    const val API_USER_AGENT = "FirefoxPrivateNetworkVPN/2.21.0 (Android; arm64-v8a)"

    /**
     * User agent used inside the login WebView. Chrome-like and without the "wv" token so
     * that Google/Apple sign-in inside the FxA page is not rejected.
     */
    const val BROWSER_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/126.0.6478.122 Mobile Safari/537.36"

    private const val LOGIN_PATH = "/api/v2/vpn/login/android"
    private const val VERIFY_PATH = "/api/v2/vpn/login/verify"
    private const val ACCOUNT_PATH = "/api/v1/vpn/account"

    private const val VERIFIER_CHARS =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"

    fun newPkce(random: SecureRandom = SecureRandom()): FoxyPkce {
        val chars = CharArray(VERIFIER_LENGTH)
        for (i in chars.indices) chars[i] = VERIFIER_CHARS[random.nextInt(VERIFIER_CHARS.length)]
        val verifier = String(chars)
        return FoxyPkce(verifier = verifier, challenge = challengeFor(verifier))
    }

    /** S256 challenge: base64url(sha256(verifier)) without padding. */
    fun challengeFor(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(verifier.toByteArray(StandardCharsets.US_ASCII))
        return base64Url(digest)
    }

    fun loginUrl(
        challenge: String,
        userAgent: String = API_USER_AGENT,
        email: String? = null,
    ): String {
        val sb = StringBuilder(API_BASE)
            .append(LOGIN_PATH)
            .append("?code_challenge=").append(percentEncode(challenge))
            .append("&code_challenge_method=S256")
            .append("&user_agent=").append(percentEncode(userAgent))
        if (!email.isNullOrBlank()) sb.append("&email=").append(percentEncode(email.trim()))
        return sb.toString()
    }

    /** True when [rawUrl] is the redirect Guardian sends once the user has signed in. */
    fun isLoginCallback(rawUrl: String?): Boolean {
        if (rawUrl.isNullOrBlank()) return false
        return try {
            val uri = java.net.URI(rawUrl)
            val scheme = uri.scheme?.lowercase() ?: return false
            if (scheme == DEEP_LINK_SCHEME) {
                uri.host.equals(DEEP_LINK_HOST, ignoreCase = true) &&
                    (uri.path.isNullOrEmpty() || uri.path == DEEP_LINK_PATH)
            } else {
                val path = uri.path ?: return false
                val host = uri.host.orEmpty()
                when {
                    path.endsWith("/vpn/client/login/success") -> true
                    path.endsWith("/login/success") -> true
                    // Guardian bounces the browser through https://vpn.mozilla.org/oauth/success
                    // before handing the code to the app; either hop carries the same code.
                    path.endsWith("/oauth/success") -> extractAuthCode(rawUrl) != null
                    host.endsWith("mozilla.org") && path.endsWith("/success") -> extractAuthCode(rawUrl) != null
                    else -> false
                }
            }
        } catch (_: Exception) {
            rawUrl.startsWith("$DEEP_LINK_SCHEME://$DEEP_LINK_HOST")
        }
    }

    /** Pulls the `code` parameter out of the deep link / redirect URL. */
    fun extractAuthCode(rawUrl: String?): String? {
        if (rawUrl.isNullOrBlank()) return null
        val query = rawUrl.substringAfter('?', "").substringBefore('#')
        val fragment = if ('#' in rawUrl) rawUrl.substringAfter('#') else ""
        for (candidate in listOf(query, fragment)) {
            if (candidate.isEmpty()) continue
            extractParam(candidate, "code")?.let { return it }
        }
        return null
    }

    private fun extractParam(query: String, name: String): String? {
        for (pair in query.split('&')) {
            val idx = pair.indexOf('=')
            if (idx <= 0) continue
            val key = pair.substring(0, idx)
            if (key == name) {
                return percentDecode(pair.substring(idx + 1)).takeIf { it.isNotBlank() }
            }
        }
        return null
    }

    /**
     * Exchanges the authorisation code for a Guardian session token and enriches the
     * account with the profile/quota Guardian reports.
     */
    suspend fun signIn(code: String, verifier: String): FoxyWebLoginResult = withContext(Dispatchers.IO) {
        AppLogRepository.info(LogSource.APP, "Foxy: exchanging Mozilla authorisation code (\${code.take(6)}…, ${code.length} chars)")
        try {
            val payload = JSONObject()
                .put("code", code)
                .put("code_verifier", verifier)
                .toString()
            val (status, body) = postJson(VERIFY_PATH, payload)
            if (status !in 200..299) {
                val message = describeError(body, status, "Sign-in was rejected by Mozilla Guardian.")
                AppLogRepository.warning(LogSource.APP, "Foxy: /login/verify failed — HTTP $status — ${body.take(240)}")
                return@withContext FoxyWebLoginResult(success = false, errorMessage = message)
            }
            val doc = JSONObject(body.ifBlank { "{}" })
            val token = doc.optString("token", "")
            if (token.isBlank()) {
                return@withContext FoxyWebLoginResult(
                    success = false,
                    errorMessage = "Mozilla did not return a session token. Try signing in again.",
                )
            }
            val user = doc.optJSONObject("user")
            val account = FoxyAccount(
                email = user?.optString("email").orEmpty(),
                accessToken = token,
                refreshToken = "",
                sessionToken = "",
                authMethod = FoxyAccount.AUTH_WEB,
                tokenExpiryEpochSeconds = GuardianClient.extractJwtExpiry(token)
                    ?: (System.currentTimeMillis() / 1000 + DEFAULT_TOKEN_LIFETIME_SECONDS),
            )
            AppLogRepository.success(
                LogSource.APP,
                "Foxy: Mozilla sign-in OK for ${account.email.ifBlank { "(no email in response)" }}; token valid ${account.tokenExpiryEpochSeconds}s epoch",
            )
            enrichFromGuardian(account)
            FoxyWebLoginResult(success = true, account = account)
        } catch (e: Exception) {
            AppLogRepository.error(LogSource.APP, "Foxy: sign-in threw ${e.javaClass.simpleName}: ${e.message}")
            FoxyWebLoginResult(success = false, errorMessage = e.message ?: "Sign-in failed.")
        }
    }

    /** Fills in email/plan/quota from Guardian. Failures never abort a successful login. */
    private suspend fun enrichFromGuardian(account: FoxyAccount) {
        try {
            val conn = open(URL(API_BASE + ACCOUNT_PATH))
            conn.setRequestProperty("Authorization", "Bearer ${account.accessToken}")
            conn.setRequestProperty("Accept", "application/json")
            val status = conn.responseCode
            val body = readBody(conn)
            if (status in 200..299) {
                val doc = JSONObject(body.ifBlank { "{}" })
                val email = doc.optString("email", "")
                if (email.isNotBlank()) account.email = email
                val active = doc.optJSONObject("subscriptions")
                    ?.optJSONObject("vpn")
                    ?.optBoolean("active", false) ?: false
                if (!active) {
                    account.quotaErrorMessage = "This Firefox account has no active Mozilla VPN subscription."
                }
                doc.optInt("max_devices", 0).takeIf { it > 0 }?.let { account.maxDevices = it }
            }
        } catch (_: Exception) {
        }
        try {
            val pass = GuardianClient().fetchProxyPass(account.accessToken)
            account.proxyPassToken = pass.token
            account.proxyPassExpiryEpochSeconds = pass.expiresAtEpochSeconds
            pass.quotaLimit?.let { account.quotaLimitBytes = it }
            pass.quotaRemaining?.let { account.quotaRemainingBytes = it }
            pass.quotaReset?.let { account.quotaResetEpochSeconds = it }
            account.quotaErrorMessage = ""
        } catch (e: FoxyQuotaExceededException) {
            account.quotaRemainingBytes = 0
            account.quotaErrorMessage = "Monthly quota used up."
        } catch (_: Exception) {
            // Quota display is best effort; connecting retries with failover.
        }
        if (account.email.isBlank()) account.email = "Firefox account"
        account.lastQuotaCheckUtc = System.currentTimeMillis()
    }

    private fun postJson(path: String, json: String): Pair<Int, String> {
        val url = URL(API_BASE + path)
        val conn = open(url)
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("User-Agent", API_USER_AGENT)
        conn.setRequestProperty("Accept", "application/json")
        conn.setRequestProperty("Accept-Language", "en-US, en")
        conn.setRequestProperty("Content-Type", "application/json")
        val bytes = json.toByteArray(StandardCharsets.UTF_8)
        conn.setFixedLengthStreamingMode(bytes.size)
        conn.outputStream.use { it.write(bytes) }
        val status = conn.responseCode
        return status to readBody(conn)
    }

    private fun open(url: URL): HttpURLConnection {
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 20_000
        conn.readTimeout = 30_000
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

    private fun describeError(body: String, status: Int, fallback: String): String {
        val raw = body.trim()
        if (raw.isEmpty()) return "$fallback (HTTP $status)"
        return try {
            val doc = JSONObject(raw)
            val message = doc.optString("message").ifBlank { doc.optString("error") }
            if (message.isBlank()) "$fallback (HTTP $status)" else "$message (HTTP $status)"
        } catch (_: Exception) {
            "$fallback (HTTP $status)"
        }
    }

    /**
     * base64url, hand-rolled on purpose: android.util.Base64 is unavailable in JVM unit
     * tests and java.util.Base64 needs API 26 while minSdk is 24.
     *
     * Padding is kept because Mozilla's own Android client sends the padded form and
     * Guardian compares the challenge string it stored when the login page was opened.
     */
    internal fun base64Url(bytes: ByteArray, padded: Boolean = true): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        val sb = StringBuilder((bytes.size * 4 + 2) / 3)
        var i = 0
        while (i + 2 < bytes.size) {
            val n = ((bytes[i].toInt() and 0xFF) shl 16) or
                ((bytes[i + 1].toInt() and 0xFF) shl 8) or
                (bytes[i + 2].toInt() and 0xFF)
            sb.append(alphabet[(n ushr 18) and 0x3F])
            sb.append(alphabet[(n ushr 12) and 0x3F])
            sb.append(alphabet[(n ushr 6) and 0x3F])
            sb.append(alphabet[n and 0x3F])
            i += 3
        }
        when (bytes.size - i) {
            1 -> {
                val n = (bytes[i].toInt() and 0xFF) shl 16
                sb.append(alphabet[(n ushr 18) and 0x3F])
                sb.append(alphabet[(n ushr 12) and 0x3F])
            }
            2 -> {
                val n = ((bytes[i].toInt() and 0xFF) shl 16) or ((bytes[i + 1].toInt() and 0xFF) shl 8)
                sb.append(alphabet[(n ushr 18) and 0x3F])
                sb.append(alphabet[(n ushr 12) and 0x3F])
                sb.append(alphabet[(n ushr 6) and 0x3F])
            }
        }
        if (padded) {
            while (sb.length % 4 != 0) sb.append('=')
        }
        return sb.toString()
    }

    private fun percentEncode(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun percentDecode(value: String): String =
        try {
            java.net.URLDecoder.decode(value, "UTF-8")
        } catch (_: Exception) {
            value
        }

    private const val VERIFIER_LENGTH = 64
    private const val DEFAULT_TOKEN_LIFETIME_SECONDS = 30L * 24 * 60 * 60
}
