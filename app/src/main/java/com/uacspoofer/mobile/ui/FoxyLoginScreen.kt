/*
 * Native Firefox Accounts sign-in (no WebView) — mirrors Se7en Pro `AddFoxyAccountDialog`.
 * Ported/adapted for UAC SNI Spoofer Android (AGPLv3).
 */
package com.uacspoofer.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Mail
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uacspoofer.mobile.engine.foxy.FoxyAccountStore
import com.uacspoofer.mobile.engine.foxy.FxaClient
import com.uacspoofer.mobile.ui.theme.UacColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FoxyLoginDialog(
    onDismiss: () -> Unit,
    onAccountAdded: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val accent = UacColors.DisconnectedBlue
    // CompositionLocal is unreadable inside click handlers, so snapshot it once.
    val persian = LocalHomePersian.current
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var pendingSession by remember { mutableStateOf<String?>(null) }
    var pendingEmail by remember { mutableStateOf("") }
    var done by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color(0xFF0B121B),
        contentColor = UacColors.TextPrimary,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        homeText("Add Firefox account", "افزودن حساب فایرفاکس"),
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        homeText("Sign in natively — no browser needed", "ورود مستقیم — بدون نیاز به مرورگر"),
                        color = UacColors.TextSecondary,
                        fontSize = 11.5.sp,
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(
                        Icons.Outlined.Close,
                        contentDescription = homeText("Close", "بستن"),
                        tint = UacColors.TextSecondary,
                    )
                }
            }

            if (done) {
                Text(
                    homeText(
                        "Account added. You can now connect with Foxy.",
                        "حساب اضافه شد. حالا می‌توانید با Foxy وصل شوید.",
                    ),
                    color = UacColors.ConnectedGreen,
                    fontSize = 13.sp,
                )
                Button(
                    onClick = onAccountAdded,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = accent),
                ) {
                    Text(homeText("Done", "انجام شد"))
                }
                return@Column
            }

            if (pendingSession == null) {
                FoxyLoginField(
                    value = email,
                    onValueChange = { email = it; error = null },
                    label = homeText("Email", "ایمیل"),
                    icon = Icons.Outlined.Mail,
                    keyboardType = KeyboardType.Email,
                )
                FoxyLoginField(
                    value = password,
                    onValueChange = { password = it; error = null },
                    label = homeText("Password", "رمز عبور"),
                    icon = Icons.Outlined.Key,
                    keyboardType = KeyboardType.Password,
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    trailing = {
                        TextButton(onClick = { showPassword = !showPassword }) {
                            Text(
                                if (showPassword) homeText("Hide", "پنهان") else homeText("Show", "نمایش"),
                                fontSize = 11.sp,
                            )
                        }
                    },
                )
                Button(
                    onClick = {
                        if (busy) return@Button
                        val cleanEmail = email.trim()
                        if (cleanEmail.isBlank() || password.isBlank()) {
                            error = homeText(persian, "Enter your email and password", "ایمیل و رمز عبور را وارد کنید")
                            return@Button
                        }
                        busy = true
                        error = null
                        scope.launch(Dispatchers.IO) {
                            val result = runCatching { FxaClient().startLogin(cleanEmail, password) }
                            withContext(Dispatchers.Main) {
                                busy = false
                                val login = result.getOrNull()
                                when {
                                    login == null -> error = result.exceptionOrNull()?.message
                                        ?: homeText(persian, "Sign-in failed", "ورود ناموفق بود")
                                    login.success && login.account != null -> {
                                        FoxyAccountStore.get(context).storeAccount(login.account)
                                        done = true
                                    }
                                    login.requires2Fa && login.sessionToken != null -> {
                                        pendingSession = login.sessionToken
                                        pendingEmail = cleanEmail
                                    }
                                    else -> error = login.errorMessage
                                        ?: homeText(persian, "Sign-in failed", "ورود ناموفق بود")
                                }
                            }
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = accent),
                ) {
                    if (busy) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                        Spacer(Modifier.size(8.dp))
                    }
                    Text(homeText("Sign in", "ورود"))
                }
            } else {
                Text(
                    homeText(
                        "Mozilla sent a 6-digit code to $pendingEmail. Enter it below.",
                        "موزیلا یک کد ۶ رقمی به $pendingEmail فرستاد. آن را وارد کنید.",
                    ),
                    color = UacColors.TextSecondary,
                    fontSize = 12.sp,
                )
                FoxyLoginField(
                    value = code,
                    onValueChange = { code = it.filter(Char::isDigit).take(6); error = null },
                    label = homeText("Verification code", "کد تأیید"),
                    icon = Icons.Outlined.Key,
                    keyboardType = KeyboardType.Number,
                )
                Button(
                    onClick = {
                        if (busy) return@Button
                        if (code.length != 6) {
                            error = homeText(persian, "Enter the 6-digit code", "کد ۶ رقمی را وارد کنید")
                            return@Button
                        }
                        busy = true
                        error = null
                        val session = pendingSession ?: return@Button
                        scope.launch(Dispatchers.IO) {
                            val result = runCatching {
                                FxaClient().submitVerificationCode(pendingEmail, session, code)
                            }
                            withContext(Dispatchers.Main) {
                                busy = false
                                val verify = result.getOrNull()
                                when {
                                    verify == null -> error = result.exceptionOrNull()?.message
                                        ?: homeText(persian, "Verification failed", "تأیید ناموفق بود")
                                    verify.success && verify.account != null -> {
                                        FoxyAccountStore.get(context).storeAccount(verify.account)
                                        done = true
                                    }
                                    else -> error = verify.errorMessage
                                        ?: homeText(persian, "Verification failed", "تأیید ناموفق بود")
                                }
                            }
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = accent),
                ) {
                    if (busy) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                        Spacer(Modifier.size(8.dp))
                    }
                    Text(homeText("Verify & sign in", "تأیید و ورود"))
                }
                TextButton(onClick = { pendingSession = null; code = "" }, modifier = Modifier.align(Alignment.Start)) {
                    Text(homeText("Back", "برگشت"), fontSize = 12.sp)
                }
            }

            if (error != null) {
                Text(error!!, color = UacColors.ErrorRed, fontSize = 11.5.sp, lineHeight = PowHintLine)
            }

            Text(
                homeText(
                    "Credentials are sent only to accounts.firefox.com and stored encrypted on this device. " +
                        "Two-factor codes arrive by email.",
                    "اطلاعات فقط به accounts.firefox.com ارسال و به‌صورت رمزنگاری‌شده روی دستگاه ذخیره می‌شود. " +
                        "کد دو مرحله‌ای به ایمیل شما می‌آید.",
                ),
                color = UacColors.TextSecondary,
                fontSize = 10.sp,
                lineHeight = PowHintLine,
            )
        }
    }
}

@Composable
private fun FoxyLoginField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    keyboardType: KeyboardType,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailing: (@Composable () -> Unit)? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, fontSize = 12.sp) },
        leadingIcon = { Icon(icon, null, tint = UacColors.TextSecondary, modifier = Modifier.size(18.dp)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        visualTransformation = visualTransformation,
        trailingIcon = trailing,
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
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
