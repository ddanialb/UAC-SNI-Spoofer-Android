package com.uacspoofer.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.uacspoofer.mobile.ui.theme.UacColors

/**
 * Asked right after the user taps connect without a Firefox account, so the account step is a
 * decision the user makes rather than a failure that appears inside the service.
 */
@Composable
internal fun FoxySignInPrompt(
    onDismiss: () -> Unit,
    onContinue: () -> Unit,
) {
    val persian = LocalHomePersian.current
    val font = homeLocalizedFont()
    val accent = UacColors.DisconnectedBlue
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 360.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(Color(0xF2101C29))
                .border(1.dp, PowBorder, RoundedCornerShape(20.dp))
                .padding(horizontal = 18.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(accent.copy(alpha = 0.14f))
                        .padding(7.dp),
                ) {
                    Icon(
                        Icons.Outlined.AccountCircle,
                        contentDescription = null,
                        tint = accent,
                    )
                }
                Text(
                    homeText("Firefox account needed", "ورود به حساب فایرفاکس لازم است"),
                    color = UacColors.TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = font,
                    modifier = Modifier.weight(1f),
                )
            }
            Text(
                homeText(
                    "Foxy connects through Mozilla's own VPN servers, so it needs a Firefox account. " +
                        "Sign in, or create one for free in the app.",
                    "Foxy از سرورهای خود موزیلا عبور می‌کند و به حساب فایرفاکس نیاز دارد. " +
                        "وارد شو، یا همان‌جا یک حساب رایگان بساز.",
                ),
                color = UacColors.TextSecondary,
                fontSize = 12.sp,
                lineHeight = 18.sp,
                fontFamily = font,
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                PromptButton(
                    label = homeText("Not now", "فعلاً نه"),
                    primary = false,
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                )
                PromptButton(
                    label = homeText("Sign in", "ورود"),
                    primary = true,
                    onClick = onContinue,
                    modifier = Modifier.weight(1f),
                )
            }
            if (persian) {
                Text(
                    "می‌توانی بعداً از تنظیمات Foxy هم وارد شوی.",
                    color = UacColors.TextSecondary.copy(alpha = 0.75f),
                    fontSize = 10.5.sp,
                    fontFamily = font,
                )
            }
        }
    }
}

@Composable
private fun PromptButton(
    label: String,
    primary: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = UacColors.DisconnectedBlue
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(11.dp))
            .background(if (primary) accent.copy(alpha = 0.22f) else Color.Transparent)
            .border(
                1.dp,
                if (primary) accent.copy(alpha = 0.8f) else PowBorder,
                RoundedCornerShape(11.dp),
            )
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (primary) UacColors.TextPrimary else UacColors.TextSecondary,
            fontSize = 12.5.sp,
            fontWeight = if (primary) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}
