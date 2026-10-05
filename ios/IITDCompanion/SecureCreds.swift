import Foundation
import Security

/// Credentials live in the iOS Keychain, readable only on this device once it
/// has been unlocked after boot. They never sync to iCloud or leave the phone.
struct Creds: Equatable, Hashable {
    let kerberos: String
    let password: String
}

enum SecureCreds {
    private static let service = "rch_secure"
    private static let installedKey = "installed"

    static func save(_ c: Creds) {
        write("u", c.kerberos)
        write("p", c.password)
    }

    static func load() -> Creds? {
        // Keychain items survive an uninstall; a fresh install must start signed out.
        let defaults = UserDefaults.standard
        if !defaults.bool(forKey: installedKey) {
            clear()
            defaults.set(true, forKey: installedKey)
            return nil
        }
        guard let u = read("u"), let p = read("p") else { return nil }
        return Creds(kerberos: u, password: p)
    }

    static func clear() {
        SecItemDelete([kSecClass: kSecClassGenericPassword, kSecAttrService: service] as CFDictionary)
    }

    private static func write(_ account: String, _ value: String) {
        let query: [CFString: Any] = [
            kSecClass: kSecClassGenericPassword, kSecAttrService: service, kSecAttrAccount: account,
        ]
        SecItemDelete(query as CFDictionary)
        var add = query
        add[kSecValueData] = Data(value.utf8)
        add[kSecAttrAccessible] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        SecItemAdd(add as CFDictionary, nil)
    }

    private static func read(_ account: String) -> String? {
        let query: [CFString: Any] = [
            kSecClass: kSecClassGenericPassword, kSecAttrService: service, kSecAttrAccount: account,
            kSecReturnData: true, kSecMatchLimit: kSecMatchLimitOne,
        ]
        var out: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &out) == errSecSuccess, let data = out as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }
}
