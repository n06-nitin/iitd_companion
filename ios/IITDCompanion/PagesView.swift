import SwiftUI
import UIKit
import WebKit

/// Holds every opened tab's WebView as a face of a cube that turns with the swipe.
struct PagesView: UIViewRepresentable {
    let model: CompanionModel
    /// Passed in so SwiftUI updates the container when a tab gets its WebView.
    let opened: [Portal]

    func makeUIView(context: Context) -> PagesContainer { PagesContainer(model: model) }

    func updateUIView(_ view: PagesContainer, context: Context) { view.sync(opened) }
}

final class PagesContainer: UIView {
    private let model: CompanionModel
    private var faces: [Portal: Face] = [:]

    /// Distance from the eye to the page plane: Compose's `cameraDistance = 16 * density`.
    private static let cameraDistance: CGFloat = 16 * 72

    init(model: CompanionModel) {
        self.model = model
        super.init(frame: .zero)
        clipsToBounds = true
        backgroundColor = .clear
        let swiper = model.swiper
        swiper.onChange = { [weak self] in self?.poseFaces() }
        addGestureRecognizer(PageSwipeRecognizer(
            swiper: swiper,
            onDown: { [weak self] in self?.model.session(swiper.current).touchStarted() },
            pageWants: { [weak self] dir, start in self?.pageWants(dir, start) ?? true }
        ))
    }

    required init?(coder: NSCoder) { fatalError() }

    func sync(_ opened: [Portal]) {
        for p in opened where faces[p] == nil {
            let face = Face(session: model.session(p))
            faces[p] = face
            addSubview(face)
        }
        setNeedsLayout()
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        model.swiper.width = max(bounds.width, 1)
        poseFaces()
    }

    /// Cube turn: each page hinges on the edge it shares with its neighbour.
    private func poseFaces() {
        let w = bounds.width, h = bounds.height
        guard w > 0 else { return }
        let swiper = model.swiper
        for (p, face) in faces {
            guard let pos = swiper.positionOf(p) else {
                face.isHidden = true
                continue
            }
            face.isHidden = false
            face.bounds = CGRect(x: 0, y: 0, width: w, height: h)
            face.layer.anchorPoint = CGPoint(x: pos < 0 ? 1 : 0, y: 0.5)
            face.layer.position = CGPoint(x: pos < 0 ? w : 0, y: h / 2)
            let depth = 1 - 0.14 * sin(.pi * abs(pos))
            var perspective = CATransform3DIdentity
            perspective.m34 = -1 / Self.cameraDistance
            var t = CATransform3DMakeScale(depth, depth, 1)
            t = CATransform3DConcat(t, CATransform3DMakeRotation(.pi / 2 * pos, 0, 1, 0))
            t = CATransform3DConcat(t, perspective)
            t = CATransform3DConcat(t, CATransform3DMakeTranslation(pos * w, 0, 0))
            face.layer.transform = t
        }
    }

    private func pageWants(_ dir: Int, _ start: CGPoint) -> Bool {
        let s = model.session(model.swiper.current)
        guard let wv = s.webView else { return false }
        // the edge swipe goes back / forward in the page's history
        if dir < 0 && start.x < 30 && wv.canGoBack { return true }
        if dir > 0 && start.x > bounds.width - 30 && wv.canGoForward { return true }
        return s.touchBusy || (s.pagePans != false && canScrollHorizontally(wv.scrollView, dir))
    }

    private func canScrollHorizontally(_ sv: UIScrollView, _ dir: Int) -> Bool {
        let inset = sv.adjustedContentInset
        if dir > 0 { return sv.contentOffset.x + sv.bounds.width < sv.contentSize.width + inset.right - 1 }
        return sv.contentOffset.x > -inset.left + 1
    }
}

/// One cube face: the tab's WebView, with a stand-in until its first page paints.
private final class Face: UIView {
    init(session: PortalSession) {
        super.init(frame: .zero)
        backgroundColor = .clear
        let web = session.makeWebView()
        web.removeFromSuperview()
        web.frame = bounds
        web.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        addSubview(web)

        let host = UIHostingController(rootView: LoadingFace(session: session))
        host.safeAreaRegions = []
        host.view.backgroundColor = .clear
        host.view.isUserInteractionEnabled = false
        host.view.frame = bounds
        host.view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        addSubview(host.view)
    }

    required init?(coder: NSCoder) { fatalError() }
}

/// Stands in for a page that hasn't painted yet, so a cube face is never blank.
private struct LoadingFace: View {
    let session: PortalSession
    var body: some View {
        if !session.painted {
            let p = session.portal
            ZStack {
                Clay.color
                VStack(spacing: 0) {
                    Image(p.icon).renderingMode(.template).resizable()
                        .frame(width: 34, height: 34)
                        .foregroundStyle(p.accent.color)
                        .frame(width: 72, height: 72)
                        .clay(radius: 24, depth: 10)
                    Spacer().frame(height: 18)
                    Text("Opening \(p.label)…")
                        .m3(14)
                        .foregroundStyle(Muted.color)
                }
            }
        }
    }
}
