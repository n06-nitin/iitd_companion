import AVFoundation
import SwiftUI

/// Every tab's session, the swiper, and which tabs have a live WebView.
@Observable @MainActor
final class CompanionModel {
    @ObservationIgnored let sessions: [Portal: PortalSession]
    @ObservationIgnored let swiper: TabSwiper
    /// Tabs with a live WebView. Only the open tab gets one at launch; once its page
    /// is up the rest warm up one at a time (sign-in-by-themselves portals first), so
    /// no WebView is ever built in the middle of a swipe.
    private(set) var opened: [Portal]

    init(creds: Creds) {
        sessions = Dictionary(uniqueKeysWithValues: Portal.allCases.map { ($0, PortalSession(portal: $0, creds: creds)) })
        let last = UserDefaults.standard.string(forKey: "lastTab").flatMap(Portal.init(rawValue:))
        let first = last ?? Portal.allCases[0]
        swiper = TabSwiper(initial: first)
        opened = [first]
    }

    func session(_ p: Portal) -> PortalSession { sessions[p]! }

    func open(_ p: Portal) { if !opened.contains(p) { opened.append(p) } }

    func warmUp() async {
        let first = session(swiper.current)
        for _ in 0..<60 where !first.painted { try? await Task.sleep(for: .milliseconds(50)) }
        // stable sort: auto sign-in portals first, otherwise in tab order
        let order = Portal.allCases.filter(\.autoLogin) + Portal.allCases.filter { !$0.autoLogin }
        for p in order where !opened.contains(p) {
            try? await Task.sleep(for: .milliseconds(700))
            if Task.isCancelled { return }
            open(p)
        }
    }

    func destroy() { sessions.values.forEach { $0.destroy() } }
}

struct CompanionScreen: View {
    let onEditCreds: () -> Void
    let onForget: () -> Void

    @State private var model: CompanionModel
    @State private var askedCamera = false
    @State private var keyboardUp = false

    init(creds: Creds, onEditCreds: @escaping () -> Void, onForget: @escaping () -> Void) {
        self.onEditCreds = onEditCreds
        self.onForget = onForget
        _model = State(initialValue: CompanionModel(creds: creds))
    }

    var body: some View {
        let swiper = model.swiper
        let tab = swiper.current
        let session = model.session(tab)
        VStack(spacing: 0) {
            TopBar(session: model.session(swiper.visual), onEditCreds: onEditCreds, onForget: onForget)
                .swipeTabs(swiper)

            PagesView(model: model, opened: model.opened)
                .clipShape(RoundedRectangle(cornerRadius: 24, style: .circular))
                .padding(.horizontal, 10)

            if !keyboardUp {
                if tab == .rollcall { RollCallBar(session: session) }
                TabBar(model: model) { p in
                    // tapping the open tab again goes back to that portal's home page
                    if p == swiper.current { model.session(p).home() } else { swiper.goTo(p) }
                }
            }
        }
        .task {
            #if DEBUG
            if UserDefaults.standard.object(forKey: "ICDebugTurn") != nil {
                let f = UserDefaults.standard.double(forKey: "ICDebugTurn")
                Portal.allCases.forEach(model.open)
                try? await Task.sleep(for: .seconds(UserDefaults.standard.double(forKey: "ICDebugTurnDelay")))
                swiper.freeze(at: f)
                return
            }
            #endif
            await model.warmUp()
        }
        // the tab being swiped in needs a WebView too
        .onChange(of: swiper.target) { _, t in if let t { model.open(t) } }
        .onChange(of: swiper.current, initial: true) { _, t in
            model.open(t)
            model.session(t).refreshStaleCaptcha()
            UserDefaults.standard.set(t.rawValue, forKey: "lastTab")
            if t == .rollcall && !askedCamera && AVCaptureDevice.authorizationStatus(for: .video) == .notDetermined {
                askedCamera = true
                // granted or not; the site's scanner asks again through WebKit
                AVCaptureDevice.requestAccess(for: .video) { _ in }
            }
        }
        .onReceive(NotificationCenter.default.publisher(for: UIResponder.keyboardWillShowNotification)) { _ in
            keyboardUp = true
        }
        .onReceive(NotificationCenter.default.publisher(for: UIResponder.keyboardWillHideNotification)) { _ in
            keyboardUp = false
        }
        .onDisappear { model.destroy() }
    }
}

@MainActor private func statusOf(_ s: PortalSession) -> (String, Tone) {
    if s.loginFailed { return ("Sign-in failed · check password", Danger) }
    switch s.page {
    case "loading": return ("Loading \(s.portal.label)…", Caution)
    case "landing", "loggedout": return ("Opening login…", Caution)
    case "login" where s.captchaPending:
        return (s.portal == .rollcall ? "Type the CAPTCHA, then Login" : "Answer the CAPTCHA, then Go", s.portal.accent)
    case "login": return ("Signing in…", Caution)
    case "home": return ("Pick a course below", s.portal.accent)
    default: return (s.portal.label, s.portal.accent)
    }
}

private struct TopBar: View {
    let session: PortalSession
    let onEditCreds: () -> Void
    let onForget: () -> Void

    var body: some View {
        let (label, dot) = statusOf(session)
        VStack(spacing: 0) {
            HStack(spacing: 10) {
                StatusPill(label: label, dot: dot)
                ClayIconButton(icon: "icon_refresh", description: "Reload") { session.reload() }
                Menu {
                    Button("Open in browser") {
                        openExternally(session.webView?.url ?? session.portal.home)
                    }
                    Button("Edit saved login", action: onEditCreds)
                    Button("Forget login & sign out", action: onForget)
                    Button("Privacy policy") { openExternally(privacyPolicyURL) }
                } label: {
                    ClayIconFace {
                        Image("icon_more_vert").renderingMode(.template).resizable().frame(width: 20, height: 20)
                    }
                }
                .accessibilityLabel("More")
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 10)
            // thin page-load bar in the portal's hue; the spacer keeps the layout from jumping
            Group {
                if (1...99).contains(session.progress) {
                    LoadBar(fraction: CGFloat(session.progress) / 100, color: session.portal.accent)
                } else {
                    Color.clear
                }
            }
            .frame(height: 2)
            .padding(.horizontal, 24)
            Spacer().frame(height: 6)
        }
    }
}

/// Material 3's determinate linear indicator: rounded bar plus the stop dot at the end.
private struct LoadBar: View {
    let fraction: CGFloat
    let color: Tone
    var body: some View {
        GeometryReader { g in
            ZStack(alignment: .leading) {
                Capsule().fill(color.color).frame(width: max(g.size.height, g.size.width * fraction))
                Circle().fill(color.color).frame(width: g.size.height).frame(maxWidth: .infinity, alignment: .trailing)
            }
        }
        .clipShape(Capsule())
    }
}

/// Floating clay dock. The raised pill slides with the finger, stretches
/// mid-swipe and shifts from one portal's hue to the next.
private struct TabBar: View {
    let model: CompanionModel
    let onSelect: (Portal) -> Void
    private let dockHeight: CGFloat = 56

    var body: some View {
        let swiper = model.swiper
        ZStack {
            Pills(swiper: swiper, height: dockHeight)
            HStack(spacing: 0) {
                ForEach(Portal.allCases) { p in
                    TabItem(portal: p, session: model.session(p), active: p == swiper.visual)
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                        .contentShape(Rectangle())
                        .onTapGesture { onSelect(p) }
                }
            }
        }
        .frame(height: dockHeight)
        .clipShape(RoundedRectangle(cornerRadius: 22, style: .circular))
        .padding(6)
        .contentShape(Rectangle())
        .swipeTabs(swiper)
        .clay(radius: 28, depth: 12)
        .padding(.horizontal, 12)
        .padding(.vertical, 10)
    }
}

private struct Pills: View {
    let swiper: TabSwiper
    let height: CGFloat

    var body: some View {
        GeometryReader { g in
            let count = CGFloat(Portal.allCases.count)
            let itemWidth = g.size.width / count
            let progress = swiper.progress
            let stretch = sin(.pi * progress)
            let w = (itemWidth - 6) + 22 * stretch
            let color = Tone.lerp(swiper.current.accent, (swiper.target ?? swiper.current).accent, progress)
            let indicator = swiper.indicator()
            // one pill per lap: when the ring wraps, it slides off one end and in at the other
            ForEach(-1...1, id: \.self) { lap in
                ClaySlab(color: color, radius: 22, depth: 6)
                    .frame(width: w, height: height)
                    .position(x: itemWidth * (indicator + CGFloat(lap) * count + 0.5), y: height / 2)
            }
        }
    }
}

private struct TabItem: View {
    let portal: Portal
    let session: PortalSession
    let active: Bool

    var body: some View {
        // needs you: waiting for a CAPTCHA, or auto sign-in failed
        let badge: Tone? = session.loginFailed ? Danger : (session.captchaPending && !active ? portal.accent : nil)
        VStack(spacing: 0) {
            Image(portal.icon).renderingMode(.template).resizable()
                .frame(width: 22, height: 22)
                .foregroundStyle((active ? Ink : Muted).color)
                .overlay(alignment: .topTrailing) {
                    if let badge {
                        ZStack {
                            Circle().fill((active ? badge : Clay).color)
                            Circle().fill(badge.color).padding(1.5)
                        }
                        .frame(width: 9, height: 9)
                        .offset(x: 5, y: -3)
                    }
                }
            Spacer().frame(height: 3)
            Text(portal.label)
                .m3(font: Display.semiBold(11))
                .foregroundStyle((active ? Ink : Muted).color)
                .lineLimit(1)
                .truncationMode(.tail)
                .padding(.horizontal, 4)
        }
    }
}

/* ------------------------------- rollcall ------------------------------ */
private struct RollCallBar: View {
    let session: PortalSession

    var body: some View {
        let onHomeArea = session.page == "home"
        let accent = Portal.rollcall.accent
        VStack(spacing: 0) {
            if onHomeArea {
                Group {
                    if session.courses.isEmpty {
                        Text("Loading your courses…")
                            .m3(13)
                            .foregroundStyle(Muted.color)
                            .padding(.vertical, 10)
                            .padding(.horizontal, 4)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    } else {
                        CourseRow(courses: session.courses, selected: session.selected, accent: accent) { code in
                            session.run(RollCallScript.selectCourse(code))
                        }
                    }
                }
                .transition(.opacity.combined(with: .move(edge: .bottom)))
            }

            HStack(spacing: 10) {
                if onHomeArea {
                    ClayButton(text: "Mark", color: accent, contentColor: Ink, enabled: session.selected != nil) {
                        session.run(RollCallScript.markAttendance())
                    }
                    ClayButton(text: "Verify", color: Caution, contentColor: Ink, enabled: session.selected != nil) {
                        session.run(RollCallScript.verify())
                    }
                    ClayButton(text: "Log out", contentColor: Danger) { session.logout() }
                } else {
                    ClayButton(text: "Home") { session.home() }
                    ClayButton(text: "Log out", contentColor: Danger) { session.logout() }
                }
            }
        }
        .padding(.horizontal, 12)
        .padding(.top, 10)
        .animation(composeSpring(stiffness: 400), value: onHomeArea)
    }
}

/// The course chips; the row's edge fades wherever more chips are hidden, so it reads as scrollable.
private struct CourseRow: View {
    let courses: [String]
    let selected: String?
    let accent: Tone
    let onPick: (String) -> Void

    @State private var viewport: CGFloat = 0
    @State private var content: CGRect = .zero

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 10) {
                ForEach(courses, id: \.self) { code in
                    CourseChip(code: code, active: code == selected, accent: accent) { onPick(code) }
                }
            }
            .padding(.vertical, 10)
            .padding(.horizontal, 4)
            .onGeometryChange(for: CGRect.self) { $0.frame(in: .named("chips")) } action: { content = $0 }
        }
        .coordinateSpace(name: "chips")
        .onGeometryChange(for: CGFloat.self) { $0.size.width } action: { viewport = $0 }
        .mask {
            let fade = viewport > 0 ? min(28 / viewport, 0.5) : 0
            let back = content.minX < -0.5, forward = content.maxX > viewport + 0.5
            LinearGradient(stops: [
                .init(color: back ? .clear : .black, location: 0),
                .init(color: .black, location: fade),
                .init(color: .black, location: 1 - fade),
                .init(color: forward ? .clear : .black, location: 1),
            ], startPoint: .leading, endPoint: .trailing)
        }
    }
}

private struct CourseChip: View {
    let code: String
    let active: Bool
    let accent: Tone
    let onClick: () -> Void

    var body: some View {
        Text(code)
            .m3(14, weight: .semibold)
            .foregroundStyle((active ? Ink : Bone).color)
            .padding(.horizontal, 18)
            .padding(.vertical, 10)
            .clay(active ? accent : Clay, radius: 18, depth: active ? 6 : 4)
            .contentShape(RoundedRectangle(cornerRadius: 18))
            .onTapGesture(perform: onClick)
    }
}
