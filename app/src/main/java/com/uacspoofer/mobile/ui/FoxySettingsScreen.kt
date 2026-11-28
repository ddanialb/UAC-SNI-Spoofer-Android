/*
 * Foxy (Firefox VPN) settings page.
 *
 * Deliberately minimal: the engine only needs a Firefox account. Location choice lives on
 * the home screen (Foxy country dialog) and the upstream-proxy plumbing of the Windows
 * original is gone, so this page is status + account and nothing else.
 */
package com.uacspoofer.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Login
import androidx.compose.material.icons.outlined.Route
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.foundation.layout.width
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.SettingsEthernet
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Public
import com.uacspoofer.mobile.engine.foxy.FoxyEngineSettings
import com.uacspoofer.mobile.engine.foxy.FoxyAccount
import com.uacspoofer.mobile.engine.foxy.FoxyAccountStore
import com.uacspoofer.mobile.engine.foxy.FoxyStatusStore
import com.uacspoofer.mobile.engine.foxy.FoxyEngineStore
import com.uacspoofer.mobile.engine.foxy.FoxyQuota
import com.uacspoofer.mobile.engine.foxy.FoxyPhase
import com.uacspoofer.mobile.profiles.CountryMetadata
import com.uacspoofer.mobile.ui.theme.UacColors
import kotlinx.coroutines.launch

@Composable
internal fun FoxySettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val accent = UacColors.DisconnectedBlue
    val accountStore = remember(context) { FoxyAccountStore.get(context) }
    val accounts by accountStore.accounts.collectAsStateWithLifecycle()
    val status by FoxyStatusStore.status.collectAsStateWithLifecycle()
    val engineStore = remember(context) { FoxyEngineStore.get(context) }
    val engineSettings by engineStore.settings.collectAsStateWithLifecycle()
    var loginVisible by remember { mutableStateOf(false) }
    var refreshingAccount by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val helloSplitChoices = listOf(
        PowMenuChoice(
            FoxyEngineSettings.HELLO_SPLIT_SNI,
            homeText("SNI split (best)", "تقسیم SNI (بهترین)"),
            homeText("Three TCP segments around the server name.", "سه تکه، درست دور نام سرور."),
        ),
        PowMenuChoice(
            FoxyEngineSettings.HELLO_SPLIT_FIRST_BYTE,
            homeText("First byte only", "فقط بایت اول"),
            homeText("Two segments; mildest splitting.", "دو تکه؛ ملایم‌ترین حالت."),
        ),
        PowMenuChoice(
            FoxyEngineSettings.HELLO_SPLIT_RECORD,
            homeText("Two TLS records", "دو رکورد TLS"),
            homeText("Rebuilds the hello as two records.", "هلو را به دو رکورد تبدیل می‌کند."),
        ),
        PowMenuChoice(
            FoxyEngineSettings.HELLO_SPLIT_OFF,
            homeText("Off", "خاموش"),
            homeText("Send the hello in one write.", "ارسال هلو در یک بسته."),
        ),
    )
    val attemptChoices = listOf(2, 4, 6, 8, 12).map { n -> PowMenuChoice(n.toString(), homeLtr(n.toString())) }
    // Same wording as UAC PoW's "IP address type": one option per real behaviour of the dial.
    val ipModes = listOf(
        PowMenuChoice(
            FoxyEngineSettings.IP_AUTO,
            homeText("Auto", "خودکار"),
            homeText("Same as before; the app decides", "مثل قبل، خود برنامه انتخاب می‌کنه"),
        ),
        PowMenuChoice(
            FoxyEngineSettings.IP_V4,
            homeLtr("IPv4"),
            homeText("Regular addresses", "آدرس‌های معمولی"),
        ),
        PowMenuChoice(
            FoxyEngineSettings.IP_V6,
            homeLtr("IPv6"),
            homeText("If your internet has no IPv6, it uses IPv4", "اگه اینترنتت IPv6 نداشت، با IPv4 وصل می‌شه"),
        ),
        PowMenuChoice(
            FoxyEngineSettings.IP_BOTH,
            homeText("Both", "هر دو"),
            homeText("Tries both", "هر دو رو امتحان می‌کنه"),
        ),
    )


    ToolPageScaffold(
        accent = accent,
        verticalSpacing = PowGap,
        header = {
            PowCompactHeader(
                accent = accent,
                onBack = onBack,
                title = homeText("UAC Foxy", "UAC Foxy"),
                subtitle = homeText("Firefox VPN", "وی‌پی‌ان فایرفاکس"),
                icon = Icons.Outlined.Route,
            )
        },
    ) {
        item {
            FoxySection(
                icon = Icons.Outlined.Route,
                title = homeText("Connection", "اتصال"),
                summary = homeText("Split mode · edge attempts · PING", "حالت تقسیم · تعداد تلاش · پینگ"),
                accent = accent,
                initiallyExpanded = true,
            ) {
                PowPickerRow(
                    icon = Icons.Outlined.Shield,
                    label = homeText("TLS hello split", "تقسیم هلو TLS"),
                    sheetTitle = homeText("TLS hello split", "تقسیم هلو TLS"),
                    choices = helloSplitChoices,
                    selectedId = engineSettings.helloSplit,
                    accent = accent,
                    subtitle = homeText("Hides the Mozilla SNI from DPI", "نام سرور موزیلا را از DPI پنهان می‌کند"),
                    onSelect = { id -> engineStore.save(engineSettings.copy(helloSplit = id)) },
                )
                PowRowDivider()
                PowPickerRow(
                    icon = Icons.Outlined.SettingsEthernet,
                    label = homeText("Edge attempts", "تعداد تلاش سرور"),
                    sheetTitle = homeText("How many edges to try", "چند سرور امتحان شود"),
                    choices = attemptChoices,
                    selectedId = engineSettings.edgeAttempts.toString(),
                    accent = accent,
                    subtitle = homeText("Higher = survives flaky carriers", "بیشتر = شانس بالاتر روی شبکه‌های شلوغ"),
                    onSelect = { id ->
                        engineStore.save(engineSettings.copy(edgeAttempts = id.toIntOrNull() ?: 6))
                    },
                )
                PowRowDivider()
                PowPickerRow(
                    icon = Icons.Outlined.Public,
                    label = homeText("IP address type", "نوع آدرس IP"),
                    subtitle = homeText("If it won't connect, try IPv6", "اگه وصل نشدی، IPv6 رو امتحان کن"),
                    sheetTitle = homeText("IP address type", "نوع آدرس IP"),
                    choices = ipModes,
                    selectedId = engineSettings.ipMode,
                    accent = accent,
                    onSelect = { id -> engineStore.save(engineSettings.copy(ipMode = id)) },
                )
                PowRowDivider()
                PowSwitchRow(
                    icon = Icons.Outlined.Favorite,
                    title = homeText("Keep-alive PING", "پینگ نگه‌دارنده"),
                    subtitle = homeText("Detects a dead edge in 30s", "سرور مرده را در ۳۰ ثانیه می‌فهمد"),
                    checked = engineSettings.keepAlive,
                    accent = accent,
                    onCheckedChange = { engineStore.save(engineSettings.copy(keepAlive = it)) },
                )
                PowRowDivider()
                PowSwitchRow(
                    icon = Icons.Outlined.Bolt,
                    title = homeText("Auto reconnect", "اتصال خودکار مجدد"),
                    subtitle = homeText("Reconnects if the tunnel drops", "اگر تونل قطع شد خودش وصل می‌شود"),
                    checked = engineSettings.autoReconnect,
                    accent = accent,
                    onCheckedChange = { engineStore.save(engineSettings.copy(autoReconnect = it)) },
                )
            }
        }

        item {
            FoxySection(
                icon = Icons.Outlined.Tune,
                title = homeText("Advanced", "پیشرفته"),
                summary = homeText("Proxy, session pool, SOCKS port", "پروکسی، استخر نشست، پورت SOCKS"),
                accent = accent,
            ) {
                PowSwitchRow(
                    icon = Icons.Outlined.Hub,
                    title = homeText("Upstream proxy", "پروکسی بالادست"),
                    subtitle = homeText("Dial the edge through your proxy", "اتصال به سرور از طریق پروکسی خودت"),
                    checked = engineSettings.customProxyEnabled,
                    accent = accent,
                    onCheckedChange = { on ->
                        engineStore.save(engineSettings.copy(customProxyEnabled = on))
                    },
                )
                if (engineSettings.customProxyEnabled) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = PowPad, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(PowGap),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FoxyTextField(
                            value = engineSettings.customProxyHost,
                            onValueChange = { engineStore.save(engineSettings.copy(customProxyHost = it)) },
                            hint = homeText("Proxy host", "هاست پروکسی"),
                            modifier = Modifier.weight(1f),
                        )
                        FoxyTextField(
                            value = if (engineSettings.customProxyPort == 0) "" else engineSettings.customProxyPort.toString(),
                            onValueChange = { raw ->
                                engineStore.save(
                                    engineSettings.copy(customProxyPort = FoxyEngineSettings.parsePortOrDefault(raw)),
                                )
                            },
                            hint = homeText("Port", "پورت"),
                            modifier = Modifier.width(96.dp),
                            numeric = true,
                        )
                    }
                }
                PowRowDivider()
                PowSwitchRow(
                    icon = Icons.Outlined.Layers,
                    title = homeText("Session pool", "استخر نشست"),
                    subtitle = homeText("Keeps a second edge warm", "یک سرور دوم آماده نگه می‌دارد"),
                    checked = engineSettings.dualSessionPool,
                    accent = accent,
                    onCheckedChange = { engineStore.save(engineSettings.copy(dualSessionPool = it)) },
                )
                PowRowDivider()
                PowPickerRow(
                    icon = Icons.Outlined.SettingsEthernet,
                    label = homeText("Local SOCKS port", "پورت SOCKS محلی"),
                    sheetTitle = homeText("Local SOCKS port", "پورت SOCKS محلی"),
                    choices = listOf(19_060, 19_061, 10_809, 7_890).map { n ->
                        PowMenuChoice(n.toString(), homeLtr(n.toString()))
                    },
                    selectedId = engineSettings.socksPort.toString(),
                    accent = accent,
                    subtitle = homeText("Port apps and LAN share use", "پورتی که برنامه‌ها و LAN استفاده می‌کنند"),
                    onSelect = { id -> engineStore.save(engineSettings.copy(socksPort = id.toIntOrNull() ?: 19_060)) },
                )
            }
        }

        item {
            FoxySection(
                icon = Icons.Outlined.Login,
                title = homeText("Firefox account", "حساب فایرفاکس"),
                summary = if (accounts.isEmpty()) {
                    homeText("Sign in once for the free quota", "یک بار وارد شو تا سهمیهٔ رایگان فعال شود")
                } else {
                    homeLtr("${accounts.size} · ${accounts.firstOrNull { it.isActive }?.email ?: accounts.first().email}")
                },
                accent = accent,
                initiallyExpanded = true,
            ) {
                if (accounts.isEmpty()) {
                    FoxyCard {
                        Text(
                            homeText(
                                "Sign in once to use your Mozilla VPN account. The remaining traffic is read from Mozilla and shown here as soon as it is reported.",
                                "یک بار وارد شو تا از حساب Mozilla VPN خودت استفاده کنی. حجم باقی‌مانده از سرور موزیلا خوانده می‌شود و به‌محض دریافت همین‌جا نمایش داده می‌شود.",
                            ),
                            color = UacColors.TextSecondary,
                            fontSize = 11.5.sp,
                            lineHeight = PowHintLine,
                        )
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(PowGap)) {
                        accounts.forEach { account ->
                            FoxyAccountRow(
                                account = account,
                                isActive = account.isActive,
                                accent = accent,
                                refreshing = refreshingAccount == account.id,
                                onSetActive = { accountStore.setActive(account) },
                                onRefresh = {
                                    refreshingAccount = account.id
                                    scope.launch {
                                        FoxyQuota.refresh(context)
                                        refreshingAccount = ""
                                    }
                                },
                                onRemove = { accountStore.remove(account) },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(PowGap))
                Button(
                    onClick = { loginVisible = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = accent),
                ) {
                    Icon(Icons.Outlined.Login, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.size(6.dp))
                    Text(
                        if (accounts.isEmpty()) {
                            homeText("Sign in with Firefox", "ورود با حساب فایرفاکس")
                        } else {
                            homeText("Add another account", "افزودن حساب دیگر")
                        },
                        style = homeLocalizedTextStyle(),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }

    if (loginVisible) {
        FoxyWebLoginDialog(
            onDismiss = { loginVisible = false },
            onSignedIn = { loginVisible = false },
        )
    }
}

private fun LazyListScope.foxyAccountRows(
    accounts: List<FoxyAccount>,
    accent: Color,
    accountStore: FoxyAccountStore,
) {
    items(accounts.size) { index ->
        val account = accounts[index]
        FoxyAccountRow(
            account = account,
            isActive = account.isActive,
            accent = accent,
            refreshing = false,
            onSetActive = { accountStore.setActive(account) },
            onRefresh = {},
            onRemove = { accountStore.remove(account) },
        )
    }
}

/**
 * Collapsible section card, same interaction as the Tor settings page: the header row opens
 * and closes the body, and the body animates in.
 */
@Composable
private fun FoxySection(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    summary: String,
    accent: Color,
    initiallyExpanded: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    var expanded by androidx.compose.runtime.saveable.rememberSaveable(title) {
        androidx.compose.runtime.mutableStateOf(initiallyExpanded)
    }
    val chevron by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = androidx.compose.animation.core.tween(260),
        label = "foxySectionChevron",
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(PowSheetColor, PowShape)
            .border(
                1.dp,
                if (expanded) accent.copy(alpha = 0.35f) else PowBorder,
                PowShape,
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = PowPad, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(PowGap),
        ) {
            PowIconTile(icon, accent)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    color = UacColors.TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
                Text(
                    homeLatinDigits(summary),
                    color = UacColors.TextSecondary,
                    fontSize = 11.5.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                Icons.Outlined.ExpandMore,
                contentDescription = null,
                tint = if (expanded) accent else UacColors.TextSecondary,
                modifier = Modifier.size(22.dp).rotate(chevron),
            )
        }
        // Same transition POW uses: the body grows downwards from the header and collapses back
        // up, so the direction matches the vertical list instead of sliding in from the side.
        PowExpandBody(visible = expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(PowPad),
                content = content,
            )
        }
    }
}

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
internal fun FoxyCard(content: @Composable ColumnScope.() -> Unit) {
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
    refreshing: Boolean,
    onSetActive: () -> Unit,
    onRefresh: () -> Unit,
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
                    if (account.hasQuotaData) {
                        homeLtr(account.quotaSummaryText)
                    } else {
                        homeText("Traffic allowance not reported yet", "حجم باقی‌مانده هنوز از سرور نیامده")
                    },
                    color = when {
                        !account.hasQuotaData -> UacColors.TextSecondary
                        account.quotaRemainingPercentage < 10.0 -> UacColors.ErrorRed
                        else -> UacColors.TextSecondary
                    },
                    fontSize = 11.sp,
                )
                if (account.quotaErrorMessage.isNotBlank()) {
                    Text(
                        account.quotaErrorMessage,
                        color = UacColors.ErrorRed,
                        fontSize = 10.5.sp,
                        lineHeight = PowHintLine,
                    )
                } else if (account.quotaResetDisplay.isNotBlank()) {
                    Text(account.quotaResetDisplay, color = UacColors.TextSecondary, fontSize = 10.sp)
                }
            }
            IconButton(onClick = onRefresh, modifier = Modifier.size(30.dp)) {
                Icon(
                    if (refreshing) Icons.Outlined.Sync else Icons.Outlined.Refresh,
                    contentDescription = homeText("Refresh traffic", "به‌روزرسانی حجم"),
                    tint = accent,
                    modifier = Modifier.size(15.dp),
                )
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
private fun FoxyTextField(
    value: String,
    onValueChange: (String) -> Unit,
    hint: String,
    modifier: Modifier = Modifier,
    numeric: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        singleLine = true,
        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, color = UacColors.TextPrimary),
        placeholder = {
            Text(hint, color = UacColors.TextSecondary, fontSize = 11.5.sp)
        },
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
            keyboardType = if (numeric) androidx.compose.ui.text.input.KeyboardType.Number else androidx.compose.ui.text.input.KeyboardType.Text,
        ),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = UacColors.DisconnectedBlue,
            unfocusedBorderColor = PowBorder,
            cursorColor = UacColors.DisconnectedBlue,
        ),
    )
}
