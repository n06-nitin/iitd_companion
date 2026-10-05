import Foundation
import QuickLook
import UIKit
import WebKit

enum Portal: String, CaseIterable, Identifiable {
    case webmail = "WEBMAIL", moodleNew = "MOODLE_NEW", moodle = "MOODLE", rollcall = "ROLLCALL"

    var id: String { rawValue }

    var label: String {
        switch self {
        case .webmail: "Webmail"
        case .moodleNew: "Moodle New"
        case .moodle: "Moodle"
        case .rollcall: "RollCall"
        }
    }

    var home: URL {
        switch self {
        case .webmail: URL(string: "https://webmail.iitd.ac.in/")!
        case .moodleNew: URL(string: "https://moodlenew.iitd.ac.in/my/")!
        case .moodle: URL(string: "https://moodle.iitd.ac.in/my/")!
        case .rollcall: URL(string: "https://rollcall.iitd.ac.in/index.php")!
        }
    }

    /// Hosts where the saved password may be typed in.
    var loginHosts: Set<String> {
        switch self {
        case .webmail: ["webmail.iitd.ac.in"]
        case .moodleNew: ["moodlenew.iitd.ac.in"]
        case .moodle: ["moodle.iitd.ac.in"]
        case .rollcall: ["oauth.iitd.ac.in", "rollcall.iitd.ac.in"]
        }
    }

    /// nil for RollCall, which drives its own OAuth flow via `RollCallScript`.
    var login: LoginForm? {
        switch self {
        case .webmail: .webmail
        case .moodleNew: .moodleNew
        case .moodle: .moodle
        case .rollcall: nil
        }
    }

    /// Material Rounded icon in the asset catalog.
    var icon: String {
        switch self {
        case .webmail: "icon_mail"
        case .moodleNew: "icon_auto_stories"
        case .moodle: "icon_school"
        case .rollcall: "icon_qr_code_scanner"
        }
    }

    /// Each portal's own hue: the dock pill, status dot and loading bar take it on.
    var accent: Tone {
        switch self {
        case .webmail: Tone(0xFF6FA6F2)
        case .moodleNew: Tone(0xFF4FC1AE)
        case .moodle: Tone(0xFFF0913A)
        case .rollcall: Tone(0xFF7CCB6E)
        }
    }

    /// No CAPTCHA on the login form, so the app presses "Log in" itself.
    var autoLogin: Bool { login.map { $0.captcha == nil } ?? false }
    var bootstrap: String { login?.bootstrap ?? RollCallScript.bootstrap }
}

/// One tab: its WebView plus the state the injected script reports back.
/// SwiftUI reads the observable fields; the WebView outlives tab switches.
@Observable @MainActor
final class PortalSession {
    let portal: Portal
    @ObservationIgnored private let creds: Creds
    @ObservationIgnored private(set) var webView: WKWebView?

    private(set) var page = "loading"
    private(set) var progress = 0
    /// Has shown any page yet; until then the tab shows a placeholder face.
    private(set) var painted = false
    private(set) var loginFailed = false
    private(set) var captchaPending = false
    @ObservationIgnored private var captchaShownAt = Date.distantPast
    private(set) var courses: [String] = []
    private(set) var selected: String?

    // Set from the page's touchstart, read by the swipe recognizer.
    /// The finger is on something that scrolls or slides sideways itself.
    @ObservationIgnored private(set) var touchBusy = false
    /// The page itself pans sideways (zoomed in, or really wider than the screen); nil until reported.
    @ObservationIgnored private(set) var pagePans: Bool?

    func touchStarted() {
        touchBusy = false
        pagePans = nil
    }

    // reset on every page load
    @ObservationIgnored private var filled = false
    @ObservationIgnored private var oauthClicked = false
    @ObservationIgnored private var redirected = false
    // set when the app pressed "Log in"; landing on the login form again means it failed
    @ObservationIgnored private var autoSubmitted = false

    /// The address that failed to load, while its error page is showing.
    @ObservationIgnored private var failedURL: URL?
    @ObservationIgnored private var showingError = false
    @ObservationIgnored private var delegate: WebDelegate?
    @ObservationIgnored private var progressObservation: NSKeyValueObservation?

    init(portal: Portal, creds: Creds) {
        self.portal = portal
        self.creds = creds
    }

    func run(_ js: String) { webView?.evaluateJavaScript(js, completionHandler: nil) }

    func home() { webView?.load(URLRequest(url: portal.home)) }

    /// Reload, and let auto-login try again if it had given up.
    func reload() {
        loginFailed = false
        autoSubmitted = false
        guard let w = webView else { return }
        if let failed = failedURL ?? (w.url == nil ? portal.home : nil) {
            w.load(URLRequest(url: failed))
        } else {
            w.reload()
        }
    }

    /// A tab warmed up in the background may hold an old CAPTCHA; fetch a fresh one when it's opened.
    func refreshStaleCaptcha() {
        if captchaPending && Date().timeIntervalSince(captchaShownAt) > 10 * 60 { webView?.reload() }
    }

    func logout() {
        run(RollCallScript.logout())
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.8) { [weak self] in self?.home() }
    }

    func destroy() {
        progressObservation = nil
        webView?.stopLoading()
        webView?.configuration.userContentController.removeAllScriptMessageHandlers()
        webView?.removeFromSuperview()
        webView = nil
        delegate = nil
    }

    func makeWebView() -> WKWebView {
        if let webView { return webView }
        let delegate = WebDelegate(session: self)
        self.delegate = delegate

        let config = WKWebViewConfiguration()
        config.websiteDataStore = .default()
        config.allowsInlineMediaPlayback = true
        config.mediaTypesRequiringUserActionForPlayback = []
        config.preferences.javaScriptCanOpenWindowsAutomatically = true
        config.userContentController.add(delegate, name: "ic")
        config.userContentController.add(delegate, name: "icTouch")

        let w = WKWebView(frame: .zero, configuration: config)
        w.navigationDelegate = delegate
        w.uiDelegate = delegate
        // the edge swipe stands in for Android's back button
        w.allowsBackForwardNavigationGestures = true
        w.allowsLinkPreview = false
        if #available(iOS 26.0, *) {
            // no Liquid Glass blur/fade where the page meets the card's edges: the page shows as-is
            let sv = w.scrollView
            for edge in [sv.topEdgeEffect, sv.bottomEdgeEffect, sv.leftEdgeEffect, sv.rightEdgeEffect] {
                edge.isHidden = true
            }
        }
        #if DEBUG
        w.isInspectable = true // Safari ▸ Develop, debug builds only
        #endif
        progressObservation = w.observe(\.estimatedProgress, options: [.new]) { [weak self] w, _ in
            let p = Int((w.estimatedProgress * 100).rounded())
            MainActor.assumeIsolated { if self?.progress != p { self?.progress = p } }
        }
        w.load(URLRequest(url: portal.home))
        webView = w
        return w
    }

    /* ---- page lifecycle, from WebDelegate ---- */

    fileprivate func pageStarted() {
        set(\.page, "loading")
        filled = false
        oauthClicked = false
        redirected = false
        set(\.captchaPending, false)
    }

    // inject as soon as the page paints, again when it finishes; the script is idempotent
    fileprivate func pageCommitted(_ w: WKWebView) {
        set(\.painted, true)
        if showingError { showingError = false } else { failedURL = nil }
        inject(w)
    }

    fileprivate func pageFinished(_ w: WKWebView) { inject(w) }

    fileprivate func inject(_ w: WKWebView) {
        w.evaluateJavaScript(portal.bootstrap + touchProbe, completionHandler: nil)
    }

    fileprivate func loadFailed(_ w: WKWebView, url: URL?, error: Error) {
        failedURL = url ?? failedURL ?? portal.home
        showingError = true
        w.loadHTMLString(errorPage(url: failedURL, error: error), baseURL: nil)
    }

    fileprivate func touch(busy: Bool, pans: Bool) {
        touchBusy = busy
        pagePans = pans
    }

    fileprivate func onMessage(_ data: String) {
        guard let raw = data.data(using: .utf8),
              let o = (try? JSONSerialization.jsonObject(with: raw)) as? [String: Any] else { return }
        switch o["type"] as? String {
        case "filled":
            if (o["ok"] as? Bool) != true {
                filled = false // fields not ready yet; retry on the next report
            } else if (o["captcha"] as? Bool) == true {
                set(\.captchaPending, true)
                captchaShownAt = Date()
            }
        case "state":
            set(\.page, o["page"] as? String ?? "")
            if portal == .rollcall {
                let sel = (o["selected"] as? String).flatMap { $0.trimmingCharacters(in: .whitespaces).isEmpty ? nil : $0 }
                set(\.selected, sel)
                set(\.courses, (o["courses"] as? [Any])?.compactMap { $0 as? String } ?? [])
            }
            automate()
        default:
            break
        }
    }

    private func automate() {
        switch page {
        case "landing":
            if !oauthClicked {
                oauthClicked = true
                run(RollCallScript.loginOAuth())
            }
        case "loggedout":
            if !redirected {
                redirected = true
                if let url = portal.login?.loginUrl { webView?.load(URLRequest(url: url)) }
            }
        case "login":
            if !filled && onLoginHost() {
                filled = true
                if autoSubmitted { set(\.loginFailed, true) }
                let submit = portal.autoLogin && !autoSubmitted
                if submit { autoSubmitted = true }
                run(portal.login?.fill(user: creds.kerberos, pass: creds.password, submit: submit)
                    ?? RollCallScript.fillCreds(user: creds.kerberos, pass: creds.password))
            }
        default:
            autoSubmitted = false
            set(\.loginFailed, false)
            set(\.captchaPending, false)
        }
    }

    private func onLoginHost() -> Bool {
        guard let url = webView?.url, url.scheme == "https", let host = url.host else { return false }
        return portal.loginHosts.contains(host)
    }

    /// Only notify observers when a value really changes, so SwiftUI doesn't redraw on every report.
    private func set<T: Equatable>(_ key: ReferenceWritableKeyPath<PortalSession, T>, _ value: T) {
        if self[keyPath: key] != value { self[keyPath: key] = value }
    }
}

/// Tells the app, on each touchstart, whether the finger is on something that
/// scrolls sideways itself (a wide table, a slider, a map), and whether the page
/// as a whole pans sideways (pinch-zoomed, or clearly wider than the screen —
/// a few stray pixels of overflow don't count). The tab swipe leaves those alone.
private let touchProbe = """
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
    try { window.webkit.messageHandlers.icTouch.postMessage([busy, pans]); } catch(x){}
  }, {passive:true, capture:true});
})();
"""

let privacyPolicyURL = URL(string: "https://n06-nitin.github.io/iitd_companion/privacy.html")!

func isIitd(_ url: URL) -> Bool {
    guard let host = url.host?.lowercased(), url.scheme == "https" || url.scheme == "http" else { return false }
    return host == "iitd.ac.in" || host.hasSuffix(".iitd.ac.in")
}

@MainActor
func openExternally(_ url: URL) {
    UIApplication.shared.open(url) { ok in
        if !ok { Toaster.shared.show("No app can open this link") }
    }
}

private func errorPage(url: URL?, error: Error) -> String {
    func esc(_ s: String) -> String {
        s.replacingOccurrences(of: "&", with: "&amp;").replacingOccurrences(of: "<", with: "&lt;")
            .replacingOccurrences(of: ">", with: "&gt;")
    }
    return """
    <!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
    <style>body{font:16px -apple-system,sans-serif;color:#333;background:#fff;margin:0;padding:48px 24px}
    h1{font-size:22px;font-weight:600;margin:0 0 16px}p{line-height:1.45;margin:0 0 12px;word-wrap:break-word}</style></head>
    <body><h1>Webpage not available</h1>
    <p>The webpage at <b>\(esc(url?.absoluteString ?? ""))</b> could not be loaded because:</p>
    <p>\(esc(error.localizedDescription))</p></body></html>
    """
}

/* ------------------------------ web delegate ------------------------------ */

/// Navigation, dialogs, camera, downloads and the JS bridge for one tab.
@MainActor
private final class WebDelegate: NSObject, WKNavigationDelegate, WKUIDelegate, WKScriptMessageHandler, WKDownloadDelegate {
    weak var session: PortalSession?
    private var destinations: [ObjectIdentifier: URL] = [:]
    private var preview: FilePreview?

    init(session: PortalSession) { self.session = session }

    // Bridge the site's JS -> app.
    func userContentController(_ ucc: WKUserContentController, didReceive message: WKScriptMessage) {
        guard message.frameInfo.isMainFrame else { return }
        switch message.name {
        case "ic":
            if let s = message.body as? String { session?.onMessage(s) }
        case "icTouch":
            if let a = message.body as? [Any], a.count == 2 {
                session?.touch(busy: (a[0] as? Bool) ?? false, pans: (a[1] as? Bool) ?? false)
            }
        default:
            break
        }
    }

    /* ---- navigation ---- */

    func webView(_ w: WKWebView, decidePolicyFor action: WKNavigationAction,
                 decisionHandler: @escaping @MainActor (WKNavigationActionPolicy) -> Void) {
        if action.shouldPerformDownload { return decisionHandler(.download) }
        // new-window requests (target=_blank) arrive without a frame and are handled in createWebView
        guard let url = action.request.url, let frame = action.targetFrame, frame.isMainFrame else {
            return decisionHandler(.allow)
        }
        if isIitd(url) || ["about", "data", "blob"].contains(url.scheme ?? "") {
            decisionHandler(.allow)
        } else {
            openExternally(url)
            decisionHandler(.cancel)
        }
    }

    func webView(_ w: WKWebView, decidePolicyFor response: WKNavigationResponse,
                 decisionHandler: @escaping @MainActor (WKNavigationResponsePolicy) -> Void) {
        let http = response.response as? HTTPURLResponse
        let attachment = http?.value(forHTTPHeaderField: "Content-Disposition")?
            .lowercased().hasPrefix("attachment") ?? false
        // Moodle files and mail attachments are saved, not shown (PDFs too, as on Android)
        let pdf = response.response.mimeType == "application/pdf"
        decisionHandler(attachment || pdf || !response.canShowMIMEType ? .download : .allow)
    }

    func webView(_ w: WKWebView, didStartProvisionalNavigation nav: WKNavigation!) { session?.pageStarted() }
    func webView(_ w: WKWebView, didCommit nav: WKNavigation!) { session?.pageCommitted(w) }
    func webView(_ w: WKWebView, didFinish nav: WKNavigation!) { session?.pageFinished(w) }

    func webView(_ w: WKWebView, didFailProvisionalNavigation nav: WKNavigation!, withError error: Error) {
        let e = error as NSError
        // cancelled, or turned into a download / handed to another app
        if e.code == NSURLErrorCancelled || (e.domain == "WebKitErrorDomain" && e.code == 102) {
            session?.inject(w)
            return
        }
        session?.loadFailed(w, url: e.userInfo[NSURLErrorFailingURLErrorKey] as? URL, error: error)
    }

    func webViewWebContentProcessDidTerminate(_ w: WKWebView) { w.reload() }

    func webView(_ w: WKWebView, didReceive challenge: URLAuthenticationChallenge,
                 completionHandler: @escaping @MainActor (URLSession.AuthChallengeDisposition, URLCredential?) -> Void) {
        let (d, c) = IitdTrust.evaluate(challenge)
        completionHandler(d, c)
    }

    /* ---- windows, dialogs, camera ---- */

    func webView(_ w: WKWebView, createWebViewWith config: WKWebViewConfiguration,
                 for action: WKNavigationAction, windowFeatures: WKWindowFeatures) -> WKWebView? {
        // one window per tab: pop-ups open in place, other sites in their own app
        if let url = action.request.url {
            if isIitd(url) { w.load(action.request) } else { openExternally(url) }
        }
        return nil
    }

    func webView(_ w: WKWebView, runJavaScriptAlertPanelWithMessage message: String,
                 initiatedByFrame frame: WKFrameInfo, completionHandler: @escaping @MainActor () -> Void) {
        let a = UIAlertController(title: frame.securityOrigin.host, message: message, preferredStyle: .alert)
        a.addAction(UIAlertAction(title: "OK", style: .default) { _ in completionHandler() })
        present(a) { completionHandler() }
    }

    func webView(_ w: WKWebView, runJavaScriptConfirmPanelWithMessage message: String,
                 initiatedByFrame frame: WKFrameInfo, completionHandler: @escaping @MainActor (Bool) -> Void) {
        let a = UIAlertController(title: frame.securityOrigin.host, message: message, preferredStyle: .alert)
        a.addAction(UIAlertAction(title: "Cancel", style: .cancel) { _ in completionHandler(false) })
        a.addAction(UIAlertAction(title: "OK", style: .default) { _ in completionHandler(true) })
        present(a) { completionHandler(false) }
    }

    func webView(_ w: WKWebView, runJavaScriptTextInputPanelWithPrompt prompt: String, defaultText: String?,
                 initiatedByFrame frame: WKFrameInfo, completionHandler: @escaping @MainActor (String?) -> Void) {
        let a = UIAlertController(title: frame.securityOrigin.host, message: prompt, preferredStyle: .alert)
        a.addTextField { $0.text = defaultText }
        a.addAction(UIAlertAction(title: "Cancel", style: .cancel) { _ in completionHandler(nil) })
        a.addAction(UIAlertAction(title: "OK", style: .default) { [weak a] _ in completionHandler(a?.textFields?.first?.text) })
        present(a) { completionHandler(nil) }
    }

    private func present(_ vc: UIViewController, otherwise: () -> Void) {
        guard let top = topViewController() else { return otherwise() }
        top.present(vc, animated: true)
    }

    func webView(_ w: WKWebView, requestMediaCapturePermissionFor origin: WKSecurityOrigin,
                 initiatedByFrame frame: WKFrameInfo, type: WKMediaCaptureType,
                 decisionHandler: @escaping @MainActor (WKPermissionDecision) -> Void) {
        // only RollCall's QR scanner gets the camera
        decisionHandler(session?.portal == .rollcall && type == .camera ? .grant : .deny)
    }

    /* ---- downloads ---- */

    func webView(_ w: WKWebView, navigationAction: WKNavigationAction, didBecome download: WKDownload) {
        download.delegate = self
    }

    func webView(_ w: WKWebView, navigationResponse: WKNavigationResponse, didBecome download: WKDownload) {
        download.delegate = self
    }

    func download(_ download: WKDownload, decideDestinationUsing response: URLResponse,
                  suggestedFilename: String, completionHandler: @escaping @MainActor (URL?) -> Void) {
        let fm = FileManager.default
        let dir = fm.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("Downloads")
        try? fm.createDirectory(at: dir, withIntermediateDirectories: true)
        let name = suggestedFilename.isEmpty ? "download" : suggestedFilename
        let base = (name as NSString).deletingPathExtension, ext = (name as NSString).pathExtension
        var url = dir.appendingPathComponent(name)
        var n = 1
        while fm.fileExists(atPath: url.path) {
            url = dir.appendingPathComponent(ext.isEmpty ? "\(base) (\(n))" : "\(base) (\(n)).\(ext)")
            n += 1
        }
        destinations[ObjectIdentifier(download)] = url
        Toaster.shared.show("Downloading \(url.lastPathComponent)")
        completionHandler(url)
    }

    func download(_ download: WKDownload, didReceive challenge: URLAuthenticationChallenge,
                  completionHandler: @escaping @MainActor (URLSession.AuthChallengeDisposition, URLCredential?) -> Void) {
        let (d, c) = IitdTrust.evaluate(challenge)
        completionHandler(d, c)
    }

    func downloadDidFinish(_ download: WKDownload) {
        guard let url = destinations.removeValue(forKey: ObjectIdentifier(download)) else { return }
        let p = FilePreview(url: url)
        preview = p
        p.present()
    }

    func download(_ download: WKDownload, didFailWithError error: Error, resumeData: Data?) {
        destinations.removeValue(forKey: ObjectIdentifier(download))
        Toaster.shared.show("Couldn't download this file")
    }
}

/// Opens a finished download, with the share button for Files / other apps.
@MainActor
private final class FilePreview: NSObject, QLPreviewControllerDataSource {
    let url: URL
    init(url: URL) { self.url = url }

    func present() {
        let ql = QLPreviewController()
        ql.dataSource = self
        topViewController()?.present(ql, animated: true)
    }

    nonisolated func numberOfPreviewItems(in controller: QLPreviewController) -> Int { 1 }
    nonisolated func previewController(_ controller: QLPreviewController, previewItemAt index: Int) -> QLPreviewItem {
        MainActor.assumeIsolated { url as NSURL }
    }
}

/// moodle.iitd.ac.in and moodlenew.iitd.ac.in serve a broken chain: their *.iitd.ac.in
/// certificate is issued by "GlobalSign GCC R46 OV TLS CA 2025", but the server sends a
/// different (2018) intermediate. We ship that public intermediate (from
/// secure.globalsign.com, valid until 2029-06-23) and trust it for iitd.ac.in only.
/// Every other certificate check stays as normal.
enum IitdTrust {
    private static let intermediate: SecCertificate? = {
        guard let url = Bundle.main.url(forResource: "globalsign_gcc_r46_ov_tls_ca_2025", withExtension: "cer"),
              let data = try? Data(contentsOf: url) else { return nil }
        return SecCertificateCreateWithData(nil, data as CFData)
    }()

    static func evaluate(_ challenge: URLAuthenticationChallenge) -> (URLSession.AuthChallengeDisposition, URLCredential?) {
        let space = challenge.protectionSpace
        let host = space.host.lowercased()
        guard space.authenticationMethod == NSURLAuthenticationMethodServerTrust,
              host == "iitd.ac.in" || host.hasSuffix(".iitd.ac.in"),
              let trust = space.serverTrust, let intermediate else { return (.performDefaultHandling, nil) }
        if SecTrustEvaluateWithError(trust, nil) { return (.performDefaultHandling, nil) }
        // system roots plus the missing intermediate
        SecTrustSetAnchorCertificates(trust, [intermediate] as CFArray)
        SecTrustSetAnchorCertificatesOnly(trust, false)
        guard SecTrustEvaluateWithError(trust, nil) else { return (.performDefaultHandling, nil) }
        return (.useCredential, URLCredential(trust: trust))
    }
}
