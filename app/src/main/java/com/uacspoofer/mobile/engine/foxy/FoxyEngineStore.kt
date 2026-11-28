package com.uacspoofer.mobile.engine.foxy

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class FoxyEngineStore private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val secure = FoxySecureStore(appContext)
    private val mutableSettings = MutableStateFlow(read())
    val settings: StateFlow<FoxyEngineSettings> = mutableSettings.asStateFlow()

    fun snapshot(): FoxyEngineSettings = mutableSettings.value

    @Synchronized
    fun save(settings: FoxyEngineSettings): FoxyEngineSettings {
        val validated = settings.validated()
        prefs.edit()
            .putString(KEY_COUNTRY, validated.serverCountry)
            .putString(KEY_CITY, validated.serverCity)
            .putString(KEY_LAST_HOST, validated.lastEdgeHost)
            .putInt(KEY_LAST_PORT, validated.lastEdgePort)
            .putBoolean(KEY_PROXY_ENABLED, validated.customProxyEnabled)
            .putString(KEY_PROXY_TYPE, validated.customProxyType)
            .putString(KEY_PROXY_HOST, validated.customProxyHost)
            .putInt(KEY_PROXY_PORT, validated.customProxyPort)
            .putString(KEY_PROXY_USER, validated.customProxyUsername)
            .putBoolean(KEY_DUAL_POOL, validated.dualSessionPool)
            .putInt(KEY_SOCKS_PORT, validated.socksPort)
            .putString(KEY_HELLO_SPLIT, validated.helloSplit)
            .putString(KEY_IP_MODE, validated.ipMode)
            .putBoolean(KEY_KEEP_ALIVE, validated.keepAlive)
            .putBoolean(KEY_AUTO_RECONNECT, validated.autoReconnect)
            .putInt(KEY_EDGE_ATTEMPTS, validated.edgeAttempts)
            .putString(KEY_FALLBACK_PORTS, validated.fallbackPorts)
            .apply()
        secure.putEncrypted(KEY_PROXY_PASS, validated.customProxyPassword)
        mutableSettings.value = validated
        return validated
    }

    fun rememberLastEdge(host: String, port: Int) {
        val current = mutableSettings.value
        save(current.copy(lastEdgeHost = host, lastEdgePort = port))
    }

    fun clearLastEdge() {
        val current = mutableSettings.value
        if (current.lastEdgeHost.isNotEmpty()) save(current.copy(lastEdgeHost = "", lastEdgePort = 0))
    }

    private fun read(): FoxyEngineSettings {
        val d = FoxyEngineSettings.DEFAULT
        fun s(k: String, def: String) = prefs.getString(k, def) ?: def
        fun i(k: String, def: Int) = prefs.getInt(k, def)
        fun b(k: String, def: Boolean) = prefs.getBoolean(k, def)
        return FoxyEngineSettings(
            serverCountry = s(KEY_COUNTRY, d.serverCountry),
            serverCity = s(KEY_CITY, d.serverCity),
            lastEdgeHost = s(KEY_LAST_HOST, d.lastEdgeHost),
            lastEdgePort = i(KEY_LAST_PORT, d.lastEdgePort),
            customProxyEnabled = b(KEY_PROXY_ENABLED, d.customProxyEnabled),
            customProxyType = s(KEY_PROXY_TYPE, d.customProxyType),
            customProxyHost = s(KEY_PROXY_HOST, d.customProxyHost),
            customProxyPort = i(KEY_PROXY_PORT, d.customProxyPort),
            customProxyUsername = s(KEY_PROXY_USER, d.customProxyUsername),
            customProxyPassword = secure.getDecrypted(KEY_PROXY_PASS, ""),
            dualSessionPool = b(KEY_DUAL_POOL, d.dualSessionPool),
            socksPort = i(KEY_SOCKS_PORT, d.socksPort),
            helloSplit = s(KEY_HELLO_SPLIT, d.helloSplit),
            ipMode = s(KEY_IP_MODE, d.ipMode),
            keepAlive = b(KEY_KEEP_ALIVE, d.keepAlive),
            autoReconnect = b(KEY_AUTO_RECONNECT, d.autoReconnect),
            edgeAttempts = i(KEY_EDGE_ATTEMPTS, d.edgeAttempts),
            fallbackPorts = s(KEY_FALLBACK_PORTS, d.fallbackPorts),
        ).validated()
    }

    companion object {
        private const val PREFS = "engine_foxy_v1"
        private const val KEY_COUNTRY = "server_country"
        private const val KEY_CITY = "server_city"
        private const val KEY_LAST_HOST = "last_edge_host"
        private const val KEY_LAST_PORT = "last_edge_port"
        private const val KEY_PROXY_ENABLED = "custom_proxy_enabled"
        private const val KEY_PROXY_TYPE = "custom_proxy_type"
        private const val KEY_PROXY_HOST = "custom_proxy_host"
        private const val KEY_PROXY_PORT = "custom_proxy_port"
        private const val KEY_PROXY_USER = "custom_proxy_username"
        private const val KEY_PROXY_PASS = "custom_proxy_password_enc"
        private const val KEY_DUAL_POOL = "dual_session_pool"
        private const val KEY_SOCKS_PORT = "socks_port"
        private const val KEY_HELLO_SPLIT = "foxy_hello_split"
        private const val KEY_IP_MODE = "foxy_ip_mode"
        private const val KEY_KEEP_ALIVE = "foxy_keep_alive"
        private const val KEY_AUTO_RECONNECT = "foxy_auto_reconnect"
        private const val KEY_EDGE_ATTEMPTS = "foxy_edge_attempts"
        private const val KEY_FALLBACK_PORTS = "foxy_fallback_ports"

        @Volatile private var instance: FoxyEngineStore? = null

        fun get(context: Context): FoxyEngineStore = instance ?: synchronized(this) {
            instance ?: FoxyEngineStore(context.applicationContext).also { instance = it }
        }
    }
}
