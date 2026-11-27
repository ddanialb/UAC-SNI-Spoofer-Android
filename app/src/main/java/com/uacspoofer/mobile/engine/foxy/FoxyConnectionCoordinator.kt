/*
 * Ported from Se7en Pro (MIT) — Se7enPro/Services/Foxy/FoxyEngine.cs
 * Copyright (c) 2026 kng7-p (King Network). MIT License.
 * Adapted for UAC SNI Spoofer Android (AGPLv3).
 * Runs inside UacVpnService (no second VpnService). SOCKS feeds SocksTunRelay like Tor/PoW.
 */
package com.uacspoofer.mobile.engine.foxy

import android.net.VpnService
import android.os.ParcelFileDescriptor
import com.uacspoofer.mobile.logging.AppLogRepository
import com.uacspoofer.mobile.logging.LogSource
import com.uacspoofer.mobile.settings.AdvancedSettingsData
import com.uacspoofer.mobile.settings.CONNECTION_MODE_PROXY
import com.uacspoofer.mobile.settings.TunBackend
import com.uacspoofer.mobile.vpn.SocksTunRelay
import com.uacspoofer.mobile.vpn.TunStats
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
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicLong

class FoxyConnectionCoordinator(
    private val service: VpnService,
) {
    private val appContext = service.applicationContext
    private val engineStore = FoxyEngineStore.get(appContext)
    private val accountStore = FoxyAccountStore.get(appContext)
    private val tunRelay = SocksTunRelay()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lifecycle = Mutex()
    private val sessionSwapLocks = arrayOf(Mutex(), Mutex())
    private val sessions = arrayOfNulls<FoxyH2Session>(2)
    private val retiredSessions = mutableListOf<FoxyH2Session>()
    private var socksServer: FoxySocksServer? = null
    private var guardian: GuardianClient? = null
    private var fxa: FxaClient? = null
    private var cookieJar: FoxyCookieJar? = null

    @Volatile private var tunDescriptor: ParcelFileDescriptor? = null
    @Volatile private var currentProxyPassToken = ""
    @Volatile private var currentProxyPassExpiry = 0L
    @Volatile private var connectedRegion = ""
    @Volatile private var connectedEdge = ""
    @Volatile private var connectedIp = ""
    private val bytesSent = AtomicLong(0L)
    private val bytesReceived = AtomicLong(0L)
    private val failedEdges = mutableListOf<Pair<String, Long>>()
    private var renewalJob: Job? = null
    private var secondaryJob: Job? = null
    private var staggerJob: Job? = null
    @Volatile private var running = false
    @Volatile private var sessionSocksPort = 0
    @Volatile private var guardianProxyKey = ""
    @Volatile private var fxaProxyKey = ""
    @Volatile private var sessionHttpPort = 0

    fun socksPort(): Int = sessionSocksPort
    fun httpPort(): Int = sessionHttpPort
    fun isRunning(): Boolean = running && (sessions[0]?.isConnected == true || sessions[1]?.isConnected == true)
    fun isRelayRunning(): Boolean = tunRelay.isRunning()
    fun tunStats(): TunStats = tunRelay.stats()
    fun stats(): TunStats {
        val tun = tunRelay.stats()
        if (tun != TunStats.ZERO) return tun
        // TunStats(txPackets, txBytes, rxPackets, rxBytes) — SOCKS byte counters only.
        return TunStats(0L, bytesSent.get(), 0L, bytesReceived.get())
    }

    suspend fun connect(
        settings: AdvancedSettingsData,
        backend: TunBackend,
        establishTun: (AdvancedSettingsData) -> ParcelFileDescriptor?,
    ) = withContext(Dispatchers.IO) {
        lifecycle.withLock {
            FoxyStatusStore.reset()
            FoxyStatusStore.update(FoxyPhase.STARTING, 10, "Initializing Firefox VPN...")
            val account = resolveActiveAccount()
            if (account == null) {
                FoxyStatusStore.update(FoxyPhase.FAILED, 0, "No usable Firefox account. Sign in in Foxy Settings.")
                throw IllegalStateException("No usable Firefox account. Sign in in Foxy Settings.")
            }
            FoxyStatusStore.update(FoxyPhase.AUTH, 30, "Signing in to Firefox Accounts (${account.email})...")
            val pass = ensureProxyPassWithFailover(account)
                ?: throw IllegalStateException("Could not obtain a proxy pass from Mozilla Guardian.")
            FoxyStatusStore.update(FoxyPhase.EDGE, 50, "Resolving edge server...")
            val countries = guardian().fetchCountries()
            val engineSettings = engineStore.snapshot()
            val candidates = GuardianClient.getCandidates(countries, engineSettings.serverCountry, engineSettings.serverCity)
            if (candidates.isEmpty()) {
                FoxyStatusStore.update(FoxyPhase.FAILED, 0, "No Firefox VPN edge matched the selected location.")
                throw IllegalStateException("No Firefox VPN edge matched the selected location.")
            }
            val ordered = orderCandidates(candidates)
            FoxyStatusStore.update(FoxyPhase.EDGE, 60, "Ranking ${ordered.size} Firefox VPN edge(s)...")
            val (session, chosen, lastError) = dialEdgeSession(ordered)
            if (session == null || chosen == null) {
                val attempts = minOf(ordered.size, MAX_EDGE_ATTEMPTS)
                val msg = lastError?.message?.let { "All $attempts Firefox VPN edge(s) failed. Last error: $it" }
                    ?: "No Firefox VPN edge could be reached across $attempts attempt(s)."
                FoxyStatusStore.update(FoxyPhase.FAILED, 0, msg)
                throw IllegalStateException(msg)
            }
            currentProxyPassToken = pass.token
            currentProxyPassExpiry = pass.expiresAtEpochSeconds
            attachSession(session, chosen, slot = 0)
            rememberSuccessfulEdge(chosen)
            FoxyStatusStore.update(FoxyPhase.TUNNEL, 85, "Starting local SOCKS5 & HTTP relays...")
            startSocksServer(settings)
            if (settings.connectionMode != CONNECTION_MODE_PROXY) {
                FoxyStatusStore.update(FoxyPhase.TUNNEL, 90, "Routing device traffic through Foxy")
                val mtu = FoxyTunRelayConfig.mtuForChain(settings.tunMtu)
                val backendSettings = if (backend == TunBackend.ZEPTUN) settings.copy(tunMtu = mtu) else settings
                val tun = checkNotNull(establishTun(backendSettings)) { "Android could not establish the VPN interface" }
                tunDescriptor = tun
                try {
                    tunRelay.start(
                        service, backend, tun,
                        SocksTunRelay.Config(sessionSocksPort, mtu, udp = false, fakeDns = FoxyTunRelayConfig.MAP_DNS),
                    ) {
                        "foxy/hev.yml" to FoxyTunRelayConfig.yaml(
                            mtu = mtu,
                            socksPort = sessionSocksPort,
                            tunIpv4 = settings.tunAddress,
                            blockUdp = true,
                            bigBuffers = settings.bigBuffers,
                        )
                    }
                } catch (e: Exception) {
                    tunDescriptor = null
                    runCatching { tun.close() }
                    throw e
                }
            }
            startProxyPassRenewal()
            if (engineStore.snapshot().dualSessionPool) {
                secondaryJob = scope.launch { ensureSecondarySession(ordered) }
            }
            scope.launch { probeExitIp() }
            running = true
            FoxyStatusStore.update(FoxyPhase.CONNECTED, 100, "Connected to Firefox VPN", edgeHost = chosen.host, countryCode = chosen.countryCode)
            AppLogRepository.success(LogSource.APP, "Foxy engine is active edge=${chosen.authority} country=${chosen.countryName}")
        }
    }

    suspend fun stop() {
        lifecycle.withLock {
            running = false
            renewalJob?.cancel()
            secondaryJob?.cancel()
            staggerJob?.cancel()
            renewalJob = null
            secondaryJob = null
            staggerJob = null
            socksServer?.dispose()
            socksServer = null
            sessionSocksPort = 0
            sessionHttpPort = 0
            for (i in sessions.indices) {
                sessions[i]?.dispose()
                sessions[i] = null
            }
            synchronized(retiredSessions) {
                for (s in retiredSessions) s.dispose()
                retiredSessions.clear()
            }
            tunRelay.stop()
            tunDescriptor?.let { runCatching { it.close() } }
            tunDescriptor = null
            currentProxyPassToken = ""
            currentProxyPassExpiry = 0L
            connectedRegion = ""
            connectedEdge = ""
            connectedIp = ""
            bytesSent.set(0L)
            bytesReceived.set(0L)
        }
    }

    fun dispose() {
        scope.cancel()
    }

    // --- Account / proxy pass ---

    private fun resolveActiveAccount(): FoxyAccount? {
        val accounts = accountStore.snapshot()
        if (accounts.isEmpty()) return null
        val account = accountStore.activeAccount() ?: accounts.first()
        if (!account.hasUsableCredentials) {
            AppLogRepository.warning(LogSource.APP, "Foxy: stored credentials for ${account.email} could not be read. Remove the account and sign in again.")
            return null
        }
        return account
    }

    private suspend fun ensureProxyPassWithFailover(account: FoxyAccount): ProxyPassResult? {
        val accounts = accountStore.snapshot()
        val tried = mutableSetOf(account.id)
        var current = account
        for (round in 1..3) {
            try {
                val now = System.currentTimeMillis() / 1000
                if (current.tokenExpiryEpochSeconds in 1..(now + 300)) {
                    if (!current.canRefreshSilently) {
                        AppLogRepository.warning(
                            LogSource.APP,
                            "Foxy: the Mozilla session for ${current.email} expired. Sign in again from Foxy settings.",
                        )
                        break
                    }
                    if (!fxa().refreshAccessToken(current)) {
                        AppLogRepository.warning(LogSource.APP, "Foxy: could not refresh the access token for ${current.email}; sign in again.")
                        break
                    }
                    accountStore.saveAccount(current)
                }
                val pass = guardian().fetchProxyPass(current.accessToken)
                applyProxyPass(current, pass)
                accountStore.saveAccount(current)
                return pass
            } catch (e: FoxyQuotaExceededException) {
                current.quotaRemainingBytes = 0
                accountStore.saveAccount(current)
                val next = accounts.firstOrNull { it.id !in tried && it.quotaRemainingBytes > 0 }
                if (next == null) break
                accountStore.setActive(next)
                tried.add(next.id)
                current = next
            } catch (e: FoxyAuthTokenInvalidException) {
                if (!current.canRefreshSilently) {
                    AppLogRepository.warning(
                        LogSource.APP,
                        "Foxy: Mozilla rejected the stored session for ${current.email}. Sign in again from Foxy settings.",
                    )
                    break
                }
                if (!fxa().refreshAccessToken(current)) break
                accountStore.saveAccount(current)
            }
        }
        return null
    }

    private fun applyProxyPass(account: FoxyAccount, pass: ProxyPassResult) {
        account.proxyPassToken = pass.token
        account.proxyPassExpiryEpochSeconds = pass.expiresAtEpochSeconds
        if (pass.quotaLimit != null) account.quotaLimitBytes = pass.quotaLimit
        if (pass.quotaRemaining != null) account.quotaRemainingBytes = pass.quotaRemaining
        if (pass.quotaReset != null) account.quotaResetEpochSeconds = pass.quotaReset
        account.lastUsedAtUtc = System.currentTimeMillis()
        account.lastQuotaCheckUtc = System.currentTimeMillis()
        account.quotaErrorMessage = ""
    }

    private suspend fun refreshProxyPass(): String? {
        val account = resolveActiveAccount() ?: return null
        return try {
            val pass = ensureProxyPassWithFailover(account) ?: return null
            currentProxyPassToken = pass.token
            currentProxyPassExpiry = pass.expiresAtEpochSeconds
            updateAllSessionTokens(pass.token)
            pass.token
        } catch (_: Exception) {
            null
        }
    }

    private fun updateAllSessionTokens(token: String) {
        for (i in sessions.indices) sessions[i]?.updateProxyPassToken(token)
    }

    // --- Edge dial ---

    private suspend fun dialEdgeSession(
        ordered: List<ProxyCandidate>,
        avoidAddress: InetAddress? = null,
    ): Triple<FoxyH2Session?, ProxyCandidate?, Exception?> {
        var session: FoxyH2Session? = null
        var chosen: ProxyCandidate? = null
        var lastError: Exception? = null
        val attempts = minOf(ordered.size, MAX_EDGE_ATTEMPTS)
        for (i in 0 until attempts) {
            val candidate = ordered[i]
            // Mozilla edges answer on 2499 and (through Fastly) on 443. Trying both, per
            // edge, is what makes this survive networks that only allow outbound HTTPS.
            for (port in candidatePorts(candidate.port)) {
                val authority = "${candidate.host}:$port"
                try {
                    FoxyStatusStore.update(
                        FoxyPhase.EDGE,
                        60 + i * 30 / maxOf(1, attempts),
                        "Connecting to Fastly edge ($authority)...",
                    )
                    val edge = withTimeoutOrNull(EDGE_ATTEMPT_TIMEOUT_MS) {
                        val session = FoxyH2Session(
                            candidate.host,
                            port,
                            engineStore.snapshot().upstreamProxy(),
                            avoidAddress,
                            service as? VpnService,
                        )
                        session.connect()
                        session
                    }
                    if (edge == null) {
                        lastError = java.io.IOException("Timed out connecting to $authority")
                        AppLogRepository.warning(LogSource.APP, "Foxy edge $authority timed out after ${EDGE_ATTEMPT_TIMEOUT_MS / 1000}s")
                        continue
                    }
                    session = edge
                    chosen = candidate
                    break
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    lastError = e
                    AppLogRepository.warning(LogSource.APP, "Foxy edge $authority failed: ${e.message}")
                }
            }
            if (session != null) break
        }
        if (session == null && ordered.none { it.host.equals("p.m1.fastly-masque.net", ignoreCase = true) }) {
            try {
                val edge = FoxyH2Session("p.m1.fastly-masque.net", 2499, engineStore.snapshot().upstreamProxy(), avoidAddress, service as? VpnService)
                edge.connect()
                session = edge
                chosen = ProxyCandidate("p.m1.fastly-masque.net", 2499, "REC", "Global Anycast", "REC")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
            }
        }
        return Triple(session, chosen, lastError)
    }

    private fun orderCandidates(candidates: List<ProxyCandidate>): List<ProxyCandidate> {
        val avoid = failedEdgesSnapshot()
        val settings = engineStore.snapshot()
        return GuardianClient.orderCandidates(candidates, settings.lastEdgeHost, settings.lastEdgePort, avoid)
    }

    private fun failedEdgesSnapshot(): List<String> {
        val now = System.currentTimeMillis()
        synchronized(failedEdges) {
            failedEdges.removeAll { it.second < now }
            return failedEdges.map { it.first }
        }
    }

    private fun noteEdgeFailed(host: String) {
        synchronized(failedEdges) {
            failedEdges.removeAll { it.first.equals(host, ignoreCase = true) }
            failedEdges.add(host to System.currentTimeMillis() + FAILED_EDGE_COOLDOWN_MS)
            while (failedEdges.size > MAX_REMEMBERED_FAILED_EDGES) failedEdges.removeAt(0)
        }
    }

    private fun rememberSuccessfulEdge(chosen: ProxyCandidate) {
        engineStore.rememberLastEdge(chosen.host, chosen.port)
        synchronized(failedEdges) { failedEdges.removeAll { it.first.equals(chosen.host, ignoreCase = true) } }
    }

    private fun attachSession(session: FoxyH2Session, chosen: ProxyCandidate, slot: Int) {
        session.updateProxyPassToken(currentProxyPassToken)
        session.onDraining = { reason -> onSessionDraining(session, reason) }
        session.onFailed = { reason -> onSessionFailed(session, reason) }
        sessions[slot] = session
        if (slot == 0) {
            connectedRegion = chosen.countryCode
            connectedEdge = chosen.host
            val remote = session.remoteAddress?.hostAddress ?: ""
            if (remote.isNotEmpty() && !remote.startsWith("127.")) connectedIp = remote
        }
    }

    private fun findSessionSlot(session: FoxyH2Session): Int {
        for (i in sessions.indices) if (sessions[i] === session) return i
        return -1
    }

    private fun hasOtherHealthySession(slot: Int, requireActiveStreams: Boolean = false): Boolean {
        for (i in sessions.indices) {
            if (i == slot) continue
            val peer = sessions[i] ?: continue
            if (peer.isConnected && (!requireActiveStreams || peer.activeStreamCount > 0)) return true
        }
        return false
    }

    private fun onSessionDraining(session: FoxyH2Session, reason: String) {
        if (findSessionSlot(session) < 0 || !running) return
        scope.launch { replaceSessionInPlace(session, reason, isFailure = false) }
    }

    private fun onSessionFailed(session: FoxyH2Session, reason: String) {
        if (findSessionSlot(session) < 0 || !running) return
        scope.launch { replaceSessionInPlace(session, reason, isFailure = true) }
    }

    private suspend fun replaceSessionInPlace(targetSession: FoxyH2Session, reason: String, isFailure: Boolean) {
        var slot = findSessionSlot(targetSession)
        if (slot < 0) return
        sessionSwapLocks[slot].withLock {
            slot = findSessionSlot(targetSession)
            if (slot < 0 || !running) return@withLock
            if (isFailure) {
                noteEdgeFailed(targetSession.edgeHost)
                val settings = engineStore.snapshot()
                if (settings.lastEdgeHost.equals(targetSession.edgeHost, ignoreCase = true)) engineStore.clearLastEdge()
            }
            val account = resolveActiveAccount()
            if (account != null && System.currentTimeMillis() / 1000 >= currentProxyPassExpiry - 30) {
                val freshPass = ensureProxyPassWithFailover(account)
                if (freshPass != null) {
                    currentProxyPassToken = freshPass.token
                    currentProxyPassExpiry = freshPass.expiresAtEpochSeconds
                    updateAllSessionTokens(freshPass.token)
                }
            }
            val countries = guardian().fetchCountries()
            val engineSettings = engineStore.snapshot()
            var candidates = GuardianClient.getCandidates(countries, engineSettings.serverCountry, engineSettings.serverCity)
            if (candidates.isEmpty()) {
                candidates = listOf(ProxyCandidate(targetSession.edgeHost, 2499, connectedRegion, targetSession.edgeHost, connectedRegion))
            }
            val ordered = orderCandidates(candidates)
            val avoidAddr = sessions[1 - slot]?.remoteAddress
            val (replacement, chosen, lastError) = dialEdgeSession(ordered, avoidAddr)
            if (!running) {
                replacement?.dispose()
                return@withLock
            }
            if (replacement == null || chosen == null) {
                if (hasOtherHealthySession(slot)) return@withLock
                FoxyStatusStore.update(FoxyPhase.FAILED, 0, "Foxy tunnel dropped and replacement failed: ${lastError?.message ?: reason}")
                running = false
                return@withLock
            }
            val old = sessions[slot]
            attachSession(replacement, chosen, slot)
            rememberSuccessfulEdge(chosen)
            retireSession(old)
        }
    }

    private fun retireSession(oldSession: FoxyH2Session?) {
        if (oldSession == null) return
        oldSession.onDraining = null
        oldSession.onFailed = null
        if (!oldSession.isConnected) {
            oldSession.dispose()
            return
        }
        synchronized(retiredSessions) { retiredSessions.add(oldSession) }
        scope.launch {
            delay(30_000)
            synchronized(retiredSessions) { retiredSessions.remove(oldSession) }
            oldSession.dispose()
        }
    }

    private suspend fun ensureSecondarySession(ordered: List<ProxyCandidate>) {
        sessionSwapLocks[1].withLock {
            if (!running || sessions[1]?.isConnected == true) return@withLock
            val primary = sessions[0]
            val avoidAddr = primary?.remoteAddress
            val secondaryCandidates = if (ordered.size > 1 && primary != null) {
                ordered.filter { !it.host.equals(primary.edgeHost, ignoreCase = true) } +
                    ordered.filter { it.host.equals(primary.edgeHost, ignoreCase = true) }
            } else ordered
            val (secondary, chosen, _) = dialEdgeSession(secondaryCandidates, avoidAddr)
            if (secondary == null || chosen == null || !running) {
                secondary?.dispose()
                return@withLock
            }
            attachSession(secondary, chosen, slot = 1)
            staggerJob = scope.launch { runDualPoolStaggerLoop(ordered) }
        }
    }

    private suspend fun runDualPoolStaggerLoop(ordered: List<ProxyCandidate>) {
        var slot = 1
        while (running) {
            delay(4 * 60 * 1000L)
            if (!running || !engineStore.snapshot().dualSessionPool) break
            sessionSwapLocks[slot].withLock {
                if (!running || !engineStore.snapshot().dualSessionPool) return@withLock
                val current = sessions[slot]
                val avoidAddr = sessions[1 - slot]?.remoteAddress
                val candidates = refreshCandidatesOrFallback(ordered)
                val (fresh, chosen, _) = dialEdgeSession(candidates, avoidAddr)
                if (fresh == null || chosen == null || !running) {
                    fresh?.dispose()
                    return@withLock
                }
                attachSession(fresh, chosen, slot)
                retireSession(current)
            }
            slot = 1 - slot
        }
    }

    private fun refreshCandidatesOrFallback(fallback: List<ProxyCandidate>): List<ProxyCandidate> {
        val countries = guardian().cachedCountriesOrFallback
        if (countries.isNotEmpty()) {
            val settings = engineStore.snapshot()
            val candidates = GuardianClient.getCandidates(countries, settings.serverCountry, settings.serverCity)
            if (candidates.isNotEmpty()) return orderCandidates(candidates)
        }
        return fallback
    }

    // --- SOCKS ---

    private fun startSocksServer(settings: AdvancedSettingsData) {
        val server = FoxySocksServer(
            sessionProvider = { selectUsableSession() },
            openTunnel = { session, host, port -> openConnectWithPassRetry(session, host, port) },
            onBytes = { sent, received -> bytesSent.set(sent); bytesReceived.set(received) },
        )
        socksServer = server
        val socksRequested = settings.socksPort
        val httpRequested = if (settings.socksPort in 1024..65_534) settings.socksPort + 1 else 0
        server.start(socksRequested, httpRequested)
        sessionSocksPort = server.socksPort
        sessionHttpPort = server.httpPort
    }

    private fun selectUsableSession(): FoxyH2Session? {
        var best: FoxyH2Session? = null
        var bestCount = Int.MAX_VALUE
        for (i in sessions.indices) {
            val s = sessions[i] ?: continue
            if (!s.canOpenNewStreams) continue
            val count = s.activeStreamCount
            if (count < bestCount) {
                best = s
                bestCount = count
            }
        }
        return best
    }

    private suspend fun awaitUsableSession(timeoutMs: Long): FoxyH2Session? {
        selectUsableSession()?.let { return it }
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline && running) {
            delay(100)
            selectUsableSession()?.let { return it }
        }
        return null
    }

    private suspend fun openConnectWithPassRetry(session: FoxyH2Session, host: String, port: Int): FoxyH2Stream? {
        var currentSession = session
        var swapHop = 0
        while (true) {
            if (!currentSession.canOpenNewStreams && swapHop == 0) {
                currentSession = awaitUsableSession(8_000)
                    ?: throw java.io.IOException("HTTP/2 session stopped accepting streams.")
                swapHop++
                continue
            }
            try {
                return currentSession.openConnectStream(host, port, currentProxyPassToken)
            } catch (e: FoxyAuthTokenInvalidException) {
                break
            } catch (e: java.io.IOException) {
                if (!currentSession.canOpenNewStreams && swapHop == 0) {
                    val fresh = awaitUsableSession(8_000)
                    if (fresh != null && fresh !== currentSession) {
                        currentSession = fresh
                        swapHop++
                        continue
                    }
                }
                throw e
            }
        }
        val refreshed = refreshProxyPass() ?: return null
        return try {
            currentSession.openConnectStream(host, port, refreshed)
        } catch (_: Exception) {
            null
        }
    }

    // --- Renewal ---

    private fun startProxyPassRenewal() {
        renewalJob?.cancel()
        renewalJob = scope.launch {
            while (running) {
                val delayMs = calculateRenewalDelay(currentProxyPassExpiry)
                delay(delayMs)
                if (!running) break
                val account = resolveActiveAccount() ?: break
                try {
                    val pass = ensureProxyPassWithFailover(account)
                    if (pass != null) {
                        currentProxyPassToken = pass.token
                        currentProxyPassExpiry = pass.expiresAtEpochSeconds
                        updateAllSessionTokens(pass.token)
                    } else {
                        delay(30_000)
                    }
                } catch (_: Exception) {
                    delay(30_000)
                }
            }
        }
    }

    private fun calculateRenewalDelay(expiresAtEpochSeconds: Long): Long {
        val remaining = expiresAtEpochSeconds - System.currentTimeMillis() / 1000
        if (remaining <= 0) return 15_000L
        val half = remaining * 0.5
        val beforeExpiry = remaining - 30
        return minOf(half, beforeExpiry.toDouble()).coerceIn(15.0, 1800.0).toLong() * 1000
    }

    /**
     * Best-effort exit IP probe through our own loopback HTTP relay, so the reported address is
     * the one the edge is actually egressing from. Mirrors Windows ProbeExitIpAsync: failures are
     * swallowed because the tunnel is already usable.
     */
    private suspend fun probeExitIp() = withContext(Dispatchers.IO) {
        delay(500)
        val socks = sessionSocksPort
        if (socks <= 0) return@withContext
        val httpPort = if (socks in 1024..65_534) socks + 1 else 0
        if (httpPort == 0) return@withContext
        val url = "https://api.ipify.org"
        try {
            val proxy = java.net.Proxy(java.net.Proxy.Type.HTTP, java.net.InetSocketAddress("127.0.0.1", httpPort))
            val conn = java.net.URL(url).openConnection(proxy) as java.net.HttpURLConnection
            conn.connectTimeout = 8_000
            conn.readTimeout = 8_000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("Accept", "text/plain")
            val ip = try {
                conn.inputStream.bufferedReader().use { it.readText() }
            } catch (_: Exception) {
                conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            }.trim()
            if (IPV4_REGEX.matches(ip) || IPV6_REGEX.matches(ip)) {
                FoxyStatusStore.update(
                    phase = FoxyStatusStore.status.value.phase,
                    progressPercent = FoxyStatusStore.status.value.progressPercent,
                    exitIp = ip,
                )
            }
        } catch (_: Exception) {
            // Non-fatal: no exit IP shown.
        }
    }

    /** Current upstream proxy plus its fingerprint, so cached clients rebuild on change. */
    private fun currentProxy(): Triple<java.net.Proxy, String, String?> {
        val upstream = engineStore.snapshot().upstreamProxy()
        return Triple(
            FoxyUpstreamProxy.toProxy(upstream),
            FoxyUpstreamProxy.fingerprint(upstream),
            FoxyUpstreamProxy.authHeader(upstream),
        )
    }

    /** Edge ports worth trying for one host, in order. */
    private fun candidatePorts(preferred: Int): List<Int> =
        if (preferred == 443) listOf(443, 2499) else listOf(preferred, 443)

    private fun guardian(): GuardianClient {
        val (proxy, fingerprint, auth) = currentProxy()
        val existing = guardian
        if (existing != null && fingerprint == guardianProxyKey) return existing
        guardianProxyKey = fingerprint
        return GuardianClient(proxy, proxyAuthHeader = auth).also { guardian = it }
    }

    private fun fxa(): FxaClient {
        val (proxy, fingerprint, auth) = currentProxy()
        // The cookie jar must survive proxy changes: it carries the Fastly challenge cookies.
        val jar = cookieJar ?: FoxyCookieJar().also { cookieJar = it }
        val existing = fxa
        if (existing != null && fingerprint == fxaProxyKey) return existing
        fxaProxyKey = fingerprint
        return FxaClient(jar, proxy, auth).also { fxa = it }
    }

    companion object {
        private const val MAX_EDGE_ATTEMPTS = 8
        private const val EDGE_ATTEMPT_TIMEOUT_MS = 12_000L
        private const val MAX_REMEMBERED_FAILED_EDGES = 8
        private const val FAILED_EDGE_COOLDOWN_MS = 10 * 60 * 1000L
        private val IPV4_REGEX = Regex("""^(\d{1,3}\.){3}\d{1,3}$""")
        private val IPV6_REGEX = Regex("^[0-9a-fA-F:]{2,45}$")
    }
}
