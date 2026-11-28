package com.uacspoofer.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.CompositionLocalProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.uacspoofer.mobile.core.ConnectionState
import com.uacspoofer.mobile.core.ConnectionStateStore
import com.uacspoofer.mobile.engine.foxy.FoxyStage
import com.uacspoofer.mobile.engine.foxy.FoxyStageStatus
import com.uacspoofer.mobile.engine.foxy.FoxyStatusStore
import com.uacspoofer.mobile.engine.foxy.FoxyStageState
import com.uacspoofer.mobile.ui.theme.UacColors

/**
 * Foxy connect steps panel — the same bottom sheet the PoW/Tor engines use, fed by the engine's
 * real ordered stages so the list walks forward one row at a time (green check, then the next
 * row starts spinning) instead of a fixed four-line script.
 */
@Composable
internal fun FoxyConnectSheet(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val panelStore = remember(context) { ConnectPanelStore.get(context) }
    val panelMode by panelStore.steps.collectAsStateWithLifecycle()
    val engineMode = rememberDisplayedEngineMode()
    if (!engineMode.isFoxy) return
    val status by FoxyStatusStore.status.collectAsStateWithLifecycle()
    val connection by ConnectionStateStore.state.collectAsStateWithLifecycle()
    val previousConnection = remember { mutableStateOf(connection) }
    LaunchedEffect(connection) {
        val enteredError = connection == ConnectionState.ERROR && previousConnection.value != ConnectionState.ERROR
        previousConnection.value = connection
        if (enteredError) panelStore.setSteps(ConnectPanelMode.OPEN)
    }
    val visible = connection == ConnectionState.CONNECTING || connection == ConnectionState.ERROR
    if (!visible || status.stages.isEmpty() || panelMode == ConnectPanelMode.CLOSED) return

    val persian = LocalHomePersian.current
    val font = homeLocalizedFont()
    val rows = remember(status.stages, persian) { foxyStageRows(status.stages, persian) }
    val scroll = rememberScrollState()
    LaunchedEffect(rows.size, rows.lastOrNull()?.state) {
        scroll.animateScrollTo(scroll.maxValue)
    }
    val shape = RoundedCornerShape(18.dp)
    val paint = connectPanelPaint(primary = true)
    val done = rows.count { it.state == FoxyStageState.DONE }
    val current = rows.firstOrNull { it.state == FoxyStageState.RUNNING }
        ?: rows.firstOrNull { it.state == FoxyStageState.FAILED }
        ?: rows.lastOrNull()
    var openedAt by remember { mutableLongStateOf(0L) }
    LaunchedEffect(panelMode) {
        if (panelMode == ConnectPanelMode.OPEN) openedAt = System.currentTimeMillis()
    }
    val requestCollapse: () -> Unit = {
        if (System.currentTimeMillis() - openedAt > COLLAPSE_GRACE_MS) {
            panelStore.setSteps(ConnectPanelMode.COLLAPSED)
        }
    }
    CompositionLocalProvider(
        LocalLayoutDirection provides if (persian) LayoutDirection.Rtl else LayoutDirection.Ltr,
    ) {
        Box(
            modifier.fillMaxWidth().animateContentSize(tween(250, easing = FastOutSlowInEasing)),
            contentAlignment = Alignment.BottomCenter,
        ) {
            AnimatedContent(
                targetState = panelMode == ConnectPanelMode.OPEN,
                transitionSpec = { fadeIn(tween(250)) togetherWith fadeOut(tween(250)) },
                label = "foxy-steps-fold",
            ) { expanded ->
                if (!expanded) {
                    CollapsedConnectSteps(
                        title = current?.title.orEmpty(),
                        state = when (current?.state) {
                            FoxyStageState.DONE -> MiniStepState.DONE
                            FoxyStageState.FAILED -> MiniStepState.FAILED
                            FoxyStageState.RUNNING -> MiniStepState.RUNNING
                            else -> null
                        },
                        done = done,
                        total = rows.size,
                        paint = paint,
                        onExpand = { panelStore.setSteps(ConnectPanelMode.OPEN) },
                    )
                } else {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = 320.dp)
                            .clip(shape)
                            .background(paint.background)
                            .border(1.dp, paint.border.copy(alpha = 0.75f), shape)
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                homeText("Connection steps", "مراحل اتصال"),
                                color = UacColors.TextPrimary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                fontFamily = font,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                homeLtr("$done/${rows.size}"),
                                color = UacColors.TextSecondary,
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                homeText("Hide", "بستن"),
                                color = UacColors.TextSecondary,
                                fontSize = 11.sp,
                                fontFamily = font,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable(onClick = requestCollapse)
                                    .padding(horizontal = 8.dp, vertical = 3.dp),
                            )
                        }
                        Column(
                            Modifier.fillMaxWidth().verticalScroll(scroll),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            rows.forEach { row -> FoxyStageLine(row, persian, font) }
                        }
                    }
                }
            }
        }
    }
}

private data class FoxyStageRow(
    val stage: FoxyStage,
    val title: String,
    val detail: String,
    val state: FoxyStageState?,
)

private fun foxyStageRows(stages: Map<FoxyStage, FoxyStageStatus>, persian: Boolean): List<FoxyStageRow> =
    FoxyStage.entries.map { stage ->
        val entry = stages[stage]
        FoxyStageRow(
            stage = stage,
            title = FoxyStageCopy.title(stage, persian),
            detail = entry?.detail.orEmpty(),
            state = entry?.state,
        )
    }

@Composable
private fun FoxyStageLine(row: FoxyStageRow, persian: Boolean, font: FontFamily?) {
    val pending = row.state == null
    val state = row.state
    val titleColor = when {
        state == FoxyStageState.FAILED -> UacColors.ErrorRed
        pending -> UacColors.TextSecondary.copy(alpha = 0.55f)
        state == FoxyStageState.RUNNING -> UacColors.TextPrimary
        else -> UacColors.TextPrimary.copy(alpha = 0.88f)
    }
    val direction = TextStyle(textDirection = if (persian) TextDirection.Rtl else TextDirection.Ltr)
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .padding(top = 2.dp)
                .size(18.dp)
                .background(
                    when (state) {
                        FoxyStageState.DONE -> UacColors.ConnectedGreen.copy(alpha = 0.14f)
                        FoxyStageState.FAILED -> UacColors.ErrorRed.copy(alpha = 0.14f)
                        else -> Color.Transparent
                    },
                    CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            when (state) {
                FoxyStageState.DONE -> Icon(
                    Icons.Rounded.Check,
                    contentDescription = null,
                    tint = UacColors.ConnectedGreen,
                    modifier = Modifier.size(13.dp),
                )
                FoxyStageState.FAILED -> Icon(
                    Icons.Rounded.Close,
                    contentDescription = null,
                    tint = UacColors.ErrorRed,
                    modifier = Modifier.size(13.dp),
                )
                FoxyStageState.RUNNING -> CircularProgressIndicator(
                    color = UacColors.ConnectingCyan,
                    trackColor = UacColors.ConnectingCyan.copy(alpha = 0.15f),
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(14.dp),
                )
                else -> Box(
                    Modifier
                        .size(7.dp)
                        .background(UacColors.TextSecondary.copy(alpha = 0.35f), CircleShape),
                )
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                homeLatinDigits(row.title),
                color = titleColor,
                fontSize = 13.sp,
                lineHeight = 19.sp,
                fontWeight = if (state == FoxyStageState.RUNNING) FontWeight.Medium else FontWeight.Normal,
                fontFamily = font,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Start,
                style = direction,
            )
            if (row.detail.isNotBlank() && !pending) {
                Text(
                    homeLtr(row.detail),
                    color = if (state == FoxyStageState.FAILED) {
                        UacColors.ErrorRed.copy(alpha = 0.8f)
                    } else {
                        UacColors.TextSecondary
                    },
                    fontSize = 11.5.sp,
                    lineHeight = 16.sp,
                    fontFamily = FontFamily.Default,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Start,
                    style = direction,
                )
            }
        }
        Spacer(Modifier.size(2.dp))
    }
}
