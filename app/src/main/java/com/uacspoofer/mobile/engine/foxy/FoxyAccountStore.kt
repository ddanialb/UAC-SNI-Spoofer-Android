/*
 * Ported from Se7en Pro (MIT) — Se7enPro/Services/Foxy/FoxyAccountService.cs (account persistence)
 * Copyright (c) 2026 kng7-p (King Network). MIT License.
 * Adapted for UAC SNI Spoofer Android (AGPLv3).
 * Tokens are Keystore-encrypted via FoxySecureStore; only metadata is in plaintext prefs.
 */
package com.uacspoofer.mobile.engine.foxy

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

class FoxyAccountStore private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val secure = FoxySecureStore(appContext)
    private val mutableAccounts = MutableStateFlow(load())
    val accounts: StateFlow<List<FoxyAccount>> = mutableAccounts.asStateFlow()

    fun snapshot(): List<FoxyAccount> = mutableAccounts.value

    fun activeAccount(): FoxyAccount? {
        val list = mutableAccounts.value
        if (list.isEmpty()) return null
        val activeId = prefs.getString(KEY_ACTIVE_ID, "") ?: ""
        return list.firstOrNull { it.id == activeId } ?: list.firstOrNull { it.isActive } ?: list.firstOrNull()
    }

    @Synchronized
    fun storeAccount(account: FoxyAccount): FoxyAccount {
        val list = mutableAccounts.value.toMutableList()
        val existing = list.firstOrNull { it.email.equals(account.email, ignoreCase = true) }
        val stored: FoxyAccount
        if (existing != null) {
            existing.sessionToken = account.sessionToken
            existing.accessToken = account.accessToken
            existing.refreshToken = account.refreshToken
            existing.tokenExpiryEpochSeconds = account.tokenExpiryEpochSeconds
            existing.quotaErrorMessage = account.quotaErrorMessage
            existing.maxDevices = account.maxDevices
            existing.authMethod = account.authMethod
            existing.addedAtUtc = System.currentTimeMillis()
            stored = existing
        } else {
            list.add(account)
            stored = account
        }
        val activeId = prefs.getString(KEY_ACTIVE_ID, "") ?: ""
        if (activeId.isEmpty() || list.size == 1) {
            setActiveLocked(list, stored)
        } else {
            persist(list)
        }
        mutableAccounts.value = list.toList()
        return stored
    }

    @Synchronized
    fun setActive(account: FoxyAccount) {
        val list = mutableAccounts.value.toMutableList()
        setActiveLocked(list, account)
        persist(list)
        mutableAccounts.value = list.toList()
    }

    @Synchronized
    fun remove(account: FoxyAccount) {
        val list = mutableAccounts.value.toMutableList()
        list.removeAll { it.id == account.id }
        val activeId = prefs.getString(KEY_ACTIVE_ID, "") ?: ""
        if (activeId == account.id) {
            val next = list.firstOrNull()
            prefs.edit().putString(KEY_ACTIVE_ID, next?.id ?: "").apply()
            for (a in list) a.isActive = (a == next)
        }
        persist(list)
        mutableAccounts.value = list.toList()
    }

    @Synchronized
    fun saveAccount(account: FoxyAccount) {
        val list = mutableAccounts.value.toMutableList()
        val idx = list.indexOfFirst { it.id == account.id }
        if (idx >= 0) list[idx] = account else list.add(account)
        persist(list)
        mutableAccounts.value = list.toList()
    }

    @Synchronized
    fun saveAll() {
        persist(mutableAccounts.value.toMutableList())
    }

    private fun setActiveLocked(list: MutableList<FoxyAccount>, account: FoxyAccount) {
        for (a in list) a.isActive = (a.id == account.id)
        prefs.edit().putString(KEY_ACTIVE_ID, account.id).apply()
    }

    private fun persist(list: List<FoxyAccount>) {
        val array = JSONArray()
        for (account in list) {
            val obj = JSONObject()
            obj.put("id", account.id)
            obj.put("email", account.email)
            obj.put("tokenExpiryEpochSeconds", account.tokenExpiryEpochSeconds)
            obj.put("proxyPassExpiryEpochSeconds", account.proxyPassExpiryEpochSeconds)
            obj.put("quotaLimitBytes", account.quotaLimitBytes)
            obj.put("quotaRemainingBytes", account.quotaRemainingBytes)
            obj.put("quotaResetEpochSeconds", account.quotaResetEpochSeconds)
            obj.put("isActive", account.isActive)
            obj.put("addedAtUtc", account.addedAtUtc)
            obj.put("lastUsedAtUtc", account.lastUsedAtUtc ?: JSONObject.NULL)
            obj.put("lastQuotaCheckUtc", account.lastQuotaCheckUtc ?: JSONObject.NULL)
            obj.put("quotaErrorMessage", account.quotaErrorMessage)
            obj.put("authMethod", account.authMethod)
            obj.put("maxDevices", account.maxDevices)
            // Secrets (Keystore-encrypted).
            obj.put("sessionTokenEnc", secure.encrypt(account.sessionToken))
            obj.put("accessTokenEnc", secure.encrypt(account.accessToken))
            obj.put("refreshTokenEnc", secure.encrypt(account.refreshToken))
            obj.put("proxyPassTokenEnc", secure.encrypt(account.proxyPassToken))
            array.put(obj)
        }
        prefs.edit().putString(KEY_ACCOUNTS, array.toString()).apply()
    }

    private fun load(): List<FoxyAccount> {
        val raw = prefs.getString(KEY_ACCOUNTS, null) ?: return emptyList()
        if (raw.isBlank()) return emptyList()
        return try {
            val array = JSONArray(raw)
            val out = mutableListOf<FoxyAccount>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val account = FoxyAccount(
                    id = obj.optString("id", ""),
                    email = obj.optString("email", ""),
                    sessionToken = secure.decrypt(obj.optString("sessionTokenEnc", "")),
                    accessToken = secure.decrypt(obj.optString("accessTokenEnc", "")),
                    refreshToken = secure.decrypt(obj.optString("refreshTokenEnc", "")),
                    tokenExpiryEpochSeconds = obj.optLong("tokenExpiryEpochSeconds", 0L),
                    proxyPassToken = secure.decrypt(obj.optString("proxyPassTokenEnc", "")),
                    proxyPassExpiryEpochSeconds = obj.optLong("proxyPassExpiryEpochSeconds", 0L),
                    quotaLimitBytes = obj.optLong("quotaLimitBytes", FoxyAccount.DEFAULT_QUOTA_BYTES),
                    quotaRemainingBytes = obj.optLong("quotaRemainingBytes", FoxyAccount.DEFAULT_QUOTA_BYTES),
                    quotaResetEpochSeconds = obj.optLong("quotaResetEpochSeconds", 0L),
                    isActive = obj.optBoolean("isActive", false),
                    addedAtUtc = obj.optLong("addedAtUtc", System.currentTimeMillis()),
                    lastUsedAtUtc = if (obj.isNull("lastUsedAtUtc")) null else obj.optLong("lastUsedAtUtc", 0L).takeIf { it > 0 },
                    lastQuotaCheckUtc = if (obj.isNull("lastQuotaCheckUtc")) null else obj.optLong("lastQuotaCheckUtc", 0L).takeIf { it > 0 },
                    quotaErrorMessage = obj.optString("quotaErrorMessage", ""),
                    maxDevices = obj.optInt("maxDevices", 0),
                    authMethod = obj.optString("authMethod", FoxyAccount.AUTH_LEGACY),
                )
                if (account.id.isNotBlank()) out.add(account)
            }
            out
        } catch (_: Exception) {
            emptyList()
        }
    }

    companion object {
        private const val PREFS = "foxy_accounts_v1"
        private const val KEY_ACCOUNTS = "accounts_json"
        private const val KEY_ACTIVE_ID = "active_account_id"

        @Volatile private var instance: FoxyAccountStore? = null

        fun get(context: Context): FoxyAccountStore = instance ?: synchronized(this) {
            instance ?: FoxyAccountStore(context.applicationContext).also { instance = it }
        }
    }
}
