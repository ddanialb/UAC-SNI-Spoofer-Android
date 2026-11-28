package com.uacspoofer.mobile.engine.foxy

import com.uacspoofer.mobile.logging.AppLogRepository
import com.uacspoofer.mobile.logging.LogSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
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
    /**
     * Excludes the socket from the VPN. Without this the "direct" dial was routed back into the
     * TUN interface — i.e. into the very relay that opened it — so a paused tunnel looped instead
     * of leaving the device, and the app looked paused while traffic still went through Foxy.
     */
    private val protect: ((Socket) -> Unit)? = null,
) : FoxyRelayStream {
    private val socket: Socket
    private val input: InputStream
    private val output: OutputStream

    init {
        // Fast path: the app's own resolver (while paused its queries leave through the relay,
        // which dials them directly). Only if that produces nothing usable do we fall back to the
        // DoH/pinned pool, so a paused connect stays as quick as an unprotected one.
        val system = runCatching { java.net.InetAddress.getAllByName(host).toList() }.getOrDefault(emptyList())
        var winner = dialAny(system, host, port)
        if (winner == null) {
            runCatching {
                AppLogRepository.debug(
                    LogSource.APP,
                    "Foxy: paused dial to $host:$port fell back to DNS-over-HTTPS resolution",
                )
            }
            val doh = runCatching {
                kotlinx.coroutines.runBlocking { FoxyNetwork.resolveHostAddresses(host).toList() }
            }.getOrDefault(emptyList())
            winner = dialAny(doh, host, port)
        }
        socket = winner ?: throw java.io.IOException("cannot reach $host:$port directly")
        input = socket.getInputStream()
        output = socket.getOutputStream()
    }

    /** Dials the first address that answers, protecting every socket before it connects. */
    private fun dialAny(addresses: List<InetAddress>, host: String, port: Int): Socket? {
        for (address in addresses) {
            val candidate = Socket()
            candidate.tcpNoDelay = true
            candidate.soTimeout = 20_000
            runCatching { protect?.invoke(candidate) }
            try {
                candidate.connect(InetSocketAddress(address, port), DIRECT_CONNECT_TIMEOUT_MS)
                return candidate
            } catch (_: Exception) {
                runCatching { candidate.close() }
            }
        }
        if (addresses.isNotEmpty()) return null
        // No address list at all (name handed straight to the stack).
        return runCatching {
            Socket().apply {
                tcpNoDelay = true
                soTimeout = 20_000
                runCatching { protect?.invoke(this) }
                connect(InetSocketAddress(host, port), DIRECT_CONNECT_TIMEOUT_MS)
            }
        }.getOrNull()
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

    private companion object {
        const val DIRECT_CONNECT_TIMEOUT_MS = 8_000
    }
}
