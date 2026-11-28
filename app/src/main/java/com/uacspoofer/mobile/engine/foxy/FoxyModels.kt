/*
 * Ported from Se7en Pro (MIT) — Se7enPro/Services/Foxy/FoxyModels.cs
 * Copyright (c) 2026 kng7-p (King Network). MIT License.
 * Adapted for UAC SNI Spoofer Android (AGPLv3). No Windows binaries copied.
 */
package com.uacspoofer.mobile.engine.foxy

data class VpnCountry(val name: String, val code: String, val cities: List<VpnCity>)
data class VpnCity(val name: String, val code: String, val servers: List<VpnServerNode>)
data class VpnServerNode(val hostname: String, val port: Int, val quarantined: Boolean = false)

data class ProxyCandidate(
    val host: String,
    val port: Int,
    val countryCode: String,
    val countryName: String,
    val cityCode: String,
) {
    val authority: String get() = "$host:$port"
}

data class ProxyPassResult(
    val token: String,
    val expiresAtEpochSeconds: Long,
    val quotaLimit: Long? = null,
    val quotaRemaining: Long? = null,
    val quotaReset: Long? = null,
)

data class UpstreamProxySettings(
    val enabled: Boolean,
    val type: String,
    val host: String,
    val port: Int,
    val username: String? = null,
    val password: String? = null,
)

data class FxaLoginResult(
    val success: Boolean,
    val requires2Fa: Boolean,
    val sessionToken: String? = null,
    val account: FoxyAccount? = null,
    val errorMessage: String? = null,
)

class FxaException(message: String, val errno: Int? = null, val statusCode: Int? = null) :
    Exception(message)

class FoxyQuotaExceededException(message: String) : Exception(message)
class FoxyAuthTokenInvalidException(message: String) : Exception(message)
class FoxyTargetRejectedException(message: String) : Exception(message)
