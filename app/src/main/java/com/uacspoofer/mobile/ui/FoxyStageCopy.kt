package com.uacspoofer.mobile.ui

import com.uacspoofer.mobile.engine.foxy.FoxyPhase
import com.uacspoofer.mobile.engine.foxy.FoxyStage

/**
 * Per-stage wording for the Foxy connect sheet, in both languages. Rows mirror the PoW/Tor sheet:
 * a short label plus the technical detail the engine published for that stage.
 */
internal object FoxyStageCopy {
    /** Short row title. */
    fun title(stage: FoxyStage, persian: Boolean): String = if (persian) {
        when (stage) {
            FoxyStage.PREPARE -> "آماده‌سازی Foxy"
            FoxyStage.ACCOUNT -> "بررسی حساب فایرفاکس"
            FoxyStage.SIGN_IN -> "ورود به حساب موزیلا"
            FoxyStage.PASS -> "گرفتن مجوز اتصال"
            FoxyStage.SERVERS -> "دریافت فهرست سرورها"
            FoxyStage.SELECT -> "انتخاب سرور فایرفاکس"
            FoxyStage.TLS -> "دست‌دهی امن با سرور"
            FoxyStage.H2 -> "توافق HTTP/2"
            FoxyStage.TUNNEL -> "باز کردن تونل"
            FoxyStage.TUN -> "ساخت رابط شبکه"
            FoxyStage.RELAY -> "راه‌اندازی تونل امن"
            FoxyStage.VERIFY -> "بررسی اتصال"
            FoxyStage.READY -> "اتصال آماده است"
        }
    } else {
        when (stage) {
            FoxyStage.PREPARE -> "Preparing Foxy"
            FoxyStage.ACCOUNT -> "Checking the Firefox account"
            FoxyStage.SIGN_IN -> "Signing in to Mozilla"
            FoxyStage.PASS -> "Getting the proxy pass"
            FoxyStage.SERVERS -> "Fetching the server list"
            FoxyStage.SELECT -> "Choosing a Firefox server"
            FoxyStage.TLS -> "Securing the link"
            FoxyStage.H2 -> "Agreeing on HTTP/2"
            FoxyStage.TUNNEL -> "Opening the tunnel"
            FoxyStage.TUN -> "Building the VPN interface"
            FoxyStage.RELAY -> "Starting the secure tunnel"
            FoxyStage.VERIFY -> "Checking the connection"
            FoxyStage.READY -> "Connected"
        }
    }

    /** One-line sentence for the collapsed pill / overlay, e.g. «در حال راه‌اندازی تونل امن…». */
    fun progress(stage: FoxyStage, persian: Boolean): String = if (persian) {
        when (stage) {
            FoxyStage.PREPARE -> "در حال آماده‌سازی Foxy…"
            FoxyStage.ACCOUNT -> "در حال بررسی حساب فایرفاکس…"
            FoxyStage.SIGN_IN -> "در حال ورود به حساب موزیلا…"
            FoxyStage.PASS -> "در حال گرفتن مجوز اتصال…"
            FoxyStage.SERVERS -> "در حال دریافت فهرست سرورها…"
            FoxyStage.SELECT -> "در حال انتخاب سرور فایرفاکس…"
            FoxyStage.TLS -> "در حال دست‌دهی امن با سرور…"
            FoxyStage.H2 -> "در حال توافق HTTP/2…"
            FoxyStage.TUNNEL -> "در حال باز کردن تونل…"
            FoxyStage.TUN -> "در حال ساخت رابط شبکه…"
            FoxyStage.RELAY -> "در حال راه‌اندازی تونل امن…"
            FoxyStage.VERIFY -> "در حال بررسی اتصال…"
            FoxyStage.READY -> "اتصال برقرار شد"
        }
    } else {
        when (stage) {
            FoxyStage.PREPARE -> "Preparing Foxy…"
            FoxyStage.ACCOUNT -> "Checking the Firefox account…"
            FoxyStage.SIGN_IN -> "Signing in to Mozilla…"
            FoxyStage.PASS -> "Getting the proxy pass…"
            FoxyStage.SERVERS -> "Fetching the server list…"
            FoxyStage.SELECT -> "Choosing a Firefox server…"
            FoxyStage.TLS -> "Securing the link…"
            FoxyStage.H2 -> "Agreeing on HTTP/2…"
            FoxyStage.TUNNEL -> "Opening the tunnel…"
            FoxyStage.TUN -> "Building the VPN interface…"
            FoxyStage.RELAY -> "Starting the secure tunnel…"
            FoxyStage.VERIFY -> "Checking the connection…"
            FoxyStage.READY -> "Connected"
        }
    }

    /** Overlay headline per coarse phase (kept for the compact status pill). */
    fun phase(phase: FoxyPhase, persian: Boolean): String? = when (phase) {
        FoxyPhase.IDLE, FoxyPhase.FAILED -> null
        FoxyPhase.STARTING -> if (persian) "در حال آماده‌سازی…" else "Preparing…"
        FoxyPhase.AUTH -> if (persian) "در حال ورود به حساب…" else "Signing in…"
        FoxyPhase.EDGE -> if (persian) "در حال اتصال به سرور…" else "Reaching the edge…"
        FoxyPhase.TUNNEL -> if (persian) "در حال راه‌اندازی تونل امن…" else "Starting the secure tunnel…"
        FoxyPhase.CONNECTED -> if (persian) "اتصال برقرار شد" else "Connected"
    }
}
