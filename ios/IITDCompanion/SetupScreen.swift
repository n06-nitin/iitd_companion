import SwiftUI

struct SetupScreen: View {
    let initial: Creds?
    let onSaved: (Creds) -> Void
    let onCancel: (() -> Void)?

    @State private var kerberos: String
    @State private var password: String
    @State private var show = false
    @State private var error: String?
    @State private var visible = false
    @State private var contentHeight: CGFloat = 0

    init(initial: Creds?, onSaved: @escaping (Creds) -> Void, onCancel: (() -> Void)?) {
        self.initial = initial
        self.onSaved = onSaved
        self.onCancel = onCancel
        _kerberos = State(initialValue: initial?.kerberos ?? "")
        _password = State(initialValue: initial?.password ?? "")
    }

    var body: some View {
        GeometryReader { geo in
            ScrollView {
                content
                    .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { contentHeight = $0 }
                    .opacity(visible ? 1 : 0)
                    .offset(y: visible ? 0 : contentHeight / 8)
                    .padding(24)
                    .frame(maxWidth: .infinity, minHeight: geo.size.height)
            }
            .scrollBounceBehavior(.basedOnSize)
            .scrollDismissesKeyboard(.interactively)
        }
        .onAppear { withAnimation(tween(0.5)) { visible = true } }
    }

    private var content: some View {
        VStack(spacing: 0) {
            Image("icon_school").renderingMode(.template).resizable()
                .frame(width: 42, height: 42)
                .foregroundStyle(Bone.color)
                .frame(width: 84, height: 84)
                .clay(radius: 26, depth: 12)
            Spacer().frame(height: 24)
            Text("IITD Companion")
                .m3(font: Display.extraBold(34), lineHeight: 38)
                .foregroundStyle(Bone.color)
                .multilineTextAlignment(.center)
            Spacer().frame(height: 6)
            Text("One login for all your portals")
                .m3(15)
                .foregroundStyle(Muted.color)
                .multilineTextAlignment(.center)
            Spacer().frame(height: 14)
            PortalDots()
            Spacer().frame(height: 28)

            ClayCard {
                Field(text: $kerberos, label: "Kerberos ID", placeholder: "e.g. me2240525", contentType: .username) {
                    Color.clear.frame(width: 16)
                }
                Spacer().frame(height: 14)
                Field(text: $password, label: "Password", placeholder: "Kerberos password",
                      secure: !show, contentType: .password) {
                    Text(show ? "Hide" : "Show")
                        .m3(13, weight: .semibold)
                        .foregroundStyle(Muted.color)
                        .padding(.horizontal, 12)
                        .padding(.vertical, 8)
                        .contentShape(Rectangle())
                        .onTapGesture { show.toggle() }
                        .padding(.trailing, 4)
                }

                if let error {
                    Text(error)
                        .m3(13)
                        .foregroundStyle(Danger.color)
                        .padding(.top, 12)
                        .padding(.leading, 4)
                        .transition(.opacity.combined(with: .move(edge: .top)))
                }

                Spacer().frame(height: 22)
                ClayButton(text: "Save & continue", color: Bone, contentColor: Ink) {
                    if kerberos.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                        || password.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                        error = "Enter your Kerberos ID and password."
                    } else {
                        onSaved(Creds(kerberos: kerberos.trimmingCharacters(in: .whitespacesAndNewlines), password: password))
                    }
                }
                if let onCancel {
                    Spacer().frame(height: 8)
                    Text("Cancel")
                        .m3(14)
                        .foregroundStyle(Muted.color)
                        .padding(10)
                        .contentShape(Rectangle())
                        .onTapGesture { onCancel() }
                        .frame(maxWidth: .infinity)
                }
            }
            .animation(composeSpring(stiffness: 400), value: error)
            .onChange(of: kerberos) { _, _ in error = nil }
            .onChange(of: password) { _, _ in error = nil }

            Spacer().frame(height: 22)
            Text("Saved only on this phone")
                .m3(12)
                .foregroundStyle(Muted.color)
                .multilineTextAlignment(.center)
            Spacer().frame(height: 4)
            Text("Unofficial · not affiliated with IIT Delhi")
                .m3(11)
                .foregroundStyle(Faint.color)
                .multilineTextAlignment(.center)
            Text("Privacy policy")
                .m3(11)
                .underline()
                .foregroundStyle(Muted.color)
                .padding(.horizontal, 10)
                .contentShape(Rectangle())
                .onTapGesture { openExternally(privacyPolicyURL) }
        }
    }
}

/// The four portals, each in its own hue — the same colours the tab dock uses.
private struct PortalDots: View {
    var body: some View {
        HStack(spacing: 10) {
            ForEach(Portal.allCases) { p in
                ClaySlab(color: p.accent, radius: 4, depth: 0)
                    .clipShape(Circle())
                    .frame(width: 8, height: 8)
            }
        }
    }
}
