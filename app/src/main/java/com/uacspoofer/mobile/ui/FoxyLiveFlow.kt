/*
 * Live path dialog for the Foxy engine: device → TUN → SOCKS → edge → internet.
 * Adapted for UAC SNI Spoofer Android (AGPLv3).
 */
package com.uacspoofer.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.uacspoofer.mobile.engine.foxy.FoxyPhase
import com.uacspoofer.mobile.engine.foxy.FoxyStatusStore
import com.uacspoofer.mobile.ui.theme.UacColors

@Composable
internal fun FoxyLiveFlowDialog(visible: Boolean, onDismissRequest: () -> Unit) {
    AnimatedHomeMetricDialog(
        visible = visible,
        title = homeText("Foxy live path", "مسیر زنده Foxy"),
        subtitle = homeText("Device to Firefox VPN edge, step by step", "از گوشی تا سرور فایرفاکس، قدم به قدم"),
        icon = Icons.Outlined.Route,
        onDismissRequest = onDismissRequest,
        expanded = true,
        panelModifier = Modifier.fillMaxWidth().fillMaxHeight(0.86f),
    ) {
        if (visible) FoxyLiveFlowContent(Modifier.weight(1f))
    }
}

@Composable
private fun FoxyLiveFlowContent(modifier: Modifier = Modifier) {
    val status by FoxyStatusStore.status.collectAsStateWithLifecycle()
    val accent = UacColors.DisconnectedBlue
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val nodes = listOf(
            FoxyFlowNode(
                homeText("This device", "این دستگاه"),
                homeText("Apps send traffic to the VPN interface", "برنامه‌ها ترافیک را به رابط VPN می‌فرستند"),
                FoxyPhase.STARTING,
            ),
            FoxyFlowNode(
                homeText("Local TUN relay", "رله محلی TUN"),
                homeText("TUN → SOCKS 127.0.0.1", "TUN → SOCKS ‏۱۲۷.۰.۰.۱"),
                FoxyPhase.TUNNEL,
            ),
            FoxyFlowNode(
                homeText("Mozilla account", "حساب موزیلا"),
                if (status.phase == FoxyPhase.AUTH) {
                    homeText("Requesting a proxy pass from Guardian", "دریافت کلید پروکسی از Guardian")
                } else {
                    homeText("Signed in · quota checked by Guardian", "وارد شده · سهمیه توسط Guardian بررسی می‌شود")
                },
                FoxyPhase.AUTH,
            ),
            FoxyFlowNode(
                homeText("Firefox VPN edge", "سرور وی‌پی‌ان فایرفاکس"),
                if (status.edgeHost.isNotBlank()) {
                    homeLtr("${status.edgeHost}:2499 · HTTP/2")
                } else {
                    homeText("Fastly MASQUE edge, chosen from Mozilla's list", "سرور Fastly MASQUE از فهرست موزیلا")
                },
                FoxyPhase.EDGE,
            ),
            FoxyFlowNode(
                homeText("Internet", "اینترنت"),
                homeText("Traffic exits from the edge's network", "ترافیک از شبکه سرور خارج می‌شود"),
                FoxyPhase.CONNECTED,
            ),
        )
        nodes.forEachIndexed { index, node ->
            FoxyFlowRow(node, accent, active = status.phase == node.phase)
            if (index != nodes.lastIndex) {
                Box(
                    modifier = Modifier
                        .padding(start = 9.dp)
                        .size(width = 1.dp, height = 12.dp)
                        .background(PowBorder),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            status.detail.ifBlank { homeText("Not connected yet", "هنوز متصل نیست") },
            color = UacColors.TextSecondary,
            fontSize = 11.sp,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        if (status.exitIp.isNotBlank()) {
            Text(homeLtr("Exit IP ${status.exitIp}"), color = accent, fontSize = 11.sp)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            homeText(
                "The relay is TCP-only: UDP/QUIC is blocked inside the tunnel so browsers fall back to TCP.",
                "رله فقط TCP است: UDP/QUIC داخل تونل مسدود می‌شود تا مرورگرها به TCP برگردند.",
            ),
            color = UacColors.TextSecondary,
            fontSize = 10.sp,
            lineHeight = PowHintLine,
        )
    }
}

private data class FoxyFlowNode(val title: String, val detail: String, val phase: FoxyPhase)

@Composable
private fun FoxyFlowRow(node: FoxyFlowNode, accent: Color, active: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(19.dp)
                .clip(CircleShape)
                .background(if (active) accent.copy(alpha = 0.22f) else Color.Transparent)
                .border(1.dp, if (active) accent else PowBorder, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(if (active) accent else UacColors.TextSecondary.copy(alpha = 0.45f)),
            )
        }
        Spacer(Modifier.size(10.dp))
        Column(
            modifier = Modifier
                .weight(1f)
                .background(PowSheetColor, RoundedCornerShape(10.dp))
                .border(1.dp, PowBorder, RoundedCornerShape(10.dp))
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            Text(node.title, color = UacColors.TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            Text(node.detail, color = UacColors.TextSecondary, fontSize = 10.5.sp)
        }
    }
}
