/*
 * Ported from Se7en Pro (MIT) — Se7enPro/Services/Foxy/FoxyH2Session.cs
 * Copyright (c) 2026 kng7-p (King Network). MIT License.
 * Adapted for UAC SNI Spoofer Android (AGPLv3).
 * TLS via SSLSocket with ALPN "h2" (API 29+ direct, reflection fallback for API 24-28).
 */
package com.uacspoofer.mobile.engine.foxy

import android.net.VpnService
import com.uacspoofer.mobile.logging.AppLogRepository
import com.uacspoofer.mobile.logging.LogSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

class FoxyH2Session(
    val edgeHost: String,
    val edgePort: Int = 2499,
    private val proxySettings: UpstreamProxySettings? = null,
    private val avoidAddress: InetAddress? = null,
    private val vpnService: VpnService? = null,
    /** How the TLS ClientHello is fragmented on the wire (DPI evasion). */
    private val helloSplitMode: HelloSplitMode = HelloSplitMode.SNI,
    /** Prefer IPv4 addresses when the edge resolves to both families. */
    private val preferIpv4: Boolean = true,
    /** Send PING frames so a silently-dead edge is detected. */
    private val keepAliveEnabled: Boolean = true,
    /** Test seam: lets the fake-edge tunnel test terminate TLS with its own certificate. */
    private val socketFactoryOverride: SSLSocketFactory? = null,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + foxyCoroutineGuard("h2-session"))
    private val writeLock = Mutex()
    private val activeStreams = ConcurrentHashMap<Int, FoxyH2Stream>()
    private val pendingHeaderBlocks = ConcurrentHashMap<Int, ByteArray>()
    private val hpackDynamicTable = mutableListOf<Pair<String, String>>()
    private val windowLock = Any()
    /** Coalescing signal: kotlinx Semaphore(0) is illegal (it requires >= 1 permit). */
    private val sendWindowSignal = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)
    private var peerInitialWindowSize = 65535

    @Volatile
    private var peerMaxFrameSize = DEFAULT_MAX_FRAME_SIZE
    private var connSendWindow = 65535L
    private val nextStreamId = AtomicInteger(1)
    private val disposed = AtomicBoolean(false)
    private val failedRaised = AtomicBoolean(false)
    @Volatile private var goAwayReceived = false
    @Volatile private var draining = false
    @Volatile private var goAwayFatal = false
    @Volatile private var proxyPassToken = ""
    private val pingsOutstanding = AtomicInteger(0)
    private val drainNotified = AtomicBoolean(false)
    /** Completed as soon as the edge sends its HTTP/2 SETTINGS frame. */
    private val serverSettings = kotlinx.coroutines.CompletableDeferred<Unit>()

    @Volatile private var socket: Socket? = null
    @Volatile private var sslSocket: SSLSocket? = null
    @Volatile private var readerJob: Job? = null
    @Volatile private var keepAliveJob: Job? = null

    var onDraining: ((String) -> Unit)? = null
    var onFailed: ((String) -> Unit)? = null

    val activeStreamCount: Int get() = activeStreams.size
    val isConnected: Boolean get() = !disposed.get() && !goAwayReceived && socket?.isConnected == true && sslSocket != null
    val canOpenNewStreams: Boolean get() = !disposed.get() && !draining && !goAwayFatal && socket?.isConnected == true && sslSocket != null
    val remoteAddress: InetAddress? get() = socket?.remoteSocketAddress?.let { (it as? InetSocketAddress)?.address }

    private fun signalSendWindow() {
        runCatching { sendWindowSignal.trySend(Unit) }
    }

    fun raiseFailed(reason: String) {
        if (disposed.get() || !failedRaised.compareAndSet(false, true)) return
        AppLogRepository.warning(LogSource.APP, "Foxy H2 session to $edgeHost ended: $reason")
        runCatching { onFailed?.invoke(reason) }
    }

    fun updateProxyPassToken(token: String) {
        if (token.isBlank()) return
        proxyPassToken = token
    }

    suspend fun connect() = withContext(Dispatchers.IO) {
        AppLogRepository.info(
            LogSource.APP,
            "Foxy: connecting H2 tunnel to $edgeHost:$edgePort (hello split=${helloSplitMode.name.lowercase()})",
        )
        val raw = Socket()
        raw.tcpNoDelay = true
        raw.soTimeout = HANDSHAKE_TIMEOUT_MS
        runCatching { raw.keepAlive = true }
        if (proxySettings?.enabled == true && proxySettings.host.isNotEmpty()) {
            connectViaProxy(raw, proxySettings, edgeHost, edgePort)
        } else {
            connectDirect(raw)
        }
        socket = raw

        // Wrap so the ClientHello leaves in several TCP segments (SNI hiding).
        val transport: Socket = if (helloSplitMode == HelloSplitMode.OFF) raw else ClientHelloSplitSocket(raw, helloSplitMode)
        // Device trust store + the public roots the Fastly edges chain to (see FoxyTrust).
        val factory = socketFactoryOverride ?: FoxyTrust.socketFactory
        val ssl = factory.createSocket(transport, edgeHost, edgePort, true) as SSLSocket
        ssl.enabledProtocols = arrayOf("TLSv1.2", "TLSv1.3")
        setAlpn(ssl, arrayOf("h2"))
        ssl.startHandshake()

        val negotiated = getAlpn(ssl)
        AppLogRepository.debug(LogSource.APP, "Foxy: edge ALPN = ${negotiated ?: "none"}")
        if (negotiated != "h2") {
            // Fastly's plain CDN endpoint answers on some ports with a valid certificate for a
            // different hostname and ALPN "null": treating that as a session made the app report
            // CONNECTED and drop the tunnel a heartbeat later.
            ssl.close()
            raw.close()
            throw IOException(
                "edge $edgeHost:$edgePort did not negotiate HTTP/2 (ALPN=${negotiated ?: "none"}); this port is not a Foxy edge",
            )
        }
        if (!verifyHostname(ssl)) {
            ssl.close()
            raw.close()
            throw IOException("edge certificate is not valid for $edgeHost (possible interception)")
        }
        sslSocket = ssl
        ssl.soTimeout = READ_IDLE_TIMEOUT_MS

        val out = ssl.outputStream
        out.write(H2_PREFACE)
        out.flush()
        writeFrame(4, 0, 0, byteArrayOf(0, 2, 0, 0, 0, 0, 0, 4, 1, 0, 0, 0, 0, 5, 0, 4, 0, 0))
        // WINDOW_UPDATE payload must be exactly 4 bytes; a 5th byte made the edge answer
        // GOAWAY FRAME_SIZE_ERROR before a single stream could be opened.
        writeFrame(8, 0, 0, encodeWindowUpdatePayload(INITIAL_CONNECTION_WINDOW))
        readerJob = scope.launch { readerLoop() }
        val ready = withTimeoutOrNull(HANDSHAKE_TIMEOUT_MS.toLong()) { serverSettings.await() } != null
        if (!ready) {
            dispose()
            throw IOException("edge $edgeHost:$edgePort accepted TLS but never answered the HTTP/2 handshake")
        }
        keepAliveJob = if (keepAliveEnabled) scope.launch { keepAliveLoop() } else null
        AppLogRepository.info(LogSource.APP, "Foxy: H2 tunnel session established with $edgeHost:$edgePort")
    }

    /**
     * Dials the edge directly, preferring IPv4 and trying every resolved address. Creating an
     * IPv6 socket and connecting it to an IPv4-only network is what produced
     * `connect failed: ENETUNREACH` on Iranian carriers (the socket was bound to `::`).
     */
    private suspend fun connectDirect(raw: Socket) {
        val resolved = runCatching { FoxyNetwork.resolveHostAddresses(edgeHost).toList() }.getOrDefault(emptyList())
            .ifEmpty {
                runCatching { InetAddress.getAllByName(edgeHost).toList() }.getOrDefault(emptyList())
            }
        if (resolved.isEmpty()) throw IOException("cannot resolve edge host $edgeHost")
        val ordered = resolved.sortedWith(
            compareBy(
                { if (preferIpv4 && it is java.net.Inet4Address) 0 else 1 },
                { if (it == avoidAddress) 1 else 0 },
            ),
        )
        val service = vpnService
        if (service != null && com.uacspoofer.mobile.vpn.SocketProtectGate.isEnabled(service)) {
            runCatching { service.protect(raw) }
        }
        var lastError: Exception? = null
        for (address in ordered.take(MAX_ADDRESS_ATTEMPTS)) {
            try {
                raw.connect(java.net.InetSocketAddress(address, edgePort), ADDRESS_CONNECT_TIMEOUT_MS)
                if (address != ordered.first()) {
                    AppLogRepository.info(LogSource.APP, "Foxy: edge $edgeHost:$edgePort reached via ${address.hostAddress}")
                }
                return
            } catch (e: Exception) {
                lastError = e
                AppLogRepository.debug(
                    LogSource.APP,
                    "Foxy: edge $edgeHost:$edgePort address ${address.hostAddress} failed: ${e.message}",
                )
                runCatching { raw.close() }
            }
        }
        throw lastError ?: IOException("cannot reach edge $edgeHost:$edgePort")
    }

    private fun verifyHostname(ssl: SSLSocket): Boolean = runCatching {
        val verifier = javax.net.ssl.HttpsURLConnection.getDefaultHostnameVerifier()
        verifier.verify(edgeHost, ssl.session)
    }.getOrDefault(false)

    /**
     * ALPN must be real: the Fastly edge only speaks Foxy when "h2" is negotiated. Reflection on
     * `ssl.javaClass` always failed (Conscrypt/JDK socket classes are not public), so ALPN was
     * silently left unset and every handshake ended as `ALPN=none`.
     */
    /**
     * ALPN is what makes the edge speak Foxy at all, and getting it switched on is fiddly:
     * `SSLParameters.setApplicationProtocols` is silently ignored by Conscrypt (verified live:
     * the negotiated protocol stayed null and every edge answered "not HTTP/2"), and Android's
     * SSLSocket has no setApplicationProtocols() of its own. So every known route is applied:
     * the Conscrypt socket method Android exposes, the Conscrypt helper (bundled and platform
     * package names), and finally SSLParameters for non-Conscrypt stacks.
     */
    private fun setAlpn(ssl: SSLSocket, protocols: Array<String>) {
        var applied = false
        // 1. Conscrypt socket method (what OkHttp uses on Android).
        applied = runCatching {
            val m = ssl.javaClass.getMethod("setAlpnProtocols", Array<ByteArray>::class.java)
            m.isAccessible = true
            m.invoke(ssl, protocols.map { it.toByteArray(StandardCharsets.UTF_8) } as Any)
            true
        }.getOrDefault(false)
        // 2/3. org.conscrypt.Conscrypt (bundled) and com.android.org.conscrypt.Conscrypt (platform).
        for (className in CONSCRYPT_CLASS_NAMES) {
            applied = applyConscryptAlpn(className, ssl, protocols) || applied
        }
        // 4. Plain SSLParameters.
        if (!applied) {
            runCatching {
                val params = ssl.sslParameters
                params.applicationProtocols = protocols
                ssl.sslParameters = params
            }
        }
    }

    private fun applyConscryptAlpn(className: String, ssl: SSLSocket, protocols: Array<String>): Boolean =
        runCatching {
            val method = Class.forName(className).getMethod(
                "setApplicationProtocols",
                javax.net.ssl.SSLSocket::class.java,
                Array<String>::class.java,
            )
            method.invoke(null, ssl, protocols)
            true
        }.getOrDefault(false)

    private fun getAlpn(ssl: SSLSocket): String? {
        runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                ssl.applicationProtocol?.let { return it }
            }
        }
        runCatching {
            val m = ssl.javaClass.getMethod("getAlpnSelectedProtocol")
            m.isAccessible = true
            val bytes = m.invoke(ssl) as? ByteArray ?: return null
            return String(bytes, StandardCharsets.UTF_8)
        }
        return null
    }

    private suspend fun keepAliveLoop() {
        try {
            while (!disposed.get() && isConnected) {
                delay(KEEP_ALIVE_INTERVAL_MS)
                if (!isConnected) break
                if (pingsOutstanding.getAndSet(0) != 0) {
                    raiseFailed("edge stopped answering PING - connection is dead")
                    break
                }
                pingsOutstanding.incrementAndGet()
                writeFrame(6, 0, 0, ByteArray(8))
            }
        } catch (_: CancellationException) {
        } catch (e: Exception) {
            if (!disposed.get()) raiseFailed("keepalive failed: ${e.message}")
        }
    }

    suspend fun openConnectStream(targetHost: String, targetPort: Int, proxyPassTokenOverride: String? = null): FoxyH2Stream {
        if (!canOpenNewStreams) {
            if (!draining) raiseFailed("HTTP/2 session is not accepting new streams (closed).")
            throw IOException("HTTP/2 session is not accepting new streams.")
        }
        val token = if (proxyPassTokenOverride.isNullOrBlank()) proxyPassToken else proxyPassTokenOverride
        if (token.isBlank()) throw IOException("No proxy pass token available for CONNECT.")
        val authority = if (targetHost.contains(":")) "[$targetHost]:$targetPort" else "$targetHost:$targetPort"
        val streamId: Int
        val channel: FoxyH2Stream
        writeLock.withLock {
            streamId = nextStreamId.getAndAdd(2)
            if (streamId <= 0) throw IOException("HTTP/2 stream ids exhausted; reconnect required.")
            val initialWindow = synchronized(windowLock) { peerInitialWindowSize }
            channel = FoxyH2Stream(streamId, this, initialWindow)
            activeStreams[streamId] = channel
            val headerBlock = HpackCodec.encodeConnectHeaders(authority, token)
            val maxFrame = peerMaxFrameSize
            if (headerBlock.size <= maxFrame) {
                writeFrameLocked(1, 4, streamId, headerBlock)
            } else {
                // HEADERS without END_HEADERS, followed by CONTINUATION frames.
                writeFrameLocked(1, 1, streamId, headerBlock.copyOfRange(0, maxFrame))
                var offset = maxFrame
                while (offset < headerBlock.size) {
                    val end = minOf(offset + maxFrame, headerBlock.size)
                    val last = end == headerBlock.size
                    writeFrameLocked(9, if (last) 4 else 0, streamId, headerBlock.copyOfRange(offset, end))
                    offset = end
                }
            }
        }
        try {
            val status = withTimeoutOrNull(12_000) { channel.waitForStatus() }
                ?: throw IOException("CONNECT to $authority timed out")
            when (status) {
                429 -> throw FoxyQuotaExceededException("Account proxy quota exceeded (HTTP 429).")
                403 -> throw FoxyTargetRejectedException("Edge rejected tunnel target (HTTP $status).")
                401, 407 -> throw FoxyAuthTokenInvalidException("Edge rejected proxy pass (HTTP $status).")
            }
            if (status !in 200..299) throw IOException("Edge rejected CONNECT to $authority with HTTP $status.")
            return channel
        } catch (e: Exception) {
            activeStreams.remove(streamId)
            channel.dispose()
            throw e
        }
    }

    suspend fun sendDataFrame(streamId: Int, data: ByteArray, endStream: Boolean) {
        if (data.isEmpty()) {
            writeFrame(0, if (endStream) 1 else 0, streamId, data)
            return
        }
        var offset = 0
        while (offset < data.size) {
            val remaining = data.size - offset
            var chunkLen = remaining
            val streamObj = activeStreams[streamId]
            if (streamObj != null) {
                var windowAllocated = false
                for (attempt in 0 until 16) {
                    synchronized(windowLock) {
                        val avail = minOf(connSendWindow, streamObj.sendWindowValue())
                        if (avail > 0) {
                            chunkLen = minOf(remaining.toLong(), avail).toInt()
                            connSendWindow -= chunkLen
                            streamObj.addSendWindow(-chunkLen.toLong())
                            windowAllocated = true
                        }
                    }
                    if (windowAllocated) break
                    withTimeoutOrNull(500) { sendWindowSignal.receive() }
                }
                if (!windowAllocated) {
                    val fallbackSlice = minOf(remaining, 16384)
                    synchronized(windowLock) {
                        val avail = minOf(connSendWindow, streamObj.sendWindowValue())
                        if (avail <= 0) {
                            connSendWindow = maxOf(connSendWindow, fallbackSlice.toLong())
                            streamObj.ensurePositiveSendWindow(fallbackSlice.toLong())
                        }
                        chunkLen = minOf(remaining.toLong(), minOf(connSendWindow, streamObj.sendWindowValue())).toInt()
                        connSendWindow -= chunkLen
                        streamObj.addSendWindow(-chunkLen.toLong())
                    }
                }
            }
            chunkLen = minOf(chunkLen, peerMaxFrameSize)
            val isFinalChunk = (offset + chunkLen) >= data.size
            val flags = if (isFinalChunk && endStream) 1 else 0
            writeFrame(0, flags, streamId, data.copyOfRange(offset, offset + chunkLen))
            offset += chunkLen
        }
    }

    fun sendWindowUpdate(streamId: Int, delta: Int) {
        if (delta <= 0) return
        scope.launch { foxyRunQuietly("window-update") { writeFrame(8, 0, streamId, encodeWindowUpdatePayload(delta)) } }
    }

    fun sendWindowUpdatePair(streamId: Int, delta: Int) {
        if (delta <= 0 || disposed.get() || sslSocket == null) return
        scope.launch {
            foxyRunQuietly("window-update-pair") {
                writeLock.withLock {
                    if (disposed.get() || sslSocket == null) return@withLock
                    val payload = encodeWindowUpdatePayload(delta)
                    writeFrameRaw(8, 0, 0, payload)
                    writeFrameRaw(8, 0, streamId, payload)
                    sslSocket?.outputStream?.flush()
                }
            }
        }
    }

    fun closeOldestStreams(maxCount: Int): Int {
        if (maxCount <= 0) return 0
        var closed = 0
        for (id in activeStreams.keys.sorted().take(maxCount)) {
            activeStreams[id]?.let { it.dispose(); closed++ }
        }
        return closed
    }

    fun removeStream(streamId: Int) {
        activeStreams.remove(streamId)
        pendingHeaderBlocks.remove(streamId)
        if (draining && activeStreams.isEmpty()) {
            goAwayReceived = true
            raiseFailed("edge finished its graceful drain and no streams remain")
        }
    }

    private suspend fun writeFrame(type: Int, flags: Int, streamId: Int, payload: ByteArray) {
        writeLock.withLock { writeFrameLocked(type, flags, streamId, payload) }
    }

    private suspend fun writeFrameLocked(type: Int, flags: Int, streamId: Int, payload: ByteArray) {
        val ssl = sslSocket ?: return
        writeFrameRaw(type, flags, streamId, payload)
        ssl.outputStream.flush()
    }

    private fun writeFrameRaw(type: Int, flags: Int, streamId: Int, payload: ByteArray) {
        val ssl = sslSocket ?: return
        val out = ssl.outputStream
        val header = ByteArray(9)
        header[0] = ((payload.size ushr 16) and 0xff).toByte()
        header[1] = ((payload.size ushr 8) and 0xff).toByte()
        header[2] = (payload.size and 0xff).toByte()
        header[3] = type.toByte()
        header[4] = flags.toByte()
        header[5] = ((streamId ushr 24) and 0x7f).toByte()
        header[6] = ((streamId ushr 16) and 0xff).toByte()
        header[7] = ((streamId ushr 8) and 0xff).toByte()
        header[8] = (streamId and 0xff).toByte()
        out.write(header)
        if (payload.isNotEmpty()) out.write(payload)
    }

    private suspend fun readerLoop() {
        val header = ByteArray(9)
        try {
            while (!disposed.get() && sslSocket != null) {
                readExact(header, 9)
                val length = ((header[0].toInt() and 0xff) shl 16) or ((header[1].toInt() and 0xff) shl 8) or (header[2].toInt() and 0xff)
                val type = header[3].toInt() and 0xff
                val flags = header[4].toInt() and 0xff
                val streamId = (((header[5].toInt() and 0x7f) shl 24) or ((header[6].toInt() and 0xff) shl 16) or ((header[7].toInt() and 0xff) shl 8) or (header[8].toInt() and 0xff))
                val payload = if (length > 0) {
                    val buf = ByteArray(length)
                    readExact(buf, length)
                    buf
                } else ByteArray(0)
                processIncomingFrame(type, flags, streamId, payload)
                if (goAwayReceived) break
            }
        } catch (e: Exception) {
            if (!disposed.get()) {
                AppLogRepository.debug(LogSource.APP, "Foxy H2 reader ended: ${e.message}")
                raiseFailed(e.message ?: "reader ended")
            }
        } finally {
            dispose()
        }
    }

    private fun processIncomingFrame(type: Int, flags: Int, streamId: Int, payload: ByteArray) {
        when (type) {
            0 -> {
                val padded = if ((flags and 8) != 0 && payload.isNotEmpty()) payload[0].toInt() and 0xff else 0
                val channel = activeStreams[streamId]
                if (channel != null) {
                    val isEnd = (flags and 1) != 0
                    val padBytes = if ((flags and 8) != 0) 1 else 0
                    val dataLength = payload.size - padBytes - padded
                    val data = if (dataLength > 0 && padBytes + dataLength <= payload.size) {
                        payload.copyOfRange(padBytes, padBytes + dataLength)
                    } else ByteArray(0)
                    val nonDataBytes = payload.size - data.size
                    if (nonDataBytes > 0) {
                        sendWindowUpdate(0, nonDataBytes)
                        sendWindowUpdate(streamId, nonDataBytes)
                    }
                    channel.onDataReceived(data, isEnd)
                } else if (payload.isNotEmpty()) {
                    sendWindowUpdate(0, payload.size)
                }
            }
            1 -> {
                val channel = activeStreams[streamId] ?: return
                var pos = 0
                var remaining = payload.size
                if ((flags and 8) != 0 && remaining > 0) {
                    val pad = payload[pos++].toInt() and 0xff
                    remaining -= pad + 1
                }
                if ((flags and 0x20) != 0 && remaining >= 5) {
                    pos += 5
                    remaining -= 5
                }
                val endHeaders = (flags and 0x04) != 0
                val block = if (remaining > 0 && pos + remaining <= payload.size) {
                    payload.copyOfRange(pos, pos + remaining)
                } else ByteArray(0)
                if (!endHeaders) {
                    pendingHeaderBlocks.merge(streamId, block) { existing, new -> existing + new }
                    return
                }
                val status = runCatching { HpackCodec.decodeStatusCode(block, hpackDynamicTable) }.getOrDefault(0)
                channel.onHeadersReceived(status)
            }
            9 -> {
                val channel = activeStreams[streamId] ?: return
                val combined = pendingHeaderBlocks.merge(streamId, payload) { existing, new -> existing + new }!!
                if ((flags and 0x04) != 0) {
                    val status = runCatching { HpackCodec.decodeStatusCode(combined, hpackDynamicTable) }.getOrDefault(0)
                    pendingHeaderBlocks.remove(streamId)
                    if (status != 0 || combined.isNotEmpty()) channel.onHeadersReceived(status)
                }
            }
            3 -> {
                pendingHeaderBlocks.remove(streamId)
                activeStreams[streamId]?.onResetReceived()
            }
            4 -> {
                if ((flags and 1) == 0) {
                    serverSettings.complete(Unit)
                    var i = 0
                    while (i + 6 <= payload.size) {
                        val settingId = ((payload[i].toInt() and 0xff) shl 8) or (payload[i + 1].toInt() and 0xff)
                        val value = ((payload[i + 2].toInt() and 0xff).toLong() shl 24) or
                            ((payload[i + 3].toInt() and 0xff).toLong() shl 16) or
                            ((payload[i + 4].toInt() and 0xff).toLong() shl 8) or
                            (payload[i + 5].toInt() and 0xff).toLong()
                        if (settingId == 0x5 && value in 16_384L..16_777_215L) {
                            peerMaxFrameSize = value.toInt()
                        }
                        if (settingId == 0x4 && value <= Int.MAX_VALUE) {
                            val newInit = value.toInt()
                            synchronized(windowLock) {
                                val delta = newInit - peerInitialWindowSize
                                peerInitialWindowSize = newInit
                                for (s in activeStreams.values) s.addSendWindow(delta.toLong())
                            }
                            signalSendWindow()
                        }
                        i += 6
                    }
                    scope.launch { foxyRunQuietly("settings-ack") { writeFrame(4, 1, 0, ByteArray(0)) } }
                }
            }
            8 -> {
                if (payload.size >= 4) {
                    val increment = (((payload[0].toInt() and 0x7f) shl 24) or ((payload[1].toInt() and 0xff) shl 16) or ((payload[2].toInt() and 0xff) shl 8) or (payload[3].toInt() and 0xff))
                    if (increment > 0) {
                        synchronized(windowLock) {
                            if (streamId == 0) connSendWindow += increment
                            else activeStreams[streamId]?.addSendWindow(increment.toLong())
                        }
                        signalSendWindow()
                    }
                }
            }
            6 -> {
                if ((flags and 1) == 0) scope.launch { foxyRunQuietly("ping-ack") { writeFrame(6, 1, 0, payload) } }
                else pingsOutstanding.set(0)
            }
            7 -> {
                var lastStreamId = 0L
                var errorCode = 0L
                if (payload.size >= 4) {
                    lastStreamId = (((payload[0].toInt() and 0xff).toLong() shl 24) or ((payload[1].toInt() and 0xff).toLong() shl 16) or ((payload[2].toInt() and 0xff).toLong() shl 8) or (payload[3].toInt() and 0xff).toLong()) and 0x7FFFFFFFL
                }
                if (payload.size >= 8) {
                    errorCode = ((payload[4].toInt() and 0xff).toLong() shl 24) or ((payload[5].toInt() and 0xff).toLong() shl 16) or ((payload[6].toInt() and 0xff).toLong() shl 8) or (payload[7].toInt() and 0xff).toLong()
                }
                if (errorCode == 0L || errorCode == 5L) {
                    draining = true
                    for (id in activeStreams.keys.toList()) {
                        if (id > lastStreamId.toInt()) {
                            activeStreams.remove(id)?.failUnprocessed("stream $id was not processed by the edge (last $lastStreamId)")
                        }
                    }
                    val drainReason = describeGoAway(lastStreamId, errorCode, activeStreams.size)
                    AppLogRepository.info(LogSource.APP, "Foxy edge $edgeHost is draining gracefully (last-stream-id=$lastStreamId, error=$errorCode); keeping ${activeStreams.size} live stream(s).")
                    if (activeStreams.isEmpty()) {
                        goAwayReceived = true
                        raiseFailed("edge finished its graceful drain and no streams remain")
                        return
                    }
                    if (drainNotified.compareAndSet(false, true)) {
                        runCatching { onDraining?.invoke(drainReason) }
                    }
                } else {
                    val reason = describeGoAway(lastStreamId, errorCode, activeStreams.size)
                    goAwayReceived = true
                    goAwayFatal = true
                    AppLogRepository.warning(LogSource.APP, "Foxy H2 GOAWAY from $edgeHost: $reason")
                    raiseFailed(reason)
                }
            }
        }
    }

    private fun describeGoAway(lastStreamId: Long, errorCode: Long, openStreams: Int): String = when {
        errorCode != 0L -> "edge sent GOAWAY (error ${describeHttp2Error(errorCode)}, last-stream-id $lastStreamId, $openStreams open)"
        lastStreamId == 0L -> "edge sent GOAWAY (no error, idle teardown)"
        else -> "edge sent GOAWAY (no error, last-stream-id $lastStreamId, $openStreams open - graceful drain)"
    }

    private fun describeHttp2Error(code: Long): String = when (code) {
        0L -> "NO_ERROR"; 1L -> "PROTOCOL_ERROR"; 2L -> "INTERNAL_ERROR"; 3L -> "FLOW_CONTROL_ERROR"
        4L -> "SETTINGS_TIMEOUT"; 5L -> "STREAM_CLOSED"; 6L -> "FRAME_SIZE_ERROR"; 7L -> "REFUSED_STREAM"
        8L -> "CANCEL"; 9L -> "COMPRESSION_ERROR"; 10L -> "CONNECT_ERROR"; 11L -> "ENHANCE_YOUR_CALM"
        12L -> "INADEQUATE_SECURITY"; 13L -> "HTTP_1_1_REQUIRED"; else -> "0x${code.toString(16).uppercase()}"
    }

    private fun readExact(buffer: ByteArray, count: Int) {
        val input: InputStream = sslSocket?.inputStream ?: throw IOException("H2 connection closed")
        var read = 0
        while (read < count) {
            val n = input.read(buffer, read, count - read)
            if (n == -1) throw IOException("HTTP/2 connection reached EOF.")
            read += n
        }
    }

    private fun connectViaProxy(client: Socket, proxy: UpstreamProxySettings, destHost: String, destPort: Int) {
        // Resolve proxy host (pinned/DoH) then TCP connect (protected).
        val addresses = runBlockingIO { FoxyNetwork.resolveHostAddresses(proxy.host) }
        val target = if (addresses.isNotEmpty()) addresses[0] else InetAddress.getByName(proxy.host)
        if (vpnService != null && com.uacspoofer.mobile.vpn.SocketProtectGate.isEnabled(vpnService)) {
            runCatching { vpnService?.protect(client) }
        }
        client.connect(InetSocketAddress(target, proxy.port), 15_000)
        val stream = client.getOutputStream()
        if (proxy.type.equals("http", ignoreCase = true)) {
            var request = "CONNECT $destHost:$destPort HTTP/1.1\r\nHost: $destHost:$destPort\r\n"
            if (!proxy.username.isNullOrEmpty()) {
                val basic = android.util.Base64.encodeToString(
                    "${proxy.username}:${proxy.password ?: ""}".toByteArray(StandardCharsets.UTF_8),
                    android.util.Base64.NO_WRAP,
                )
                request += "Proxy-Authorization: Basic $basic\r\n"
            }
            request += "\r\n"
            stream.write(request.toByteArray(StandardCharsets.US_ASCII))
            stream.flush()
            val input = client.getInputStream()
            val lineBuf = mutableListOf<Byte>()
            var isFirstLine = true
            while (true) {
                val b = input.read()
                if (b == -1) break
                lineBuf.add(b.toByte())
                if (lineBuf.size >= 2 && lineBuf[lineBuf.size - 2] == '\r'.code.toByte() && b == '\n'.code) {
                    val line = String(lineBuf.toByteArray(), StandardCharsets.US_ASCII)
                    if (line.trimEnd('\r', '\n').isEmpty()) break
                    if (isFirstLine) {
                        isFirstLine = false
                        if (!line.contains(" 200")) throw IOException("HTTP proxy rejected the CONNECT tunnel: ${line.trim()}")
                    }
                    lineBuf.clear()
                }
            }
            return
        }
        val hasAuth = !proxy.username.isNullOrEmpty()
        stream.write(if (hasAuth) byteArrayOf(5, 2, 0, 2) else byteArrayOf(5, 1, 0))
        stream.flush()
        val method = ByteArray(2)
        readExactFrom(client.getInputStream(), method, 2)
        if (method[0] != 5.toByte()) throw IOException("Invalid SOCKS5 greeting response.")
        if (method[1] == 2.toByte() && hasAuth) {
            val user = proxy.username!!.toByteArray(StandardCharsets.UTF_8)
            val pass = (proxy.password ?: "").toByteArray(StandardCharsets.UTF_8)
            val auth = ByteArray(3 + user.size + pass.size)
            auth[0] = 1
            auth[1] = user.size.toByte()
            System.arraycopy(user, 0, auth, 2, user.size)
            auth[2 + user.size] = pass.size.toByte()
            System.arraycopy(pass, 0, auth, 3 + user.size, pass.size)
            stream.write(auth)
            stream.flush()
            val authResp = ByteArray(2)
            readExactFrom(client.getInputStream(), authResp, 2)
            if (authResp[1] != 0.toByte()) throw IOException("SOCKS5 proxy authentication failed.")
        } else if (method[1] != 0.toByte()) {
            throw IOException("SOCKS5 proxy rejected authentication method ${method[1]}.")
        }
        val host = destHost.toByteArray(StandardCharsets.US_ASCII)
        val connect = ByteArray(5 + host.size + 2)
        connect[0] = 5
        connect[1] = 1
        connect[2] = 0
        connect[3] = 3
        connect[4] = host.size.toByte()
        System.arraycopy(host, 0, connect, 5, host.size)
        connect[5 + host.size] = (destPort ushr 8).toByte()
        connect[6 + host.size] = (destPort and 0xFF).toByte()
        stream.write(connect)
        stream.flush()
        val reply = ByteArray(4)
        readExactFrom(client.getInputStream(), reply, 4)
        if (reply[1] != 0.toByte()) throw IOException("SOCKS5 CONNECT failed with status ${reply[1]}.")
        val tail = when (reply[3].toInt() and 0xff) {
            1 -> 4
            4 -> 16
            3 -> {
                val b = ByteArray(1)
                readExactFrom(client.getInputStream(), b, 1)
                b[0].toInt() and 0xff
            }
            else -> 0
        }
        if (tail > 0) readExactFrom(client.getInputStream(), ByteArray(tail + 2), tail + 2)
    }

    private fun readExactFrom(input: InputStream, buffer: ByteArray, count: Int) {
        var read = 0
        while (read < count) {
            val n = input.read(buffer, read, count - read)
            if (n == -1) throw IOException("Proxy connection reached EOF.")
            read += n
        }
    }

    private fun encodeWindowUpdatePayload(delta: Int): ByteArray = byteArrayOf(
        ((delta ushr 24) and 0x7F).toByte(),
        ((delta ushr 16) and 0xFF).toByte(),
        ((delta ushr 8) and 0xFF).toByte(),
        (delta and 0xFF).toByte(),
    )

    fun sendRstStream(streamId: Int, errorCode: Int) {
        scope.launch { foxyRunQuietly("rst-stream") {
            writeFrame(
                3, 0, streamId,
                byteArrayOf(
                    ((errorCode ushr 24) and 0xFF).toByte(),
                    ((errorCode ushr 16) and 0xFF).toByte(),
                    ((errorCode ushr 8) and 0xFF).toByte(),
                    (errorCode and 0xFF).toByte(),
                ),
            )
        } }
    }

    fun dispose() {
        if (!disposed.compareAndSet(false, true)) return
        readerJob?.cancel()
        keepAliveJob?.cancel()
        for (stream in activeStreams.values) stream.dispose()
        activeStreams.clear()
        runCatching { sslSocket?.close() }
        runCatching { socket?.close() }
        sslSocket = null
        socket = null
        scope.cancel()
    }

    companion object {
        private const val HANDSHAKE_TIMEOUT_MS = 12_000
        private const val READ_IDLE_TIMEOUT_MS = 0
        private const val ADDRESS_CONNECT_TIMEOUT_MS = 6_000
        private const val MAX_ADDRESS_ATTEMPTS = 3
        private val H2_PREFACE = "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".toByteArray(StandardCharsets.US_ASCII)
        private const val KEEP_ALIVE_INTERVAL_MS = 15_000L
        private const val INITIAL_CONNECTION_WINDOW = 4 * 1024 * 1024
        private const val DEFAULT_MAX_FRAME_SIZE = 16_384
        private val CONSCRYPT_CLASS_NAMES = listOf(
            "org.conscrypt.Conscrypt",
            "com.android.org.conscrypt.Conscrypt",
        )
    }
}

private fun <T> runBlockingIO(block: suspend () -> T): T =
    kotlinx.coroutines.runBlocking(Dispatchers.IO) { block() }
