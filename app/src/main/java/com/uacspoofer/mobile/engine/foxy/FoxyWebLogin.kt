/*
 * In-app Firefox account sign-in (Foxy engine).
 *
 * The OAuth "authorisation code" road does not work for us: Guardian consumes the code it
 * receives at https://vpn.mozilla.org/oauth/success before the app can exchange it, which
 * is exactly the `{"errno":129,"error":"'code' not found or expired"}` we were getting back.
 *
 * Mozilla's page can instead speak Firefox's WebChannel protocol. We open the normal
 * accounts.firefox.com sign-in page with a Firefox user agent, inject a small bridge, and
 * the page itself hands us the signed-in account's `sessionToken` (64 hex characters).
 * That token is then traded for OAuth credentials with the `fxa-credentials` grant, which
 * is the same request the password login uses — see FxaClient.completeBrowserLogin.
 */
package com.uacspoofer.mobile.engine.foxy

import com.uacspoofer.mobile.logging.AppLogRepository
import com.uacspoofer.mobile.logging.LogSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** One WebChannel command lifted out of the sign-in page. */
data class FoxyWebChannelEvent(
    val command: String,
    val messageId: String,
    val channelId: String,
    val sessionToken: String = "",
    val email: String = "",
)

/** Outcome of an in-app sign-in attempt. */
data class FoxyWebLoginResult(
    val success: Boolean,
    val account: FoxyAccount? = null,
    val errorMessage: String = "",
)

object FoxyWebLogin {
    /**
     * The plain Firefox Accounts sign-in page, in the "desktop" context so that it talks to
     * us over WebChannel instead of redirecting to an OAuth callback we cannot receive.
     */
    const val LOGIN_URL = "https://accounts.firefox.com/?context=fx_desktop_v3&service=sync&action=email"

    /** Must look like Firefox, otherwise the page never emits WebChannel messages. */
    const val BROWSER_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Mobile; rv:159.0) Gecko/159.0 Firefox/159.0"

    /** Used by our own control-plane calls. */
    const val API_USER_AGENT = "FirefoxPrivateNetworkVPN/2.21.0 (Android; arm64-v8a)"

    const val JS_BRIDGE_NAME = "foxyWebChannel"
    const val CHANNEL_ID = "account_updates"

    private val SESSION_TOKEN = Regex("^[0-9a-f]{64}$")

    fun isValidSessionToken(value: String?): Boolean =
        value != null && SESSION_TOKEN.matches(value.trim().lowercase())

    /**
     * Parses one `{"id":…,"message":{"command":…,"messageId":…,"data":{…}}}` envelope.
     * Returns null for anything that is not a WebChannel message we care about.
     */
    fun parseWebChannel(payload: String?): FoxyWebChannelEvent? {
        if (payload.isNullOrBlank()) return null
        val envelope = runCatching { JSONObject(payload) }.getOrNull() ?: return null
        val message = envelope.optJSONObject("message") ?: return null
        val command = message.optString("command").ifBlank { return null }
        val data = message.optJSONObject("data") ?: JSONObject()
        val rawToken = data.optString("sessionToken").ifBlank { data.optString("token") }
        val token = rawToken.trim().replace(Regex("[\\s\"]"), "").lowercase()
        return FoxyWebChannelEvent(
            command = command,
            messageId = message.optString("messageId"),
            channelId = envelope.optString("id").ifBlank { CHANNEL_ID },
            sessionToken = if (isValidSessionToken(token)) token else "",
            email = data.optString("email"),
        )
    }

    /** Reply to `fxaccounts:fxa_status`: we are a browser that is not signed in yet. */
    fun statusReply(): String = """{"capabilities":{},"signedInUser":null}"""

    /** Reply to `fxaccounts:can_link_account`. */
    fun canLinkAccountReply(): String = """{"ok":true}"""

    /** "Create a Firefox account" lands on the sign-up form of the very same page. */
    const val SIGNUP_URL =
        "https://accounts.firefox.com/?context=fx_desktop_v3&service=sync&action=signup"

    fun loginUrl(email: String? = null, signUp: Boolean = false): String {
        val base = if (signUp) SIGNUP_URL else LOGIN_URL
        if (email.isNullOrBlank()) return base
        val encoded = java.net.URLEncoder.encode(email.trim(), "UTF-8").replace("+", "%20")
        return "$base&email=$encoded"
    }

    /**
     * Trades the browser session token for OAuth credentials and fills in the account
     * details Guardian reports (plan, quota).
     */
    suspend fun signIn(sessionToken: String): FoxyWebLoginResult = withContext(Dispatchers.IO) {
        try {
            AppLogRepository.info(LogSource.APP, "Foxy: got a Firefox session token, trading it for OAuth credentials")
            val account = FxaClient().completeBrowserLogin(sessionToken)
            AppLogRepository.success(
                LogSource.APP,
                "Foxy: Mozilla sign-in OK for ${account.email.ifBlank { "(email unknown)" }}",
            )
            enrichFromGuardian(account)
            FoxyWebLoginResult(success = true, account = account)
        } catch (e: Exception) {
            AppLogRepository.error(LogSource.APP, "Foxy: sign-in failed — ${e.javaClass.simpleName}: ${e.message}")
            FoxyWebLoginResult(
                success = false,
                errorMessage = e.message ?: "Sign-in failed. Try again.",
            )
        }
    }

    /** Best effort: email, plan and quota from Guardian. Never aborts a successful login. */
    private suspend fun enrichFromGuardian(account: FoxyAccount) {
        try {
            val conn = java.net.URL("https://vpn.mozilla.org/api/v1/vpn/account").openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            conn.setRequestProperty("User-Agent", API_USER_AGENT)
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("Authorization", "Bearer ${account.accessToken}")
            val status = conn.responseCode
            val body = runCatching {
                (if (status in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.use { it.readText() }.orEmpty()
            }.getOrDefault("")
            if (status in 200..299) {
                val doc = runCatching { JSONObject(body) }.getOrNull()
                if (doc != null) {
                    doc.optString("email").takeIf { it.isNotBlank() }?.let { account.email = it }
                    val active = doc.optJSONObject("subscriptions")
                        ?.optJSONObject("vpn")
                        ?.optBoolean("active", false) ?: false
                    if (!active) {
                        account.quotaErrorMessage = "This Firefox account has no active Mozilla VPN subscription."
                    }
                    doc.optInt("max_devices", 0).takeIf { it > 0 }?.let { account.maxDevices = it }
                }
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
        }
        if (account.email.isBlank()) account.email = "Firefox account"
        account.lastQuotaCheckUtc = System.currentTimeMillis()
    }

    /**
     * JavaScript that relays Firefox's WebChannel traffic to [JS_BRIDGE_NAME] and lets us
     * answer back through `window.__foxyReply`.
     */
    val JS_HOOK: String = """
        (function() {
          if (window.__foxyChannelHooked) { return; }
          window.__foxyChannelHooked = true;
          var send = function(o) {
            try {
              var host = window.location.hostname;
              if (host !== 'accounts.firefox.com' && !host.endsWith('.firefox.com')) { return; }
              var payload = (typeof o === 'string') ? o : JSON.stringify(o);
              window.$JS_BRIDGE_NAME.onMessage(payload, window.location.href);
            } catch (e) {}
          };
          var onChannel = function(e) {
            var d = e.detail;
            if (typeof d === 'string') { try { d = JSON.parse(d); } catch (err) { return; } }
            if (d && d.message) { send(d); }
          };
          window.addEventListener('WebChannelMessageToChrome', onChannel, true);
          document.addEventListener('WebChannelMessageToChrome', onChannel, true);
          window.__foxyReply = function(id, messageId, command, data) {
            var detail = { id: id, message: { messageId: messageId, command: command, data: data } };
            window.dispatchEvent(new CustomEvent('WebChannelMessageToContent', { detail: detail }));
            document.dispatchEvent(new CustomEvent('WebChannelMessageToContent', { detail: detail }));
          };
        })();
    """.trimIndent()
}
