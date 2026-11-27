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
    var quotaLimitBytes: Long = DEFAULT_QUOTA_BYTES,
    var quotaRemainingBytes: Long = DEFAULT_QUOTA_BYTES,
    var quotaResetEpochSeconds: Long = 0L,
    var isActive: Boolean = false,
    var addedAtUtc: Long = System.currentTimeMillis(),
    var lastUsedAtUtc: Long? = null,
    var lastQuotaCheckUtc: Long? = null,
    var quotaErrorMessage: String = "",
) {
    val hasUsableCredentials: Boolean get() = accessToken.isNotBlank() && refreshToken.isNotBlank()
    val hasQuotaError: Boolean get() = quotaErrorMessage.isNotBlank()
    val quotaUsedPercentage: Double
        get() = if (quotaLimitBytes <= 0) 0.0 else ((quotaLimitBytes - quotaRemainingBytes).toDouble() / quotaLimitBytes * 100.0).coerceIn(0.0, 100.0)
    val quotaRemainingPercentage: Double
        get() = if (quotaLimitBytes <= 0) 100.0 else (quotaRemainingBytes.toDouble() / quotaLimitBytes * 100.0).coerceIn(0.0, 100.0)
    val quotaRemainingDisplay: String get() = "%.2f GB left".format(toGb(quotaRemainingBytes))
    val quotaUsedDisplay: String get() = "%.2f GB used".format(toGb(quotaLimitBytes - quotaRemainingBytes))
    val quotaLimitDisplay: String get() = "%.2f GB".format(toGb(quotaLimitBytes))
    val quotaDisplay: String get() = "%.2f GB / %.2f GB".format(toGb(quotaRemainingBytes), toGb(quotaLimitBytes))
    val quotaSummaryText: String get() = "%.2f GB of %.2f GB remaining".format(toGb(quotaRemainingBytes), toGb(quotaLimitBytes))
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
        const val DEFAULT_QUOTA_BYTES = 53_687_091_200L // 50 GB
        private fun toGb(bytes: Long): Double = maxOf(0L, bytes) / 1_073_741_824.0
    }
}
