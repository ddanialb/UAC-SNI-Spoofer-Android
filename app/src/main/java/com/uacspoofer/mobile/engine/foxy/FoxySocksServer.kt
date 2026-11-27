/*
 * Ported from Se7en Pro (MIT) — Se7enPro/Services/Foxy/FoxyEngine.cs (SOCKS/HTTP listeners + Relay)
 * Copyright (c) 2026 kng7-p (King Network). MIT License.
 * Adapted for UAC SNI Spoofer Android (AGPLv3).
 * Local SOCKS5 (no-auth) + HTTP CONNECT on 127.0.0.1, relayed via H2 CONNECT streams.
 */
package com.uacspoofer.mobile.engine.foxy

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicLong

/**
 * Converts a proxy-style request head into one an origin server will accept:
 * origin-form request line, hop-by-hop proxy headers removed, Host guaranteed present.
 */
internal fun rewriteToOriginForm(head: String, url: java.net.URL, host: String, port: Int): String {
    val lines = head.split("\r\n")
    val first = lines.firstOrNull()?.trim().orEmpty()
    val parts = first.split(' ', limit = 3)
    val verb = if (parts.isNotEmpty() && parts[0].isNotEmpty()) parts[0] else "GET"
    val version = if (parts.size == 3) parts[2] else "HTTP/1.1"
    val rawPath = buildString {
        append(url.path.ifEmpty { "/" })
        url.query?.let { append('?').append(it) }
    }
    val path = if (rawPath.startsWith("/")) rawPath else "/$rawPath"
    val out = StringBuilder()
    out.append(verb).append(' ').append(path).append(' ').append(version).append("\r\n")
    var hasHost = false
    for (line in lines.drop(1)) {
        if (line.isEmpty()) continue
        val name = line.substringBefore(':', "").trim()
        if (name.equals("Host", ignoreCase = true)) {
            hasHost = true
            out.append(line).append("\r\n")
            continue
        }
        // Proxy-only hop-by-hop headers must not be forwarded through the tunnel.
        if (name.equals("Proxy-Connection", ignoreCase = true) ||
            name.equals("Proxy-Authorization", ignoreCase = true) ||
            name.equals("Proxy-Authenticate", ignoreCase = true)
        ) {
            continue
        }
        out.append(line).append("\r\n")
    }
    if (!hasHost) {
        val suffix = if (port == 80 || port == 443) host else "$host:$port"
        out.append("Host: ").append(suffix).append("\r\n")
    }
    out.append("\r\n")
    return out.toString()
}

class FoxySocksServer(
    private val sessionProvider: suspend () -> FoxyH2Session?,
    private val openTunnel: suspend (session: FoxyH2Session, host: String, port: Int) -> FoxyH2Stream?,
    private val onBytes: (sent: Long, received: Long) -> Unit = { _, _ -> },
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var socksListener: ServerSocket? = null
    private var httpListener: ServerSocket? = null
    private var acceptJob: Job? = null
    private val bytesSent = AtomicLong(0L)
    private val bytesReceived = AtomicLong(0L)

    var socksPort: Int = 0
        private set
    var httpPort: Int = 0
        private set

    fun start(socksRequested: Int, httpRequested: Int) {
        stop()
        socksListener = bindLoopback(socksRequested).also { socksPort = it.localPort }
        httpListener = bindLoopback(httpRequested).also { httpPort = it.localPort }
        acceptJob = scope.launch {
            launch { acceptLoop(socksListener!!, ::handleSocksClient) }
            launch { acceptLoop(httpListener!!, ::handleHttpClient) }
        }
    }

    fun stop() {
        runCatching { socksListener?.close() }
        runCatching { httpListener?.close() }
        socksListener = null
        httpListener = null
        socksPort = 0
        httpPort = 0
    }

    fun dispose() {
        stop()
        scope.cancel()
    }

    private fun bindLoopback(requested: Int): ServerSocket {
        val port = if (requested in 1024..65_535) requested else 0
        return try {
            ServerSocket(port, 50, InetAddress.getByName("127.0.0.1"))
        } catch (_: Exception) {
            ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        }
    }

    private suspend fun acceptLoop(listener: ServerSocket, handler: suspend (Socket) -> Unit) {
        while (!listener.isClosed) {
            val client = try {
                listener.accept()
            } catch (_: IOException) {
                break
            }
            client.tcpNoDelay = true
            scope.launch {
                try {
                    handler(client)
                } catch (_: CancellationException) {
                } catch (_: Exception) {
                } finally {
                    runCatching { client.close() }
                }
            }
        }
    }

    private suspend fun handleSocksClient(client: Socket) {
        val input = client.getInputStream()
        val output = client.getOutputStream()
        val greeting = ByteArray(2)
        readExact(input, greeting, 2)
        if (greeting[0] != 5.toByte()) return
        val methods = ByteArray(greeting[1].toInt() and 0xff)
        if (methods.isNotEmpty()) readExact(input, methods, methods.size)
        output.write(byteArrayOf(5, 0))
        output.flush()
        val header = ByteArray(4)
        readExact(input, header, 4)
        if (header[0] != 5.toByte() || header[1] != 1.toByte()) {
            output.write(socksReply(7))
            output.flush()
            return
        }
        val targetHost: String = when (header[3].toInt() and 0xff) {
            1 -> {
                val ip = ByteArray(4)
                readExact(input, ip, 4)
                InetAddress.getByAddress(ip).hostAddress ?: return
            }
            3 -> {
                val length = readByte(input)
                if (length <= 0) return
                val name = ByteArray(length)
                readExact(input, name, length)
                String(name, StandardCharsets.US_ASCII)
            }
            4 -> {
                val ip = ByteArray(16)
                readExact(input, ip, 16)
                InetAddress.getByAddress(ip).hostAddress ?: return
            }
            else -> {
                output.write(socksReply(8))
                output.flush()
                return
            }
        }
        val portBytes = ByteArray(2)
        readExact(input, portBytes, 2)
        val targetPort = ((portBytes[0].toInt() and 0xff) shl 8) or (portBytes[1].toInt() and 0xff)
        val session = sessionProvider() ?: run {
            output.write(socksReply(5))
            output.flush()
            return
        }
        val tunnel: FoxyH2Stream? = try {
            openTunnel(session, targetHost, targetPort)
        } catch (e: FoxyTargetRejectedException) {
            output.write(socksReply(2))
            output.flush()
            return
        } catch (e: FoxyQuotaExceededException) {
            output.write(socksReply(4))
            output.flush()
            return
        } catch (_: Exception) {
            output.write(socksReply(4))
            output.flush()
            return
        }
        if (tunnel == null) {
            output.write(socksReply(4))
            output.flush()
            return
        }
        output.write(socksReply(0))
        output.flush()
        try {
            relay(input, output, tunnel)
        } finally {
            tunnel.dispose()
        }
    }

    private suspend fun handleHttpClient(client: Socket) {
        val input = client.getInputStream()
        val output = client.getOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        var headerEnd = -1
        val headerDeadline = System.currentTimeMillis() + 15_000
        while (total < 8192 && System.currentTimeMillis() < headerDeadline) {
            val read = input.read(buffer, total, 8192 - total)
            if (read <= 0) return
            total += read
            for (i in 3 until total) {
                if (buffer[i - 3] == '\r'.code.toByte() && buffer[i - 2] == '\n'.code.toByte() &&
                    buffer[i - 1] == '\r'.code.toByte() && buffer[i] == '\n'.code.toByte()
                ) {
                    headerEnd = i + 1
                    break
                }
            }
            if (headerEnd >= 0) break
        }
        if (headerEnd < 0) return
        val head = String(buffer, 0, headerEnd, StandardCharsets.US_ASCII)
        val lineEnd = head.indexOf("\r\n")
        if (lineEnd < 0) return
        val requestLine = head.substring(0, lineEnd).trim().split(' ', limit = 3)
        if (requestLine.size < 2) return
        val verb = requestLine[0]
        val target = requestLine[1]
        if (verb.equals("CONNECT", ignoreCase = true)) {
            val (host, port) = splitHostPort(target, 443)
            val tunnel = openTunnelForHttp(output, host, port) ?: return
            output.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
            output.flush()
            val leftover = total - headerEnd
            if (leftover > 0) tunnel.write(buffer, headerEnd, leftover)
            try {
                relay(input, output, tunnel)
            } finally {
                tunnel.dispose()
            }
            return
        }
        // Absolute-form ("GET http://host/path") is what browsers send to an HTTP proxy. The tunnel
        // we open targets that host directly, so the request must be rewritten to origin-form
        // ("GET /path"); forwarding the absolute URI would reach the origin server unchanged.
        val absolute = target.startsWith("http://", ignoreCase = true) || target.startsWith("https://", ignoreCase = true)
        val targetHost: String
        val targetPort: Int
        var outgoingHead = head
        if (absolute) {
            val url = try {
                java.net.URL(target)
            } catch (_: Exception) {
                writeHttpError(output, "400 Bad Request")
                return
            }
            targetHost = url.host
            targetPort = if (url.port > 0) url.port else if (url.protocol == "https") 443 else 80
            outgoingHead = rewriteToOriginForm(head, url, targetHost, targetPort)
        } else {
            var host = ""
            var port = 80
            for (line in head.split("\r\n")) {
                if (line.startsWith("Host:", ignoreCase = true)) {
                    val parsed = splitHostPort(line.substring(5).trim(), 80)
                    host = parsed.first
                    port = parsed.second
                    break
                }
            }
            targetHost = host
            targetPort = port
        }
        if (targetHost.isEmpty()) {
            writeHttpError(output, "400 Bad Request")
            return
        }
        val plain = openTunnelForHttp(output, targetHost, targetPort) ?: return
        val outgoingBytes = outgoingHead.toByteArray(StandardCharsets.US_ASCII)
        plain.write(outgoingBytes, 0, outgoingBytes.size)
        // Any body bytes already read with the headers follow the rewritten head.
        val bodySoFar = total - headerEnd
        if (bodySoFar > 0) plain.write(buffer, headerEnd, bodySoFar)
        try {
            relay(input, output, plain)
        } finally {
            plain.dispose()
        }
    }

    private suspend fun openTunnelForHttp(output: OutputStream, host: String, port: Int): FoxyH2Stream? {
        val session = sessionProvider() ?: run {
            writeHttpError(output, "502 Bad Gateway")
            return null
        }
        return try {
            openTunnel(session, host, port) ?: run {
                writeHttpError(output, "502 Bad Gateway")
                null
            }
        } catch (_: FoxyQuotaExceededException) {
            writeHttpError(output, "502 Bad Gateway")
            null
        } catch (e: FoxyTargetRejectedException) {
            writeHttpError(output, "403 Forbidden")
            null
        } catch (_: Exception) {
            writeHttpError(output, "504 Gateway Timeout")
            null
        }
    }

    private fun writeHttpError(output: OutputStream, status: String) {
        runCatching {
            output.write("HTTP/1.1 $status\r\nConnection: close\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
            output.flush()
        }
    }

    private fun splitHostPort(value: String, defaultPort: Int): Pair<String, Int> {
        val colon = value.lastIndexOf(':')
        if (colon > 0) {
            val parsed = value.substring(colon + 1).toIntOrNull()
            if (parsed != null) return value.substring(0, colon).trim('[', ']') to parsed
        }
        return value.trim('[', ']') to defaultPort
    }

    /**
     * Pumps bytes in both directions between the client socket and the H2 tunnel stream. The two
     * halves run as siblings in a [coroutineScope] so a failure in one cancels the other instead of
     * leaking a blocked reader.
     */
    private suspend fun relay(clientIn: InputStream, clientOut: OutputStream, tunnel: FoxyH2Stream) =
        coroutineScope {
            val up = async(Dispatchers.IO) { pumpClientToTunnel(clientIn, tunnel) }
            val down = async(Dispatchers.IO) { pumpTunnelToClient(tunnel, clientOut) }
            try {
                up.await()
                down.await()
            } finally {
                up.cancel()
                down.cancel()
                runCatching { tunnel.sendEndStream() }
            }
        }

    private suspend fun pumpClientToTunnel(from: InputStream, to: FoxyH2Stream) {
        val buffer = ByteArray(RELAY_BUFFER_SIZE)
        try {
            while (true) {
                val read = try {
                    from.read(buffer)
                } catch (_: Exception) {
                    -1
                }
                if (read <= 0) break
                to.write(buffer, 0, read)
                val total = bytesSent.addAndGet(read.toLong())
                onBytes(total, bytesReceived.get())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Peer closed; the tunnel side is torn down by the caller.
        }
    }

    private suspend fun pumpTunnelToClient(from: FoxyH2Stream, to: OutputStream) {
        val buffer = ByteArray(RELAY_BUFFER_SIZE)
        try {
            while (true) {
                val read = from.read(buffer, 0, buffer.size)
                if (read <= 0) break
                to.write(buffer, 0, read)
                to.flush()
                val total = bytesReceived.addAndGet(read.toLong())
                onBytes(bytesSent.get(), total)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Client hung up or the stream ended; nothing to salvage.
        }
    }

    private fun readExact(input: InputStream, buffer: ByteArray, count: Int) {
        var read = 0
        while (read < count) {
            val n = input.read(buffer, read, count - read)
            if (n == -1) throw IOException("EOF")
            read += n
        }
    }

    private fun readByte(input: InputStream): Int {
        val b = input.read()
        if (b == -1) throw IOException("EOF")
        return b
    }

    private fun socksReply(code: Int): ByteArray = byteArrayOf(5, code.toByte(), 0, 1, 0, 0, 0, 0, 0, 0)

    private companion object {
        const val RELAY_BUFFER_SIZE = 32 * 1024
    }
}
