/*
 * In-app Firefox account sign-in for the Foxy engine.
 *
 * The Mozilla sign-in page (Google/Apple sign-in included) is rendered by a WebView that
 * belongs to the app. A small JavaScript bridge relays Firefox's WebChannel traffic, so the
 * page hands over the account's sessionToken; we trade that token for OAuth credentials and
 * store the account. No password ever reaches our code.
 */
package com.uacspoofer.mobile.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.uacspoofer.mobile.engine.foxy.FoxyAccount
import com.uacspoofer.mobile.engine.foxy.FoxyAccountStore
import com.uacspoofer.mobile.engine.foxy.FoxyWebChannelEvent
import com.uacspoofer.mobile.engine.foxy.FoxyWebLogin
import com.uacspoofer.mobile.logging.AppLogRepository
import com.uacspoofer.mobile.logging.LogSource
import com.uacspoofer.mobile.ui.theme.UacColors
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference

@Composable
internal fun FoxyWebLoginDialog(
    onDismiss: () -> Unit,
    onSignedIn: (FoxyAccount) -> Unit,
) {
    val context = LocalContext.current
    val persian = LocalHomePersian.current
    val accountStore = remember(context) { FoxyAccountStore.get(context) }
    val scope = rememberCoroutineScope()
    var signUp by remember { mutableStateOf(false) }
    val loginUrl = remember(signUp) { FoxyWebLogin.loginUrl(signUp = signUp) }

    var progress by remember { mutableFloatStateOf(0f) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var claimed by remember { mutableStateOf(false) }
    var webView by remember { mutableStateOf<WebView?>(null) }

    fun finishWithToken(token: String) {
        if (claimed) return
        claimed = true
        busy = true
        error = ""
        scope.launch {
            val result = FoxyWebLogin.signIn(token)
            busy = false
            val account = result.account
            if (result.success && account != null) {
                val stored = accountStore.storeAccount(account)
                accountStore.setActive(stored)
                onSignedIn(stored)
            } else {
                claimed = false
                error = result.errorMessage.ifBlank {
                    homeText(persian, "Sign-in failed. Try again.", "ورود ناموفق بود. دوباره تلاش کن.")
                }
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(UacColors.BackgroundMiddle),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(Color(0x99101C29))
                        .border(1.dp, PowBorder, CircleShape)
                        .clickable(onClick = onDismiss),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = homeText("Cancel sign-in", "لغو ورود"),
                        tint = UacColors.TextPrimary,
                        modifier = Modifier.size(22.dp),
                    )
                }
                Text(
                    homeText("Sign in with Firefox", "ورود با حساب فایرفاکس"),
                    color = UacColors.TextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (signUp) {
                        homeText("New to Firefox?", "حساب فایرفاکس نداری؟")
                    } else {
                        homeText("Already have a Firefox account?", "حساب فایرفاکس داری؟")
                    },
                    color = UacColors.TextSecondary,
                    fontSize = 11.5.sp,
                )
                TextButton(onClick = { signUp = !signUp }) {
                    Text(
                        if (signUp) {
                            homeText("Sign in instead", "ورود به حساب")
                        } else {
                            homeText("Create an account", "ساخت حساب")
                        },
                        fontSize = 11.5.sp,
                        color = UacColors.DisconnectedBlue,
                    )
                }
            }

            if (progress < 1f || busy) {
                LinearProgressIndicator(
                    progress = { if (busy) 1f else progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp),
                    color = UacColors.DisconnectedBlue,
                    trackColor = PowBorder,
                )
            }

            if (busy) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = UacColors.DisconnectedBlue,
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(
                        homeText("Finishing sign-in…", "در حال تکمیل ورود…"),
                        color = UacColors.TextSecondary,
                        fontSize = 11.5.sp,
                    )
                }
            }

            if (error.isNotBlank()) {
                Text(
                    error,
                    color = UacColors.ErrorRed,
                    fontSize = 11.5.sp,
                    textAlign = TextAlign.Start,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                )
                TextButton(onClick = { error = "" }) {
                    Text(
                        homeText("Try again", "تلاش دوباره"),
                        fontSize = 12.sp,
                        color = UacColors.DisconnectedBlue,
                    )
                }
            }

            AndroidView(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 4.dp),
                factory = { ctx ->
                    WebView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                        setBackgroundColor(android.graphics.Color.WHITE)
                        configureForLogin()
                        addJavascriptInterface(
                            WebChannelBridge(this@apply) { event -> handleEvent(event, ::finishWithToken) },
                            FoxyWebLogin.JS_BRIDGE_NAME,
                        )
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                view: WebView,
                                request: WebResourceRequest,
                            ): Boolean {
                                val scheme = request.url.scheme?.lowercase().orEmpty()
                                if (scheme != "http" && scheme != "https") return true
                                val host = request.url.host?.lowercase().orEmpty()
                                return !(host.endsWith("firefox.com") || host.endsWith("mozilla.org") ||
                                    host.endsWith("google.com") || host.endsWith("googleapis.com") ||
                                    host.endsWith("gstatic.com") || host.endsWith("apple.com") ||
                                    host.endsWith("accounts.google.com"))
                            }

                            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                                super.onPageStarted(view, url, favicon)
                                runCatching { view.evaluateJavascript(FoxyWebLogin.JS_HOOK, null) }
                            }

                            override fun onPageFinished(view: WebView, url: String?) {
                                progress = 1f
                                runCatching {
                                    CookieManager.getInstance().flush()
                                    view.evaluateJavascript(FoxyWebLogin.JS_HOOK, null)
                                }
                            }
                        }
                        webChromeClient = object : android.webkit.WebChromeClient() {
                            override fun onProgressChanged(view: WebView, newProgress: Int) {
                                progress = newProgress / 100f
                            }
                        }
                        loadUrl(loginUrl)
                        webView = this
                    }
                },
                update = { view ->
                    webView = view
                    // Switching between sign-in and sign-up reuses the same WebView.
                    val target = if (signUp) FoxyWebLogin.SIGNUP_URL else FoxyWebLogin.LOGIN_URL
                    if (view.url != target && view.tag != target) {
                        view.tag = target
                        view.loadUrl(target)
                    }
                },
            )
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            webView?.let { view ->
                view.stopLoading()
                view.removeJavascriptInterface(FoxyWebLogin.JS_BRIDGE_NAME)
                (view.parent as? ViewGroup)?.removeView(view)
                view.destroy()
            }
            webView = null
        }
    }
}

/** Runs on the WebChannel thread; only touches plain state + the main looper. */
private fun handleEvent(event: FoxyWebChannelEvent, onToken: (String) -> Unit) {
    when (event.command) {
        "fxaccounts:login", "fxaccounts:oauth_login" -> {
            if (event.sessionToken.isNotBlank()) {
                AppLogRepository.info(
                    LogSource.APP,
                    "Foxy: sign-in page reported ${event.command} for ${event.email.ifBlank { "unknown account" }}",
                )
                onToken(event.sessionToken)
            }
        }
        else -> Unit
    }
}

/**
 * JavaScript ⇄ Kotlin bridge for Firefox's WebChannel protocol. The page dispatches
 * `WebChannelMessageToChrome` events; we answer with `WebChannelMessageToContent`.
 */
private class WebChannelBridge(
    webView: WebView,
    private val onEvent: (FoxyWebChannelEvent) -> Unit,
) {
    private val viewRef = WeakReference(webView)

    @Volatile private var dispatchedToken: String? = null

    @JavascriptInterface
    fun onMessage(payload: String, originUrl: String) {
        val host = runCatching { android.net.Uri.parse(originUrl).host?.lowercase() }.getOrNull().orEmpty()
        if (!(host == "accounts.firefox.com" || host.endsWith(".firefox.com"))) return
        val event = FoxyWebLogin.parseWebChannel(payload) ?: return
        when (event.command) {
            "fxaccounts:fxa_status" -> reply(event, FoxyWebLogin.statusReply())
            "fxaccounts:can_link_account" -> reply(event, FoxyWebLogin.canLinkAccountReply())
            else -> Unit
        }
        if (event.sessionToken.isNotBlank() && event.sessionToken != dispatchedToken) {
            dispatchedToken = event.sessionToken
            onEvent(event)
        }
    }

    private fun reply(event: FoxyWebChannelEvent, data: String) {
        if (event.messageId.isBlank()) return
        val view = viewRef.get() ?: return
        val script = "window.__foxyReply && window.__foxyReply(" +
            "${org.json.JSONObject.quote(event.channelId)}," +
            "${org.json.JSONObject.quote(event.messageId)}," +
            "${org.json.JSONObject.quote(event.command)},$data);"
        view.post { runCatching { view.evaluateJavascript(script, null) } }
    }
}

@SuppressLint("SetJavaScriptEnabled")
private fun WebView.configureForLogin() {
    settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        databaseEnabled = true
        javaScriptCanOpenWindowsAutomatically = false
        setSupportMultipleWindows(false)
        loadWithOverviewMode = true
        useWideViewPort = true
        cacheMode = WebSettings.LOAD_DEFAULT
        userAgentString = FoxyWebLogin.BROWSER_USER_AGENT
    }
    CookieManager.getInstance().setAcceptCookie(true)
    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
    isVerticalScrollBarEnabled = true
}
