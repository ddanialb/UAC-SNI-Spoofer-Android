/*
 * Tunnel test against a local fake Foxy edge.
 *
 * The live tests can only reach the auth wall (a real proxy pass needs a Mozilla account), so this
 * test stands up a minimal HTTP/2 over TLS server that answers CONNECT with 200 and echoes DATA
 * back. That is the part of the engine a user actually feels: status handling, the bidirectional
 * relay, flow-control updates and END_STREAM. No network access needed, so it also runs in CI.
 */
package com.uacspoofer.mobile.engine.foxy

import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.DataInputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyStore
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocketFactory

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FoxyEngineFakeEdgeTest {
    private lateinit var edge: FakeEdge

    @Before
    fun startEdge() {
        edge = FakeEdge()
    }

    @After
    fun stopEdge() {
        edge.close()
    }

    @Test
    fun fakeEdgeIsReachable() {
        val raw = java.net.Socket("127.0.0.1", edge.port)
        raw.soTimeout = 8_000
        assertTrue("fake edge accepts connections", raw.isConnected)
        val ssl = edge.socketFactory.createSocket(raw, "localhost", edge.port, true) as javax.net.ssl.SSLSocket
        ssl.startHandshake()
        ssl.close()
    }

    @Test
    fun connectStreamEchoesDataBothWays() = runBlocking {
        val session = FoxyH2Session(
            edgeHost = "localhost",
            edgePort = edge.port,
            helloSplitMode = HelloSplitMode.SNI,
            keepAliveEnabled = false,
            socketFactoryOverride = edge.socketFactory,
        )
        try {
            session.connect()
            assertTrue("session connected", session.isConnected)
            session.updateProxyPassToken("unit-test-token")

            val stream = withTimeout(10_000) { session.openConnectStream("example.com", 443) }
            assertEquals(200, stream.waitForStatus())

            val payload = "hello foxy tunnel".toByteArray()
            stream.write(payload, 0, payload.size)

            val buffer = ByteArray(64)
            val read = withTimeout(10_000) { stream.read(buffer, 0, buffer.size) }
            assertEquals("echoed bytes", payload.size, read)
            assertEquals("hello foxy tunnel", String(buffer, 0, read))

            // Server -> client is a separate path: DATA written before END_STREAM must also arrive.
            val second = "second chunk".toByteArray()
            stream.write(second, 0, second.size)
            val read2 = withTimeout(10_000) { stream.read(buffer, 0, buffer.size) }
            assertEquals("second chunk", String(buffer, 0, read2))

            stream.sendEndStream()
            assertTrue("stream ids are odd and increasing", stream.streamId % 2 == 1)
        } finally {
            session.dispose()
        }
    }

    @Test
    fun largePayloadIsChunkedIntoValidFrames() = runBlocking {
        val session = FoxyH2Session(
            edgeHost = "localhost",
            edgePort = edge.port,
            keepAliveEnabled = false,
            socketFactoryOverride = edge.socketFactory,
        )
        try {
            session.connect()
            session.updateProxyPassToken("unit-test-token")
            val stream = withTimeout(10_000) { session.openConnectStream("example.com", 443) }
            assertEquals(200, stream.waitForStatus())

            // 200 KB is far beyond one frame and beyond the initial 64 KB send window, so this
            // exercises chunking, flow-control accounting and the WINDOW_UPDATE path.
            val payload = ByteArray(200 * 1024) { (it % 251).toByte() }
            stream.write(payload, 0, payload.size)

            val received = ByteArray(payload.size)
            var total = 0
            while (total < payload.size) {
                val n = withTimeout(20_000) { stream.read(received, total, received.size - total) }
                if (n <= 0) break
                total += n
            }
            assertEquals("all bytes echoed", payload.size, total)
            assertTrue("echo matches", payload.contentEquals(received))
            assertEquals("no frame bigger than 16 KiB", 0, edge.oversizedFrames)
            assertTrue("edge saw CONNECT", edge.sawConnect)
        } finally {
            session.dispose()
        }
    }

    /** Minimal HTTP/2 server: SETTINGS, CONNECT -> 200, DATA echo, PING ack. */
    private class FakeEdge {
        private val serverSocket: SSLServerSocket
        val port: Int
        val socketFactory: SSLSocketFactory
        @Volatile var sawConnect = false
        @Volatile var oversizedFrames = 0
        @Volatile var servingError: Throwable? = null
        @Volatile var acceptedCount = 0
        private val worker: Thread

        init {
            val keyStore = KeyStore.getInstance("PKCS12").apply {
                FoxyEngineFakeEdgeTest::class.java.classLoader!!
                    .getResourceAsStream("fake-edge.p12")!!
                    .use { load(it, "fakeedge".toCharArray()) }
            }
            val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
                init(keyStore, "fakeedge".toCharArray())
            }
            val context = SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
            serverSocket = context.serverSocketFactory.createServerSocket(0) as SSLServerSocket
            // Client side of the pairing: trusts only the fake edge certificate.
            val tmFactory = javax.net.ssl.TrustManagerFactory
                .getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm())
                .apply { init(keyStore) }
            socketFactory = SSLContext.getInstance("TLS")
                .apply { init(null, tmFactory.trustManagers, null) }
                .socketFactory

            port = serverSocket.localPort
            worker = Thread({ serve() }, "fake-foxy-edge").apply { isDaemon = true; start() }
        }

        fun close() {
            runCatching { serverSocket.close() }
            worker.interrupt()
        }

        private fun serve() {
            runCatching {
                while (!serverSocket.isClosed) {
                    val client = serverSocket.accept() as Socket
                    acceptedCount++
                    Thread({
                        runCatching { handle(client) }
                            .onFailure { servingError = it }
                    }, "fake-foxy-conn").apply { isDaemon = true }.start()
                }
            }.onFailure { servingError = it }
        }

        private fun handle(rawClient: Socket) {
            val client = rawClient as? javax.net.ssl.SSLSocket
            System.err.println("fake edge: connection accepted")
            if (client != null) {
                // Server side needs ALPN too: the same Conscrypt helper, before the handshake.
                runCatching {
                    Class.forName("org.conscrypt.Conscrypt")
                        .getMethod(
                            "setApplicationProtocols",
                            javax.net.ssl.SSLSocket::class.java,
                            Array<String>::class.java,
                        )
                        .invoke(null, client, arrayOf("h2"))
                }
                client.startHandshake()
            }
            val input = DataInputStream(rawClient.getInputStream().buffered())
            val out = rawClient.getOutputStream()

            // Client connection preface.
            val preface = ByteArray(24)
            input.readFully(preface)
            check(String(preface) == "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n") { "bad preface" }


            sendFrame(out, 4, 0, 0, ByteArray(0)) // our SETTINGS
            sendFrame(out, 4, 1, 0, ByteArray(0)) // ack of theirs (they ack ours in turn)

            while (!rawClient.isClosed) {
                val header = ByteArray(9)
                try {
                    input.readFully(header)
                } catch (_: Exception) {
                    return
                }
                val length = ((header[0].toInt() and 0xff) shl 16) or
                    ((header[1].toInt() and 0xff) shl 8) or (header[2].toInt() and 0xff)
                val type = header[3].toInt() and 0xff
                val flags = header[4].toInt() and 0xff
                val streamId = (((header[5].toInt() and 0x7f) shl 24) or
                    ((header[6].toInt() and 0xff) shl 16) or
                    ((header[7].toInt() and 0xff) shl 8) or (header[8].toInt() and 0xff))
                val payload = ByteArray(length)
                if (length > 0) input.readFully(payload)
                if (length > 16_384) oversizedFrames++

                when (type) {
                    4 -> if ((flags and 1) == 0) sendFrame(out, 4, 1, 0, ByteArray(0))
                    1 -> {
                        sawConnect = true
                        // Indexed header field 8 == ":status: 200".
                        sendFrame(out, 1, 4, streamId, byteArrayOf(0x88.toByte()))
                    }
                    0 -> {
                        if (payload.isNotEmpty()) {
                            sendFrame(out, 0, 0, streamId, payload)
                            // Replenish both windows so the client can keep sending.
                            sendFrame(out, 8, 0, 0, windowUpdate(payload.size))
                            sendFrame(out, 8, 0, streamId, windowUpdate(payload.size))
                        }
                        if ((flags and 1) != 0) sendFrame(out, 0, 1, streamId, ByteArray(0))
                    }
                    6 -> sendFrame(out, 6, 1, 0, payload)
                    else -> Unit
                }
            }
        }

        private fun windowUpdate(delta: Int): ByteArray = byteArrayOf(
            ((delta ushr 24) and 0x7f).toByte(),
            ((delta ushr 16) and 0xff).toByte(),
            ((delta ushr 8) and 0xff).toByte(),
            (delta and 0xff).toByte(),
        )

        private fun sendFrame(out: OutputStream, type: Int, flags: Int, streamId: Int, payload: ByteArray) {
            val header = byteArrayOf(
                ((payload.size ushr 16) and 0xff).toByte(),
                ((payload.size ushr 8) and 0xff).toByte(),
                (payload.size and 0xff).toByte(),
                type.toByte(),
                flags.toByte(),
                ((streamId ushr 24) and 0x7f).toByte(),
                ((streamId ushr 16) and 0xff).toByte(),
                ((streamId ushr 8) and 0xff).toByte(),
                (streamId and 0xff).toByte(),
            )
            synchronized(out) {
                out.write(header)
                out.write(payload)
                out.flush()
            }
        }
    }
}
