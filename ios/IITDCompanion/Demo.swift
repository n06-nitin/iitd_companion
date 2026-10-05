import Foundation
import WebKit

/// Demo mode, for App Review and anyone without an IIT Delhi account.
///
/// Signing in with `Demo.user` / `Demo.password` points every tab at
/// `iitdemo://<same host>/<same path>` instead of `https://…`. `DemoServer`
/// answers those requests from bundled pages that copy the real login forms'
/// markup, so the app's own auto-login, CAPTCHA prompts and RollCall bar run
/// exactly as they do against the real portals. Nothing goes over the network.
enum Demo {
    static let scheme = "iitdemo"
    static let user = "demo"
    static let password = "review2026"

    static func isDemo(_ c: Creds) -> Bool {
        c.kerberos.lowercased() == user && c.password == password
    }

    /// The demo copy of a real portal address.
    static func url(_ real: URL) -> URL { swapScheme(real, to: scheme) }

    /// The real portal address behind a demo one ("Open in browser").
    static func real(_ url: URL) -> URL { url.scheme == scheme ? swapScheme(url, to: "https") : url }

    private static func swapScheme(_ url: URL, to s: String) -> URL {
        var c = URLComponents(url: url, resolvingAgainstBaseURL: false)
        c?.scheme = s
        return c?.url ?? url
    }
}

/// A tiny stand-in for the four portals' servers. Sign-in state lives in memory
/// per host, like a session cookie; it resets when the app restarts or the login
/// is forgotten. Pages are templates in Resources/Demo with `{{…}}` slots.
@MainActor
final class DemoServer: NSObject, WKURLSchemeHandler {
    static let shared = DemoServer()

    private static let moodleCaptcha = (question: "4 + 9", answer: "13")
    private static let rollcallCaptcha = "R7K4P"

    private var signedIn: Set<String> = []

    static func reset() { shared.signedIn = [] }

    func webView(_ webView: WKWebView, start task: WKURLSchemeTask) {
        guard let url = task.request.url else { return }
        let host = url.host ?? ""
        let path = url.path.isEmpty ? "/" : url.path
        let query = Dictionary(
            (URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems ?? []).map { ($0.name, $0.value ?? "") },
            uniquingKeysWith: { _, last in last }
        )

        let reply: Reply
        if path.hasPrefix("/_demo/") {
            reply = asset(url)
        } else {
            switch host {
            case "webmail.iitd.ac.in": reply = webmail(query)
            case "moodle.iitd.ac.in", "moodlenew.iitd.ac.in": reply = moodle(host, path, query)
            case "rollcall.iitd.ac.in": reply = rollcall(path)
            case "oauth.iitd.ac.in": reply = oauth(query)
            default: reply = .notFound
            }
        }
        send(reply, for: url, to: task)
    }

    func webView(_ webView: WKWebView, stop task: WKURLSchemeTask) {}

    /* ---- portals ---- */

    private func webmail(_ q: [String: String]) -> Reply {
        let host = "webmail.iitd.ac.in"
        switch q["_task"] {
        case "login":
            guard valid(q["_user"], q["_pass"]) else {
                return page("webmail-login", ["ERROR": #"<div class="error">Login failed.</div>"#])
            }
            signedIn.insert(host)
            return .redirect("/?_task=mail&_mbox=INBOX")
        case "logout":
            signedIn.remove(host)
            return page("webmail-login", ["ERROR": #"<div class="notice">Successfully logged out.</div>"#])
        default:
            return signedIn.contains(host) ? page("webmail") : page("webmail-login", ["ERROR": ""])
        }
    }

    private func moodle(_ host: String, _ path: String, _ q: [String: String]) -> Reply {
        let old = host == "moodle.iitd.ac.in"
        let captcha = old
            ? """
              <div class="captcha"><label for="valuepkg3">Please solve: <b>\(Self.moodleCaptcha.question) =</b></label>
              <input type="text" name="valuepkg3" id="valuepkg3" class="form-control" autocomplete="off"></div>
              """
            : ""
        func login(_ error: String) -> Reply {
            page("moodle-login", ["ERROR": error, "CAPTCHA": captcha, "REMEMBER": old ? "" : "hidden"])
        }
        switch path {
        case "/login/index.php":
            if signedIn.contains(host) { return .redirect("/my/") }
            guard q["username"] != nil else { return login("") }
            guard valid(q["username"], q["password"]) else {
                return login(#"<div class="alert alert-danger" id="loginerrormessage">Invalid login, please try again</div>"#)
            }
            if old && q["valuepkg3"]?.trimmingCharacters(in: .whitespaces) != Self.moodleCaptcha.answer {
                return login(#"<div class="alert alert-danger" id="loginerrormessage">Wrong answer to the security question, please try again</div>"#)
            }
            signedIn.insert(host)
            return .redirect("/my/")
        case "/login/logout.php":
            signedIn.remove(host)
            return .redirect("/")
        default:
            return signedIn.contains(host) ? page("moodle") : page("moodle-guest")
        }
    }

    private func rollcall(_ path: String) -> Reply {
        let host = "rollcall.iitd.ac.in"
        if path == "/logout.php" {
            signedIn.remove(host)
            return .redirect("/index.php")
        }
        return signedIn.contains(host) ? page("rollcall") : page("rollcall-landing")
    }

    private func oauth(_ q: [String: String]) -> Reply {
        func login(_ error: String) -> Reply { page("oauth-login", ["ERROR": error]) }
        guard q["username"] != nil else { return login("") }
        guard valid(q["username"], q["password"]) else { return login(#"<p class="error">Invalid username or password.</p>"#) }
        guard q["ct_captcha"]?.trimmingCharacters(in: .whitespaces).uppercased() == Self.rollcallCaptcha else {
            return login(#"<p class="error">The security code entered was incorrect.</p>"#)
        }
        signedIn.insert("rollcall.iitd.ac.in")
        return .redirect("\(Demo.scheme)://rollcall.iitd.ac.in/index.php")
    }

    private func valid(_ user: String?, _ pass: String?) -> Bool {
        user?.trimmingCharacters(in: .whitespaces).lowercased() == Demo.user && pass == Demo.password
    }

    /* ---- responses ---- */

    private enum Reply {
        case body(Data, mime: String, headers: [String: String] = [:])
        case redirect(String)
        case notFound
    }

    private func page(_ name: String, _ slots: [String: String] = [:]) -> Reply {
        guard let url = Bundle.main.url(forResource: name, withExtension: "html"),
              var html = try? String(contentsOf: url, encoding: .utf8) else { return .notFound }
        for (k, v) in slots { html = html.replacingOccurrences(of: "{{\(k)}}", with: v) }
        return .body(Data(html.utf8), mime: "text/html; charset=utf-8")
    }

    /// The bundled file behind a demo `/_demo/<file>` address.
    static func file(_ url: URL) -> URL? {
        guard url.scheme == Demo.scheme, url.path.hasPrefix("/_demo/") else { return nil }
        let file = String(url.path.dropFirst("/_demo/".count))
        let name = (file as NSString).deletingPathExtension, ext = (file as NSString).pathExtension
        guard !name.isEmpty, !name.contains("/") else { return nil }
        return Bundle.main.url(forResource: name, withExtension: ext)
    }

    private func asset(_ url: URL) -> Reply {
        guard let file = Self.file(url), let data = try? Data(contentsOf: file) else { return .notFound }
        let ext = file.pathExtension
        switch ext {
        case "css": return .body(data, mime: "text/css; charset=utf-8")
        case "svg": return .body(data, mime: "image/svg+xml")
        case "pdf": return .body(data, mime: "application/pdf",
                                 headers: ["Content-Disposition": "attachment; filename=\"\(file.lastPathComponent)\""])
        default: return .body(data, mime: "application/octet-stream")
        }
    }

    private func send(_ reply: Reply, for url: URL, to task: WKURLSchemeTask) {
        var status = 200
        var headers: [String: String] = [:]
        let data: Data
        switch reply {
        case let .body(d, mime, extra):
            data = d
            headers = extra
            headers["Content-Type"] = mime
        case let .redirect(to):
            // a page that moves on at once; custom schemes can't send a real 302
            data = Data("<!doctype html><script>location.replace(\(jsQuote(to)))</script>".utf8)
            headers["Content-Type"] = "text/html; charset=utf-8"
        case .notFound:
            status = 404
            data = Data("<!doctype html><p>Not found</p>".utf8)
            headers["Content-Type"] = "text/html; charset=utf-8"
        }
        headers["Content-Length"] = String(data.count)
        let response = HTTPURLResponse(url: url, statusCode: status, httpVersion: "HTTP/1.1", headerFields: headers)!
        task.didReceive(response)
        task.didReceive(data)
        task.didFinish()
    }
}
