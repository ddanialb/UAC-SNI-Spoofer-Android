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
import androidx.compose.material.icons.outlined.Login
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.uacspoofer.mobile.engine.foxy.FoxyAccount
import com.uacspoofer.mobile.engine.foxy.FoxyAccountStore
import com.uacspoofer.mobile.engine.foxy.FoxyStatusStore
import com.uacspoofer.mobile.ui.theme.UacColors

@Composable
internal fun FoxySettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val accent = UacColors.DisconnectedBlue
    val accountStore = remember(context) { FoxyAccountStore.get(context) }
    val accounts by accountStore.accounts.collectAsStateWithLifecycle()
    val status by FoxyStatusStore.status.collectAsStateWithLifecycle()
    var loginVisible by remember { mutableStateOf(false) }
    var flowVisible by remember { mutableStateOf(false) }

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
                            status.detail.ifBlank { homeText("Idle", "بی‌کار") },
                            color = UacColors.TextSecondary,
                            fontSize = 11.5.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (status.exitIp.isNotBlank()) {
                        Text(homeLtr(status.exitIp), color = accent, fontSize = 11.5.sp)
                    }
                }
                if (status.phase.name != "IDLE") {
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { status.progressPercent / 100f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp),
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

        item { FoxySectionTitle(homeText("Firefox account", "حساب فایرفاکس")) }

        if (accounts.isEmpty()) {
            item {
                FoxyCard {
                    Text(
                        homeText(
                            "Sign in once to use the free Mozilla VPN quota (50 GB per account).",
                            "یک بار وارد شو تا از سهمیهٔ رایگان Mozilla VPN استفاده کنی (۵۰ گیگ برای هر حساب).",
                        ),
                        color = UacColors.TextSecondary,
                        fontSize = 11.5.sp,
                        lineHeight = PowHintLine,
                    )
                }
            }
        } else {
            foxyAccountRows(accounts, accent, accountStore)
        }

        item {
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
                    fontSize = 13.sp,
                )
            }
        }

        item {
            Text(
                homeLtr("Foxy build 4 · app ${com.uacspoofer.mobile.BuildConfig.VERSION_NAME}"),
                color = UacColors.TextSecondary.copy(alpha = 0.6f),
                fontSize = 10.sp,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
        item { Spacer(Modifier.height(24.dp)) }
    }

    if (loginVisible) {
        FoxyWebLoginDialog(
            onDismiss = { loginVisible = false },
            onSignedIn = { loginVisible = false },
        )
    }
    FoxyLiveFlowDialog(visible = flowVisible, onDismissRequest = { flowVisible = false })
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
            onSetActive = { accountStore.setActive(account) },
            onRemove = { accountStore.remove(account) },
        )
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
