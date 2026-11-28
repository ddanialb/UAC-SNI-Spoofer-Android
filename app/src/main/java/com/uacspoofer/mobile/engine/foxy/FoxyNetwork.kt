/*
 * Ported from Se7en Pro (MIT) — Se7enPro/Services/Foxy/FoxyNetwork.cs
 * Copyright (c) 2026 kng7-p (King Network). MIT License.
 * Adapted for UAC SNI Spoofer Android (AGPLv3).
 * DoH via HttpURLConnection (no new dependency). Socket protection via VpnService.protect.
 */
package com.uacspoofer.mobile.engine.foxy

import android.net.VpnService
import com.uacspoofer.mobile.logging.AppLogRepository
import com.uacspoofer.mobile.logging.LogSource
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
    private const val DOH_TIMEOUT_MS = 3_000
    private const val DOH_TOTAL_BUDGET_MS = 3_500L
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
        // Every source is consulted and merged: on carriers that hijack DNS (or block DoH) the
        // pinned anycast addresses are the only working path, while on healthy networks the live
        // answer is preferred. Dialling then races all of them.
        val pinned = runCatching { tryGetPinnedAddresses(host) }.getOrNull()
        val doh = runCatching { queryDoh(host) }.getOrDefault(emptyArray())
        val system = runCatching {
            InetAddress.getAllByName(host).filter { !isBlockPageOrBogon(it) }.toTypedArray()
        }.getOrDefault(emptyArray())
        orderAddressesByHealth(mergeAddressSources(system, doh, pinned))
    }

    /** Union of the address sources, de-duplicated, keeping the first source's preference order. */
    fun mergeAddressSources(vararg sources: Array<InetAddress>?): Array<InetAddress> {
        val seen = LinkedHashSet<String>()
        val out = ArrayList<InetAddress>()
        for (source in sources) {
            source ?: continue
            for (address in source) {
                val key = address.hostAddress ?: continue
                if (isBlockPageOrBogon(address)) continue
                if (seen.add(key)) out.add(address)
            }
        }
        return out.toTypedArray()
    }

    private fun tryGetPinnedAddresses(host: String): Array<InetAddress>? {
        val lower = host.lowercase()
        knownMozillaIps[lower]?.let { return it.map { InetAddress.getByName(it) }.toTypedArray() }
        knownFastlyMasqueIps[lower]?.let { return it.map { InetAddress.getByName(it) }.toTypedArray() }
        // Fastly serves every mozillavpn.net / fastly-masque.net edge from its anycast range and
        // routes by SNI, so these addresses reach the right edge even when the carrier's DNS
        // answers with a block page or with nothing at all. On Rightel the resolver handed back an
        // address that completed TCP and then never answered TLS, which is what "stuck at the
        // handshake" looked like; racing these anycast addresses fixes that case.
        if (lower.endsWith(".fastly-masque.net") || lower.endsWith(".mozillavpn.net")) {
            return defaultFastlyMasqueIps.map { InetAddress.getByName(it) }.toTypedArray()
        }
        return null
    }

    /**
     * DNS-over-HTTPS, asked of several resolvers at once.
     *
     * Sequentially this took up to ten seconds before the system resolver was even consulted, and
     * on a carrier that blocks Cloudflare every attempt burned its full timeout. The endpoints are
     * raced now; the first one that answers wins. Shecan is included on purpose: it is reachable
     * from inside Iran, where 1.1.1.1 and dns.google are usually not.
     */
    private fun queryDoh(host: String): Array<InetAddress> {
        val escaped = URLEncoder.encode(host, "UTF-8")
        val endpoints = listOf(
            "https://1.1.1.1/dns-query?name=$escaped&type=A",
            "https://8.8.8.8/resolve?name=$escaped&type=A",
            "https://94.140.14.14/dns-query?name=$escaped&type=A",
            "https://free.shecan.ir/dns-query?name=$escaped&type=A",
        )
        val answers = java.util.concurrent.ConcurrentHashMap<String, Array<InetAddress>>()
        val threads = endpoints.map { endpoint ->
            Thread {
                runCatching { fetchDoh(endpoint) }.getOrNull()
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { answers[endpoint] = it }
            }.apply { isDaemon = true; start() }
        }
        val deadline = System.currentTimeMillis() + DOH_TOTAL_BUDGET_MS
        while (System.currentTimeMillis() < deadline && answers.isEmpty()) {
            Thread.sleep(50)
        }
        threads.forEach { runCatching { it.join(200) } }
        val merged = mergeAddressSources(*answers.values.toTypedArray())
        if (merged.isNotEmpty()) {
            AppLogRepository.debug(LogSource.APP, "Foxy: DoH resolved $host to ${merged.joinToString { it.hostAddress.orEmpty() }}")
        }
        return merged
    }

    /** One DoH endpoint: the A answer plus, when present, the AAAA answer. */
    private fun fetchDoh(url: String): Array<InetAddress> {
        val a = fetchDohOnce(url)
        val aaaa = fetchDohOnce(url.replace("type=A", "type=AAAA"))
        return (a + aaaa).distinctBy { it.hostAddress }
            .toTypedArray()
    }

    private fun fetchDohOnce(url: String): List<InetAddress> {
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = DOH_TIMEOUT_MS
            conn.readTimeout = DOH_TIMEOUT_MS
            conn.setRequestProperty("Accept", "application/dns-json")
            conn.setRequestProperty("User-Agent", "UAC-Foxy/1.0")
            conn.useCaches = false
            if (conn.responseCode != 200) return emptyList()
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            val answer = JSONObject(text).optJSONArray("Answer") ?: return emptyList()
            val out = mutableListOf<InetAddress>()
            for (i in 0 until answer.length()) {
                val data = answer.optJSONObject(i)?.optString("data")?.trim()?.trim('"') ?: continue
                if (data.isEmpty()) continue
                val ip = runCatching { InetAddress.getByName(data) }.getOrNull() ?: continue
                if (!isBlockPageOrBogon(ip)) out.add(ip)
            }
            out
        } catch (_: Exception) {
            emptyList()
        } finally {
            runCatching { conn.disconnect() }
        }
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
