package com.uacspoofer.mobile.engine.foxy

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * What the SOCKS/HTTP relays pump bytes through. Normally that is an HTTP/2 CONNECT stream on the
 * edge; while the tunnel is "paused" it is a plain socket, so the VPN keeps running but traffic
 * leaves the device directly (the same semantics PoW/Tor get from their SOCKS split).
 */
internal interface FoxyRelayStream {
    suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int
    suspend fun write(buffer: ByteArray, offset: Int, length: Int)
    suspend fun sendEndStream()
    fun dispose()
}

internal class FoxyDirectStream(
    host: String,
    port: Int,
    private val socket: Socket = Socket(),
) : FoxyRelayStream {
    private val input: InputStream
    private val output: OutputStream

    init {
        socket.tcpNoDelay = true
        socket.soTimeout = 20_000
        socket.connect(InetSocketAddress(host, port), 10_000)
        input = socket.getInputStream()
        output = socket.getOutputStream()
    }

    override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        withContext(Dispatchers.IO) { input.read(buffer, offset, length) }

    override suspend fun write(buffer: ByteArray, offset: Int, length: Int) =
        withContext(Dispatchers.IO) {
            output.write(buffer, offset, length)
            output.flush()
        }

    override suspend fun sendEndStream() {
        runCatching { withContext(Dispatchers.IO) { socket.shutdownOutput() } }
    }

    override fun dispose() {
        runCatching { socket.close() }
    }
}
