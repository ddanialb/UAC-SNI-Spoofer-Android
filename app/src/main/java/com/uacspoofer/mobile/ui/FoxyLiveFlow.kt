/*
 * Live path for the Foxy engine, built on the same visual language as the Tor live path:
 * a stat header, one node per hop with a state colour, animated connectors and a legend.
 */
package com.uacspoofer.mobile.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.uacspoofer.mobile.engine.foxy.FoxyPhase
import com.uacspoofer.mobile.engine.foxy.FoxyStatusStore
import com.uacspoofer.mobile.engine.foxy.FoxyUiStatus
import com.uacspoofer.mobile.ui.theme.UacColors
import kotlinx.coroutines.delay

private val FoxyFlowRunning = Color(0xFFFFB547)
private val FoxyFlowPending = Color(0xFF5B6B80)

private enum class FoxyFlowState { DONE, RUNNING, PENDING, FAILED }

private fun FoxyFlowState.color(): Color = when (this) {
    FoxyFlowState.DONE -> UacColors.ConnectedGreen
    FoxyFlowState.RUNNING -> FoxyFlowRunning
    FoxyFlowState.FAILED -> UacColors.ErrorRed
    FoxyFlowState.PENDING -> FoxyFlowPending
}

private enum class FoxyHop(val phase: FoxyPhase, val icon: ImageVector) {
    PREPARE(FoxyPhase.STARTING, Icons.Outlined.Route),
    ACCOUNT(FoxyPhase.AUTH, Icons.Outlined.Shield),
    EDGE(FoxyPhase.EDGE, Icons.Outlined.Public),
    TUNNEL(FoxyPhase.TUNNEL, Icons.Outlined.Route),
    ONLINE(FoxyPhase.CONNECTED, Icons.Outlined.Public),
}

private fun FoxyPhase.order(): Int = when (this) {
    FoxyPhase.IDLE -> -1
    FoxyPhase.STARTING -> 0
    FoxyPhase.AUTH -> 1
    FoxyPhase.EDGE -> 2
    FoxyPhase.TUNNEL -> 3
    FoxyPhase.CONNECTED -> 4
    FoxyPhase.FAILED -> 4
}

private fun hopState(status: FoxyUiStatus, hop: FoxyHop): FoxyFlowState {
    val current = status.phase.order()
    val mine = hop.ordinal
    return when {
        status.phase == FoxyPhase.FAILED && mine == current -> FoxyFlowState.FAILED
        status.phase == FoxyPhase.CONNECTED -> FoxyFlowState.DONE
        mine < current -> FoxyFlowState.DONE
        mine == current -> FoxyFlowState.RUNNING
        else -> FoxyFlowState.PENDING
    }
}

/** Tor-style entry point: one compact row that opens the live path. */
@Composable
internal fun FoxyLiveFlowButton(accent: Color, onClick: () -> Unit) {
    val status by FoxyStatusStore.status.collectAsStateWithLifecycle()
    val dot = when (status.phase) {
        FoxyPhase.IDLE -> UacColors.TextSecondary
        FoxyPhase.CONNECTED -> UacColors.ConnectedGreen
        FoxyPhase.FAILED -> UacColors.ErrorRed
        else -> FoxyFlowRunning
    }
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Brush.linearGradient(listOf(Color(0xE6142231), Color(0xD90B1724))), shape)
            .border(1.dp, accent.copy(alpha = 0.28f), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(34.dp).background(accent.copy(alpha = 0.14f), CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Route, null, tint = accent, modifier = Modifier.size(19.dp))
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                homeText("Live path", "مسیر زنده"),
                color = UacColors.TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = homeLocalizedFont(),
            )
            Text(
                status.detail.ifBlank { homeText("Every step of the Foxy connect, live", "همهٔ مراحل اتصال فاکسی، زنده") },
                color = UacColors.TextSecondary,
                fontSize = 11.5.sp,
                fontFamily = homeLocalizedFont(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(Modifier.size(8.dp).background(dot, CircleShape))
        Spacer(Modifier.width(8.dp))
        Icon(Icons.Outlined.ChevronLeft, null, tint = UacColors.TextSecondary, modifier = Modifier.size(18.dp))
    }
}

@Composable
internal fun FoxyLiveFlowDialog(visible: Boolean, onDismissRequest: () -> Unit) {
    AnimatedHomeMetricDialog(
        visible = visible,
        title = homeText("Live path · Foxy", "مسیر زنده · Foxy"),
        subtitle = homeText("The connect process, step by step", "فرایند اتصال، مرحله به مرحله"),
        icon = Icons.Outlined.Route,
        onDismissRequest = onDismissRequest,
        expanded = true,
        panelModifier = Modifier.fillMaxWidth().fillMaxHeight(0.9f),
    ) {
        if (visible) {
            // Swallow taps inside the panel so only the margin closes it (previously a tap on
            // any empty spot of the live path dismissed the dialog).
            Box(Modifier.fillMaxWidth().pointerInput(Unit) { detectTapGestures { } }) {
                FoxyLiveFlowContent()
            }
        }
    }
}

@Composable
internal fun FoxyLiveFlowContent() {
    val status by FoxyStatusStore.status.collectAsStateWithLifecycle()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val running = status.phase != FoxyPhase.IDLE &&
        status.phase != FoxyPhase.CONNECTED &&
        status.phase != FoxyPhase.FAILED
    LaunchedEffect(running) {
        while (running) {
            now = System.currentTimeMillis()
            delay(1_000L)
        }
        now = System.currentTimeMillis()
    }
    var open by remember { mutableStateOf<FoxyHop?>(null) }
    val pulse = rememberInfiniteTransition(label = "foxyFlowPulse")
    val pulseAlpha by pulse.animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "pulse",
    )
    val dash by pulse.animateFloat(
        initialValue = 0f,
        targetValue = 24f,
        animationSpec = infiniteRepeatable(tween(1_100)),
        label = "dash",
    )

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 12.dp),
    ) {
        FoxyFlowHeader(status, now)
        Spacer(Modifier.height(12.dp))
        if (status.phase == FoxyPhase.IDLE) {
            Text(
                homeText("Connect with Foxy to see the live path.", "برای دیدن مسیر زنده، با Foxy وصل شو."),
                color = UacColors.TextSecondary,
                fontSize = 12.5.sp,
                fontFamily = homeLocalizedFont(),
                modifier = Modifier.padding(8.dp),
            )
        }
        val hops = FoxyHop.entries
        hops.forEachIndexed { index, hop ->
            FoxyFlowNode(
                hop = hop,
                status = status,
                state = hopState(status, hop),
                expanded = open == hop,
                pulseAlpha = pulseAlpha,
                onClick = { open = if (open == hop) null else hop },
            )
            if (index < hops.lastIndex) {
                FoxyFlowConnector(
                    color = if (hopState(status, hop) == FoxyFlowState.DONE) {
                        UacColors.ConnectedGreen.copy(alpha = 0.7f)
                    } else {
                        FoxyFlowPending.copy(alpha = 0.5f)
                    },
                    flowing = hopState(status, hops[index + 1]) == FoxyFlowState.RUNNING,
                    phase = dash,
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        FoxyFlowLegend()
        Spacer(Modifier.height(10.dp))
        Text(
            homeText(
                "The tunnel is TCP-only: UDP/QUIC is blocked inside it so browsers fall back to TCP.",
                "تونل فقط TCP است: UDP/QUIC داخلش مسدود می‌شود تا مرورگرها به TCP برگردند.",
            ),
            color = UacColors.TextSecondary,
            fontSize = 10.sp,
            lineHeight = PowHintLine,
            fontFamily = homeLocalizedFont(),
        )
    }
}

@Composable
private fun FoxyFlowHeader(status: FoxyUiStatus, now: Long) {
    val font = homeLocalizedFont()
    val shape = RoundedCornerShape(14.dp)
    val total = when {
        status.startedAt <= 0L -> 0L
        status.endedAt > 0L -> status.endedAt - status.startedAt
        else -> now - status.startedAt
    }
    val progress by animateFloatAsState(status.progressPercent / 100f, tween(500), label = "foxyPct")
    Column(
        Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color(0xFF15304F), Color(0xFF0B1A2C))), shape)
            .border(1.dp, UacColors.DisconnectedBlue.copy(alpha = 0.35f), shape)
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FoxyHeaderStat(homeText("Elapsed", "زمان کل"), formatFlowMs(total).ifEmpty { "–" }, Modifier.weight(1f))
            FoxyHeaderStat(homeText("Progress", "پیشرفت"), "${status.progressPercent}%", Modifier.weight(1f))
            FoxyHeaderStat(
                homeText("Edge", "سرور"),
                status.edgeHost.ifBlank { "–" },
                Modifier.weight(1.8f),
            )
        }
        Spacer(Modifier.height(10.dp))
        Box(Modifier.fillMaxWidth().height(6.dp).background(Color(0x33FFFFFF), CircleShape)) {
            Box(
                Modifier
                    .fillMaxWidth(progress.coerceIn(0f, 1f))
                    .height(6.dp)
                    .background(
                        Brush.horizontalGradient(listOf(UacColors.DisconnectedBlue, UacColors.ConnectedGreen)),
                        CircleShape,
                    ),
            )
        }
        if (status.detail.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                status.detail,
                color = UacColors.TextSecondary,
                fontSize = 11.sp,
                fontFamily = font,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun FoxyHeaderStat(label: String, value: String, modifier: Modifier) {
    val font = homeLocalizedFont()
    Column(modifier) {
        Text(label, color = UacColors.TextSecondary, fontSize = 10.5.sp, fontFamily = font)
        Text(
            value,
            color = UacColors.TextPrimary,
            fontSize = 13.5.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = font,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun FoxyFlowNode(
    hop: FoxyHop,
    status: FoxyUiStatus,
    state: FoxyFlowState,
    expanded: Boolean,
    pulseAlpha: Float,
    onClick: () -> Unit,
) {
    val font = homeLocalizedFont()
    val accent = UacColors.DisconnectedBlue
    val shape = RoundedCornerShape(14.dp)
    val title = when (hop) {
        FoxyHop.PREPARE -> homeText("Prepare tunnel", "آماده‌سازی تونل")
        FoxyHop.ACCOUNT -> homeText("Firefox account", "حساب فایرفاکس")
        FoxyHop.EDGE -> homeText("Fastly edge", "سرور Fastly")
        FoxyHop.TUNNEL -> homeText("Tunnel device", "رابط تونل")
        FoxyHop.ONLINE -> homeText("Internet", "اینترنت")
    }
    val summary = when (hop) {
        FoxyHop.PREPARE -> homeText("Account, quota and route checks", "بررسی حساب، سهمیه و مسیر")
        FoxyHop.ACCOUNT -> when (status.phase) {
            FoxyPhase.AUTH -> homeText("Requesting a proxy pass from Guardian", "دریافت کلید پروکسی از Guardian")
            FoxyPhase.IDLE, FoxyPhase.STARTING -> homeText("Proxy pass comes from Guardian, not stored", "کلید پروکسی از Guardian گرفته می‌شود")
            else -> homeText("Proxy pass ready", "کلید پروکسی آماده است")
        }
        FoxyHop.EDGE ->
            if (status.edgeHost.isNotBlank()) {
                homeLtr("${status.edgeHost} · TLS 1.3 + HTTP/2")
            } else {
                homeText("Fastly MASQUE edge from Mozilla's list", "سرور Fastly MASQUE از فهرست موزیلا")
            }
        FoxyHop.TUNNEL -> homeLtr("TUN ↔ SOCKS 127.0.0.1")
        FoxyHop.ONLINE ->
            if (status.exitIp.isNotBlank()) homeLtr("${homeText("Exit IP", "آی‌پی خروجی")} ${status.exitIp}")
            else homeText("Traffic exits from the edge", "ترافیک از سرور خارج می‌شود")
    }
    val details = remember(hop, status.steps) {
        status.steps
            .map { it.text }
            .filter { line ->
                when (hop) {
                    FoxyHop.PREPARE -> line.contains("account", true) || line.contains("quota", true)
                    FoxyHop.ACCOUNT -> line.contains("Guardian", true) || line.contains("pass", true)
                    FoxyHop.EDGE -> line.contains("edge", true) || line.contains("Fastly", true) || line.contains("HTTP/2", true)
                    FoxyHop.TUNNEL -> line.contains("SOCKS", true) || line.contains("TUN", true)
                    FoxyHop.ONLINE -> line.contains("exit", true) || line.contains("active", true)
                }
            }
            .takeLast(6)
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Color.White.copy(alpha = if (expanded) 0.055f else 0.03f))
            .border(
                1.dp,
                if (state == FoxyFlowState.RUNNING) FoxyFlowRunning.copy(alpha = 0.5f * pulseAlpha)
                else if (expanded) accent.copy(alpha = 0.4f) else UacColors.Divider.copy(alpha = 0.35f),
                shape,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier
                    .size(30.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(state.color().copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(hop.icon, null, tint = state.color(), modifier = Modifier.size(16.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(title, color = UacColors.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, fontFamily = font)
                Text(
                    summary,
                    color = UacColors.TextSecondary,
                    fontSize = 11.sp,
                    fontFamily = font,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box(Modifier.size(8.dp).background(state.color(), CircleShape))
            Icon(
                Icons.Outlined.ChevronLeft,
                null,
                tint = UacColors.TextSecondary,
                modifier = Modifier.size(16.dp).rotate(if (expanded) 90f else 0f),
            )
        }
        if (expanded) {
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(UacColors.Divider.copy(alpha = 0.6f)))
            Spacer(Modifier.height(8.dp))
            if (details.isEmpty()) {
                Text(
                    homeText("No steps recorded yet.", "هنوز مرحله‌ای ثبت نشده."),
                    color = UacColors.TextSecondary,
                    fontSize = 11.sp,
                    fontFamily = font,
                )
            } else {
                details.forEach { line ->
                    Row(Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.Top) {
                        Box(Modifier.padding(top = 5.dp).size(4.dp).background(accent, CircleShape))
                        Spacer(Modifier.width(7.dp))
                        Text(
                            line,
                            color = UacColors.TextSecondary,
                            fontSize = 11.sp,
                            lineHeight = PowHintLine,
                            fontFamily = font,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FoxyFlowConnector(color: Color, flowing: Boolean, phase: Float) {
    Canvas(Modifier.fillMaxWidth().height(14.dp)) {
        val inset = 27.dp.toPx()
        val x = if (layoutDirection == androidx.compose.ui.unit.LayoutDirection.Rtl) size.width - inset else inset
        val effect = if (flowing) PathEffect.dashPathEffect(floatArrayOf(8f, 6f), -phase) else null
        drawLine(
            color = if (flowing) FoxyFlowRunning else color,
            start = Offset(x, 0f),
            end = Offset(x, size.height),
            strokeWidth = 2.5.dp.toPx(),
            cap = StrokeCap.Round,
            pathEffect = effect,
        )
    }
}

@Composable
private fun FoxyFlowLegend() {
    val font = homeLocalizedFont()
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        listOf(
            FoxyFlowState.DONE to homeText("done", "انجام شد"),
            FoxyFlowState.RUNNING to homeText("running", "در حال اجرا"),
            FoxyFlowState.PENDING to homeText("waiting", "در انتظار"),
            FoxyFlowState.FAILED to homeText("failed", "خطا"),
        ).forEach { (state, label) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).background(state.color(), CircleShape))
                Spacer(Modifier.width(4.dp))
                Text(label, color = UacColors.TextSecondary, fontSize = 10.sp, fontFamily = font)
            }
        }
    }
}

private fun formatFlowMs(ms: Long): String {
    if (ms <= 0) return ""
    val seconds = ms / 1000.0
    return if (seconds < 10) String.format(java.util.Locale.US, "%.1fs", seconds)
    else String.format(java.util.Locale.US, "%ds", seconds.toInt())
}
