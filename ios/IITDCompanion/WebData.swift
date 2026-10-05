import UIKit
import WebKit

/// Cookies and site data shared by every tab.
///
/// WKWebView drops session cookies (Moodle's MoodleSession, Roundcube's session…)
/// whenever the app process ends, which would sign every portal out on each
/// launch. So the iitd.ac.in session cookies are kept in a protected file when
/// the app goes to the background and put back before any tab loads.
@MainActor
enum WebData {
    private static var store: WKHTTPCookieStore { WKWebsiteDataStore.default().httpCookieStore }

    private static var vault: URL {
        let dir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir.appendingPathComponent("session-cookies.plist")
    }

    /// Persist session cookies so the portals stay signed in across launches.
    static func persist() {
        let task = UIApplication.shared.beginBackgroundTask(expirationHandler: nil)
        Task {
            let cookies = await store.allCookies().filter { $0.isSessionOnly && isIitd($0.domain) }
            let rows: [[String: Any]] = cookies.compactMap { c in
                guard let props = c.properties else { return nil }
                var row: [String: Any] = [:]
                for (k, v) in props where v is String || v is NSNumber || v is Date || v is Data {
                    row[k.rawValue] = v
                }
                return row
            }
            if let data = try? PropertyListSerialization.data(fromPropertyList: rows, format: .binary, options: 0) {
                try? data.write(to: vault, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
            }
            UIApplication.shared.endBackgroundTask(task)
        }
    }

    /// Put the saved session cookies back; call before any tab loads.
    static func restore() async {
        guard let data = try? Data(contentsOf: vault),
              let rows = try? PropertyListSerialization.propertyList(from: data, format: nil) as? [[String: Any]]
        else { return }
        let existing = Set(await store.allCookies().map { "\($0.name)|\($0.domain)|\($0.path)" })
        for row in rows {
            let props = Dictionary(uniqueKeysWithValues: row.map { (HTTPCookiePropertyKey($0.key), $0.value) })
            guard let c = HTTPCookie(properties: props),
                  !existing.contains("\(c.name)|\(c.domain)|\(c.path)") else { continue }
            await store.setCookie(c)
        }
    }

    /// Sign out everywhere: every cookie, cache and storage of every portal.
    static func clear() async {
        DemoServer.reset()
        try? FileManager.default.removeItem(at: vault)
        let all = WKWebsiteDataStore.allWebsiteDataTypes()
        await WKWebsiteDataStore.default().removeData(ofTypes: all, modifiedSince: .distantPast)
        HTTPCookieStorage.shared.removeCookies(since: .distantPast)
    }

    private static func isIitd(_ domain: String) -> Bool {
        let d = domain.hasPrefix(".") ? String(domain.dropFirst()) : domain
        return d == "iitd.ac.in" || d.hasSuffix(".iitd.ac.in")
    }
}
