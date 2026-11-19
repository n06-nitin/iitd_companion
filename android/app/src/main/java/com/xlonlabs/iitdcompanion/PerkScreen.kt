package com.xlonlabs.iitdcompanion

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

internal val PerkAccent = Color(0xFFF2C14E)

private const val REMOTE_PERKS_URL =
    "https://raw.githubusercontent.com/n06-nitin/iitd_companion/main/docs/perks.html"

/**
 * IITD Perks Screen: loaded from the 3-dot (⋮) menu over the tabs.
 * Fetches docs/perks.html directly via HTTP and renders with explicit "text/html" MIME type,
 * with instant fallback to local bundled assets.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun PerkScreen(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var isLoading by remember { mutableStateOf(false) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    // Read bundled local asset for instant 0ms initial display
    val localHtml = remember {
        runCatching {
            ctx.assets.open("perks.html").bufferedReader().use { it.readText() }
        }.getOrNull() ?: ""
    }

    // Function to fetch the latest remote HTML and render as "text/html"
    fun fetchRemotePerks(showFeedback: Boolean = false) {
        scope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) { isLoading = true }
            val remoteText = runCatching {
                val url = URL("$REMOTE_PERKS_URL?t=${System.currentTimeMillis()}")
                val conn = url.openConnection() as HttpURLConnection
                conn.useCaches = false
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) IITDCompanion")
                conn.setRequestProperty("Cache-Control", "no-cache, no-store, must-revalidate")
                conn.setRequestProperty("Pragma", "no-cache")
                conn.inputStream.bufferedReader().use { it.readText() }
            }.getOrNull()

            withContext(Dispatchers.Main) {
                isLoading = false
                if (!remoteText.isNullOrBlank() && remoteText.contains("<html", ignoreCase = true)) {
                    webViewRef?.loadDataWithBaseURL("https://github.com", remoteText, "text/html", "UTF-8", null)
                    if (showFeedback) {
                        Toast.makeText(ctx, "Perks updated from GitHub", Toast.LENGTH_SHORT).show()
                    }
                } else if (showFeedback) {
                    Toast.makeText(ctx, "Using offline perks", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

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

    // Auto-fetch fresh version in background when opened
    LaunchedEffect(Unit) {
        fetchRemotePerks()
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
                    fetchRemotePerks(showFeedback = true)
                }
                ClayIconButton(Icons.Rounded.Close, "Close", onClose)
            }

            // Loading indicator
            if (isLoading) {
                LinearProgressIndicator(
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
                            useWideViewPort = true
                            loadWithOverviewMode = true
                            builtInZoomControls = false
                            displayZoomControls = false
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

                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): Boolean {
                                val uri = request?.url ?: return false
                                val url = uri.toString()
                                // If mailto or external link, launch outside WebView
                                if (url.startsWith("mailto:") || (url.startsWith("http") && !url.contains("perks.html"))) {
                                    runCatching {
                                        c.startActivity(Intent(Intent.ACTION_VIEW, uri))
                                    }
                                    return true
                                }
                                return false
                            }
                        }

                        // Render bundled local HTML immediately as "text/html"
                        if (localHtml.isNotBlank()) {
                            loadDataWithBaseURL("https://github.com", localHtml, "text/html", "UTF-8", null)
                        }
                        webViewRef = this
                    }
                }
            )
        }
    }
}
