package com.uacspoofer.mobile.ui

import com.uacspoofer.mobile.engine.foxy.FoxyPhase

/**
 * Persian wording for the connect overlay, mirroring PowStatusCopy: the engine reports technical
 * English details (edge host, HTTP status), while a Persian user gets the stage in their language.
 */
internal object FoxyStatusCopy {
    fun stage(phase: FoxyPhase, persian: Boolean): String? {
        if (!persian) return null
        return when (phase) {
            FoxyPhase.STARTING -> "آماده‌سازی موتور فایرفاکس"
            FoxyPhase.AUTH -> "ورود به حساب فایرفاکس"
            FoxyPhase.EDGE -> "پیدا کردن نزدیک‌ترین سرور فایرفاکس"
            FoxyPhase.TUNNEL -> "ساخت تونل و رلهٔ محلی"
            FoxyPhase.CONNECTED -> "به فایرفاکس VPN وصل شدی"
            FoxyPhase.IDLE, FoxyPhase.FAILED -> null
        }
    }
}
