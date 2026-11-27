/*
 * Country picker for the Foxy engine (Mozilla VPN server list).
 * Adapted for UAC SNI Spoofer Android (AGPLv3).
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.uacspoofer.mobile.engine.foxy.FoxyEngineStore
import com.uacspoofer.mobile.engine.foxy.GuardianClient
import com.uacspoofer.mobile.engine.foxy.VpnCountry
import com.uacspoofer.mobile.ui.theme.UacColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Country list straight from Mozilla's Remote Settings server list, falling back to the built-in
 * list when the network is unavailable. Selecting a country only changes the preference; the
 * reconnect picks the first matching edge.
 */
@Composable
internal fun HomeFoxyCountryDialog(
    visible: Boolean,
    connected: Boolean,
    onDismissRequest: () -> Unit,
) {
    val context = LocalContext.current
    val store = remember(context) { FoxyEngineStore.get(context) }
    val settings by store.settings.collectAsStateWithLifecycle()
    var countries by remember { mutableStateOf<List<VpnCountry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(visible) {
        if (!visible) return@LaunchedEffect
        loading = true
        countries = withContext(Dispatchers.IO) {
            runCatching {
                GuardianClient().fetchCountries().takeIf { it.isNotEmpty() }
                    ?: GuardianClient.getBuiltInFallbackCountries()
            }.getOrDefault(GuardianClient.getBuiltInFallbackCountries())
        }
        loading = false
    }

    AnimatedHomeMetricDialog(
        visible = visible,
        title = homeText("Firefox VPN location", "موقعیت وی‌پی‌ان فایرفاکس"),
        subtitle = if (connected) {
            homeText("Disconnect to change location", "برای تغییر موقعیت ابتدا قطع کنید")
        } else {
            homeText("Mozilla VPN server country", "کشور سرور وی‌پی‌ان موزیلا")
        },
        icon = Icons.Outlined.Public,
        onDismissRequest = onDismissRequest,
        expanded = true,
        panelModifier = Modifier.fillMaxWidth().fillMaxHeight(0.8f),
    ) {
        if (!visible) return@AnimatedHomeMetricDialog
        Column(modifier = Modifier.fillMaxWidth()) {
            FoxyCountryRow(
                code = "REC",
                name = homeText("Recommended", "پیشنهادی"),
                selected = settings.serverCountry == "REC",
                enabled = !connected,
                onClick = {
                    store.save(settings.copy(serverCountry = "REC", serverCity = ""))
                    onDismissRequest()
                },
            )
            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(PowBorder))
            if (loading) {
                Text(
                    homeText("Loading Mozilla's server list…", "در حال دریافت فهرست سرورهای موزیلا…"),
                    color = UacColors.TextSecondary,
                    fontSize = 11.5.sp,
                    modifier = Modifier.padding(12.dp),
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    itemsIndexed(countries, key = { _, item -> item.code }) { _, country ->
                        FoxyCountryRow(
                            code = country.code,
                            name = country.name,
                            selected = settings.serverCountry.equals(country.code, ignoreCase = true),
                            enabled = !connected,
                            onClick = {
                                store.save(settings.copy(serverCountry = country.code, serverCity = ""))
                                onDismissRequest()
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FoxyCountryRow(
    code: String,
    name: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val accent = UacColors.DisconnectedBlue
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            if (selected) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
            contentDescription = null,
            tint = when {
                selected -> accent
                enabled -> UacColors.TextSecondary
                else -> UacColors.TextSecondary.copy(alpha = 0.4f)
            },
            modifier = Modifier.size(17.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                name,
                color = if (enabled) UacColors.TextPrimary else UacColors.TextPrimary.copy(alpha = 0.5f),
                fontSize = 12.5.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                homeLtr(code),
                color = UacColors.TextSecondary,
                fontSize = 10.sp,
            )
        }
        if (selected) {
            Box(
                modifier = Modifier
                    .background(accent.copy(alpha = 0.16f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Text(homeText("Active", "فعال"), color = accent, fontSize = 9.5.sp)
            }
        }
    }
}
