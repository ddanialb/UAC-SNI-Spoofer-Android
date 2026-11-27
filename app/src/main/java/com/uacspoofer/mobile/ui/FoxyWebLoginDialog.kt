/*
 * In-app Firefox account sign-in for the Foxy engine.
 *
 * The user never types a password into our UI: the Mozilla sign-in page (which also
 * supports "continue with Google/Apple") loads inside a WebView that belongs to the app.
 * When Guardian redirects to mozilla-vpn://login/success?code=…, the code is exchanged
 * for a Guardian session token and the account is stored.
 */
package com.uacspoofer.mobile.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.CookieManager
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import com.uacspoofer.mobile.engine.foxy.FoxyWebLogin
import com.uacspoofer.mobile.ui.theme.UacColors
import com.uacspoofer.mobile.logging.AppLogRepository
import com.uacspoofer.mobile.logging.LogSource
import kotlinx.coroutines.launch

/**
 * Full-screen in-app browser for the Mozilla sign-in flow.
 *
 * @param onSignedIn called with the stored account after a successful exchange.
 */
@Composable
internal fun FoxyWebLoginDialog(
    onDismiss: () -> Unit,
    onSignedIn: (FoxyAccount) -> Unit,
) {
    val context = LocalContext.current
    val persian = LocalHomePersian.current
    val accountStore = remember(context) { FoxyAccountStore.get(context) }
    val scope = rememberCoroutineScope()
    val pkce = remember { FoxyWebLogin.newPkce() }
    val loginUrl = remember(pkce) { FoxyWebLogin.loginUrl(pkce.challenge) }

    var progress by remember { mutableFloatStateOf(0f) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var claimedCode by remember { mutableStateOf(false) }
    var webView by remember { mutableStateOf<WebView?>(null) }

    fun submitCode(code: String) {
        if (claimedCode) return
        AppLogRepository.info(LogSource.APP, "Foxy: captured Mozilla authorisation code, exchanging it")
        claimedCode = true
        busy = true
        error = ""
        scope.launch {
            val result = FoxyWebLogin.signIn(code, pkce.verifier)
            busy = false
            if (result.success && result.account != null) {
                val stored = accountStore.storeAccount(result.account)
                accountStore.setActive(stored)
                onSignedIn(stored)
            } else {
                error = result.errorMessage.ifBlank {
                    homeText(persian, "Sign-in failed. Try again.", "ورود ناموفق بود. دوباره تلاش کن.")
                }
                claimedCode = false
                // Let the user retry from the sign-in page.
                webView?.post { webView?.loadUrl(loginUrl) }
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
            // ---- Header (same visual language as the other tool pages) ----
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
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        homeText("Sign in with Firefox", "ورود با حساب فایرفاکس"),
                        color = UacColors.TextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
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
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                view: WebView,
                                request: WebResourceRequest,
                            ): Boolean = claimFromUrl(view, request.url.toString())

                            @Suppress("DEPRECATION")
                            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
                                claimFromUrl(view, url)

                            /**
                             * FxA answers with an HTTP 302 to
                             * https://vpn.mozilla.org/oauth/success?code=… and the WebView follows
                             * redirects internally, so onPageStarted/shouldOverrideUrlLoading are not
                             * guaranteed to see it. shouldInterceptRequest fires for every main-frame
                             * load, redirect hops included — this is the reliable hook.
                             */
                            override fun shouldInterceptRequest(
                                view: WebView,
                                request: WebResourceRequest,
                            ): android.webkit.WebResourceResponse? {
                                if (request.isForMainFrame) {
                                    val url = request.url.toString()
                                    view.post { claimFromUrl(view, url) }
                                }
                                return null
                            }

                            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                                url?.let { claimFromUrl(view, it) }
                            }

                            override fun onPageFinished(view: WebView, url: String?) {
                                progress = 1f
                                url?.let { claimFromUrl(view, it) }
                            }

                            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                                url?.let { claimFromUrl(view, it) }
                            }

                            private fun claimFromUrl(view: WebView, raw: String): Boolean {
                                if (!raw.startsWith("http") && !raw.startsWith("mozilla-vpn")) return false
                                val code = FoxyWebLogin.captureCode(raw)
                                if (code.isNullOrBlank()) return false
                                if (busy || claimedCode) return false
                                runCatching { view.stopLoading() }
                                submitCode(code)
                                return true
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
                update = { view -> webView = view },
            )
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            webView?.let { view ->
                view.stopLoading()
                (view.parent as? ViewGroup)?.removeView(view)
                view.destroy()
            }
            webView = null
        }
    }

    LaunchedEffect(Unit) {
        // Nothing to do yet; kept so the dialog recomposes when the login URL changes.
    }
}

@SuppressLint("SetJavaScriptEnabled")
private fun WebView.configureForLogin() {
    settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        databaseEnabled = true
        javaScriptCanOpenWindowsAutomatically = true
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
