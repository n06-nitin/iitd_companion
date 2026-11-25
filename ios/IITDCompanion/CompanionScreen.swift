import AVFoundation
import SwiftUI

/// Every tab's session, the swiper, the Perks coordinator, and which tabs have a live WebView.
@Observable @MainActor
final class CompanionModel {
    @ObservationIgnored let creds: Creds
    @ObservationIgnored let sessions: [Portal: PortalSession]
    @ObservationIgnored let perks: PerkWebCoordinator
    @ObservationIgnored let swiper: TabSwiper
    private(set) var dock: [Portal]
    /// Tabs with a live WebView. Only the open tab gets one at launch; once its page
    /// is up the rest warm up one at a time (sign-in-by-themselves portals first), so
    /// no WebView is ever built in the middle of a swipe.
    private(set) var opened: [Portal]
    private(set) var perksLoaded = false

    var more: [Portal] { Portal.pickable.filter { !dock.contains($0) } }

    init(creds: Creds) {
        self.creds = creds
        let initialDock = loadDock()
        dock = initialDock
        sessions = Dictionary(uniqueKeysWithValues: Portal.pickable.map { ($0, PortalSession(portal: $0, creds: creds)) })
        perks = PerkWebCoordinator(kerberos: creds.kerberos)
        let last = UserDefaults.standard.string(forKey: "lastTab").flatMap(Portal.init(rawValue:)).flatMap { initialDock.contains($0) ? $0 : nil }
        let first = last ?? initialDock[0]
        swiper = TabSwiper(tabs: initialDock, initial: first)
        opened = [first]
    }

    func session(_ p: Portal) -> PortalSession { sessions[p]! }

    func open(_ p: Portal) { if !opened.contains(p) { opened.append(p) } }

    func markPerksLoaded() { if !perksLoaded { perksLoaded = true } }

    func updateDock(_ newDock: [Portal]) {
        for p in opened where !newDock.contains(p) {
            sessions[p]?.destroy()
        }
        opened.removeAll { !newDock.contains($0) }
        dock = newDock
        saveDock(newDock)
        swiper.setTabs(newDock)
        open(swiper.current)
    }

    func warmUp() async {
        let first = session(swiper.current)
        for _ in 0..<60 where !first.painted { try? await Task.sleep(for: .milliseconds(50)) }
        // stable sort: auto sign-in portals first, otherwise in tab order
        let order = dock.filter(\.autoLogin) + dock.filter { !$0.autoLogin }
        for p in order where !opened.contains(p) {
            try? await Task.sleep(for: .milliseconds(700))
            if Task.isCancelled { return }
            open(p)
        }
        if !perksLoaded {
            try? await Task.sleep(for: .milliseconds(700))
            if Task.isCancelled { return }
            perksLoaded = true
        }
    }

    func destroy() {
        sessions.values.forEach { $0.destroy() }
        perks.destroy()
    }
}

struct CompanionScreen: View {
    let onEditCreds: () -> Void
    let onForget: () -> Void

    @Environment(\.scenePhase) private var scenePhase
    @State private var model: CompanionModel
    @State private var askedCamera = false
    @State private var keyboardUp = false
    /// A portal opened from the ⋮ menu, shown over the tabs.
    @State private var more: Portal?
    @State private var vpnOpen = false
    @State private var editTabsOpen = false

    @State private var lastSeenDropKey: String? = UserDefaults.standard.string(forKey: "lastSeenDropKey")
    @State private var cachedDropActive: Bool = UserDefaults.standard.bool(forKey: "lastDropActive")
    @State private var cachedDropKey: String = UserDefaults.standard.string(forKey: "lastDropKey") ?? ""
    @State private var cycleTick: String = current8AmIstCycleKey()
    private var dropStore = DropStore.shared

    init(creds: Creds, onEditCreds: @escaping () -> Void, onForget: @escaping () -> Void) {
        self.onEditCreds = onEditCreds
        self.onForget = onForget
        _model = State(initialValue: CompanionModel(creds: creds))
    }

    private var activeDrop: Bool { dropStore.status?.drop ?? cachedDropActive }
    private var resolvedDropKey: String {
        let raw = dropStore.status?.dropKey ?? cachedDropKey
        if activeDrop && raw.hasSuffix(":daily") {
            let prefix = String(raw.dropLast(":daily".count))
            return "\(prefix):\(cycleTick)"
        }
        return raw
    }
    private var perksDotVisible: Bool {
        activeDrop && !resolvedDropKey.isEmpty && lastSeenDropKey != resolvedDropKey
    }

    private func openPerks() {
        let key = resolvedDropKey
        if !key.isEmpty {
            lastSeenDropKey = key
            UserDefaults.standard.set(key, forKey: "lastSeenDropKey")
        }
        model.markPerksLoaded()
        model.swiper.goToPerks()
    }

    var body: some View {
        let swiper = model.swiper
        let tab = swiper.current
        let session = model.session(tab)
        let perksBrowserURL = URL(string: "\(workerPerksURL)?user=\(model.creds.kerberos.trimmingCharacters(in: .whitespacesAndNewlines).lowercased())")!

        ZStack {
            VStack(spacing: 0) {
                TopBar(session: model.session(swiper.visual),
                       perksSelected: swiper.visualIsPerks,
                       perksLoading: model.perks.isLoading,
                       perksBrowserURL: perksBrowserURL,
                       morePortals: model.more,
                       onEditCreds: onEditCreds,
                       onEditTabs: { withAnimation(tween(0.3)) { editTabsOpen = true } },
                       onForget: onForget,
                       onOpen: { p in withAnimation(tween(0.3)) { more = p } },
                       onReloadPerks: { model.perks.fetchRemote() },
                       onOpenVpn: { withAnimation(tween(0.3)) { vpnOpen = true } })
                    .swipeTabs(swiper)

                PagesView(model: model, opened: model.opened, perksLoaded: model.perksLoaded)
                    .clipShape(RoundedRectangle(cornerRadius: 24, style: .circular))
                    .padding(.horizontal, 10)

                if !keyboardUp {
                    if !swiper.visualIsPerks && tab == .rollcall { RollCallBar(session: session) }
                    TabBar(
                        model: model,
                        onSelect: { p in
                            // tapping the open tab again goes back to that portal's home page
                            if swiper.inPerks || p != swiper.current { swiper.goTo(p) }
                            else { model.session(p).home() }
                        },
                        showPerksBadge: perksDotVisible,
                        onOpenPerks: {
                            if swiper.inPerks { model.perks.scrollToTop() }
                            else { openPerks() }
                        }
                    )
                }
            }

            if editTabsOpen {
                EditTabsSheet(
                    current: model.dock,
                    onSave: { newDock in
                        model.updateDock(newDock)
                        withAnimation(tween(0.2)) { editTabsOpen = false }
                        Task { await model.warmUp() }
                    },
                    onClose: { withAnimation(tween(0.2)) { editTabsOpen = false } }
                )
                .transition(.opacity.combined(with: .offset(y: 40)))
                .zIndex(1)
            }

            if vpnOpen {
                VpnSheet(
                    onRequestVpn: { withAnimation(tween(0.3)) { more = .vpnRequest } },
                    onClose: { withAnimation(tween(0.2)) { vpnOpen = false } }
                )
                .transition(.opacity.combined(with: .offset(y: 40)))
                .zIndex(1)
            }

            if let p = more {
                MoreSheet(portal: p, creds: model.creds, onClose: { withAnimation(tween(0.2)) { more = nil } })
                    .transition(.opacity.combined(with: .offset(y: 40)))
                    .zIndex(2)
            }
        }
        .task {
            WorkerTracker.checkin(kerberos: model.creds.kerberos)
            #if DEBUG
            // simulator aid: -ICDebugOpen EACADEMICS opens a ⋮-menu portal at launch
            if let p = UserDefaults.standard.string(forKey: "ICDebugOpen").flatMap(Portal.init(rawValue:)) { more = p }
            if UserDefaults.standard.object(forKey: "ICDebugTurn") != nil {
                let f = UserDefaults.standard.double(forKey: "ICDebugTurn")
                model.dock.forEach(model.open)
                try? await Task.sleep(for: .seconds(UserDefaults.standard.double(forKey: "ICDebugTurnDelay")))
                swiper.freeze(at: f)
                return
            }
            if UserDefaults.standard.bool(forKey: "ICDebugOpenPerks") {
                try? await Task.sleep(for: .milliseconds(300))
                openPerks()
            }
            #endif
            await model.warmUp()
        }
        .onChange(of: dropStore.status) { _, newStatus in
            if let newStatus {
                cachedDropActive = newStatus.drop
                cachedDropKey = newStatus.dropKey
                UserDefaults.standard.set(newStatus.drop, forKey: "lastDropActive")
                UserDefaults.standard.set(newStatus.dropKey, forKey: "lastDropKey")
            }
        }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active {
                cycleTick = current8AmIstCycleKey()
                WorkerTracker.refreshDropStatus()
            }
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
        // no LoginForm means the IITD OAuth page (RollCall, eAcademics, Exam)
        return (s.portal.login == nil ? "Type the CAPTCHA, then Login" : "Answer the CAPTCHA, then Go", s.portal.accent)
    case "login" where s.loginReady: return ("Login filled · tap to sign in", s.portal.accent)
    case "login": return ("Signing in…", Caution)
    case "home" where s.portal == .rollcall: return ("Pick a course below", s.portal.accent)
    default: return (s.portal.label, s.portal.accent)
    }
}

private struct TopBar: View {
    let session: PortalSession
    let perksSelected: Bool
    let perksLoading: Bool
    let perksBrowserURL: URL
    let morePortals: [Portal]
    let onEditCreds: () -> Void
    let onEditTabs: () -> Void
    let onForget: () -> Void
    let onOpen: (Portal) -> Void
    let onReloadPerks: () -> Void
    let onOpenVpn: () -> Void

    var body: some View {
        let (label, dot) = perksSelected
            ? (perksLoading ? "Loading IITD Perks…" : "IITD Perks · Student Hub", PerkAccent)
            : statusOf(session)
        VStack(spacing: 0) {
            HStack(spacing: 10) {
                StatusPill(label: label, dot: dot)
                ClayIconButton(icon: "icon_refresh", description: "Reload") {
                    if perksSelected { onReloadPerks() } else { session.reload() }
                }
                Menu {
                    Button("Open in browser") {
                        let url = perksSelected ? perksBrowserURL : Demo.real(session.webView?.url ?? session.portal.home)
                        openExternally(url)
                    }
                    Button("Edit tabs", action: onEditTabs)
                    Button("Edit saved login", action: onEditCreds)
                    Button("Forget login & sign out", action: onForget)
                    Button("Privacy policy") { openExternally(privacyPolicyURL) }
                    Section("More") {
                        ForEach(morePortals) { p in
                            Button { onOpen(p) } label: { Label(p.label, image: p.icon) }
                        }
                        Button(action: onOpenVpn) { Label("IITD VPN", image: "icon_vpn_key") }
                    }
                } label: {
                    ClayIconFace {
                        Image("icon_more_vert").renderingMode(.template).resizable().frame(width: 20, height: 20)
                    }
                }
                .accessibilityLabel("More")
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 10)
            if perksSelected {
                Group {
                    if perksLoading {
                        LoadBar(fraction: 0.7, color: PerkAccent)
                    } else {
                        Color.clear
                    }
                }
                .frame(height: 2)
                .padding(.horizontal, 24)
                Spacer().frame(height: 6)
            } else {
                PageProgress(session: session)
            }
        }
    }
}

/// Thin page-load bar in the portal's hue; the spacer keeps the layout from jumping.
private struct PageProgress: View {
    let session: PortalSession
    var body: some View {
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

/// A portal from the ⋮ menu, over the tabs. Its WebView exists only while it's
/// open, so the tabs keep their memory; the edge swipe steps back through its pages.
private struct MoreSheet: View {
    let onClose: () -> Void
    @State private var session: PortalSession

    init(portal: Portal, creds: Creds, onClose: @escaping () -> Void) {
        self.onClose = onClose
        _session = State(initialValue: PortalSession(portal: portal, creds: creds))
    }

    var body: some View {
        let (label, dot) = statusOf(session)
        VStack(spacing: 0) {
            HStack(spacing: 10) {
                StatusPill(label: label, dot: dot)
                ClayIconButton(icon: "icon_refresh", description: "Reload") { session.reload() }
                ClayIconButton(icon: "icon_close", description: "Close", action: onClose)
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 10)
            PageProgress(session: session)

            SingleFace(session: session)
                .clipShape(RoundedRectangle(cornerRadius: 24, style: .circular))
                .padding(.horizontal, 10)
                .padding(.bottom, 10)
        }
        .background(ClayBackground().ignoresSafeArea())
        .onDisappear { session.destroy() }
    }
}

/// Material 3's determinate linear indicator: rounded bar plus the stop dot at the end.
struct LoadBar: View {
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

/// Floating clay dock with 4 cyclic portal slots on the left and a pinned
/// non-removable Perks slot on the right. The raised pill slides across slots,
/// stretches mid-swipe and shifts hue.
private struct TabBar: View {
    let model: CompanionModel
    let onSelect: (Portal) -> Void
    let showPerksBadge: Bool
    let onOpenPerks: () -> Void

    private let dockHeight: CGFloat = 56
    private let dividerWidth: CGFloat = 9

    var body: some View {
        let swiper = model.swiper
        let perksVisual = swiper.visualIsPerks

        GeometryReader { g in
            let slotWidth = (g.size.width - dividerWidth) / CGFloat(model.dock.count + 1)
            let portalAreaWidth = slotWidth * CGFloat(model.dock.count)

            ZStack(alignment: .leading) {
                Pills(
                    swiper: swiper,
                    slotWidth: slotWidth,
                    dividerWidth: dividerWidth,
                    portalAreaWidth: portalAreaWidth,
                    totalWidth: g.size.width,
                    height: dockHeight
                )

                HStack(spacing: 0) {
                    HStack(spacing: 0) {
                        ForEach(model.dock) { p in
                            TabItem(portal: p, session: model.session(p), active: !perksVisual && p == swiper.visual)
                                .frame(maxWidth: .infinity, maxHeight: .infinity)
                                .contentShape(Rectangle())
                                .onTapGesture { onSelect(p) }
                        }
                    }
                    .frame(width: portalAreaWidth, height: dockHeight)
                    .contentShape(Rectangle())
                    .swipeTabs(swiper)

                    Rectangle()
                        .fill(Faint.color.opacity(0.35))
                        .frame(width: 1, height: 28)
                        .padding(.horizontal, 4)

                    PerksTabItem(active: perksVisual, showBadge: !perksVisual && showPerksBadge)
                        .frame(width: slotWidth, height: dockHeight)
                        .contentShape(Rectangle())
                        .swipeTabs(swiper)
                        .onTapGesture { onOpenPerks() }
                }
            }
        }
        .frame(height: dockHeight)
        .padding(6)
        .clay(radius: 28, depth: 12)
        .padding(.horizontal, 12)
        .padding(.vertical, 10)
    }
}

private struct Pills: View {
    let swiper: TabSwiper
    let slotWidth: CGFloat
    let dividerWidth: CGFloat
    let portalAreaWidth: CGFloat
    let totalWidth: CGFloat
    let height: CGFloat

    var body: some View {
        let progress = swiper.progress
        let stretch = sin(.pi * progress)
        let w = (slotWidth - 6) + 22 * stretch
        let color = swiper.pillColor()
        let indicator = swiper.indicator()
        let count = swiper.tabs.count

        if !swiper.perksVisible {
            ZStack {
                ForEach(-1...1, id: \.self) { lap in
                    ClaySlab(color: color, radius: 22, depth: 6)
                        .frame(width: w, height: height)
                        .position(x: slotWidth * (indicator + CGFloat(lap * count) + 0.5), y: height / 2)
                }
            }
            .frame(width: portalAreaWidth, height: height)
            .clipShape(RoundedRectangle(cornerRadius: 22, style: .circular))
        } else {
            let totalSlots = count + 1
            let lastPortal = CGFloat(count - 1)
            ZStack {
                ForEach(-1...1, id: \.self) { lap in
                    let s = indicator + CGFloat(lap * totalSlots)
                    let center: CGFloat = if s <= lastPortal {
                        slotWidth * (s + 0.5)
                    } else if s <= CGFloat(count) {
                        slotWidth * (lastPortal + 0.5) + (s - lastPortal) * (slotWidth + dividerWidth)
                    } else {
                        slotWidth * (s + 0.5) + dividerWidth
                    }
                    ClaySlab(color: color, radius: 22, depth: 6)
                        .frame(width: w, height: height)
                        .position(x: center, y: height / 2)
                }
            }
            .frame(width: totalWidth, height: height)
            .clipShape(RoundedRectangle(cornerRadius: 22, style: .circular))
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
                .font(Font(Display.semiBold(10) as CTFont))
                .foregroundStyle((active ? Ink : Muted).color)
                .lineLimit(1)
                .minimumScaleFactor(0.85)
                .truncationMode(.tail)
                .padding(.horizontal, 2)
        }
    }
}

private struct PerksTabItem: View {
    let active: Bool
    let showBadge: Bool

    var body: some View {
        VStack(spacing: 0) {
            Image(systemName: "gift")
                .font(.system(size: 19, weight: .semibold))
                .frame(width: 22, height: 22)
                .foregroundStyle((active ? Ink : PerkAccent).color)
                .overlay(alignment: .topTrailing) {
                    if showBadge {
                        ZStack {
                            Circle().fill(Clay.color)
                            Circle().fill(PerkAccent.color).padding(1.5)
                        }
                        .frame(width: 9, height: 9)
                        .offset(x: 5, y: -3)
                    }
                }
            Spacer().frame(height: 3)
            Text("Perks")
                .font(Font(Display.semiBold(10) as CTFont))
                .foregroundStyle((active ? Ink : Bone).color)
                .lineLimit(1)
                .minimumScaleFactor(0.85)
                .truncationMode(.tail)
                .padding(.horizontal, 2)
        }
    }
}

/// 8:00 AM IST daily cycle key ("YYYY-MM-DD"), matching the backend worker's rollover.
private func current8AmIstCycleKey() -> String {
    var cal = Calendar(identifier: .gregorian)
    cal.timeZone = TimeZone(identifier: "Asia/Kolkata")!
    var date = Date()
    if cal.component(.hour, from: date) < 8 {
        date = cal.date(byAdding: .day, value: -1, to: date) ?? date
    }
    let y = cal.component(.year, from: date)
    let m = cal.component(.month, from: date)
    let d = cal.component(.day, from: date)
    return String(format: "%04d-%02d-%02d", y, m, d)
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

/// Just the code: "MTL2006 (Sec B)", "MEL2002-Lab", "PYL1001LH408" → MTL2006, MEL2002, PYL1001.
private func courseCode(_ name: String) -> String {
    name.prefixMatch(of: /[A-Z]{2,4}\d{3,4}/).map { String($0.output) } ?? name
}

private struct CourseChip: View {
    let code: String
    let active: Bool
    let accent: Tone
    let onClick: () -> Void

    var body: some View {
        Text(courseCode(code))
            .m3(14, weight: .semibold)
            .foregroundStyle((active ? Ink : Bone).color)
            .padding(.horizontal, 18)
            .padding(.vertical, 10)
            .clay(active ? accent : Clay, radius: 18, depth: active ? 6 : 4)
            .contentShape(RoundedRectangle(cornerRadius: 18))
            .onTapGesture(perform: onClick)
    }
}
