/*
 * Live engine test: drives the real FoxyH2Session against Mozilla's real Fastly edges.
 *
 * Skipped unless FOXY_LIVE_TEST=1 so offline builds stay green. Run with:
 *   FOXY_LIVE_TEST=1 ./gradlew :app:testDebugUnitTest --tests "*FoxyEngineLiveTest*"
 */
package com.uacspoofer.mobile.engine.foxy

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FoxyEngineLiveTest {
    private val edgeHost = "muc139.m1.fastly-masque.net"

    @Before
    fun onlyWhenAsked() {
        assumeTrue("set FOXY_LIVE_TEST=1 to run", System.getenv("FOXY_LIVE_TEST") == "1")
    }

    /**
     * Pins the finding this suite uncovered: SSLParameters.setApplicationProtocols is silently
     * ignored by Conscrypt, so ALPN stayed unset ("ALPN=none") on every device. Only Conscrypt's
     * own helper actually negotiates h2, and the engine must keep using it.
     */
    @Test
    fun onlyConscryptHelperNegotiatesH2() {
        assertEquals("sslParameters must stay unsupported", null, tryAlpn("sslParameters") { ssl ->
            val params = ssl.sslParameters
            params.applicationProtocols = arrayOf("h2")
            ssl.sslParameters = params
        })
        assertEquals("Conscrypt.setApplicationProtocols must negotiate h2", "h2",
            tryAlpn("Conscrypt.setApplicationProtocols") { ssl ->
                val cls = Class.forName("org.conscrypt.Conscrypt")
                val m = cls.getMethod(
                    "setApplicationProtocols",
                    javax.net.ssl.SSLSocket::class.java,
                    Array<String>::class.java,
                )
                m.invoke(null, ssl, arrayOf("h2"))
            },
        )
    }

    private fun tryAlpn(label: String, configure: (javax.net.ssl.SSLSocket) -> Unit): String? {
        val host = "muc139.m1.fastly-masque.net"
        try {
            val raw = java.net.Socket()
            raw.tcpNoDelay = true
            raw.connect(java.net.InetSocketAddress(host, 2499), 10_000)
            val ssl = FoxyTrust.socketFactory.createSocket(raw, host, 2499, true) as javax.net.ssl.SSLSocket
            ssl.enabledProtocols = arrayOf("TLSv1.2", "TLSv1.3")
            configure(ssl)
            ssl.startHandshake()
            val negotiated = runCatching { ssl.applicationProtocol }.getOrNull()
            val legacy = runCatching {
                val m = ssl.javaClass.getMethod("getAlpnSelectedProtocol")
                m.isAccessible = true
                String(m.invoke(ssl) as ByteArray)
            }.getOrNull()
            println("[$label] negotiated=$negotiated legacy=$legacy via ${ssl.javaClass.simpleName}")
            ssl.close()
            return negotiated
        } catch (e: Exception) {
            println("[$label] FAILED ${e.javaClass.simpleName}: ${e.message}")
            return null
        }
    }

    @Test
    fun connectorReachesTheEdgeWithBundledRoots() = runBlocking {
        val session = FoxyH2Session(
            edgeHost = edgeHost,
            edgePort = 2499,
            proxySettings = null,
            avoidAddress = null,
            vpnService = null,
            helloSplitMode = HelloSplitMode.SNI,
            ipMode = FoxyEngineSettings.IP_AUTO,
            keepAliveEnabled = true,
        )
        session.onFailed = { reason -> println("session.onFailed: $reason") }
        try {
            session.connect()
            println("after connect: connected=${session.isConnected} canOpen=${session.canOpenNewStreams}")
            kotlinx.coroutines.delay(150)
            println("after 150ms: connected=${session.isConnected} canOpen=${session.canOpenNewStreams} streams=${session.activeStreamCount}")
            assertTrue("session reports connected", session.isConnected)
            assertTrue("session accepts new streams", session.canOpenNewStreams)
        } finally {
            session.dispose()
        }
    }

    @Test
    fun everyHelloSplitModeCompletesTheHandshake() = runBlocking {
        for (mode in HelloSplitMode.entries) {
            val session = FoxyH2Session(
                edgeHost = edgeHost,
                edgePort = 2499,
                helloSplitMode = mode,
            )
            try {
                session.connect()
                assertTrue("mode $mode connected", session.isConnected)
            } finally {
                session.dispose()
            }
        }
    }

    @Test
    fun connectStreamSendsAWellFormedRequest() = runBlocking {
        val session = FoxyH2Session(edgeHost = edgeHost, edgePort = 2499)
        try {
            session.connect()
            // No proxy pass yet: the engine must refuse locally instead of opening a stream.
            try {
                session.openConnectStream("example.com", 80)
                fail("expected the engine to refuse a CONNECT without a proxy pass")
            } catch (e: Exception) {
                assertTrue(
                    "unexpected error: ${e.message}",
                    e.message.orEmpty().contains("proxy pass", ignoreCase = true),
                )
            }
            // With a bogus pass the edge must answer a proper HTTP/2 status, not reset the stream.
            session.updateProxyPassToken("bogus.jwt.token")
            try {
                session.openConnectStream("example.com", 80)
                fail("the edge should reject an invalid proxy pass")
            } catch (e: Exception) {
                val message = e.message.orEmpty()
                assertNotNull(message, e)
                println("edge answer: $message")
                assertTrue(
                    "expected an HTTP status rejection, got: $message",
                    message.contains("400") || message.contains("401") || message.contains("403") ||
                        message.contains("rejected", ignoreCase = true),
                )
            }
        } finally {
            session.dispose()
        }
    }

    @Test
    fun severalConcurrentConnectStreamsStayHealthy() = runBlocking {
        val session = FoxyH2Session(edgeHost = edgeHost, edgePort = 2499)
        try {
            session.connect()
            session.updateProxyPassToken("bogus.jwt.token")
            val results = coroutineScope {
                (1..3).map { index ->
                    async(Dispatchers.IO) {
                        runCatching { session.openConnectStream("example$index.com", 443) }
                            .exceptionOrNull()?.message.orEmpty()
                    }
                }.awaitAll()
            }
            println("concurrent answers: $results")
            assertTrue("every stream got an HTTP answer: $results", results.all { it.contains("HTTP 4") })
            assertTrue("session survives concurrent streams", session.canOpenNewStreams)
        } finally {
            session.dispose()
        }
    }

    @Test
    fun port443IsRejectedInsteadOfFaked() = runBlocking {
        val session = FoxyH2Session(edgeHost = edgeHost, edgePort = 443)
        try {
            session.connect()
            fail("443 is not a Foxy edge and must not be accepted")
        } catch (e: Exception) {
            val message = e.message.orEmpty()
            println("443 rejection: $message")
            // 443 is Fastly's CDN: it may speak h2 but never presents a certificate for the
            // masque hostname, so the engine must refuse it either way.
            assertTrue(
                "443 must be rejected, got: $message",
                message.contains("HTTP/2", ignoreCase = true) ||
                    message.contains("not a Foxy edge", ignoreCase = true) ||
                    message.contains("certificate", ignoreCase = true),
            )
        } finally {
            session.dispose()
        }
    }
}
