package com.uacspoofer.mobile.engine.foxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sign-in bridge: Firefox Accounts hands us a sessionToken over WebChannel, we trade it
 * for OAuth credentials. Everything testable without an account lives here.
 */
class FoxyWebLoginTest {

    private val token = "0a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f9"

    @Test
    fun `login page is the webchannel flavoured firefox accounts page`() {
        val url = FoxyWebLogin.loginUrl()
        assertTrue(url.startsWith("https://accounts.firefox.com/?"))
        assertTrue(url.contains("context=fx_desktop_v3"))
        assertTrue(url.contains("service=sync"))
    }

    @Test
    fun `optional email is carried into the login page`() {
        assertTrue(FoxyWebLogin.loginUrl("user@example.com").contains("email=user%40example.com"))
    }

    @Test
    fun `browser user agent must look like firefox or the page stays silent`() {
        assertTrue(FoxyWebLogin.BROWSER_USER_AGENT.contains("Firefox/"))
        assertFalse(FoxyWebLogin.BROWSER_USER_AGENT.contains("wv"))
    }

    @Test
    fun `session tokens are 64 hex characters`() {
        assertTrue(FoxyWebLogin.isValidSessionToken(token))
        assertTrue(FoxyWebLogin.isValidSessionToken("  ${token.uppercase()}  "))
        assertFalse(FoxyWebLogin.isValidSessionToken(token.dropLast(1)))
        assertFalse(FoxyWebLogin.isValidSessionToken("z".repeat(64)))
        assertFalse(FoxyWebLogin.isValidSessionToken(null))
    }

    @Test
    fun `fxaccounts login envelope yields the session token and email`() {
        val payload = """
            {"id":"account_updates","message":{"messageId":"42","command":"fxaccounts:login",
            "data":{"sessionToken":"$token","email":"user@example.com","verified":true}}}
        """.trimIndent()
        val event = FoxyWebLogin.parseWebChannel(payload)
        assertEquals("fxaccounts:login", event?.command)
        assertEquals("42", event?.messageId)
        assertEquals("account_updates", event?.channelId)
        assertEquals(token, event?.sessionToken)
        assertEquals("user@example.com", event?.email)
    }

    @Test
    fun `oauth_login envelopes carry the token too`() {
        val payload = """{"id":"account_updates","message":{"messageId":"7","command":"fxaccounts:oauth_login","data":{"sessionToken":"$token"}}}"""
        assertEquals(token, FoxyWebLogin.parseWebChannel(payload)?.sessionToken)
    }

    @Test
    fun `a bare token field is accepted and cleaned`() {
        val payload = """{"message":{"command":"fxaccounts:login","data":{"token":"$token"}}}"""
        assertEquals(token, FoxyWebLogin.parseWebChannel(payload)?.sessionToken)
    }

    @Test
    fun `status and can_link messages parse without a token`() {
        val status = FoxyWebLogin.parseWebChannel(
            """{"id":"account_updates","message":{"messageId":"1","command":"fxaccounts:fxa_status","data":{}}}""",
        )
        assertEquals("fxaccounts:fxa_status", status?.command)
        assertEquals("", status?.sessionToken)
        assertEquals("""{"capabilities":{},"signedInUser":null}""", FoxyWebLogin.statusReply())
        assertEquals("""{"ok":true}""", FoxyWebLogin.canLinkAccountReply())
    }

    @Test
    fun `junk payloads are ignored`() {
        assertNull(FoxyWebLogin.parseWebChannel(null))
        assertNull(FoxyWebLogin.parseWebChannel(""))
        assertNull(FoxyWebLogin.parseWebChannel("not json"))
        assertNull(FoxyWebLogin.parseWebChannel("""{"id":"x"}"""))
    }

    @Test
    fun `the injected hook relays webchannel traffic and can reply`() {
        val hook = FoxyWebLogin.JS_HOOK
        assertTrue(hook.contains("WebChannelMessageToChrome"))
        assertTrue(hook.contains("WebChannelMessageToContent"))
        assertTrue(hook.contains(FoxyWebLogin.JS_BRIDGE_NAME))
        assertTrue(hook.contains("__foxyReply"))
    }

    @Test
    fun `accounts signed in through the browser can refresh silently`() {
        val account = FoxyAccount(email = "u@e.com", accessToken = "access", refreshToken = "refresh")
        assertTrue(account.hasUsableCredentials)
        assertTrue(account.canRefreshSilently)
        assertTrue(account.isWebLogin)
    }
}
