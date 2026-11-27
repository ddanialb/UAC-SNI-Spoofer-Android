/*
 * Ported from Se7en Pro (MIT) — Se7enPro/Services/Foxy/FxaClient.cs
 * Copyright (c) 2026 kng7-p (King Network). MIT License.
 * Adapted for UAC SNI Spoofer Android (AGPLv3).
 * Uses HttpURLConnection (FxA does not require HTTP/2). Same endpoints, Hawk, PKCE fallback.
 */
package com.uacspoofer.mobile.engine.foxy

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom

class FxaClient(
    private val cookieJar: FoxyCookieJar = FoxyCookieJar(),
    private val proxy: java.net.Proxy = java.net.Proxy.NO_PROXY,
    private val proxyAuthHeader: String? = null,
) {
    private val refreshGate = Mutex()
    private val challengeSolver = FastlyChallengeSolver(cookieJar, proxy, proxyAuthHeader)

    suspend fun startLogin(email: String, password: String): FxaLoginResult = withContext(Dispatchers.IO) {
        val cleanEmail = email.trim()
        val cleanPassword = password.trim()
        try {
            val authPw = FxaCrypto.deriveAuthPw(cleanEmail, cleanPassword)
            val (doc, sessionToken) = loginAttempt(cleanEmail, authPw, withVerificationMethod = true)
            val verified = doc.optBoolean("verified", false)
            if (!verified) {
                return@withContext FxaLoginResult(success = false, requires2Fa = true, sessionToken = sessionToken)
            }
            val account = completeLogin(cleanEmail, sessionToken)
            FxaLoginResult(success = true, requires2Fa = false, sessionToken = sessionToken, account = account)
        } catch (e: Exception) {
            FxaLoginResult(success = false, requires2Fa = false, errorMessage = e.message ?: "Login failed")
        }
    }

    suspend fun submitVerificationCode(email: String, sessionToken: String, code: String): FxaLoginResult =
        withContext(Dispatchers.IO) {
            val cleanEmail = email.trim()
            val cleanCode = code.trim()
            try {
                val body = JSONObject().put("code", cleanCode).toString()
                fxaRequest("POST", "/session/verify_code", sessionToken, body)
                val account = completeLogin(cleanEmail, sessionToken)
                FxaLoginResult(success = true, requires2Fa = false, sessionToken = sessionToken, account = account)
            } catch (e: Exception) {
                FxaLoginResult(success = false, requires2Fa = true, sessionToken = sessionToken, errorMessage = e.message ?: "Verification failed")
            }
        }

    suspend fun refreshAccessToken(account: FoxyAccount): Boolean = withContext(Dispatchers.IO) {
        if (account.refreshToken.isBlank()) return@withContext false
        refreshGate.withLock {
            try {
                val body = JSONObject()
                    .put("client_id", CLIENT_ID)
                    .put("grant_type", "refresh_token")
                    .put("refresh_token", account.refreshToken)
                    .put("scope", OAUTH_SCOPE)
                    .toString()
                val doc = fxaRequest("POST", "/oauth/token", null, body)
                val accessToken = doc.optString("access_token", "")
                if (accessToken.isBlank()) return@withLock false
                val refreshToken = doc.optString("refresh_token", account.refreshToken)
                val expiresIn = doc.optLong("expires_in", 3600L)
                val expiry = System.currentTimeMillis() / 1000 + expiresIn
                account.accessToken = accessToken
                account.refreshToken = if (refreshToken.isBlank()) account.refreshToken else refreshToken
                account.tokenExpiryEpochSeconds = expiry
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    private suspend fun completeLogin(email: String, sessionToken: String): FoxyAccount {
        val body = JSONObject()
            .put("client_id", CLIENT_ID)
            .put("grant_type", "fxa-credentials")
            .put("scope", OAUTH_SCOPE)
            .put("access_type", "offline")
            .toString()
        val doc = try {
            fxaRequest("POST", "/oauth/token", sessionToken, body)
        } catch (e: Exception) {
            if (isFastly406OrChallengeFailure(e)) {
                exchangeSessionWithOAuthCode(sessionToken)
            } else throw e
        }
        val accessToken = doc.getString("access_token")
        val refreshToken = doc.optString("refresh_token", "")
        val expiresIn = doc.optLong("expires_in", 86400L)
        return FoxyAccount(
            email = email,
            sessionToken = sessionToken,
            accessToken = accessToken,
            refreshToken = refreshToken,
            tokenExpiryEpochSeconds = System.currentTimeMillis() / 1000 + expiresIn,
        )
    }

    private fun isFastly406OrChallengeFailure(e: Exception): Boolean {
        if (e is FxaException && e.statusCode == 406) return true
        val msg = e.message ?: ""
        return msg.contains("CAPTCHA", ignoreCase = true) || msg.contains("Fastly", ignoreCase = true)
    }

    private fun exchangeSessionWithOAuthCode(sessionToken: String): JSONObject {
        val verifierBytes = ByteArray(32)
        SecureRandom().nextBytes(verifierBytes)
        val verifier = base64UrlEncode(verifierBytes)
        val challengeBytes = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(StandardCharsets.US_ASCII))
        val challenge = base64UrlEncode(challengeBytes)
        val stateBytes = ByteArray(16)
        SecureRandom().nextBytes(stateBytes)
        val state = stateBytes.joinToString("") { "%02x".format(it) }
        val authorizeUrl = URL(FXA_AUTH_SERVER + "/oauth/authorization")
        val authorizeBody = JSONObject()
            .put("client_id", CLIENT_ID)
            .put("response_type", "code")
            .put("scope", OAUTH_SCOPE)
            .put("state", state)
            .put("access_type", "offline")
            .put("code_challenge", challenge)
            .put("code_challenge_method", "S256")
            .toString()
        val authorizeBytes = authorizeBody.toByteArray(StandardCharsets.UTF_8)
        val (tokenId, hmacKey) = FxaCrypto.deriveHawkCredentials(sessionToken)
        val authConn = open(authorizeUrl)
        authConn.requestMethod = "POST"
        authConn.doOutput = true
        applyOAuthJsonHeaders(authConn)
        authConn.setRequestProperty("Authorization", FxaCrypto.buildHawkHeader("POST", authorizeUrl, tokenId, hmacKey, authorizeBytes))
        authConn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        authConn.outputStream.use { it.write(authorizeBytes) }
        val authStatus = authConn.responseCode
        val authText = readBody(authConn)
        cookieJar.captureCookies(authConn, authorizeUrl)
        if (authStatus !in 200..299) throw parseFxaError(authText, authStatus)
        val authDoc = JSONObject(authText)
        val code = authDoc.optString("code", "")
        val respState = authDoc.optString("state", "")
        if (code.isEmpty() || respState != state) throw IllegalStateException("Invalid OAuth authorization response from Firefox Accounts.")
        val tokenBody = JSONObject()
            .put("client_id", CLIENT_ID)
            .put("grant_type", "authorization_code")
            .put("code", code)
            .put("code_verifier", verifier)
            .toString()
        val tokenUrl = URL(FXA_OAUTH_TOKEN_URL)
        val tokenConn = open(tokenUrl)
        tokenConn.requestMethod = "POST"
        tokenConn.doOutput = true
        applyOAuthJsonHeaders(tokenConn)
        tokenConn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        tokenConn.outputStream.use { it.write(tokenBody.toByteArray(StandardCharsets.UTF_8)) }
        val tokenStatus = tokenConn.responseCode
        val tokenText = readBody(tokenConn)
        cookieJar.captureCookies(tokenConn, tokenUrl)
        if (tokenStatus !in 200..299) throw parseFxaError(tokenText, tokenStatus)
        return JSONObject(tokenText.ifBlank { "{}" })
    }

    private fun applyOAuthJsonHeaders(conn: HttpURLConnection) {
        conn.setRequestProperty("User-Agent", FastlyChallengeSolver.SOLVER_USER_AGENT)
        conn.setRequestProperty("Accept", "application/json;q=0.9,*/*;q=0.2")
        conn.setRequestProperty("Accept-Language", "en-US, en")
        conn.setRequestProperty("Accept-Encoding", "identity")
        conn.setRequestProperty("Cache-Control", "no-cache")
        conn.setRequestProperty("Pragma", "no-cache")
        conn.setRequestProperty("Sec-Fetch-Dest", "empty")
        conn.setRequestProperty("Sec-Fetch-Mode", "no-cors")
        conn.setRequestProperty("Sec-Fetch-Site", "none")
        conn.setRequestProperty("Priority", "u=4")
    }

    private fun base64UrlEncode(bytes: ByteArray): String =
        android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP).trimEnd('=').replace('+', '-').replace('/', '_')

    private fun parseFxaError(text: String, statusCode: Int): FxaException {
        var errno: Int? = null
        var message = text
        try {
            val doc = JSONObject(text)
            if (doc.has("errno")) errno = doc.optInt("errno", -1).takeIf { it >= 0 }
            if (doc.has("message")) message = doc.optString("message", text)
        } catch (_: Exception) {
        }
        return FxaException(message, errno, statusCode)
    }

    private fun loginAttempt(email: String, authPw: String, withVerificationMethod: Boolean): Pair<JSONObject, String> {
        val body = if (withVerificationMethod) {
            JSONObject().put("email", email).put("authPW", authPw).put("verificationMethod", "email-2fa").toString()
        } else {
            JSONObject().put("email", email).put("authPW", authPw).toString()
        }
        try {
            val doc = fxaRequest("POST", "/account/login", null, body)
            val sessionToken = doc.getString("sessionToken")
            return doc to sessionToken
        } catch (e: FxaException) {
            if (e.errno == FXA_ERRNO_INVALID_PARAMETER && withVerificationMethod) {
                return loginAttempt(email, authPw, withVerificationMethod = false)
            }
            throw e
        }
    }

    private fun fxaRequest(method: String, path: String, sessionToken: String?, body: String): JSONObject {
        val url = URL(FXA_AUTH_SERVER + path)
        val bodyBytes = body.toByteArray(StandardCharsets.UTF_8)
        var captchaSolvedInRequest = false
        for (attempt in 1..3) {
            val conn = open(url)
            conn.requestMethod = method
            conn.doOutput = true
            conn.setRequestProperty("User-Agent", FastlyChallengeSolver.SOLVER_USER_AGENT)
            conn.setRequestProperty("Accept", "*/*")
            conn.setRequestProperty("Accept-Encoding", "identity")
            conn.setRequestProperty("Content-Type", "application/json")
            if (!sessionToken.isNullOrEmpty()) {
                val (tokenId, hmacKey) = FxaCrypto.deriveHawkCredentials(sessionToken)
                conn.setRequestProperty("Authorization", FxaCrypto.buildHawkHeader(method, url, tokenId, hmacKey, bodyBytes))
            }
            conn.outputStream.use { it.write(bodyBytes) }
            val status = conn.responseCode
            val text = readBody(conn)
            cookieJar.captureCookies(conn, url)
            if (status == 406) {
                if (captchaSolvedInRequest) {
                    throw FxaException(
                        "Fastly rejected the login request even after CAPTCHA verification. Use an upstream proxy, enable Personal Relay, or switch your IP.",
                        null, 406,
                    )
                }
                captchaSolvedInRequest = true
                kotlinx.coroutines.runBlocking(Dispatchers.IO) {
                    challengeSolver.solveAndInstall(text, "https://api.accounts.firefox.com")
                }
                continue
            }
            if (status !in 200..299) {
                var errno: Int? = null
                var message = text
                try {
                    val doc = JSONObject(text)
                    if (doc.has("errno")) errno = doc.optInt("errno", -1).takeIf { it >= 0 }
                    if (doc.has("message")) message = doc.optString("message", text)
                } catch (_: Exception) {
                }
                if (status == 429) {
                    message = if (message.isBlank()) {
                        "Too many requests (HTTP 429). Mozilla temporarily rate-limited this IP; switch VPN/proxy IP or wait before retrying."
                    } else {
                        "$message (HTTP 429 — switch IP/proxy or wait before retrying)"
                    }
                }
                throw FxaException(message, errno, status)
            }
            return JSONObject(text.ifBlank { "{}" })
        }
        throw FxaException("Exceeded maximum retry attempts for Fastly challenge.", null, 406)
    }

    private fun open(url: URL): HttpURLConnection {
        val conn = url.openConnection(proxy) as HttpURLConnection
        FoxyUpstreamProxy.applyAuth(conn, proxyAuthHeader)
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.useCaches = false
        conn.instanceFollowRedirects = true
        cookieJar.applyCookies(conn, url)
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
        private const val FXA_AUTH_SERVER = "https://api.accounts.firefox.com/v1"
        private const val FXA_OAUTH_TOKEN_URL = "https://oauth.accounts.firefox.com/v1/token"
        private const val CLIENT_ID = "5882386c6d801776"
        private const val OAUTH_SCOPE = "profile https://identity.mozilla.com/apps/vpn"
        private const val FXA_ERRNO_INVALID_PARAMETER = 107
    }
}
