package com.xlonlabs.iitdcompanion

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

internal val PerkAccent = Color(0xFFF2C14E)

private const val REMOTE_PERKS_URL =
    "https://raw.githubusercontent.com/n06-nitin/iitd_companion/main/docs/perks.html"
private const val LOCAL_PERKS_URL = "file:///android_asset/perks.html"

/**
 * IITD Perks Screen: loaded from the 3-dot (⋮) menu over the tabs.
 * Dynamically fetches docs/perks.html from the GitHub repository with instant local asset fallback.
 * Allows instant, zero-APK updates by editing docs/perks.html and pushing to git.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun PerkScreen(onClose: () -> Unit) {
    val ctx = LocalContext.current
    var progress by remember { mutableIntStateOf(0) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    BackHandler {
        val wv = webViewRef
        if (wv != null && wv.canGoBack()) wv.goBack() else onClose()
    }

    DisposableEffect(Unit) {
        onDispose {
            webViewRef?.destroy()
            webViewRef = null
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Ink)
            // prevent touches reaching underlying tabs
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }
    ) {
        // Top Header
        Column(Modifier.fillMaxWidth().statusBarsPadding()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                StatusPill("IITD Perks · Student Hub", PerkAccent, Modifier.weight(1f))
                ClayIconButton(Icons.Rounded.Refresh, "Reload") {
                    webViewRef?.loadUrl("$REMOTE_PERKS_URL?t=${System.currentTimeMillis()}")
                }
                ClayIconButton(Icons.Rounded.Close, "Close", onClose)
            }

            // Progress bar
            if (progress in 1..99) {
                LinearProgressIndicator(
                    progress = { progress / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                        .height(2.dp)
                        .clip(CircleShape),
                    color = PerkAccent,
                    trackColor = Color.Transparent
                )
            } else {
                Spacer(Modifier.height(2.dp))
            }
            Spacer(Modifier.height(6.dp))
        }

        // WebView Container
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = 10.dp, end = 10.dp, bottom = 10.dp)
                .clip(RoundedCornerShape(24.dp))
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { c ->
                    WebView(c).apply {
                        setBackgroundColor(Ink.toArgb())
                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            cacheMode = WebSettings.LOAD_DEFAULT
                            builtInZoomControls = false
                            displayZoomControls = false
                            useWideViewPort = true
                            loadWithOverviewMode = true
                        }

                        // JavaScript interface for native clipboard copy & external browser launch
                        addJavascriptInterface(object {
                            @JavascriptInterface
                            fun copyText(text: String) {
                                val cm = c.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                cm?.setPrimaryClip(ClipData.newPlainText("IITD Perk", text))
                                Toast.makeText(c, "Link copied to clipboard", Toast.LENGTH_SHORT).show()
                            }

                            @JavascriptInterface
                            fun openExternal(url: String) {
                                runCatching {
                                    c.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                                }
                            }
                        }, "AndroidApp")

                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                progress = newProgress
                            }
                        }

                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): Boolean {
                                val uri = request?.url ?: return false
                                val url = uri.toString()
                                // If link is to perks.html or assets, let webview load it
                                if (url.contains("perks.html")) return false
                                // External link: open in user's browser app
                                runCatching {
                                    c.startActivity(Intent(Intent.ACTION_VIEW, uri))
                                }
                                return true
                            }

                            override fun onReceivedError(
                                view: WebView?,
                                request: WebResourceRequest?,
                                error: WebResourceError?
                            ) {
                                // If remote URL fails to load (offline), fallback to local bundled asset
                                if (request?.isForMainFrame == true && request.url.toString().startsWith("http")) {
                                    view?.loadUrl(LOCAL_PERKS_URL)
                                }
                            }
                        }

                        // Load remote URL (fresh cache-bust param), falls back to local on failure
                        loadUrl("$REMOTE_PERKS_URL?t=${System.currentTimeMillis()}")
                        webViewRef = this
                    }
                }
            )
        }
    }
}
