import Observation
import SwiftUI
import WebKit

let PerkAccent = Caution

let workerPerksURL = "https://iitd-companion-worker.hellonitinbro.workers.dev/api/perks"
private let remotePerksURL = "https://raw.githubusercontent.com/n06-nitin/iitd_companion/main/docs/perks.html"

private let carouselTouchJS = """
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
        if (window.webkit && window.webkit.messageHandlers && window.webkit.messageHandlers.touchBusy) {
            window.webkit.messageHandlers.touchBusy.postMessage(busy);
        }
    }, { passive: true, capture: true });
}
"""

/// Holds the embedded IITD Perks WKWebView and remote refresh state.
@Observable @MainActor
final class PerkWebCoordinator: NSObject, WKNavigationDelegate, WKScriptMessageHandler {
    private(set) var isLoading = false
    @ObservationIgnored var touchBusy = false
    @ObservationIgnored private(set) var webView: WKWebView?
    @ObservationIgnored private var loadedOnce = false
    let kerberos: String

    init(kerberos: String = "") {
        self.kerberos = kerberos
        super.init()
    }

    func touchStarted() {
        touchBusy = false
    }

    func createWebView() -> WKWebView {
        if let existing = webView { return existing }

        let config = WKWebViewConfiguration()
        let contentController = WKUserContentController()
        contentController.add(self, name: "copyText")
        contentController.add(self, name: "openBrowser")
        contentController.add(self, name: "touchBusy")

        // Bridge for AndroidApp.copyText and AndroidApp.openExternal/openBrowser compatibility
        let bridgeJS = """
        window.AndroidApp = {
            copyText: function(text) {
                window.webkit.messageHandlers.copyText.postMessage(text);
            },
            openExternal: function(url) {
                window.webkit.messageHandlers.openBrowser.postMessage(url);
            },
            openBrowser: function(url) {
                window.webkit.messageHandlers.openBrowser.postMessage(url);
            }
        };
        """
        contentController.addUserScript(WKUserScript(source: bridgeJS, injectionTime: .atDocumentStart, forMainFrameOnly: false))
        config.userContentController = contentController

        let wv = WKWebView(frame: .zero, configuration: config)
        wv.backgroundColor = UIColor(Ink.color)
        wv.isOpaque = false
        wv.scrollView.backgroundColor = UIColor(Ink.color)
        if #available(iOS 26.0, *) {
            // no Liquid Glass blur/fade where the page meets the card's edges: the page shows as-is
            let sv = wv.scrollView
            for edge in [sv.topEdgeEffect, sv.bottomEdgeEffect, sv.leftEdgeEffect, sv.rightEdgeEffect] {
                edge.isHidden = true
            }
        }
        wv.navigationDelegate = self
        self.webView = wv

        // Render bundled local HTML immediately for instant 0ms display
        if let localURL = Bundle.main.url(forResource: "perks", withExtension: "html"),
           let localHtml = try? String(contentsOf: localURL, encoding: .utf8) {
            wv.loadHTMLString(localHtml, baseURL: Bundle.main.bundleURL)
        }

        ensureLoaded()
        return wv
    }

    func ensureLoaded() {
        guard !loadedOnce else { return }
        loadedOnce = true
        fetchRemote()
    }

    func fetchRemote() {
        isLoading = true
        Task { [weak self] in
            guard let self else { return }
            defer { self.isLoading = false }
            guard let wv = self.webView else { return }

            // 1. Primary: Query Cloudflare Worker with user kerberos
            let userParam = self.kerberos.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
            let workerURLString = "\(workerPerksURL)?user=\(userParam)&t=\(Int(Date().timeIntervalSince1970 * 1000))"

            if let fetchURL = URL(string: workerURLString) {
                var req = URLRequest(url: fetchURL)
                req.timeoutInterval = 6
                req.cachePolicy = .reloadIgnoringLocalAndRemoteCacheData
                req.setValue("no-cache, no-store, must-revalidate", forHTTPHeaderField: "Cache-Control")
                req.setValue("Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) IITDCompanion-iOS", forHTTPHeaderField: "User-Agent")

                if let (htmlData, resp) = try? await URLSession.shared.data(for: req),
                   let httpResp = resp as? HTTPURLResponse, httpResp.statusCode == 200,
                   let html = String(data: htmlData, encoding: .utf8),
                   html.localizedCaseInsensitiveContains("<html") {
                    wv.loadHTMLString(html, baseURL: Bundle.main.bundleURL)
                    return
                }
            }

            // 2. Fallback: Query latest commit SHA to completely bypass edge CDN cache
            var targetURLString = remotePerksURL
            if let apiURL = URL(string: "https://api.github.com/repos/n06-nitin/iitd_companion/commits/main") {
                var req = URLRequest(url: apiURL)
                req.timeoutInterval = 4
                req.setValue("IITDCompanion-iOS", forHTTPHeaderField: "User-Agent")
                if let (data, _) = try? await URLSession.shared.data(for: req),
                   let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                   let sha = json["sha"] as? String, !sha.isEmpty {
                    targetURLString = "https://raw.githubusercontent.com/n06-nitin/iitd_companion/\(sha)/docs/perks.html"
                }
            }

            // 3. Fallback: Fetch fresh HTML from GitHub
            guard let fetchURL = URL(string: targetURLString) else { return }
            var req = URLRequest(url: fetchURL)
            req.timeoutInterval = 8
            req.cachePolicy = .reloadIgnoringLocalAndRemoteCacheData
            req.setValue("no-cache", forHTTPHeaderField: "Cache-Control")
            req.setValue("no-cache", forHTTPHeaderField: "Pragma")

            if let (htmlData, resp) = try? await URLSession.shared.data(for: req),
               let httpResp = resp as? HTTPURLResponse, httpResp.statusCode == 200,
               let html = String(data: htmlData, encoding: .utf8),
               html.localizedCaseInsensitiveContains("<html") {
                wv.loadHTMLString(html, baseURL: Bundle.main.bundleURL)
            }
        }
    }

    func scrollToTop() {
        webView?.evaluateJavaScript("window.scrollTo({top: 0, behavior: 'smooth'})", completionHandler: nil)
    }

    func destroy() {
        webView?.configuration.userContentController.removeAllScriptMessageHandlers()
        webView?.stopLoading()
        webView = nil
    }

    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        webView.evaluateJavaScript(carouselTouchJS, completionHandler: nil)
    }

    // Intercept external links and mailto
    func webView(_ webView: WKWebView, decidePolicyFor navigationAction: WKNavigationAction,
                 decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
        guard let url = navigationAction.request.url else {
            decisionHandler(.allow)
            return
        }

        let scheme = url.scheme?.lowercased() ?? ""
        if scheme == "mailto" {
            UIApplication.shared.open(url)
            decisionHandler(.cancel)
            return
        }

        // Only intercept explicit link taps by the user to open Safari
        if navigationAction.navigationType == .linkActivated {
            if scheme == "http" || scheme == "https" {
                UIApplication.shared.open(url)
                decisionHandler(.cancel)
                return
            }
        }

        decisionHandler(.allow)
    }

    func userContentController(_ userContentController: WKUserContentController, didReceive message: WKScriptMessage) {
        if message.name == "copyText", let text = message.body as? String {
            UIPasteboard.general.string = text
            Toaster.shared.show("Link copied to clipboard")
        } else if message.name == "openBrowser", let urlString = message.body as? String, let url = URL(string: urlString) {
            UIApplication.shared.open(url)
        } else if message.name == "touchBusy", let busy = message.body as? Bool {
            touchBusy = busy
        }
    }
}
