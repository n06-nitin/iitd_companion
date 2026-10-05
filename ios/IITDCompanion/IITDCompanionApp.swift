import SwiftUI

@main
struct IITDCompanionApp: App {
    @Environment(\.scenePhase) private var phase

    var body: some Scene {
        WindowGroup {
            AppRoot()
                .preferredColorScheme(.dark)
        }
        .onChange(of: phase) { _, p in
            // keep session cookies so the portals stay signed in across launches
            if p == .background { WebData.persist() }
        }
    }
}

/* --------------------------------- app --------------------------------- */
private struct AppRoot: View {
    @State private var creds: Creds?
    @State private var editing: Bool
    @State private var ready = false

    init() {
        #if DEBUG
        // simulator aid: -ICDebugCreds user:password saves a login at launch
        if let pair = UserDefaults.standard.string(forKey: "ICDebugCreds"), let i = pair.firstIndex(of: ":") {
            _ = SecureCreds.load()
            SecureCreds.save(Creds(kerberos: String(pair[..<i]), password: String(pair[pair.index(after: i)...])))
        }
        #endif
        let c = SecureCreds.load()
        _creds = State(initialValue: c)
        _editing = State(initialValue: c == nil)
    }

    var body: some View {
        GeometryReader { geo in
            ZStack {
                ClayBackground().ignoresSafeArea()
                if ready {
                    let transition = AnyTransition.asymmetric(
                        insertion: .opacity.combined(with: .offset(y: geo.size.height / 10)).animation(tween(0.4)),
                        removal: .opacity.animation(tween(0.2))
                    )
                    if !editing, let c = creds {
                        CompanionScreen(
                            creds: c,
                            onEditCreds: { withAnimation(tween(0.4)) { editing = true } },
                            onForget: {
                                SecureCreds.clear()
                                Task {
                                    await WebData.clear()
                                    withAnimation(tween(0.4)) { creds = nil; editing = true }
                                }
                            }
                        )
                        .transition(transition)
                    } else {
                        SetupScreen(
                            initial: creds,
                            onSaved: { new in
                                Task {
                                    // a different account must not inherit the old one's sessions
                                    if new.kerberos != creds?.kerberos { await WebData.clear() }
                                    SecureCreds.save(new)
                                    withAnimation(tween(0.4)) { creds = new; editing = false }
                                }
                            },
                            onCancel: creds != nil ? { withAnimation(tween(0.4)) { editing = false } } : nil
                        )
                        .transition(transition)
                    }
                }
                ToastOverlay()
            }
        }
        .task {
            await WebData.restore()
            ready = true
        }
    }
}
