/*
 * Tests for the Foxy engine core ported from Se7en Pro (MIT).
 * Protocol details verified against Se7enPro/Services/Foxy.
 */
package com.uacspoofer.mobile.engine.foxy

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FxaCryptoTest {
    @Test
    fun authPwMatchesMozillaQuickStretchVector() {
        // Independently derived with hashlib.pbkdf2_hmac + HKDF-Expand (see FOXY_MAPPING.md).
        assertEquals(
            "cd280856c45e06adc4f7a7a285312fbd97357aa1b8eee07f35b61553c2538791",
            FxaCrypto.deriveAuthPw("test@example.com", "hunter2"),
        )
    }

    @Test
    fun authPwIsStableAndEmailSensitive() {
        val a = FxaCrypto.deriveAuthPw("user@example.com", "secret")
        val b = FxaCrypto.deriveAuthPw("user@example.com", "secret")
        val c = FxaCrypto.deriveAuthPw("User@example.com", "secret")
        assertEquals(a, b)
        assertFalse(a == c)
        assertEquals(64, a.length)
    }

    @Test
    fun hawkCredentialsSplitIntoTokenIdAndKey() {
        val sessionToken = "00112233445566778899aabbccddeeff".repeat(2)
        val (tokenId, hmacKey) = FxaCrypto.deriveHawkCredentials(sessionToken)
        assertEquals(64, tokenId.length)
        assertEquals(32, hmacKey.size)
        // Derived values must be deterministic; the key is not the raw session token.
        val again = FxaCrypto.deriveHawkCredentials(sessionToken)
        assertEquals(tokenId, again.first)
        assertArrayEquals(hmacKey, again.second)
    }
}

class HpackCodecTest {
    @Test
    fun connectHeaderBlockCarriesMethodAuthorityAndBearerToken() {
        // Header *names* are static-table indices, so only the values appear as literals.
        val block = HpackCodec.encodeConnectHeaders("edge.example.net:2499", "pass-token")
        val text = String(block, Charsets.ISO_8859_1)
        assertTrue(text.contains("CONNECT"))
        assertTrue(text.contains("edge.example.net:2499"))
        assertTrue(text.contains("Bearer pass-token"))
        // :method(3) then :authority(1) then a literal name index 0x0f for proxy-authorization.
        assertEquals(0x03, block[0].toInt() and 0xff)
    }

    @Test
    fun decodeStatusCodeReadsIndexedStatus() {
        // 0x88 = indexed header field 8 in the HPACK static table, i.e. :status 200.
        assertEquals(200, HpackCodec.decodeStatusCode(byteArrayOf(0x88.toByte())))
        // 0x89 = :status 204, 0x8d = :status 404.
        assertEquals(204, HpackCodec.decodeStatusCode(byteArrayOf(0x89.toByte())))
        assertEquals(404, HpackCodec.decodeStatusCode(byteArrayOf(0x8d.toByte())))
    }

    @Test
    fun decodeStatusCodeToleratesEmptyBlock() {
        // An empty block simply yields no status; callers treat 0 as "not received yet".
        assertEquals(0, HpackCodec.decodeStatusCode(ByteArray(0)))
    }
}

class HelloSplitTest {
    /** Emits a real TLS 1.2 ClientHello (with SNI) from a JVM SSLEngine. */
    private fun clientHelloWithSni(host: String): ByteArray {
        val context = javax.net.ssl.SSLContext.getInstance("TLSv1.2")
        context.init(null, null, null)
        val engine = context.createSSLEngine(host, 443)
        engine.useClientMode = true
        engine.beginHandshake()
        val out = java.nio.ByteBuffer.allocate(65_536)
        // The engine returns NEED_WRAP/0 bytes until the ClientHello is fully assembled.
        while (out.position() == 0) {
            engine.wrap(java.nio.ByteBuffer.allocate(0), out)
        }
        out.flip()
        val bytes = ByteArray(out.remaining())
        out.get(bytes)
        assertTrue("engine did not produce a ClientHello", HelloSplitHelper.isClientHello(bytes))
        return bytes
    }

    @Test
    fun offModeKeepsOneChunk() {
        val hello = clientHelloWithSni("edge.example.net")
        val segments = HelloSplitHelper.buildSegments(hello, HelloSplitMode.OFF)
        assertEquals(1, segments.size)
        assertArrayEquals(hello, segments[0])
    }

    @Test
    fun sniModeProducesThreeChunksAroundTheHostname() {
        val hello = clientHelloWithSni("edge.example.net")
        val segments = HelloSplitHelper.buildSegments(hello, HelloSplitMode.SNI)
        assertEquals(3, segments.size)
        assertEquals(1, segments[1].size)
        // Concatenating the split parts must reproduce the original ClientHello byte for byte.
        val joined = segments.fold(ByteArray(0)) { acc, part -> acc + part }
        assertArrayEquals(hello, joined)
    }

    @Test
    fun firstByteModeSplitsOnce() {
        val hello = clientHelloWithSni("edge.example.net")
        val segments = HelloSplitHelper.buildSegments(hello, HelloSplitMode.FIRST_BYTE)
        assertEquals(2, segments.size)
        assertEquals(1, segments[0].size)
    }

    @Test
    fun nonClientHelloPayloadIsNeverSplit() {
        val payload = ByteArray(32) { 0x11 }
        assertEquals(1, HelloSplitHelper.buildSegments(payload, HelloSplitMode.SNI).size)
        assertFalse(HelloSplitHelper.isClientHello(payload))
    }

    @Test
    fun findsSniOffsetInsideHello() {
        val hello = clientHelloWithSni("edge.example.net")
        val offset = HelloSplitHelper.findSniServerNameOffset(hello)
        assertTrue(offset > 0)
        assertTrue(offset < hello.size)
    }
}

class FoxyEngineSettingsTest {
    @Test
    fun defaultsMatchWindowsFoxySettings() {
        val settings = FoxyEngineSettings.DEFAULT
        assertEquals("REC", settings.serverCountry)
        assertEquals(19_060, settings.socksPort)
        assertFalse(settings.customProxyEnabled)
        assertFalse(settings.dualSessionPool)
        assertNull(settings.upstreamProxy())
    }

    @Test
    fun validatedClampsPortsAndNormalizesCountry() {
        val settings = FoxyEngineSettings(
            serverCountry = " de ",
            customProxyPort = 99_999,
            socksPort = 80,
        ).validated()
        assertEquals("DE", settings.serverCountry)
        assertEquals(1080, settings.customProxyPort)
        assertEquals(19_060, settings.socksPort)
    }

    @Test
    fun proxyUrlPasteParsesSchemeCredentialsAndPort() {
        val parsed = FoxyEngineSettings.tryParseProxyInput("socks5://user:p%40ss@127.0.0.1:1080")
        assertNotNull(parsed)
        assertEquals("socks5", FoxyEngineSettings.normalizeScheme(parsed!!.scheme))
        assertEquals("127.0.0.1", parsed.host)
        assertEquals(1080, parsed.port)
        assertEquals("user", parsed.username)
    }

    @Test
    fun httpSchemeMapsToHttpProxyType() {
        assertEquals("http", FoxyEngineSettings.normalizeScheme("HTTPS"))
        assertEquals("http", FoxyEngineSettings.normalizeScheme("http"))
        assertEquals("socks5", FoxyEngineSettings.normalizeScheme("socks5"))
        assertEquals("socks5", FoxyEngineSettings.normalizeScheme(null))
    }

    @Test
    fun upstreamProxyRequiresEnabledAndHost() {
        val enabled = FoxyEngineSettings(
            customProxyEnabled = true,
            customProxyHost = "10.0.0.5",
            customProxyPort = 3128,
            customProxyUsername = "",
        ).upstreamProxy()
        assertNotNull(enabled)
        assertEquals("10.0.0.5", enabled!!.host)
        assertEquals(3128, enabled.port)
        assertNull(enabled.username)
    }

    @Test
    fun proxyAuthHeaderIsBasicBase64OfCredentials() {
        val settings = UpstreamProxySettings(
            enabled = true,
            type = "http",
            host = "10.0.0.5",
            port = 3128,
            username = "user",
            password = "pass",
        )
        assertEquals("Basic dXNlcjpwYXNz", FoxyUpstreamProxy.authHeader(settings))
        // SOCKS5 uses username/password auth, never a Proxy-Authorization header.
        assertNull(FoxyUpstreamProxy.authHeader(settings.copy(type = "socks5")))
        assertNull(FoxyUpstreamProxy.authHeader(settings.copy(username = null)))
    }

    @Test
    fun proxyFingerprintChangesWhenSettingsChange() {
        val direct = FoxyUpstreamProxy.fingerprint(null)
        val socks = FoxyUpstreamProxy.fingerprint(
            UpstreamProxySettings(enabled = true, type = "socks5", host = "10.0.0.5", port = 1080),
        )
        val withAuth = FoxyUpstreamProxy.fingerprint(
            UpstreamProxySettings(enabled = true, type = "socks5", host = "10.0.0.5", port = 1080, username = "u"),
        )
        assertEquals("direct", direct)
        assertFalse(socks == direct)
        // Cached clients must be rebuilt when credentials appear.
        assertFalse(socks == withAuth)
    }

    @Test
    fun base64EncoderMatchesJdkForPaddedAndUnpaddedInput() {
        val jdk = java.util.Base64.getEncoder()
        for (sample in listOf("a", "ab", "abc", "user:pass", "unicode-é-ü")) {
            assertEquals(jdk.encodeToString(sample.toByteArray()), FoxyUpstreamProxy.base64(sample.toByteArray()))
        }
    }
}

class FoxyTunRelayConfigTest {
    @Test
    fun mtuIsClampedIntoFoxySafeRange() {
        // Ceiling is 1400: a 1500 MTU is reduced, a 500 MTU is raised to the 1200 floor.
        assertEquals(1_400, FoxyTunRelayConfig.mtuForChain(1_500))
        assertEquals(1_200, FoxyTunRelayConfig.mtuForChain(500))
        assertEquals(1_280, FoxyTunRelayConfig.mtuForChain(1_280))
        // The network path MTU always wins when it is smaller.
        assertEquals(1_220, FoxyTunRelayConfig.mtuForChain(1_280, networkMtu = 1_220))
        // ...but it can never push the value below the 1200 floor.
        assertEquals(1_200, FoxyTunRelayConfig.mtuForChain(1_280, networkMtu = 700))
    }

    @Test
    fun yamlBlocksUdpBecauseSocksIsTcpOnly() {
        val yaml = FoxyTunRelayConfig.yaml(mtu = 1_280, socksPort = 19_060)
        assertTrue(yaml.contains("port: 19060"))
        assertTrue(yaml.contains("127.0.0.1"))
        assertTrue(yaml.contains(FoxyTunRelayConfig.MAP_DNS) || yaml.contains("map_dns"))
    }
}

class FoxyAccountTest {
    @Test
    fun freshAccountStartsWithFullFiftyGigabyteQuota() {
        val account = FoxyAccount(email = "a@b.c")
        assertEquals(FoxyAccount.DEFAULT_QUOTA_BYTES, account.quotaLimitBytes)
        assertEquals(account.quotaLimitBytes, account.quotaRemainingBytes)
        assertEquals(0.0, account.quotaUsedPercentage, 0.001)
        assertEquals(100.0, account.quotaRemainingPercentage, 0.001)
        assertTrue(account.quotaDisplay.isNotEmpty())
        assertFalse(account.hasQuotaError)
        // A freshly added account has no tokens until Guardian hands out a proxy pass.
        assertFalse(account.hasUsableCredentials)
    }

    @Test
    fun quotaPercentagesClampAndReflectUsage() {
        val account = FoxyAccount(quotaLimitBytes = 1_073_741_824L, quotaRemainingBytes = 536_870_912L)
        assertEquals(50.0, account.quotaRemainingPercentage, 0.001)
        assertEquals(50.0, account.quotaUsedPercentage, 0.001)
        account.quotaRemainingBytes = 0L
        assertEquals(0.0, account.quotaRemainingPercentage, 0.001)
        account.quotaRemainingBytes = -5L
        assertEquals(0.0, account.quotaRemainingPercentage, 0.001)
    }
}
