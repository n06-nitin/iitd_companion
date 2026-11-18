import SwiftUI
import UIKit
import UniformTypeIdentifiers

let VpnAccent = Tone(0xFF56B8D6)
private let openVpnAppStoreURL = URL(string: "https://apps.apple.com/app/openvpn-connect/id590379981")!
private let maxOvpnBytes = 512 * 1024

struct VpnSheet: View {
    let onRequestVpn: () -> Void
    let onClose: () -> Void

    @State private var lastOvpn: String?
    @State private var stepsOpen: Bool
    @State private var picking = false

    init(onRequestVpn: @escaping () -> Void, onClose: @escaping () -> Void) {
        self.onRequestVpn = onRequestVpn
        self.onClose = onClose
        let saved = UserDefaults.standard.string(forKey: "lastOvpn")
        _lastOvpn = State(initialValue: saved)
        _stepsOpen = State(initialValue: saved == nil)
    }

    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 10) {
                StatusPill(label: "IITD VPN", dot: VpnAccent)
                ClayIconButton(icon: "icon_close", description: "Close", action: onClose)
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 10)

            GeometryReader { geo in
                ScrollView {
                    VStack(spacing: 0) {
                        Image("icon_vpn_key").renderingMode(.template).resizable()
                            .frame(width: 34, height: 34)
                            .foregroundStyle(VpnAccent.color)
                            .frame(width: 72, height: 72)
                            .clay(radius: 24, depth: 10)
                        Spacer().frame(height: 16)
                        Text("IITD VPN")
                            .m3(font: Display.extraBold(26), lineHeight: 30)
                            .foregroundStyle(Bone.color)
                        if let lastOvpn {
                            Spacer().frame(height: 4)
                            Text(lastOvpn)
                                .m3(13, lineHeight: 18)
                                .foregroundStyle(Muted.color)
                        }
                        Spacer().frame(height: 24)

                        ClayCard {
                            ClayButton(text: "Add .ovpn", color: VpnAccent, contentColor: Ink) {
                                picking = true
                            }
                            Spacer().frame(height: 12)
                            ClayButton(text: "OpenVPN Connect") {
                                openExternally(openVpnAppStoreURL)
                            }
                            Spacer().frame(height: 12)
                            ClayButton(text: "Request VPN", action: onRequestVpn)
                        }

                        Spacer().frame(height: 18)

                        Button {
                            withAnimation(composeSpring(stiffness: 400)) { stepsOpen.toggle() }
                        } label: {
                            HStack(spacing: 4) {
                                Text("Setup")
                                    .m3(13, weight: .medium, lineHeight: 18)
                                    .foregroundStyle(Muted.color)
                                Image(systemName: stepsOpen ? "chevron.up" : "chevron.down")
                                    .font(.system(size: 11, weight: .semibold))
                                    .foregroundStyle(Muted.color)
                            }
                            .padding(.horizontal, 12)
                            .padding(.vertical, 6)
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)

                        if stepsOpen {
                            VStack(spacing: 6) {
                                Text("1. Request VPN")
                                Text("2. Download .ovpn from Webmail")
                                Text("3. Add .ovpn & connect in OpenVPN")
                            }
                            .m3(13, lineHeight: 18)
                            .foregroundStyle(Muted.color)
                            .padding(.top, 8)
                            .transition(.opacity.combined(with: .move(edge: .top)))
                        }
                    }
                    .padding(.horizontal, 24)
                    .padding(.vertical, 20)
                    .frame(maxWidth: .infinity, minHeight: geo.size.height)
                }
                .scrollBounceBehavior(.basedOnSize)
            }
        }
        .background(ClayBackground().ignoresSafeArea())
        .fileImporter(isPresented: $picking, allowedContentTypes: [.item]) { result in
            switch result {
            case .success(let url):
                if let name = importOvpn(url) {
                    lastOvpn = name
                    UserDefaults.standard.set(name, forKey: "lastOvpn")
                    stepsOpen = false
                }
            case .failure:
                Toaster.shared.show("Couldn't open files")
            }
        }
    }

    @MainActor
    private func importOvpn(_ url: URL) -> String? {
        let accessed = url.startAccessingSecurityScopedResource()
        defer { if accessed { url.stopAccessingSecurityScopedResource() } }

        guard let data = try? Data(contentsOf: url), !data.isEmpty, data.count <= maxOvpnBytes else {
            Toaster.shared.show("Pick a .ovpn file")
            return nil
        }

        let name = url.lastPathComponent.isEmpty ? "iitd.ovpn" : url.lastPathComponent
        let text = String(data: data, encoding: .utf8) ?? ""
        let trimmed = text.trimmingCharacters(in: CharacterSet.whitespacesAndNewlines.union(CharacterSet(charactersIn: "\u{FEFF}"))).lowercased()
        if trimmed.hasPrefix("<html") || trimmed.hasPrefix("<!doctype") {
            Toaster.shared.show("Invalid .ovpn — re-download it")
            return nil
        }
        let looksLikeOvpn = text.localizedCaseInsensitiveContains("remote ") &&
            (text.contains("BEGIN CERTIFICATE") || text.localizedCaseInsensitiveContains("dev tun") || text.contains("client"))
        guard looksLikeOvpn else {
            Toaster.shared.show("Pick a valid .ovpn file")
            return nil
        }

        let fm = FileManager.default
        let dir = fm.temporaryDirectory.appendingPathComponent("vpn", isDirectory: true)
        try? fm.createDirectory(at: dir, withIntermediateDirectories: true)
        let safe = name.lowercased().hasSuffix(".ovpn") ? name : "\(name).ovpn"
        let dest = dir.appendingPathComponent(safe)
        try? fm.removeItem(at: dest)
        guard (try? data.write(to: dest)) != nil else {
            Toaster.shared.show("Couldn't read .ovpn file")
            return nil
        }

        let sheet = UIActivityViewController(activityItems: [dest], applicationActivities: nil)
        topViewController()?.present(sheet, animated: true)
        return name
    }
}
