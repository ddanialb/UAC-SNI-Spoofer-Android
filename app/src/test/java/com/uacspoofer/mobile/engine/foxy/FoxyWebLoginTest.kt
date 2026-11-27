package com.uacspoofer.mobile.engine.foxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The in-app sign-in flow only has a few pieces we can pin down without talking to Mozilla:
 * PKCE generation, the login URL, and pulling the authorisation code out of the redirect.
 */
class FoxyWebLoginTest {

    @Test
    fun `pkce challenge matches the RFC 7636 test vector`() {
        val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
        // Mozilla's clients keep the trailing '=' , so we do too.
        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM=",
            FoxyWebLogin.challengeFor(verifier),
        )
    }

    @Test
    fun `generated pkce is url safe and long enough`() {
        val pkce = FoxyWebLogin.newPkce()
        assertEquals(64, pkce.verifier.length)
        assertTrue(pkce.verifier.all { it.isLetterOrDigit() || it in "-._~" })
        // url-safe alphabet + padding, like Mozilla's client
        assertFalse(pkce.challenge.any { it == '+' || it == '/' })
        assertTrue(pkce.challenge.trimEnd('=').all { it.isLetterOrDigit() || it == '-' || it == '_' })
        assertNotEquals(pkce.verifier, pkce.challenge)
    }

    @Test
    fun `each pkce is unique`() {
        val a = FoxyWebLogin.newPkce()
        val b = FoxyWebLogin.newPkce()
        assertNotEquals(a.verifier, b.verifier)
    }

    @Test
    fun `login url points at guardian android login with S256`() {
        val url = FoxyWebLogin.loginUrl("abc-123")
        assertTrue(url.startsWith("https://vpn.mozilla.org/api/v2/vpn/login/android?"))
        assertTrue(url.contains("code_challenge=abc-123"))
        assertTrue(url.contains("code_challenge_method=S256"))
        assertTrue(url.contains("user_agent="))
    }

    @Test
    fun `login url passes an optional email`() {
        val url = FoxyWebLogin.loginUrl("abc", email = "user@example.com")
        assertTrue(url.contains("email=user%40example.com"))
    }

    @Test
    fun `deep link with a code is recognised`() {
        val url = "mozilla-vpn://login/success?code=0123456789abcdef&state=xyz"
        assertTrue(FoxyWebLogin.isLoginCallback(url))
        assertEquals("0123456789abcdef", FoxyWebLogin.extractAuthCode(url))
    }

    @Test
    fun `guardian https redirect is recognised too`() {
        val url = "https://vpn.mozilla.org/vpn/client/login/success?code=deadbeef"
        assertTrue(FoxyWebLogin.isLoginCallback(url))
        assertEquals("deadbeef", FoxyWebLogin.extractAuthCode(url))
    }

    @Test
    fun `the fxa oauth hop before the deep link is recognised`() {
        // Guardian sends the browser here first; the code is the same one the deep link carries.
        val url = "https://vpn.mozilla.org/oauth/success?code=abc123&state=pkce-42"
        assertTrue(FoxyWebLogin.isLoginCallback(url))
        assertEquals("abc123", FoxyWebLogin.extractAuthCode(url))
    }

    @Test
    fun `code in the fragment is found`() {
        val url = "mozilla-vpn://login/success#code=ff00ff"
        assertEquals("ff00ff", FoxyWebLogin.extractAuthCode(url))
    }

    @Test
    fun `success pages without a code do not count as a callback`() {
        assertFalse(FoxyWebLogin.isLoginCallback("https://vpn.mozilla.org/oauth/success"))
        assertFalse(FoxyWebLogin.isLoginCallback("https://vpn.mozilla.org/oauth/error?code=abc"))
    }

    @Test
    fun `unrelated urls are not treated as a callback`() {
        assertFalse(FoxyWebLogin.isLoginCallback("https://accounts.firefox.com/"))
        assertFalse(FoxyWebLogin.isLoginCallback("mozilla-vpn://somethingelse/success?code=x"))
        assertFalse(FoxyWebLogin.isLoginCallback(null))
        assertNull(FoxyWebLogin.extractAuthCode("https://vpn.mozilla.org/api/v2/vpn/login/android"))
    }

    @Test
    fun `code survives url encoding and extra parameters`() {
        val url = "mozilla-vpn://login/success?state=abc&code=a%2Bb%2Fc%3D&other=1"
        assertEquals("a+b/c=", FoxyWebLogin.extractAuthCode(url))
    }

    @Test
    fun `web accounts are considered usable without a refresh token`() {
        val account = FoxyAccount(
            email = "user@example.com",
            accessToken = "header.payload.signature",
            refreshToken = "",
        )
        assertTrue(account.hasUsableCredentials)
        assertFalse(account.canRefreshSilently)
        assertTrue(account.isWebLogin)
        assertEquals(FoxyAccount.AUTH_WEB, account.authMethod)
    }

    @Test
    fun `legacy fxa accounts can refresh silently`() {
        val account = FoxyAccount(
            email = "old@example.com",
            accessToken = "access",
            refreshToken = "refresh",
            authMethod = FoxyAccount.AUTH_LEGACY,
        )
        assertTrue(account.hasUsableCredentials)
        assertTrue(account.canRefreshSilently)
        assertFalse(account.isWebLogin)
    }
}
