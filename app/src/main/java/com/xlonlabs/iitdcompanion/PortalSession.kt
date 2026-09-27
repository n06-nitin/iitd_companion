package com.xlonlabs.iitdcompanion

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material.icons.rounded.Mail
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.School
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import org.json.JSONObject

enum class Portal(
    val label: String,
    val home: String,
    /** Hosts where the saved password may be typed in. */
    val loginHosts: Set<String>,
    /** null for RollCall, which drives its own OAuth flow via [RollCallScript]. */
    val login: LoginForm?,
    val icon: ImageVector,
    /** Each portal's own hue: the dock pill, status dot and loading bar take it on. */
    val accent: Color,
) {
    WEBMAIL("Webmail", "https://webmail.iitd.ac.in/",
        setOf("webmail.iitd.ac.in"), LoginForm.WEBMAIL, Icons.Rounded.Mail, Color(0xFF6FA6F2)),
    MOODLE_NEW("Moodle New", "https://moodlenew.iitd.ac.in/my/",
        setOf("moodlenew.iitd.ac.in"), LoginForm.MOODLE_NEW, Icons.Rounded.AutoStories, Color(0xFF4FC1AE)),
    MOODLE("Moodle", "https://moodle.iitd.ac.in/my/",
        setOf("moodle.iitd.ac.in"), LoginForm.MOODLE, Icons.Rounded.School, Color(0xFFF0913A)),
    ROLLCALL("RollCall", "https://rollcall.iitd.ac.in/index.php",
        setOf("oauth.iitd.ac.in", "rollcall.iitd.ac.in"), null, Icons.Rounded.QrCodeScanner, Color(0xFF7CCB6E));

    /** No CAPTCHA on the login form, so the app presses "Log in" itself. */
    val autoLogin get() = login != null && login.captcha == null
    val bootstrap get() = login?.bootstrap ?: RollCallScript.BOOTSTRAP
}

typealias FileChooser = (ValueCallback<Array<Uri>>, WebChromeClient.FileChooserParams) -> Boolean

/**
 * One tab: its WebView plus the state the injected script reports back.
 * Compose reads the mutable-state fields; the WebView outlives tab switches.
 */
class PortalSession(val portal: Portal, private val creds: Creds) {
    var webView: WebView? = null
        private set
    var page by mutableStateOf("loading")
        private set
    var progress by mutableIntStateOf(0)
        private set
    /** Has shown any page yet; until then the tab shows a placeholder face. */
    var painted by mutableStateOf(false)
        private set
    var loginFailed by mutableStateOf(false)
        private set
    var captchaPending by mutableStateOf(false)
        private set
    private var captchaShownAt = 0L
    var courses by mutableStateOf(listOf<String>())
        private set
    var selected by mutableStateOf<String?>(null)
        private set

    // Set from the page's touchstart (JS thread), read by the swipe listener.
    /** The finger is on something that scrolls or slides sideways itself. */
    @Volatile var touchBusy = false
        private set
    /** The page itself pans sideways (zoomed in, or really wider than the screen); null until reported. */
    @Volatile var pagePans: Boolean? = null
        private set

    fun touchStarted() {
        touchBusy = false
        pagePans = null
    }

    // reset on every page load
    private var filled = false
    private var oauthClicked = false
    private var redirected = false
    // set when the app pressed "Log in"; landing on the login form again means it failed
    private var autoSubmitted = false

    fun run(js: String) = webView?.evaluateJavascript(js, null)

    fun home() { webView?.loadUrl(portal.home) }

    /** Reload, and let auto-login try again if it had given up. */
    fun reload() {
        loginFailed = false
        autoSubmitted = false
        webView?.reload()
    }

    /** A tab warmed up in the background may hold an old CAPTCHA; fetch a fresh one when it's opened. */
    fun refreshStaleCaptcha() {
        if (captchaPending && SystemClock.elapsedRealtime() - captchaShownAt > 10 * 60_000L) webView?.reload()
    }

    fun logout() {
        run(RollCallScript.logout())
        Handler(Looper.getMainLooper()).postDelayed({ home() }, 800)
    }

    fun destroy() {
        webView?.destroy()
        webView = null
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun webView(ctx: Context, chooseFile: FileChooser): WebView = webView ?: WebView(ctx).apply {
        layoutParams = ViewGroup.LayoutParams(-1, -1)
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.javaScriptCanOpenWindowsAutomatically = true
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        addJavascriptInterface(WebBridge(::onMessage) { busy, pans -> touchBusy = busy; pagePans = pans }, "AndroidIC")
        webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) { this@PortalSession.progress = newProgress }

            override fun onPermissionRequest(request: PermissionRequest) {
                // only RollCall's QR scanner gets the camera
                if (portal == Portal.ROLLCALL && PermissionRequest.RESOURCE_VIDEO_CAPTURE in request.resources)
                    request.grant(arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE))
                else request.deny()
            }

            override fun onShowFileChooser(
                view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams,
            ) = chooseFile(callback, params)
        }
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (!request.isForMainFrame || isIitd(request.url)) return false
                openExternally(view.context, request.url)
                return true
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                page = "loading"
                filled = false
                oauthClicked = false
                redirected = false
                captchaPending = false
            }

            // inject as soon as the page paints, again when it finishes; the script is idempotent
            override fun onPageCommitVisible(view: WebView, url: String?) {
                painted = true
                view.evaluateJavascript(portal.bootstrap + TOUCH_PROBE, null)
            }

            override fun onPageFinished(view: WebView, url: String?) {
                view.evaluateJavascript(portal.bootstrap + TOUCH_PROBE, null)
            }
        }
        setDownloadListener { url, userAgent, disposition, mime, _ ->
            download(context, url, userAgent, disposition, mime)
        }
        loadUrl(portal.home)
        webView = this
    }

    private fun onMessage(data: String) {
        val o = runCatching { JSONObject(data) }.getOrNull() ?: return
        when (o.optString("type")) {
            "filled" -> when {
                !o.optBoolean("ok") -> filled = false // fields not ready yet; retry on the next report
                o.optBoolean("captcha") -> {
                    captchaPending = true
                    captchaShownAt = SystemClock.elapsedRealtime()
                }
            }
            "state" -> {
                page = o.optString("page")
                if (portal == Portal.ROLLCALL) {
                    selected = o.optString("selected").ifBlank { null }
                    courses = o.optJSONArray("courses")?.let { arr ->
                        (0 until arr.length()).map { arr.getString(it) }
                    } ?: emptyList()
                }
                automate()
            }
        }
    }

    private fun automate() {
        when (page) {
            "landing" -> if (!oauthClicked) {
                oauthClicked = true
                run(RollCallScript.loginOAuth())
            }
            "loggedout" -> if (!redirected) {
                redirected = true
                portal.login?.loginUrl?.let { webView?.loadUrl(it) }
            }
            "login" -> if (!filled && onLoginHost()) {
                filled = true
                if (autoSubmitted) loginFailed = true
                val submit = portal.autoLogin && !autoSubmitted
                if (submit) autoSubmitted = true
                run(
                    portal.login?.fill(creds.kerberos, creds.password, submit)
                        ?: RollCallScript.fillCreds(creds.kerberos, creds.password)
                )
            }
            else -> {
                autoSubmitted = false
                loginFailed = false
                captchaPending = false
            }
        }
    }

    private fun onLoginHost(): Boolean {
        val uri = webView?.url?.let(Uri::parse) ?: return false
        return uri.scheme == "https" && uri.host in portal.loginHosts
    }
}

/**
 * Tells the app, on each touchstart, whether the finger is on something that
 * scrolls sideways itself (a wide table, a slider, a map), and whether the page
 * as a whole pans sideways (pinch-zoomed, or clearly wider than the screen —
 * a few stray pixels of overflow don't count). The tab swipe leaves those alone.
 */
private const val TOUCH_PROBE = """
(function(){
  if (window.__IC_TOUCH) return;
  window.__IC_TOUCH = true;
  document.addEventListener('touchstart', function(e){
    var busy=false;
    for (var el=e.target; el && el.nodeType===1 && el!==document.documentElement && el!==document.body; el=el.parentElement) {
      var cs=getComputedStyle(el);
      if ((cs.overflowX==='auto'||cs.overflowX==='scroll') && el.scrollWidth>el.clientWidth+16) { busy=true; break; }
      if (cs.touchAction==='none') { busy=true; break; }
      if (el.tagName==='INPUT' && el.type==='range') { busy=true; break; }
    }
    var se=document.scrollingElement||document.documentElement;
    var zoomed=window.visualViewport ? visualViewport.scale>1.05 : false;
    var pans=zoomed || (se.scrollWidth-se.clientWidth>24);
    try { AndroidIC.touch(busy, pans); } catch(x){}
  }, {passive:true, capture:true});
})();
"""

private fun isIitd(uri: Uri): Boolean {
    val host = uri.host ?: return false
    return (uri.scheme == "https" || uri.scheme == "http") &&
        (host == "iitd.ac.in" || host.endsWith(".iitd.ac.in"))
}

private fun openExternally(ctx: Context, uri: Uri) {
    runCatching {
        val intent = if (uri.scheme == "intent") {
            Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME).apply {
                addCategory(Intent.CATEGORY_BROWSABLE)
                component = null
                selector = null
            }
        } else Intent(Intent.ACTION_VIEW, uri)
        ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure { Toast.makeText(ctx, "No app can open this link", Toast.LENGTH_SHORT).show() }
}

/** Hand Moodle files / mail attachments to DownloadManager with the tab's session cookie. */
private fun download(ctx: Context, url: String, userAgent: String, disposition: String?, mime: String?) {
    runCatching {
        val name = URLUtil.guessFileName(url, disposition, mime)
        val req = DownloadManager.Request(Uri.parse(url))
            .setMimeType(mime)
            .addRequestHeader("Cookie", CookieManager.getInstance().getCookie(url))
            .addRequestHeader("User-Agent", userAgent)
            .setTitle(name)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        if (Build.VERSION.SDK_INT >= 29) req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
        else req.setDestinationInExternalFilesDir(ctx, Environment.DIRECTORY_DOWNLOADS, name)
        ctx.getSystemService(DownloadManager::class.java).enqueue(req)
        Toast.makeText(ctx, "Downloading $name", Toast.LENGTH_SHORT).show()
    }.onFailure { Toast.makeText(ctx, "Couldn't download this file", Toast.LENGTH_SHORT).show() }
}

/** Bridge the site's JS -> app; marshals messages to the main thread. */
class WebBridge(private val onMessage: (String) -> Unit, private val onTouch: (Boolean, Boolean) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    @JavascriptInterface
    fun postMessage(data: String) { main.post { onMessage(data) } }
    /** Called on the JS thread; must be quick, so no main-thread hop. */
    @JavascriptInterface
    fun touch(busy: Boolean, pans: Boolean) = onTouch(busy, pans)
}
