package com.xlonlabs.iitdcompanion

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

internal val PerkAccent = Color(0xFFF2C14E)

internal const val WORKER_PERKS_URL =
    "https://iitd-companion-worker.hellonitinbro.workers.dev/api/perks"

private const val REMOTE_PERKS_URL =
    "https://raw.githubusercontent.com/n06-nitin/iitd_companion/main/docs/perks.html"

private const val CAROUSEL_TOUCH_JS = """
    if (!window.__icPerkTouch) {
        window.__icPerkTouch = true;
        document.addEventListener('touchstart', function(e) {
            var el = e.target;
            var busy = false;
            while (el && el !== document.body) {
                if (el.classList && (el.classList.contains('milestones-carousel') || el.classList.contains('images-carousel'))) {
                    busy = true;
                    break;
                }
                if (el.scrollWidth > el.clientWidth + 2) {
                    var cs = getComputedStyle(el).overflowX;
                    if (cs === 'auto' || cs === 'scroll') { busy = true; break; }
                }
                el = el.parentElement;
            }
            if (window.AndroidApp && window.AndroidApp.setTouchBusy) {
                window.AndroidApp.setTouchBusy(busy);
            }
        }, { passive: true, capture: true });
    }
"""

/**
 * State holder for the embedded IITD Perks WebView tab so TopBar, BackHandler,
 * and the Perks dock slot share loading state, reload, and lifecycle cleanly.
 */
@Stable
class PerkState(private val ctx: Context, private val scope: CoroutineScope) {
    var isLoading by mutableStateOf(false)
        private set
    var webView: WebView? = null
        internal set
    @Volatile var touchBusy = false
        internal set
    private var loadedOnce = false

    fun touchStarted() {
        touchBusy = false
    }

    fun ensureLoaded() {
        if (!loadedOnce) {
            loadedOnce = true
            reload()
        }
    }

    fun reload() {
        scope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) { isLoading = true }
            val kerberos = SecureCreds.load(ctx)?.kerberos.orEmpty().trim().lowercase()
            val remoteText = runCatching {
                // 1. Primary: Query Cloudflare Worker with user kerberos
                val targetUrl = "$WORKER_PERKS_URL?user=$kerberos&t=${System.currentTimeMillis()}"
                val conn = URL(targetUrl).openConnection() as HttpURLConnection
                conn.useCaches = false
                conn.connectTimeout = 6000
                conn.readTimeout = 6000
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) IITDCompanion")
                conn.setRequestProperty("Cache-Control", "no-cache, no-store, must-revalidate")
                conn.inputStream.bufferedReader().use { it.readText() }
            }.getOrNull() ?: runCatching {
                // 2. Fallback: Query GitHub commit SHA to bypass Fastly's 5-minute CDN cache
                val sha = runCatching {
                    val apiConn = URL("https://api.github.com/repos/n06-nitin/iitd_companion/commits/main")
                        .openConnection() as HttpURLConnection
                    apiConn.connectTimeout = 4000
                    apiConn.readTimeout = 4000
                    apiConn.setRequestProperty("User-Agent", "IITDCompanion")
                    val json = apiConn.inputStream.bufferedReader().use { it.readText() }
                    Regex("\"sha\"\\s*:\\s*\"([a-f0-9]{40})\"").find(json)?.groupValues?.get(1)
                }.getOrNull()

                val targetUrl = if (!sha.isNullOrBlank()) {
                    "https://raw.githubusercontent.com/n06-nitin/iitd_companion/$sha/docs/perks.html"
                } else {
                    "$REMOTE_PERKS_URL?t=${System.currentTimeMillis()}"
                }

                val conn = URL(targetUrl).openConnection() as HttpURLConnection
                conn.useCaches = false
                conn.connectTimeout = 6000
                conn.readTimeout = 6000
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) IITDCompanion")
                conn.setRequestProperty("Cache-Control", "no-cache, no-store, must-revalidate")
                conn.inputStream.bufferedReader().use { it.readText() }
            }.getOrNull()

            withContext(Dispatchers.Main) {
                isLoading = false
                if (!remoteText.isNullOrBlank() && remoteText.contains("<html", ignoreCase = true)) {
                    webView?.loadDataWithBaseURL(
                        "https://iitd-companion-worker.hellonitinbro.workers.dev",
                        remoteText,
                        "text/html",
                        "UTF-8",
                        null
                    )
                }
            }
        }
    }

    fun scrollToTop() {
        webView?.evaluateJavascript("window.scrollTo({top: 0, behavior: 'smooth'})", null)
    }

    fun destroy() {
        webView?.destroy()
        webView = null
    }
}

@Composable
fun rememberPerkState(): PerkState {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val state = remember(ctx) { PerkState(ctx, scope) }
    DisposableEffect(state) { onDispose { state.destroy() } }
    return state
}

/**
 * Embedded IITD Perks page rendered inside the main rounded tab frame,
 * fetching docs/perks.html remotely with instant local asset fallback.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun PerkContent(state: PerkState, visible: Boolean, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val localHtml = remember {
        runCatching {
            ctx.assets.open("perks.html").bufferedReader().use { it.readText() }
        }.getOrNull() ?: ""
    }

    LaunchedEffect(state) {
        state.ensureLoaded()
    }

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { c ->
            state.webView?.also { (it.parent as? ViewGroup)?.removeView(it) }
                ?: WebView(c).apply {
                    setBackgroundColor(Ink.toArgb())
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        useWideViewPort = true
                        loadWithOverviewMode = true
                        builtInZoomControls = false
                        displayZoomControls = false
                    }

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

                        @JavascriptInterface
                        fun setTouchBusy(busy: Boolean) {
                            state.touchBusy = busy
                        }
                    }, "AndroidApp")

                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            view?.evaluateJavascript(CAROUSEL_TOUCH_JS, null)
                        }

                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): Boolean {
                            val uri = request?.url ?: return false
                            val url = uri.toString()
                            if (url.startsWith("mailto:") || (url.startsWith("http") && !url.contains("perks.html"))) {
                                runCatching {
                                    c.startActivity(Intent(Intent.ACTION_VIEW, uri))
                                }
                                return true
                            }
                            return false
                        }
                    }

                    if (localHtml.isNotBlank()) {
                        loadDataWithBaseURL("https://github.com", localHtml, "text/html", "UTF-8", null)
                    }
                    state.webView = this
                }
        },
        update = {
            it.visibility = if (visible) View.VISIBLE else View.GONE
        }
    )
}
