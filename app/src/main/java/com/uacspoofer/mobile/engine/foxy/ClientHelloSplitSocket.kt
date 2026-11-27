/*
 * ClientHello splitting socket.
 *
 * The Foxy edge is reachable over TLS on a non-standard port (2499), and the mozilla
 * masque hostname inside the ClientHello is exactly what DPI boxes fingerprint or drop.
 * The Windows original wraps the *socket* (not the post-handshake stream) so the ClientHello
 * leaves the device as several TCP segments and the SNI never appears inside a single packet.
 *
 * This adapter delegates everything to a real socket but hands the TLS stack an output
 * stream that fragments the first write when it is a ClientHello.
 */
package com.uacspoofer.mobile.engine.foxy

import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.Socket
import java.net.SocketAddress

internal class ClientHelloSplitSocket(
    private val delegate: Socket,
    private val mode: HelloSplitMode,
) : Socket() {
    private val splittingStream: OutputStream by lazy {
        HelloSplitOutputStream(delegate.getOutputStream(), mode)
    }

    override fun getInputStream(): InputStream = delegate.getInputStream()

    override fun getOutputStream(): OutputStream =
        if (mode == HelloSplitMode.OFF) delegate.getOutputStream() else splittingStream

    override fun connect(endpoint: SocketAddress, timeout: Int) = delegate.connect(endpoint, timeout)

    override fun connect(endpoint: SocketAddress) = delegate.connect(endpoint)

    override fun isConnected(): Boolean = delegate.isConnected

    override fun isClosed(): Boolean = delegate.isClosed

    override fun close() = delegate.close()

    override fun setSoTimeout(timeout: Int) {
        delegate.soTimeout = timeout
    }

    override fun getSoTimeout(): Int = delegate.soTimeout

    override fun setTcpNoDelay(on: Boolean) {
        delegate.tcpNoDelay = on
    }

    override fun getTcpNoDelay(): Boolean = delegate.tcpNoDelay

    override fun setKeepAlive(on: Boolean) {
        delegate.keepAlive = on
    }

    override fun getKeepAlive(): Boolean = delegate.keepAlive

    override fun getRemoteSocketAddress(): SocketAddress? = delegate.remoteSocketAddress

    override fun getLocalSocketAddress(): SocketAddress? = delegate.localSocketAddress

    override fun getInetAddress(): InetAddress? = delegate.inetAddress

    override fun getPort(): Int = delegate.port

    override fun getLocalAddress(): InetAddress? = delegate.localAddress

    override fun getLocalPort(): Int = delegate.localPort

    override fun shutdownInput() = delegate.shutdownInput()

    override fun shutdownOutput() = delegate.shutdownOutput()

    override fun isInputShutdown(): Boolean = delegate.isInputShutdown

    override fun isOutputShutdown(): Boolean = delegate.isOutputShutdown

    override fun toString(): String = "ClientHelloSplitSocket(${mode.name}, $delegate)"
}
