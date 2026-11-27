package com.uacspoofer.mobile.engine.foxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Walks the exact URL sequence the login WebView sees, in order, using the real shapes
 * observed on vpn.mozilla.org:
 *
 *   1. GET /api/v2/vpn/login/android?code_challenge=…       → 302
 *   2. GET accounts.firefox.com/authorization?client_id=…   → the sign-in page
 *   3. GET accounts.firefox.com/?context=…                  → post-login landing
 *   4. GET https://vpn.mozilla.org/oauth/success?code=…     → 302 (the hop we used to miss)
 *   5. GET mozilla-vpn://login/success?code=…               → deep link
 *
 * Only steps 4 and 5 must produce a code, and the code must be claimed exactly once.
 */
class FoxyLoginFlowOrderTest {

    private val challenge = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM="
    private val state = "pkce-0dad06d1d8d248b32b1aced5d11ce5a86aad97c62b944b3f0830b18222948fd0"
    private val code = "d60b4de6f4a8a6e2228e82b328729d9cc1666b96a1f7a5202fdc563c925bb7a3ea3f4efa1ef3c37d"

    private fun capture(url: String): String? = FoxyWebLogin.captureCode(url)

    @Test
    fun `the browser visits the login chain in order and the code is claimed once`() {
        val visits = listOf(
            "https://vpn.mozilla.org/api/v2/vpn/login/android?code_challenge=${challenge}&code_challenge_method=S256",
            "https://accounts.firefox.com/authorization?client_id=e6eb0d1e856335fc&scope=profile&" +
                "response_type=code&redirect_uri=https%3A%2F%2Fvpn.mozilla.org%2Foauth%2Fsuccess&state=$state",
            "https://accounts.firefox.com/?context=fxoauth",
            "https://vpn.mozilla.org/oauth/success?code=$code&state=$state",
            "mozilla-vpn://login/success?code=$code",
        )

        val claimed = visits.map { capture(it) }

        assertEquals(listOf(null, null, null, code, code), claimed)
        // the dialog claims on the first hit and then stops, so exactly one exchange happens
        val firstClaimIndex = claimed.indexOfFirst { it != null }
        assertEquals(3, firstClaimIndex)
        assertEquals(code, claimed[firstClaimIndex])
    }

    @Test
    fun `the fxa hop alone is enough to sign in`() {
        // if the custom-scheme navigation never reaches the app, step 4 still carries the code
        assertEquals(
            code,
            capture("https://vpn.mozilla.org/oauth/success?code=$code&state=$state"),
        )
    }

    @Test
    fun `sign-in pages that carry no code are ignored`() {
        assertNull(capture("https://vpn.mozilla.org/oauth/success"))
        assertNull(capture("https://vpn.mozilla.org/oauth/error?reason=denied"))
        assertNull(capture("https://accounts.firefox.com/settings"))
        assertNull(capture("about:blank"))
    }

    @Test
    fun `a challenge that came back wrong does not look like a callback`() {
        assertNull(capture("https://vpn.mozilla.org/oauth/success?error=access_denied"))
    }
}
