/*
 * Foxy (Firefox VPN) settings page — mirrors Se7en Pro `FoxySettingsView.xaml`.
 * Ported/adapted for UAC SNI Spoofer Android (AGPLv3).
 */
package com.uacspoofer.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.uacspoofer.mobile.engine.foxy.FoxyAccount
import com.uacspoofer.mobile.engine.foxy.FoxyAccountStore
import com.uacspoofer.mobile.engine.foxy.FoxyEngineSettings
import com.uacspoofer.mobile.engine.foxy.FoxyEngineStore
import com.uacspoofer.mobile.engine.foxy.FoxyStatusStore
import com.uacspoofer.mobile.ui.theme.UacColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun FoxySettingsScreen(onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val accent = UacColors.DisconnectedBlue
    // CompositionLocal can only be read during composition; event handlers use this snapshot.
    val persian = LocalHomePersian.current
    val accountStore = remember(context) { FoxyAccountStore.get(context) }
    val engineStore = remember(context) { FoxyEngineStore.get(context) }
    val accounts by accountStore.accounts.collectAsStateWithLifecycle()
    val settings by engineStore.settings.collectAsStateWithLifecycle()
    val status by FoxyStatusStore.status.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScopeSafe()
    var loginVisible by remember { mutableStateOf(false) }
    var flowVisible by remember { mutableStateOf(false) }

    ToolPageScaffold(
        accent = accent,
        verticalSpacing = PowGap,
        header = {
            PowCompactHeader(
                accent = accent,
                onBack = onBack,
                title = homeText("Foxy", "Foxy"),
                subtitle = homeText("Firefox VPN · HTTP/2", "وی‌پی‌ان فایرفاکس · HTTP/2"),
                icon = Icons.Outlined.Route,
            )
        },
    ) {
        // ---- Live status ----
        item {
            FoxyCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            homeText("Status", "وضعیت"),
                            color = UacColors.TextPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            status.detail.ifBlank {
                                homeText("Idle", "بی‌کار")
                            },
                            color = UacColors.TextSecondary,
                            fontSize = 11.5.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (status.exitIp.isNotBlank()) {
                        Text(
                            homeLtr(status.exitIp),
                            color = accent,
                            fontSize = 11.5.sp,
                        )
                    }
                }
                if (status.phase.name != "IDLE") {
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { status.progressPercent / 100f },
                        modifier = Modifier.fillMaxWidth().height(4.dp),
                        color = accent,
                        trackColor = PowBorder,
                    )
                }
                Spacer(Modifier.height(3.dp))
                TextButton(onClick = { flowVisible = true }) {
                    Text(homeText("Show live path", "نمایش مسیر زنده"), fontSize = 12.sp, color = accent)
                }
            }
        }

        // ---- Accounts ----
        item {
            FoxySectionTitle(homeText("Firefox accounts", "حساب‌های فایرفاکس"))
        }
        if (accounts.isEmpty()) {
            item {
                FoxyCard {
                    Text(
                        homeText(
                            "Sign in with a Firefox account to get a Mozilla VPN quota (50 GB free per account).",
                            "با حساب فایرفاکس وارد شوید تا سهمیه وی‌پی‌ان Mozilla بگیرید (۵۰ گیگابایت رایگان برای هر حساب).",
                        ),
                        color = UacColors.TextSecondary,
                        fontSize = 11.5.sp,
                    )
                }
            }
        }
        items(accounts.size) { index ->
            val account = accounts[index]
            FoxyAccountRow(
                account = account,
                isActive = account.isActive,
                accent = accent,
                onSetActive = { accountStore.setActive(account) },
                onRemove = { accountStore.remove(account) },
            )
        }
        item {
            Button(
                onClick = { loginVisible = true },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = accent),
            ) {
                Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.size(6.dp))
                Text(homeText("Add Firefox account", "افزودن حساب فایرفاکس"), fontSize = 13.sp)
            }
        }

        // ---- Location ----
        item { FoxySectionTitle(homeText("Server location", "موقعیت سرور")) }
        item { FoxyLocationCard(accent = accent, settings = settings, engineStore = engineStore) }

        // ---- Upstream proxy ----
        item { FoxySectionTitle(homeText("Upstream proxy (optional)", "پروکسی بالادست (اختیاری)")) }
        item {
            FoxyProxyCard(
                accent = accent,
                persian = persian,
                settings = settings,
                engineStore = engineStore,
            )
        }

        // ---- Notes ----
        item {
            FoxyCard {
                Text(
                    homeText("How Foxy works", "Foxy چگونه کار می‌کند"),
                    color = UacColors.TextPrimary,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    homeText(
                        "Traffic leaves the phone through a Mozilla/Firefox VPN edge over HTTP/2 " +
                            "(Fastly MASQUE). The relay is TCP-only, so UDP/QUIC falls back to TCP. " +
                            "Quota is shared across all devices on the same Firefox account.",
                        "ترافیک گوشی از طریق سرور وی‌پی‌ان Mozilla/Firefox روی HTTP/2 (Fastly MASQUE) خارج می‌شود. " +
                            "رله فقط TCP است، پس UDP/QUIC به TCP برمی‌گردد. سهمیه بین همه دستگاه‌های همان حساب مشترک است.",
                    ),
                    color = UacColors.TextSecondary,
                    fontSize = 11.sp,
                    lineHeight = PowHintLine,
                )
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }

    if (loginVisible) {
        FoxyLoginDialog(
            onDismiss = { loginVisible = false },
            onAccountAdded = { loginVisible = false },
        )
    }
    FoxyLiveFlowDialog(visible = flowVisible, onDismissRequest = { flowVisible = false })
}

@Composable
private fun rememberCoroutineScopeSafe() = androidx.compose.runtime.rememberCoroutineScope()

@Composable
private fun FoxySectionTitle(text: String) {
    Text(
        text,
        color = UacColors.TextSecondary,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 4.dp, top = 6.dp),
    )
}

@Composable
internal fun FoxyCard(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(PowSheetColor, PowShape)
            .border(1.dp, PowBorder, PowShape)
            .padding(PowPad),
        content = content,
    )
}

@Composable
private fun FoxyAccountRow(
    account: FoxyAccount,
    isActive: Boolean,
    accent: Color,
    onSetActive: () -> Unit,
    onRemove: () -> Unit,
) {
    FoxyCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isActive) {
                        Icon(
                            Icons.Outlined.CheckCircle,
                            contentDescription = null,
                            tint = UacColors.ConnectedGreen,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.size(5.dp))
                    }
                    Text(
                        account.email,
                        color = UacColors.TextPrimary,
                        fontSize = 12.5.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(3.dp))
                Text(
                    homeLtr(account.quotaSummaryText),
                    color = if (account.quotaRemainingPercentage < 10.0) UacColors.ErrorRed else UacColors.TextSecondary,
                    fontSize = 11.sp,
                )
                if (account.quotaResetDisplay.isNotBlank()) {
                    Text(account.quotaResetDisplay, color = UacColors.TextSecondary, fontSize = 10.sp)
                }
            }
            if (!isActive) {
                TextButton(onClick = onSetActive) {
                    Text(homeText("Use", "استفاده"), fontSize = 11.sp, color = accent)
                }
            }
            IconButton(onClick = onRemove) {
                Icon(
                    Icons.Outlined.Delete,
                    contentDescription = homeText("Remove account", "حذف حساب"),
                    tint = UacColors.TextSecondary,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

@Composable
private fun FoxyLocationCard(
    accent: Color,
    settings: FoxyEngineSettings,
    engineStore: FoxyEngineStore,
) {
    FoxyCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Public, contentDescription = null, tint = accent, modifier = Modifier.size(16.dp))
            Spacer(Modifier.size(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    homeText("Preferred country", "کشور ترجیحی"),
                    color = UacColors.TextPrimary,
                    fontSize = 12.5.sp,
                )
                Text(
                    if (settings.serverCountry == "REC") {
                        homeText("Recommended (lowest latency)", "پیشنهادی (کمترین تأخیر)")
                    } else {
                        homeLtr(settings.serverCountry)
                    },
                    color = UacColors.TextSecondary,
                    fontSize = 11.sp,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("REC" to homeText("Recommended", "پیشنهادی"), "US" to "US", "DE" to "DE", "NL" to "NL").forEach { (code, label) ->
                val selected = settings.serverCountry == code
                Box(
                    modifier = Modifier
                        .background(
                            if (selected) accent.copy(alpha = 0.18f) else Color.Transparent,
                            PowTileShape,
                        )
                        .border(1.dp, if (selected) accent else PowBorder, PowTileShape)
                        .clickable {
                            engineStore.save(settings.copy(serverCountry = code, serverCity = ""))
                        }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Text(label, color = if (selected) accent else UacColors.TextSecondary, fontSize = 11.sp)
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            homeText(
                "Edges are picked from Mozilla's live server list; the last working edge is retried first.",
                "سرورها از فهرست زنده موزیلا انتخاب می‌شوند؛ آخرین سرور موفق اول امتحان می‌شود.",
            ),
            color = UacColors.TextSecondary,
            fontSize = 10.5.sp,
        )
    }
}

@Composable
private fun FoxyProxyCard(
    accent: Color,
    persian: Boolean,
    settings: FoxyEngineSettings,
    engineStore: FoxyEngineStore,
) {
    var enabled by remember(settings.customProxyEnabled) { mutableStateOf(settings.customProxyEnabled) }
    var host by remember(settings.customProxyHost) { mutableStateOf(settings.customProxyHost) }
    var port by remember(settings.customProxyPort) { mutableStateOf(settings.customProxyPort.toString()) }
    var username by remember(settings.customProxyUsername) { mutableStateOf(settings.customProxyUsername) }
    var password by remember(settings.customProxyPassword) { mutableStateOf(settings.customProxyPassword) }
    var type by remember(settings.customProxyType) { mutableStateOf(settings.customProxyType) }
    var paste by remember { mutableStateOf("") }
    var pasteError by remember { mutableStateOf<String?>(null) }

    FoxyCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(homeText("Route through a proxy", "عبور از پروکسی"), color = UacColors.TextPrimary, fontSize = 12.5.sp)
                Text(
                    homeText(
                        "Useful when Mozilla sign-in or the edge is blocked on your network.",
                        "زمانی مفید است که ورود موزیلا یا سرور در شبکه شما مسدود باشد.",
                    ),
                    color = UacColors.TextSecondary,
                    fontSize = 10.5.sp,
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = {
                    enabled = it
                    engineStore.save(settings.copy(customProxyEnabled = it))
                },
            )
        }
        if (!enabled) return@FoxyCard

        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("socks5" to "SOCKS5", "http" to "HTTP").forEach { (value, label) ->
                val selected = type == value
                Box(
                    modifier = Modifier
                        .background(if (selected) accent.copy(alpha = 0.18f) else Color.Transparent, PowTileShape)
                        .border(1.dp, if (selected) accent else PowBorder, PowTileShape)
                        .clickable { type = value }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Text(label, color = if (selected) accent else UacColors.TextSecondary, fontSize = 11.sp)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        FoxyTextField(
            value = host,
            onValueChange = { host = it },
            label = homeText("Proxy host", "میزبان پروکسی"),
        )
        Spacer(Modifier.height(6.dp))
        FoxyTextField(
            value = port,
            onValueChange = { port = it.filter(Char::isDigit).take(5) },
            label = homeText("Port", "پورت"),
            keyboardType = KeyboardType.Number,
        )
        Spacer(Modifier.height(6.dp))
        FoxyTextField(
            value = username,
            onValueChange = { username = it },
            label = homeText("Username (optional)", "نام کاربری (اختیاری)"),
        )
        Spacer(Modifier.height(6.dp))
        FoxyTextField(
            value = password,
            onValueChange = { password = it },
            label = homeText("Password (optional)", "رمز عبور (اختیاری)"),
        )
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = {
                engineStore.save(
                    settings.copy(
                        customProxyEnabled = true,
                        customProxyType = type,
                        customProxyHost = host.trim(),
                        customProxyPort = FoxyEngineSettings.parsePortOrDefault(port),
                        customProxyUsername = username.trim(),
                        customProxyPassword = password,
                    ),
                )
                pasteError = null
            },
            colors = ButtonDefaults.buttonColors(containerColor = accent),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(homeText("Save proxy", "ذخیره پروکسی"), fontSize = 13.sp)
        }
        Spacer(Modifier.height(6.dp))
        FoxyTextField(
            value = paste,
            onValueChange = { paste = it; pasteError = null },
            label = homeText("Paste socks5://user:pass@host:port", "چسباندن socks5://user:pass@host:port"),
            keyboardType = KeyboardType.Uri,
        )
        Button(
            onClick = {
                val parsed = FoxyEngineSettings.tryParseProxyInput(paste)
                if (parsed == null) {
                    pasteError = homeText(persian, "Could not parse that proxy URL", "آدرس پروکسی خوانده نشد")
                } else {
                    host = parsed.host
                    port = if (parsed.port > 0) parsed.port.toString() else port
                    username = parsed.username
                    password = parsed.password
                    type = FoxyEngineSettings.normalizeScheme(parsed.scheme)
                    pasteError = null
                    engineStore.save(
                        settings.copy(
                            customProxyEnabled = true,
                            customProxyType = type,
                            customProxyHost = parsed.host,
                            customProxyPort = if (parsed.port > 0) parsed.port else settings.customProxyPort,
                            customProxyUsername = parsed.username,
                            customProxyPassword = parsed.password,
                        ),
                    )
                }
            },
            colors = ButtonDefaults.buttonColors(containerColor = accent.copy(alpha = 0.25f)),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(homeText("Parse and apply", "خواندن و اعمال"), fontSize = 12.sp)
        }
        if (pasteError != null) {
            Text(pasteError!!, color = UacColors.ErrorRed, fontSize = 11.sp)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            homeText(
                "Only the control traffic (Mozilla sign-in, Guardian, edge) goes through this proxy. " +
                    "Proxy credentials are stored encrypted.",
                "فقط ترافیک کنترلی (ورود موزیلا، Guardian و سرور) از این پروکسی عبور می‌کند. " +
                    "اطلاعات پروکسی رمزنگاری‌شده ذخیره می‌شود.",
            ),
            color = UacColors.TextSecondary,
            fontSize = 10.sp,
        )
    }
}

@Composable
internal fun FoxyTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, fontSize = 12.sp) },
        singleLine = true,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = keyboardType),
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = UacColors.TextPrimary,
            unfocusedTextColor = UacColors.TextPrimary,
            focusedLabelColor = UacColors.TextSecondary,
            unfocusedLabelColor = UacColors.TextSecondary,
            focusedBorderColor = UacColors.DisconnectedBlue,
            unfocusedBorderColor = PowBorder,
            cursorColor = UacColors.DisconnectedBlue,
        ),
    )
}
