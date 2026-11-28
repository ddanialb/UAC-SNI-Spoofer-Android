package com.uacspoofer.mobile.engine.foxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FoxySocksServerTest {
    @Test
    fun absoluteFormRequestIsRewrittenToOriginForm() {
        val head = "GET http://example.com/a/b?x=1 HTTP/1.1\r\n" +
            "Host: example.com\r\n" +
            "Proxy-Connection: keep-alive\r\n" +
            "Proxy-Authorization: Basic abc\r\n" +
            "Accept: */*\r\n\r\n"
        val rewritten = rewriteToOriginForm(
            head,
            java.net.URL("http://example.com/a/b?x=1"),
            "example.com",
            80,
        )
        assertTrue(rewritten.startsWith("GET /a/b?x=1 HTTP/1.1\r\n"))
        assertFalse(rewritten.contains("http://example.com/a/b"))
        // Hop-by-hop proxy headers must not leak through the CONNECT tunnel.
        assertFalse(rewritten.contains("Proxy-Connection"))
        assertFalse(rewritten.contains("Proxy-Authorization"))
        assertTrue(rewritten.contains("Host: example.com"))
        assertTrue(rewritten.endsWith("\r\n\r\n"))
    }

    @Test
    fun emptyPathFallsBackToSlashAndSynthesizesHost() {
        val rewritten = rewriteToOriginForm(
            "GET http://example.com HTTP/1.1\r\nAccept: */*\r\n\r\n",
            java.net.URL("http://example.com"),
            "example.com",
            80,
        )
        assertTrue(rewritten.startsWith("GET / HTTP/1.1\r\n"))
        assertTrue(rewritten.contains("Host: example.com\r\n"))
    }

    @Test
    fun nonDefaultPortIsKeptInSynthesizedHostHeader() {
        val rewritten = rewriteToOriginForm(
            "POST http://example.com:8080/api HTTP/1.1\r\nContent-Length: 0\r\n\r\n",
            java.net.URL("http://example.com:8080/api"),
            "example.com",
            8080,
        )
        assertTrue(rewritten.startsWith("POST /api HTTP/1.1\r\n"))
        assertTrue(rewritten.contains("Host: example.com:8080\r\n"))
    }

    @Test
    fun existingHostHeaderIsPreservedVerbatim() {
        val head = "GET http://example.com/x HTTP/1.1\r\nHost: other.example.org\r\n\r\n"
        val rewritten = rewriteToOriginForm(
            head,
            java.net.URL("http://example.com/x"),
            "example.com",
            80,
        )
        assertTrue(rewritten.contains("Host: other.example.org"))
        assertEquals(1, Regex("(?i)host:").findAll(rewritten).count())
    }

    @Test
    fun base64HelperIsCorrectForProxyCredentials() {
        // Guards the shared encoder used for upstream `Proxy-Authorization`.
        assertEquals("dXNlcjpwYXNz", FoxyUpstreamProxy.base64("user:pass".toByteArray()))
        assertEquals("", FoxyUpstreamProxy.base64(ByteArray(0)))
    }
}
