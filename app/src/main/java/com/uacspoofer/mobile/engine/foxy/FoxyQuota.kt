package com.uacspoofer.mobile.engine.foxy

import android.content.Context
import com.uacspoofer.mobile.logging.AppLogRepository
import com.uacspoofer.mobile.logging.LogSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads the monthly allowance for the active Firefox account.
 *
 * Mozilla does not always report it: when nothing usable comes back the stored numbers stay
 * untouched and [FoxyAccount.hasQuotaData] keeps returning false, so the UI can show an explicit
 * "unavailable" state instead of a made-up 50 GB.
 */
internal object FoxyQuota {
    suspend fun refresh(context: Context): Boolean = withContext(Dispatchers.IO) {
        val store = FoxyAccountStore.get(context)
        val account = store.activeAccount() ?: store.snapshot().firstOrNull() ?: return@withContext false
        if (account.accessToken.isBlank()) return@withContext false
        val usage = runCatching { GuardianClient().fetchUsage(account.accessToken) }.getOrNull()
        account.lastQuotaCheckUtc = System.currentTimeMillis()
        if (usage != null) {
            usage.limit?.let { account.quotaLimitBytes = it }
            usage.remaining?.let { account.quotaRemainingBytes = it }
            usage.reset?.let { account.quotaResetEpochSeconds = it }
        }
        store.saveAccount(account)
        val known = account.hasQuotaData
        AppLogRepository.debug(
            LogSource.APP,
            if (known) "Foxy: quota refreshed (${account.quotaSummaryText})" else "Foxy: quota is not reported by the API",
        )
        known
    }
}
