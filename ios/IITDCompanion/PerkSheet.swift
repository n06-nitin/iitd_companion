import SwiftUI
import WebKit

/// IITD Perks Sheet: loaded from the 3-dot (⋮) menu over the tabs.
/// Dynamically fetches verified student perks from Cloudflare Worker,
/// falling back to GitHub and local bundled perks.html asset.
struct PerkSheet: View {
    let kerberos: String
    let onClose: () -> Void
    @StateObject private var coordinator: PerkWebCoordinator

    init(kerberos: String = "", onClose: @escaping () -> Void) {
        self.kerberos = kerberos
        self.onClose = onClose
        _coordinator = StateObject(wrappedValue: PerkWebCoordinator(kerberos: kerberos))
    }

    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 10) {
                StatusPill(label: "IITD Perks · Student Hub", dot: Caution)
                ClayIconButton(icon: "icon_refresh", description: "Reload") {
                    coordinator.fetchRemote()
                }
                ClayIconButton(icon: "icon_close", description: "Close", action: onClose)
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 10)

            // Thin loading bar
            Group {
                if coordinator.isLoading {
                    LoadBar(fraction: 0.7, color: Caution)
                } else {
                    Color.clear
                }
            }
            .frame(height: 2)
            .padding(.horizontal, 24)
            Spacer().frame(height: 6)

            PerkWebView(coordinator: coordinator)
                .clipShape(RoundedRectangle(cornerRadius: 24, style: .circular))
                .padding(.horizontal, 10)
                .padding(.bottom, 10)
        }
        .background(ClayBackground().ignoresSafeArea())
    }
}

private struct PerkWebView: UIViewRepresentable {
    @ObservedObject var coordinator: PerkWebCoordinator

    func makeUIView(context: Context) -> WKWebView {
        coordinator.createWebView()
    }

    func updateUIView(_ uiView: WKWebView, context: Context) {}
}

@MainActor
final class PerkWebCoordinator: NSObject, ObservableObject, WKNavigationDelegate, WKScriptMessageHandler {
    @Published var isLoading = false
    private var webView: WKWebView?
    let kerberos: String
    private let workerURL = "https://iitd-companion-worker.hellonitinbro.workers.dev/api/perks"
    private let remoteURL = "https://raw.githubusercontent.com/n06-nitin/iitd_companion/main/docs/perks.html"

    init(kerberos: String = "") {
        self.kerberos = kerberos
        super.init()
    }

    func createWebView() -> WKWebView {
        if let existing = webView { return existing }

        let config = WKWebViewConfiguration()
        let contentController = WKUserContentController()
        contentController.add(self, name: "copyText")
        contentController.add(self, name: "openBrowser")

        // Bridge for AndroidApp.copyText and AndroidApp.openBrowser compatibility
        let bridgeJS = """
        window.AndroidApp = {
            copyText: function(text) {
                window.webkit.messageHandlers.copyText.postMessage(text);
            },
            openBrowser: function(url) {
                window.webkit.messageHandlers.openBrowser.postMessage(url);
            }
        };
        """
        let userScript = WKUserScript(source: bridgeJS, injectionTime: .atDocumentStart, forMainFrameOnly: false)
        contentController.addUserScript(userScript)
        config.userContentController = contentController

        let wv = WKWebView(frame: .zero, configuration: config)
        wv.backgroundColor = UIColor(Ink.color)
        wv.isOpaque = false
        wv.scrollView.backgroundColor = UIColor(Ink.color)
        wv.navigationDelegate = self
        self.webView = wv

        // Render bundled local HTML immediately for instant 0ms display
        if let localURL = Bundle.main.url(forResource: "perks", withExtension: "html"),
           let localHtml = try? String(contentsOf: localURL, encoding: .utf8) {
            wv.loadHTMLString(localHtml, baseURL: Bundle.main.bundleURL)
        }

        // Fetch fresh copy from Worker / GitHub in background
        fetchRemote()

        return wv
    }

    func fetchRemote() {
        isLoading = true
        Task { [weak self] in
            guard let self else { return }
            defer { self.isLoading = false }
            guard let wv = self.webView else { return }

            // 1. Primary: Query Cloudflare Worker with user kerberos
            let userParam = self.kerberos.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
            let workerURLString = "\(self.workerURL)?user=\(userParam)&t=\(Int(Date().timeIntervalSince1970 * 1000))"

            if let fetchURL = URL(string: workerURLString) {
                var req = URLRequest(url: fetchURL)
                req.timeoutInterval = 6
                req.cachePolicy = .reloadIgnoringLocalAndRemoteCacheData
                req.setValue("no-cache", forHTTPHeaderField: "Cache-Control")
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
            var targetURLString = self.remoteURL
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
        } else if message.name == "openBrowser", let urlString = message.body as? String, let url = URL(string: urlString) {
            UIApplication.shared.open(url)
        }
    }
}
