/*
 * Ported from Se7en Pro (MIT) — Se7enPro/Models/FirefoxAccountInfo.cs
 * Copyright (c) 2026 kng7-p (King Network). MIT License.
 * Adapted for UAC SNI Spoofer Android (AGPLv3).
 */
package com.uacspoofer.mobile.engine.foxy

import java.util.UUID

class FoxyAccount(
    val id: String = UUID.randomUUID().toString().replace("-", ""),
    var email: String = "",
    var sessionToken: String = "",
    var accessToken: String = "",
    var refreshToken: String = "",
    var tokenExpiryEpochSeconds: Long = 0L,
    var proxyPassToken: String = "",
    var proxyPassExpiryEpochSeconds: Long = 0L,
    // No invented numbers: until Mozilla reports the real allowance these stay zero and the UI
    // shows an explicit "unavailable" state instead of a fake 50 GB.
    var quotaLimitBytes: Long = 0L,
    var quotaRemainingBytes: Long = 0L,
    var quotaResetEpochSeconds: Long = 0L,
    var isActive: Boolean = false,
    var addedAtUtc: Long = System.currentTimeMillis(),
    var lastUsedAtUtc: Long? = null,
    var lastQuotaCheckUtc: Long? = null,
    var quotaErrorMessage: String = "",
    var maxDevices: Int = 0,
    /**
     * How this account was signed in. [AUTH_WEB] is the in-app browser flow (Guardian
     * session JWT, no stored password); [AUTH_LEGACY] is the old FxA email+password login.
     */
    var authMethod: String = AUTH_WEB,
) {
    val hasUsableCredentials: Boolean get() = accessToken.isNotBlank()

    /** True when the token can be renewed without asking the user to sign in again. */
    val canRefreshSilently: Boolean get() = refreshToken.isNotBlank()

    val isWebLogin: Boolean get() = authMethod == AUTH_WEB
    val hasQuotaError: Boolean get() = quotaErrorMessage.isNotBlank()

    /** True only when a real allowance was read from the API for this account. */
    val hasQuotaData: Boolean
        get() = quotaLimitBytes > 0 && quotaRemainingBytes >= 0 && lastQuotaCheckUtc != null
    val quotaUsedPercentage: Double
        get() = if (quotaLimitBytes <= 0) 0.0 else ((quotaLimitBytes - quotaRemainingBytes).toDouble() / quotaLimitBytes * 100.0).coerceIn(0.0, 100.0)
    val quotaRemainingPercentage: Double
        get() = if (quotaLimitBytes <= 0) 100.0 else (quotaRemainingBytes.toDouble() / quotaLimitBytes * 100.0).coerceIn(0.0, 100.0)
    val quotaRemainingDisplay: String get() = if (hasQuotaData) "%.2f GB left".format(toGb(quotaRemainingBytes)) else ""
    val quotaUsedDisplay: String
        get() = if (hasQuotaData) "%.2f GB used".format(toGb(quotaLimitBytes - quotaRemainingBytes)) else ""
    val quotaLimitDisplay: String get() = if (hasQuotaData) "%.2f GB".format(toGb(quotaLimitBytes)) else ""
    val quotaDisplay: String
        get() = if (hasQuotaData) "%.2f GB / %.2f GB".format(toGb(quotaRemainingBytes), toGb(quotaLimitBytes)) else ""
    val quotaSummaryText: String
        get() = if (hasQuotaData) "%.2f GB of %.2f GB remaining".format(toGb(quotaRemainingBytes), toGb(quotaLimitBytes)) else ""
    val quotaResetDisplay: String
        get() {
            if (quotaResetEpochSeconds <= 0) return ""
            val whenDate = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                .format(java.util.Date(quotaResetEpochSeconds * 1000))
            return "Resets $whenDate"
        }
    val quotaProgressBarColor: String
        get() = when {
            quotaRemainingBytes <= 0 -> "#EF4444"
            toGb(quotaRemainingBytes) < 5.0 -> "#F59E0B"
            else -> "#10B981"
        }

    companion object {
        const val AUTH_WEB = "web"
        const val AUTH_LEGACY = "legacy"
        private fun toGb(bytes: Long): Double = maxOf(0L, bytes) / 1_073_741_824.0
    }
}
