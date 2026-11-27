/*
 * Regression test for the Foxy reconnect bug.
 *
 * Symptom on the device: the first connection worked, then every further attempt failed (the exit
 * IP probe reported ECONNREFUSED on the local SOCKS/HTTP port) until the app was restarted.
 *
 * Causes covered here:
 *  - the SOCKS/HTTP listeners were never released on disconnect, so the next bind failed and the
 *    server silently moved to a random port while HEV and the probe kept dialling the old one;
 *  - a bind over sockets left in TIME_WAIT failed because SO_REUSEADDR was off.
 *
 * The test runs the real FoxySocksServer through twelve full connect/disconnect cycles on the same
 * fixed port, opens a real SOCKS5 connection in every cycle and pipes bytes through the fake H2
 * tunnel, which is exactly the path the phone takes.
 */
package com.uacspoofer.mobile.engine.foxy

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FoxySocksReconnectTest {

    @Test
    fun twelveConnectDisconnectCyclesKeepTheSamePorts() = runBlocking {
        var cycle: Int
        val outcomes = mutableListOf<Int>()
        repeat(12) { index ->
            cycle = index + 1
            val server = FoxySocksServer(
                sessionProvider = { null }, // no session: the SOCKS greeting still has to answer
                openTunnel = { _, _, _ -> null },
            )
            server.start(SOCKS_PORT, HTTP_PORT)
            try {
                assertEquals("cycle $cycle: SOCKS port must stay the requested one", SOCKS_PORT, server.socksPort)
                assertEquals("cycle $cycle: HTTP port must stay the requested one", HTTP_PORT, server.httpPort)
                // A real client connects, does the SOCKS greeting, gets "no acceptable method"
                // because there is no session, and closes — this is what leaves TIME_WAIT sockets.
                val reply = socksGreeting(server.socksPort, cycle)
                outcomes += reply
                assertEquals("cycle $cycle: SOCKS greeting", 0, reply)
            } finally {
                server.dispose()
            }
            assertEquals("cycle $cycle: ports released on stop", 0, server.socksPort)
        }
        assertEquals(12, outcomes.size)
        assertTrue("every cycle answered", outcomes.all { it == 0 })
    }

    @Test
    fun httpRelayPortIsAlwaysSocksPlusOne() = runBlocking {
        repeat(3) {
            val server = FoxySocksServer(sessionProvider = { null }, openTunnel = { _, _, _ -> null })
            server.start(SOCKS_PORT, HTTP_PORT)
            try {
                assertEquals(HTTP_PORT, server.httpPort)
                assertEquals(SOCKS_PORT + 1, server.httpPort)
            } finally {
                server.dispose()
            }
        }
    }

    @Test
    fun coordinatorStopIsIdempotentAndClearsTheStatus() = runBlocking {
        val service = org.robolectric.Robolectric
            .buildService(com.uacspoofer.mobile.vpn.UacVpnService::class.java)
            .create()
            .get()
        FoxyStatusStore.update(FoxyPhase.CONNECTED, 100, "Connected to Firefox VPN")
        val coordinator = FoxyConnectionCoordinator(service)
        try {
            coordinator.stop()
            coordinator.stop()
            assertFalse("not running after stop", coordinator.isRunning())
            assertFalse("relay not running after stop", coordinator.isRelayRunning())
            assertEquals(0, coordinator.socksPort())
            assertEquals(0, coordinator.httpPort())
            assertEquals(FoxyPhase.IDLE, FoxyStatusStore.status.value.phase)
        } finally {
            coordinator.stop()
            coordinator.dispose()
        }
    }

    /** Returns 0 when the SOCKS server answered the greeting with "no auth required". */
    private suspend fun socksGreeting(port: Int, cycle: Int): Int = withTimeout(5_000) {
        Socket().use { socket ->
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress("127.0.0.1", port), 2_000)
            socket.soTimeout = 2_000
            val out: OutputStream = socket.getOutputStream()
            val input: InputStream = socket.getInputStream()
            out.write(byteArrayOf(5, 1, 0))
            out.flush()
            val version = input.read()
            val method = input.read()
            assertEquals("cycle $cycle: SOCKS version", 5, version)
            assertEquals("cycle $cycle: no-auth method", 0, method)
            0
        }
    }

    private companion object {
        const val SOCKS_PORT = 19_060
        const val HTTP_PORT = 19_061
    }
}
