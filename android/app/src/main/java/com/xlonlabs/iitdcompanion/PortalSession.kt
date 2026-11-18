package com.xlonlabs.iitdcompanion

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.EventSeat
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.HistoryEdu
import androidx.compose.material.icons.rounded.Mail
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.School
import androidx.compose.material.icons.rounded.VpnKey
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import java.io.File

enum class Portal(
    val label: String,
    val home: String,
    /** Hosts where the saved password may be typed in. */
    val loginHosts: Set<String>,
    /** Plain username/password form; null for OAuth portals and Piazza (which uses its own login). */
    val login: LoginForm?,
    val icon: ImageVector,
    /** Each portal's own hue: the dock pill, status dot and loading bar take it on. */
    val accent: Color,
    /** Included in the default 4-tab dock. */
    val inDock: Boolean = true,
    /** Available to place in the dock or ⋮ menu; false for sheets opened from another screen (e.g. VpnSheet). */
    val inMenu: Boolean = true,
    /** Signs in via IITD's OAuth page using [RollCallScript]. */
    val usesOAuth: Boolean = false,
    /**
     * After signing in, go back to [home] if the portal landed elsewhere. ClassGrid's home is its Plan
     * page, which loads the account's saved plan into the browser; the Calendar shows only that.
     */
    val returnsHome: Boolean = false,
) {
    WEBMAIL("Webmail", "https://webmail.iitd.ac.in/",
        setOf("webmail.iitd.ac.in"), LoginForm.WEBMAIL, Icons.Rounded.Mail, Color(0xFF6FA6F2)),
    MOODLE_NEW("Moodle New", "https://moodlenew.iitd.ac.in/my/",
        setOf("moodlenew.iitd.ac.in"), LoginForm.MOODLE_NEW, Icons.Rounded.AutoStories, Color(0xFF4FC1AE)),
    MOODLE("Moodle", "https://moodle.iitd.ac.in/my/",
        setOf("moodle.iitd.ac.in"), LoginForm.MOODLE, Icons.Rounded.School, Color(0xFFF0913A)),
    ROLLCALL("RollCall", "https://rollcall.iitd.ac.in/index.php",
        setOf("oauth.iitd.ac.in", "rollcall.iitd.ac.in"), null, Icons.Rounded.QrCodeScanner, Color(0xFF7CCB6E), usesOAuth = true),
    // signs in through the same IITD OAuth page as RollCall, so it reuses that script; only reachable on the intranet / VPN
    EACADEMICS("eAcademics", "https://eacademics.iitd.ac.in/sportal/login",
        setOf("oauth.iitd.ac.in"), null, Icons.Rounded.HistoryEdu, Color(0xFFE0675A), inDock = false, usesOAuth = true),
    // the exam seat plan; signs in through the IITD OAuth page too
    EXAM("Exam IITD", "https://exam.iitd.ac.in/",
        setOf("oauth.iitd.ac.in"), null, Icons.Rounded.EventSeat, Color(0xFFA98BF0), inDock = false, usesOAuth = true),
    // DevClub's timetable calendar, outside iitd.ac.in (see [isPortal]); signs in via DevClub SSO, then the IITD OAuth page
    CLASSGRID("ClassGrid", "https://classgrid.devclub.in/plan",
        setOf("oauth.iitd.ac.in"), null, Icons.Rounded.CalendarMonth, Color(0xFFE07BB5), inDock = false, usesOAuth = true, returnsHome = true),
    // Course Q&A; uses its own separate login persisted via cookies (no Kerberos autofill)
    PIAZZA("Piazza", "https://piazza.com/class",
        emptySet(), null, Icons.Rounded.Forum, Color(0xFFE8B84B), inDock = false),
    // CSC services page for requesting or renewing IITD VPN3 access; opened from VpnSheet
    VPN_REQUEST("Request VPN", "https://ldapweb.iitd.ac.in/usermanage/services.php",
        setOf("ldapweb.iitd.ac.in"), LoginForm.CSC, Icons.Rounded.VpnKey, Color(0xFF56B8D6), inDock = false, inMenu = false);

    /** A plain login form (Moodle's arithmetic CAPTCHA included), so the app presses "Log in" itself. */
    val autoLogin get() = login != null
    val bootstrap: String? get() = login?.bootstrap ?: if (usesOAuth) RollCallScript.BOOTSTRAP else null

    companion object {
        val defaultDock = entries.filter { it.inDock }
        val pickable = entries.filter { it.inMenu }
    }
}

typealias FileChooser = (ValueCallback<Array<Uri>>, WebChromeClient.FileChooserParams) -> Boolean

/**
 * One tab: its WebView plus the state the injected script reports back.
 * Compose reads the mutable-state fields; the WebView outlives tab switches.
 */
class PortalSession(val portal: Portal, private val creds: Creds) {
    /** Signed in with the demo login: the tab is served by [Demo], not the real portal. */
    private val demo = Demo.isDemo(creds)
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
    var loginReady by mutableStateOf(false)
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
    // the saved login was filled in; for [Portal.returnsHome], the next page back on the portal reloads home
    private var signingIn = false

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
        CookieManager.getInstance().flush()
        webView?.destroy()
        webView = null
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun webView(ctx: Context, chooseFile: FileChooser): WebView = webView ?: WebView(ctx).apply {
        layoutParams = ViewGroup.LayoutParams(-1, -1)
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.javaScriptCanOpenWindowsAutomatically = true
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        // before the scanner asks its camera what it can do; older WebViews get it with the bootstrap instead
        if (portal == Portal.ROLLCALL && WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT))
            WebViewCompat.addDocumentStartJavaScript(this, RollCallScript.ZOOM, setOf("https://rollcall.iitd.ac.in"))
        addJavascriptInterface(WebBridge(::onMessage) { busy, pans -> touchBusy = busy; pagePans = pans }, "AndroidIC")
        webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) { this@PortalSession.progress = newProgress }

            // keep page console output out of logcat; the login pages handle the password
            override fun onConsoleMessage(message: ConsoleMessage) = true

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
        val appCtx = ctx.applicationContext
        webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                if (demo) Demo.serve(appCtx, request.url) else null

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (!request.isForMainFrame || isPortal(request.url)) return false
                openExternally(view.context, request.url)
                return true
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                page = "loading"
                filled = false
                oauthClicked = false
                redirected = false
                loginReady = false
                captchaPending = false
            }

            // inject as soon as the page paints, again when it finishes; the script is idempotent
            override fun onPageCommitVisible(view: WebView, url: String?) {
                painted = true
                inject(view, url)
                if (portal == Portal.ROLLCALL) view.evaluateJavascript(RollCallScript.ZOOM, null)
            }

            override fun onPageFinished(view: WebView, url: String?) {
                inject(view, url)
            }
        }
        setDownloadListener { url, userAgent, disposition, mime, _ ->
            if (!demo) download(context, url, userAgent, disposition, mime, this.url)
            else Toast.makeText(context, Demo.saveDownload(context, url)?.let { "Saved $it to Downloads" }
                ?: "Couldn't download this file", Toast.LENGTH_SHORT).show()
        }
        loadUrl(portal.home)
        webView = this
    }

    private fun onMessage(data: String) {
        val o = runCatching { JSONObject(data) }.getOrNull() ?: return
        webView?.let { DebugAddon.onMessage(it, o) }
        when (o.optString("type")) {
            "filled" -> when {
                !o.optBoolean("ok") -> filled = false // fields not ready yet; retry on the next report
                o.optBoolean("captcha") -> {
                    captchaPending = true
                    captchaShownAt = SystemClock.elapsedRealtime()
                }
                isCscHost() -> loginReady = true
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
            "ovpn" -> {
                val ctx = webView?.context ?: return
                if (!isNewCertHost()) return
                val name = o.optString("name").ifBlank { "${creds.kerberos}.ovpn" }
                val text = o.optString("text")
                if (text.isNotBlank()) {
                    val saved = saveTextDownload(ctx, name, text, "application/x-openvpn-profile")
                    Toast.makeText(
                        ctx,
                        if (saved != null) "Saved $saved to Downloads" else "Couldn't save $name",
                        Toast.LENGTH_LONG
                    ).show()
                }
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
            "login" -> if (!filled) {
                val csc = isCscHost()
                if (csc || onLoginHost()) {
                    filled = true
                    if (csc) {
                        // fill credentials and let the user submit (services.php has a dropdown; vpn3cert has no session cookie)
                        run(LoginForm.CSC.fill(creds.kerberos, creds.password, submit = false))
                    } else {
                        signingIn = true
                        if (autoSubmitted) loginFailed = true
                        val submit = portal.autoLogin && !autoSubmitted
                        if (submit) autoSubmitted = true
                        run(
                            portal.login?.fill(creds.kerberos, creds.password, submit)
                                ?: RollCallScript.fillCreds(creds.kerberos, creds.password)
                        )
                    }
                }
            }
            else -> {
                autoSubmitted = false
                loginFailed = false
                loginReady = false
                captchaPending = false
                val url = webView?.url
                val home = Uri.parse(portal.home)
                if (signingIn && url != null && Uri.parse(url).host == home.host) {
                    signingIn = false
                    if (portal.returnsHome && Uri.parse(url).path != home.path) webView?.loadUrl(portal.home)
                }
            }
        }
    }

    private fun inject(view: WebView, url: String?) {
        val current = url ?: view.url
        val script = when {
            isNewCertHost(current) -> LoginForm.CSC.bootstrap + CSC_POST_DOWNLOAD
            isCscHost(current) -> LoginForm.CSC.bootstrap
            else -> portal.bootstrap
        }
        if (script == null && page == "loading") page = "in"
        view.evaluateJavascript((script ?: "") + TOUCH_PROBE, null)
    }

    private fun isNewCertHost(url: String? = webView?.url): Boolean {
        val uri = url?.let(Uri::parse) ?: return false
        return uri.scheme == "https" && uri.host?.lowercase() == "newcert.iitd.ac.in"
    }

    private fun isCscHost(url: String? = webView?.url): Boolean {
        val uri = url?.let(Uri::parse) ?: return false
        return uri.scheme == "https" && uri.host?.lowercase() in CSC_HOSTS
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

/**
 * newcert.iitd.ac.in serves the .ovpn file from a second page behind a POST form or
 * session-checked CGI link. Android's DownloadManager only issues bare GET requests
 * (dropping POST bodies and session tokens), which makes the server return an HTML
 * "session expired" page saved as *.ovpn. Let the login form (with a password field)
 * submit natively, then intercept every download form/link on the logged-in page with
 * fetch() inside WebView's own session and save the real .ovpn body straight to Downloads.
 */
private const val CSC_POST_DOWNLOAD = """
(function(){
  if (window.__IC_POST_DL) return;
  window.__IC_POST_DL = true;
  function handleRes(res, fallbackName){
    var trimmed = (res.text || '').trim();
    var isHtml = /^<(!doctype|html|head|body)/i.test(trimmed);
    var isAttach = /attachment/i.test(res.disp) || /\.ovpn/i.test(res.disp) || (!isHtml && /remote\s+/i.test(trimmed));
    if (isAttach && !isHtml) {
      var m = /filename\*?=(?:UTF-8'')?"?([^";\r\n]+)"?/i.exec(res.disp);
      var name = (m && m[1]) ? m[1].trim() : fallbackName;
      try { AndroidIC.postMessage(JSON.stringify({ type: 'ovpn', name: name, text: res.text })); } catch(x){}
    } else {
      try { if (res.url && new URL(res.url).origin === location.origin) history.replaceState(null, '', res.url); } catch(x){}
      document.open();
      document.write(res.text);
      document.close();
      attach();
    }
  }
  function onSubmit(e){
    var f = e.target;
    if (!f || f.tagName !== 'FORM') return;
    // let the login form submit natively so WebView navigates cleanly to the download page
    if (f.querySelector("input[type='password']")) return;
    var action = f.action || location.href;
    try { if (new URL(action, location.href).origin !== location.origin) return; } catch(x){ return; }
    e.preventDefault();
    var fd = (e.submitter && typeof FormData === 'function')
      ? (function(){ try { return new FormData(f, e.submitter); } catch(x){ return new FormData(f); } })()
      : new FormData(f);
    var params = new URLSearchParams();
    fd.forEach(function(v, k){ if (typeof v === 'string') params.append(k, v); });
    var method = (f.method || 'get').toUpperCase();
    var url = action, opts = { method: method, credentials: 'include' };
    if (method === 'POST') {
      opts.body = params;
      opts.headers = { 'Content-Type': 'application/x-www-form-urlencoded' };
    } else if (params.toString()) {
      url += (url.indexOf('?') === -1 ? '?' : '&') + params.toString();
    }
    var btn = e.submitter || f.querySelector("input[type='submit'],button[type='submit']");
    if (btn) btn.disabled = true;
    fetch(url, opts).then(function(r){
      var disp = r.headers.get('content-disposition') || '';
      return r.text().then(function(t){ return { disp: disp, url: r.url || url, text: t }; });
    }).then(function(res){
      if (btn) btn.disabled = false;
      handleRes(res, 'iitd.ovpn');
    }).catch(function(){
      if (btn) btn.disabled = false;
      f.submit();
    });
  }
  function onClick(e){
    var a = e.target && e.target.closest ? e.target.closest('a[href]') : null;
    if (!a) return;
    var href = a.href || '';
    try { if (!href || new URL(href, location.href).origin !== location.origin) return; } catch(x){ return; }
    // leave the login page's own links alone
    if (document.querySelector("input[type='password']")) return;
    e.preventDefault();
    var fallback = (href.split('/').pop() || 'iitd.ovpn').split('?')[0];
    if (!/\.ovpn$/i.test(fallback)) fallback = 'iitd.ovpn';
    fetch(href, { credentials: 'include' }).then(function(r){
      var disp = r.headers.get('content-disposition') || '';
      return r.text().then(function(t){ return { disp: disp, url: r.url || href, text: t }; });
    }).then(function(res){
      handleRes(res, fallback);
    }).catch(function(){
      location.href = href;
    });
  }
  function attach(){
    window.addEventListener('submit', onSubmit, true);
    window.addEventListener('click', onClick, true);
  }
  attach();
})();
"""

/** CSC usermanage login pages (VPN request, client certificate / .ovpn download) that can open inside any tab. */
private val CSC_HOSTS = setOf("newcert.iitd.ac.in", "ldapweb.iitd.ac.in")

/** Portals outside iitd.ac.in that still open inside their tab (ClassGrid, Piazza). */
private val OUTSIDE_HOSTS = setOf("classgrid.devclub.in", "auth.devclub.in", "piazza.com")

/** Stays inside the tab: the IITD portals, plus [OUTSIDE_HOSTS] (and *.piazza.com). */
private fun isPortal(uri: Uri): Boolean {
    val host = uri.host?.lowercase() ?: return false
    return (uri.scheme == "https" || uri.scheme == "http") &&
        (host == "iitd.ac.in" || host.endsWith(".iitd.ac.in") ||
            (uri.scheme == "https" && (host in OUTSIDE_HOSTS || host.endsWith(".piazza.com"))))
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
private fun download(
    ctx: Context,
    url: String,
    userAgent: String,
    disposition: String?,
    mime: String?,
    referer: String? = null,
) {
    runCatching {
        CookieManager.getInstance().flush()
        val name = URLUtil.guessFileName(url, disposition, mime)
        val req = DownloadManager.Request(Uri.parse(url))
            .setMimeType(mime)
            .addRequestHeader("Cookie", CookieManager.getInstance().getCookie(url))
            .addRequestHeader("User-Agent", userAgent)
            .apply { if (!referer.isNullOrBlank()) addRequestHeader("Referer", referer) }
            .setTitle(name)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        if (Build.VERSION.SDK_INT >= 29) req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
        else req.setDestinationInExternalFilesDir(ctx, Environment.DIRECTORY_DOWNLOADS, name)
        ctx.getSystemService(DownloadManager::class.java).enqueue(req)
        Toast.makeText(ctx, "Downloading $name", Toast.LENGTH_SHORT).show()
    }.onFailure { Toast.makeText(ctx, "Couldn't download this file", Toast.LENGTH_SHORT).show() }
}

/** Save a text payload (such as a POST-downloaded .ovpn profile) into Downloads. */
private fun saveTextDownload(ctx: Context, rawName: String, text: String, mime: String): String? = runCatching {
    val file = rawName.replace(Regex("""[^A-Za-z0-9._-]"""), "_").ifBlank { "iitd.ovpn" }
    val bytes = text.toByteArray(Charsets.UTF_8)
    if (Build.VERSION.SDK_INT >= 29) {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, file)
            put(MediaStore.Downloads.MIME_TYPE, mime)
        }
        val dest = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        ctx.contentResolver.openOutputStream(dest)?.use { it.write(bytes) } ?: return null
        ctx.contentResolver.query(dest, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else file
        } ?: file
    } else {
        val dir = ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return null
        File(dir, file).writeBytes(bytes)
        file
    }
}.getOrNull()

/** Bridge the site's JS -> app; marshals messages to the main thread. */
class WebBridge(private val onMessage: (String) -> Unit, private val onTouch: (Boolean, Boolean) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    @JavascriptInterface
    fun postMessage(data: String) { main.post { onMessage(data) } }
    /** Called on the JS thread; must be quick, so no main-thread hop. */
    @JavascriptInterface
    fun touch(busy: Boolean, pans: Boolean) = onTouch(busy, pans)
}
