/*
 * Ported from Se7en Pro (MIT) — Se7enPro/Services/Foxy/FoxyNetwork.cs
 * Copyright (c) 2026 kng7-p (King Network). MIT License.
 * Adapted for UAC SNI Spoofer Android (AGPLv3).
 * DoH via HttpURLConnection (no new dependency). Socket protection via VpnService.protect.
 */
package com.uacspoofer.mobile.engine.foxy

import android.net.VpnService
import com.uacspoofer.mobile.vpn.SocketProtectGate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

object FoxyNetwork {
    private const val DIAL_STAGGER_MS = 300L
    private const val FAILED_ADDRESS_COOLDOWN_MS = 10 * 60 * 1000L
    private val failedAddresses = ConcurrentHashMap<String, Long>()

    private val knownMozillaIps: Map<String, Array<String>> = mapOf(
        "api.accounts.firefox.com" to arrayOf("151.101.1.91", "151.101.65.91", "151.101.129.91", "151.101.193.91"),
        "accounts.firefox.com" to arrayOf("151.101.1.91", "151.101.65.91", "151.101.129.91", "151.101.193.91"),
        "vpn.mozilla.org" to arrayOf("151.101.1.91", "151.101.65.91", "151.101.129.91", "151.101.193.91"),
        "firefox.settings.services.mozilla.com" to arrayOf("34.107.243.141", "35.244.209.117"),
    )

    private val knownFastlyMasqueIps: Map<String, Array<String>> = mapOf(
        "us.m1.fastly-masque.net" to arrayOf("23.235.42.1"),
        "ca.m1.fastly-masque.net" to arrayOf("23.235.42.2"),
        "p.m1.fastly-masque.net" to arrayOf("23.235.42.11"),
        "fra1.m1.fastly-masque.net" to arrayOf("23.235.42.8"),
        "muc139.m1.fastly-masque.net" to arrayOf("23.235.42.8"),
        "eglc860.m1.fastly-masque.net" to arrayOf("23.235.42.10"),
        "ehrd229.m1.fastly-masque.net" to arrayOf("23.235.42.17"),
        "hel141.m1.fastly-masque.net" to arrayOf("23.235.42.15"),
        "essb127.m1.fastly-masque.net" to arrayOf("23.235.42.16"),
        "lfpb115.m1.fastly-masque.net" to arrayOf("23.235.42.9"),
    )

    private val defaultFastlyMasqueIps = arrayOf("23.235.42.1", "23.235.42.11")

    fun isBlockPageOrBogon(address: InetAddress): Boolean {
        if (address.isLoopbackAddress || address.isAnyLocalAddress) return true
        if (address.isLinkLocalAddress || address.isSiteLocalAddress || address.isMulticastAddress) return true
        val bytes = address.address
        if (bytes.size == 4) {
            val b0 = bytes[0].toInt() and 0xff
            val b1 = bytes[1].toInt() and 0xff
            return b0 == 0 || b0 == 10 || b0 == 127 || (b0 == 169 && b1 == 254) ||
                (b0 == 172 && b1 in 16..31) || (b0 == 192 && b1 == 168) || b0 >= 224
        }
        if (bytes.size == 16) {
            if ((bytes[0].toInt() and 0xfe) == 0xfc) return true
        }
        return false
    }

    fun markAddressFailed(address: InetAddress) {
        failedAddresses[address.hostAddress ?: address.toString()] = System.currentTimeMillis() + FAILED_ADDRESS_COOLDOWN_MS
    }

    fun clearAddressFailure(address: InetAddress) {
        failedAddresses.remove(address.hostAddress ?: address.toString())
    }

    fun orderAddressesByHealth(addresses: Array<InetAddress>): Array<InetAddress> {
        val now = System.currentTimeMillis()
        val healthy = mutableListOf<InetAddress>()
        val demoted = mutableListOf<InetAddress>()
        val seen = mutableSetOf<String>()
        for (raw in addresses) {
            val key = raw.hostAddress ?: raw.toString()
            if (!seen.add(key)) continue
            val until = failedAddresses[key]
            if (until != null) {
                if (now <= until) {
                    demoted.add(raw)
                    continue
                }
                failedAddresses.remove(key)
            }
            healthy.add(raw)
        }
        healthy.addAll(demoted)
        return healthy.toTypedArray()
    }

    suspend fun resolveHostAddresses(host: String): Array<InetAddress> = withContext(Dispatchers.IO) {
        if (host.isBlank()) return@withContext emptyArray()
        // Literal IP?
        try {
            val literal = InetAddress.getByName(host)
            if (literal.hostAddress == host) return@withContext arrayOf(literal)
        } catch (_: Exception) {
            // Not a literal; continue to pinned/DoH.
        }
        tryGetPinnedAddresses(host)?.let { return@withContext orderAddressesByHealth(it) }
        val doh = runCatching { queryDoh(host) }.getOrDefault(emptyArray())
        if (doh.isNotEmpty()) return@withContext orderAddressesByHealth(doh)
        val sys = runCatching {
            InetAddress.getAllByName(host).filter { !isBlockPageOrBogon(it) }.toTypedArray()
        }.getOrDefault(emptyArray())
        if (sys.isNotEmpty()) return@withContext orderAddressesByHealth(sys)
        emptyArray()
    }

    private fun tryGetPinnedAddresses(host: String): Array<InetAddress>? {
        knownMozillaIps[host.lowercase()]?.let { return it.map { InetAddress.getByName(it) }.toTypedArray() }
        if (host.endsWith(".fastly-masque.net", ignoreCase = true)) {
            val ips = knownFastlyMasqueIps[host.lowercase()] ?: defaultFastlyMasqueIps
            return ips.map { InetAddress.getByName(it) }.toTypedArray()
        }
        return null
    }

    private fun queryDoh(host: String): Array<InetAddress> {
        val escaped = URLEncoder.encode(host, "UTF-8")
        val endpoints = listOf(
            "https://1.1.1.1/dns-query?name=$escaped&type=A",
            "https://8.8.8.8/resolve?name=$escaped&type=A",
        )
        for (endpoint in endpoints) {
            try {
                val conn = URL(endpoint).openConnection() as HttpURLConnection
                conn.connectTimeout = 5_000
                conn.readTimeout = 5_000
                conn.setRequestProperty("Accept", "application/dns-json")
                conn.setRequestProperty("User-Agent", "UAC-Foxy/1.0")
                conn.useCaches = false
                if (conn.responseCode != 200) continue
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(text)
                val answer = json.optJSONArray("Answer") ?: continue
                val out = mutableListOf<InetAddress>()
                for (i in 0 until answer.length()) {
                    val data = answer.optJSONObject(i)?.optString("data")?.trim() ?: continue
                    if (data.isEmpty() || data.contains(":")) continue
                    try {
                        val ip = InetAddress.getByName(data)
                        if (!isBlockPageOrBogon(ip)) out.add(ip)
                    } catch (_: Exception) {
                    }
                }
                if (out.isNotEmpty()) return out.toTypedArray()
            } catch (_: Exception) {
                // Try next endpoint.
            }
        }
        return emptyArray()
    }

    /**
     * Happy-Eyeballs dial race with 300 ms stagger (Windows parity).
     * First connected socket wins; losers are closed. Protected via VpnService when available.
     */
    suspend fun connectRace(
        addresses: Array<InetAddress>,
        port: Int,
        vpnService: VpnService? = null,
        staggerMs: Long = DIAL_STAGGER_MS,
    ): Socket = coroutineScope {
        require(addresses.isNotEmpty()) { "No addresses to dial" }
        val ordered = orderAddressesByHealth(addresses)
        if (ordered.size == 1) {
            val socket = Socket()
            socket.tcpNoDelay = true
            try {
                protect(socket, vpnService)
                socket.connect(InetSocketAddress(ordered[0], port), 10_000)
                clearAddressFailure(ordered[0])
                return@coroutineScope socket
            } catch (e: Exception) {
                socket.close()
                markAddressFailed(ordered[0])
                throw e
            }
        }
        val inFlight = mutableListOf<kotlinx.coroutines.Deferred<Result<Socket>>>()
        var nextIndex = 0
        var lastError: Throwable? = null

        fun launchNext(): kotlinx.coroutines.Deferred<Result<Socket>> {
            val addr = ordered[nextIndex++]
            return async(Dispatchers.IO) {
                val socket = Socket()
                socket.tcpNoDelay = true
                try {
                    protect(socket, vpnService)
                    socket.connect(InetSocketAddress(addr, port), 10_000)
                    Result.success(socket)
                } catch (e: Throwable) {
                    runCatching { socket.close() }
                    Result.failure(e)
                }
            }
        }

        inFlight.add(launchNext())
        while (inFlight.isNotEmpty()) {
            val completed = select<Result<Socket>?> {
                inFlight.forEach { deferred ->
                    deferred.onAwait { it }
                }
                if (nextIndex < ordered.size) {
                    async { delay(staggerMs); null }.onAwait { it }
                } else {
                    // No more to launch; just await completions.
                    inFlight.first().onAwait { it }
                }
            }
            // If select returned null (stagger elapsed), launch next.
            if (completed == null) {
                if (nextIndex < ordered.size) {
                    inFlight.add(launchNext())
                    continue
                } else {
                    continue
                }
            }
            // Remove finished deferreds.
            val finished = inFlight.filter { it.isCompleted }
            inFlight.removeAll(finished)
            val result = completed
            if (result.isSuccess) {
                val socket = result.getOrThrow()
                clearAddressFailure(ordered.getOrNull(0) ?: ordered[0])
                // Cancel losers.
                inFlight.forEach { it.cancel() }
                inFlight.forEach { d ->
                    runCatching {
                        val r = d.await()
                        if (r.isSuccess) r.getOrThrow().close()
                    }
                }
                return@coroutineScope socket
            } else {
                lastError = result.exceptionOrNull()
                // Mark the address that just failed (best effort: mark all tried so far).
                // The precise address is tracked in the async block via markAddressFailed on next launch;
                // here we mark by index for health ordering.
                if (nextIndex > 0) {
                    runCatching { markAddressFailed(ordered[nextIndex - 1]) }
                }
                if (inFlight.isEmpty() && nextIndex < ordered.size) {
                    inFlight.add(launchNext())
                }
            }
        }
        throw lastError ?: java.net.SocketTimeoutException("Dial race failed")
    }

    private fun protect(socket: Socket, vpnService: VpnService?) {
        if (vpnService == null) return
        if (!SocketProtectGate.isEnabled(vpnService)) return
        runCatching { vpnService.protect(socket) }
    }

    internal fun clearAllAddressFailuresForTests() = failedAddresses.clear()
}
