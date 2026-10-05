import SwiftUI
import UIKit
import UIKit.UIGestureRecognizerSubclass

/// Interactive tab switching. `offset` is how far (pt) the current page has been
/// dragged: negative moves it left to reveal the tab on the right. The page
/// container reads `positionOf` on every change, so a drag never touches SwiftUI.
///
/// The tabs form a ring, like the faces of the cube: past the last tab comes the
/// first again. A new drag can catch a turn that's still settling and carry on
/// from there, so quick repeated swipes chain round the ring.
@Observable @MainActor
final class TabSwiper {
    @ObservationIgnored private let tabs = Portal.allCases

    private(set) var current: Portal
    /// The tab being revealed, if any.
    private(set) var target: Portal?
    private(set) var offset: CGFloat = 0
    /// The tab that would be shown if the finger lifted now. Changes once per swipe, not per frame.
    private(set) var visual: Portal
    @ObservationIgnored var width: CGFloat = 1

    /// Signed steps round the ring from `current` to `target`: ±1 for a swipe, up to ±2 for a tap.
    private var step = 0
    @ObservationIgnored private var raw: CGFloat = 0 // finger travel
    @ObservationIgnored private var animation: FrameAnimation?
    @ObservationIgnored private var generation = 0
    /// Where the running animation will land, if it's turning to another tab.
    @ObservationIgnored private var landing: Portal?
    /// Called after every change, so the cube can re-pose its faces.
    @ObservationIgnored var onChange: (() -> Void)?

    /// Fling speed that commits or cancels a turn (Android's 700 px/s).
    static let flingVelocity: CGFloat = 270

    init(initial: Portal) {
        current = initial
        visual = initial
    }

    var progress: CGFloat { min(max(abs(offset) / width, 0), 1) }

    /// -1..1 horizontal position of `p` as a cube face, or nil when off screen.
    func positionOf(_ p: Portal) -> CGFloat? {
        let f = min(max(offset / width, -1), 1)
        if p == current { return f }
        if p == target { return f + (f < 0 ? 1 : -1) }
        return nil
    }

    /// Fractional tab index for the sliding dock pill. It can run past either end
    /// (-1..size) while wrapping round; the dock draws the overflow on the other side.
    func indicator() -> CGFloat {
        let from = CGFloat(tabs.firstIndex(of: current)!)
        return target == nil ? from : from + progress * CGFloat(step)
    }

    /// Stop a running turn where it is, keeping every face in place on screen.
    func interrupt() {
        guard animation?.running == true else { return }
        animation?.cancel()
        animation = nil
        generation += 1
        let to = landing
        landing = nil
        if let to, to == target, offset != 0 {
            // it was turning to `to`: make that the current face, re-expressing the offset from its side
            let from = current
            current = to
            offset -= sign(offset) * width
            target = from
            step = -step
        }
        raw = offset
        changed()
    }

    func dragBy(_ dx: CGFloat) {
        interrupt()
        raw += dx
        if raw == 0 {
            target = nil
            offset = 0
            changed()
            return
        }
        step = raw < 0 ? 1 : -1
        target = tabs[mod(tabs.firstIndex(of: current)! + step, tabs.count)]
        offset = min(max(raw, -width), width)
        changed()
    }

    func release(velocity: CGFloat) {
        if animation?.running == true { return }
        let t = target
        let fast = abs(velocity) > Self.flingVelocity
        let flung = fast && sign(velocity) == sign(offset)
        let flungBack = fast && sign(velocity) == -sign(offset)
        let commit = t != nil && !flungBack && (abs(offset) > width * 0.22 || flung)
        let end = commit ? sign(offset) * width : 0
        run(commit ? t : nil, SpringCurve(from: offset, to: end, velocity: velocity,
                                          dampingRatio: commit ? 1 : 0.6, stiffness: 380))
    }

    /// Tapped a tab: turn the short way round the ring towards it.
    func goTo(_ p: Portal) {
        finishNow()
        if p == current { return }
        let ahead = mod(tabs.firstIndex(of: p)! - tabs.firstIndex(of: current)!, tabs.count)
        step = ahead <= tabs.count / 2 ? ahead : ahead - tabs.count
        target = p
        let end = step > 0 ? -width : width
        run(p, TweenCurve(from: 0, to: end, duration: 0.42))
    }

    #if DEBUG
    /// Screenshot aid: hold the cube part-way through a turn towards the next tab.
    func freeze(at fraction: CGFloat) {
        step = fraction < 0 ? -1 : 1
        target = tabs[mod(tabs.firstIndex(of: current)! + step, tabs.count)]
        offset = -fraction * width
        raw = offset
        changed()
    }
    #endif

    /// Jump a running turn straight to where it was going.
    private func finishNow() {
        guard animation?.running == true else { return }
        animation?.cancel()
        animation = nil
        generation += 1
        settle(landing)
    }

    private func run(_ to: Portal?, _ curve: AnimationCurve) {
        generation += 1
        let gen = generation
        landing = to
        animation = FrameAnimation(curve: curve, onFrame: { [weak self] v in
            guard let self, gen == self.generation else { return }
            self.offset = v
            self.changed()
        }, onEnd: { [weak self] in
            guard let self, gen == self.generation else { return }
            self.settle(to)
        })
    }

    private func settle(_ to: Portal?) {
        if let to { current = to }
        offset = 0
        raw = 0
        target = nil
        landing = nil
        step = 0
        changed()
    }

    private func changed() {
        let v = target.flatMap { progress > 0.5 ? $0 : nil } ?? current
        if v != visual { visual = v }
        onChange?()
    }
}

private func sign(_ x: CGFloat) -> CGFloat { x > 0 ? 1 : (x < 0 ? -1 : 0) }
private func mod(_ a: Int, _ n: Int) -> Int { ((a % n) + n) % n }

/* ------------------------------ animation ------------------------------ */

protocol AnimationCurve {
    /// Value at `t` seconds, and whether the curve has come to rest.
    func value(at t: Double) -> (CGFloat, Bool)
}

/// Compose's spring (unit mass), started with the finger's velocity.
struct SpringCurve: AnimationCurve {
    let from: CGFloat, to: CGFloat, velocity: CGFloat
    let dampingRatio: Double, stiffness: Double

    func value(at t: Double) -> (CGFloat, Bool) {
        let w0 = stiffness.squareRoot(), z = dampingRatio
        let x0 = Double(from - to), v0 = Double(velocity)
        var x: Double, v: Double
        if z >= 1 {
            let b = v0 + w0 * x0
            let e = exp(-w0 * t)
            x = (x0 + b * t) * e
            v = (b - w0 * (x0 + b * t)) * e
        } else {
            let wd = w0 * (1 - z * z).squareRoot()
            let e = exp(-z * w0 * t)
            let b = (v0 + z * w0 * x0) / wd
            x = e * (x0 * cos(wd * t) + b * sin(wd * t))
            v = -z * w0 * x + e * (-x0 * wd * sin(wd * t) + b * wd * cos(wd * t))
        }
        let done = abs(x) < 0.05 && abs(v) < 2
        return (done ? to : to + CGFloat(x), done)
    }
}

/// A FastOutSlowIn tween, cubic-bezier(0.4, 0, 0.2, 1).
struct TweenCurve: AnimationCurve {
    let from: CGFloat, to: CGFloat, duration: Double

    func value(at t: Double) -> (CGFloat, Bool) {
        let p = min(max(t / duration, 0), 1)
        return (from + (to - from) * CGFloat(Self.ease(p)), p >= 1)
    }

    static func ease(_ x: Double) -> Double {
        let x1 = 0.4, y1 = 0.0, x2 = 0.2, y2 = 1.0
        func bez(_ s: Double, _ a: Double, _ b: Double) -> Double {
            let u = 1 - s
            return 3 * u * u * s * a + 3 * u * s * s * b + s * s * s
        }
        var lo = 0.0, hi = 1.0, s = x
        for _ in 0..<24 {
            s = (lo + hi) / 2
            if bez(s, x1, x2) < x { lo = s } else { hi = s }
        }
        return bez(s, y1, y2)
    }
}

/// Drives a curve once per display frame.
@MainActor
final class FrameAnimation: NSObject {
    private let curve: AnimationCurve
    private let onFrame: (CGFloat) -> Void
    private let onEnd: () -> Void
    private var link: CADisplayLink?
    private var start: CFTimeInterval?
    private(set) var running = true

    init(curve: AnimationCurve, onFrame: @escaping (CGFloat) -> Void, onEnd: @escaping () -> Void) {
        self.curve = curve
        self.onFrame = onFrame
        self.onEnd = onEnd
        super.init()
        let link = CADisplayLink(target: self, selector: #selector(tick(_:)))
        link.preferredFrameRateRange = CAFrameRateRange(minimum: 60, maximum: 120, preferred: 120)
        link.add(to: .main, forMode: .common)
        self.link = link
    }

    @objc private func tick(_ link: CADisplayLink) {
        let now = link.targetTimestamp
        if start == nil { start = link.timestamp }
        let (v, done) = curve.value(at: now - start!)
        onFrame(v)
        if done {
            stop()
            onEnd()
        }
    }

    func cancel() { stop() }

    private func stop() {
        running = false
        link?.invalidate()
        link = nil
    }
}

/* ------------------------------- gestures ------------------------------- */

/// Swipe on the SwiftUI chrome (top bar, dock).
struct SwipeTabs: ViewModifier {
    let swiper: TabSwiper
    @State private var last: CGFloat?

    func body(content: Content) -> some View {
        content.simultaneousGesture(
            DragGesture(minimumDistance: 8, coordinateSpace: .global)
                .onChanged { v in
                    let x = v.translation.width
                    if let l = last {
                        swiper.dragBy(x - l)
                        last = x
                    } else if abs(x) >= 8 {
                        // horizontal slop passed: start from here, carrying only the overshoot
                        swiper.interrupt()
                        let over = x - (x > 0 ? 8 : -8)
                        swiper.dragBy(over)
                        last = x
                    }
                }
                .onEnded { v in
                    if last != nil { swiper.release(velocity: v.velocity.width) }
                    last = nil
                }
        )
    }
}

extension View {
    func swipeTabs(_ swiper: TabSwiper) -> some View { modifier(SwipeTabs(swiper: swiper)) }
}

/// Swipe over the pages. Sits on the container that holds every tab's WebView,
/// so a gesture survives faces coming and going mid-turn. It watches touches
/// alongside the page and only takes over a quick, mostly horizontal
/// single-finger swipe that `pageWants` doesn't claim; taking over cancels the
/// page's own touch.
final class PageSwipeRecognizer: UIGestureRecognizer, UIGestureRecognizerDelegate {
    private let swiper: TabSwiper
    private let onDown: () -> Void
    /// The page claims a sideways drag in `scrollDir` (+1 = content moving left).
    private let pageWants: (_ scrollDir: Int, _ start: CGPoint) -> Bool
    private let slop: CGFloat = 12
    private let longPress: TimeInterval = 0.4

    private var tracked: UITouch?
    private var downTime: TimeInterval = 0
    private var start: CGPoint = .zero
    private var lastPoint: CGPoint = .zero
    private var swiping = false
    private var samples: [(TimeInterval, CGFloat)] = []

    init(swiper: TabSwiper, onDown: @escaping () -> Void, pageWants: @escaping (Int, CGPoint) -> Bool) {
        self.swiper = swiper
        self.onDown = onDown
        self.pageWants = pageWants
        super.init(target: nil, action: nil)
        delegate = self
        cancelsTouchesInView = true
        delaysTouchesBegan = false
        delaysTouchesEnded = false
    }

    override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent) {
        if tracked == nil, let t = touches.first, touches.count == 1 {
            tracked = t
            downTime = t.timestamp
            start = t.location(in: view)
            lastPoint = start
            samples = [(t.timestamp, start.x)]
            onDown() // the page's touchstart reports after this
            return
        }
        // pinch-zoom belongs to the page
        if !swiping { state = .failed }
    }

    override func touchesMoved(_ touches: Set<UITouch>, with event: UIEvent) {
        guard let t = tracked, touches.contains(t) else { return }
        let p = t.location(in: view)
        let dx = p.x - lastPoint.x
        lastPoint = p
        record(t.timestamp, p.x)
        if swiping {
            swiper.dragBy(dx)
            state = .changed
            return
        }
        let travel = CGPoint(x: p.x - start.x, y: p.y - start.y)
        if abs(travel.y) > slop && abs(travel.y) > abs(travel.x) {
            state = .failed // vertical scroll
            return
        }
        if abs(travel.x) > slop && abs(travel.x) > 1.3 * abs(travel.y) {
            // a slow press-then-drag is text selection; leave it to the page
            let quick = t.timestamp - downTime < longPress
            if !quick || pageWants(travel.x < 0 ? 1 : -1, start) {
                state = .failed
                return
            }
            swiping = true
            swiper.dragBy(0) // catch a turn that's still settling
            state = .began
        }
    }

    override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent) {
        guard let t = tracked, touches.contains(t) else { return }
        record(t.timestamp, t.location(in: view).x)
        if swiping {
            swiper.release(velocity: velocity())
            state = .ended
        } else {
            state = .failed
        }
    }

    override func touchesCancelled(_ touches: Set<UITouch>, with event: UIEvent) {
        guard let t = tracked, touches.contains(t) else { return }
        if swiping {
            swiper.release(velocity: velocity())
            state = .cancelled
        } else {
            state = .failed
        }
    }

    override func reset() {
        tracked = nil
        swiping = false
        samples.removeAll()
    }

    private func record(_ t: TimeInterval, _ x: CGFloat) {
        samples.append((t, x))
        samples.removeAll { t - $0.0 > 0.1 }
    }

    /// Horizontal speed (pt/s) over the last 100 ms.
    private func velocity() -> CGFloat {
        guard let first = samples.first, let last = samples.last, last.0 - first.0 > 0.004 else { return 0 }
        return (last.1 - first.1) / CGFloat(last.0 - first.0)
    }

    // watch alongside the page's own scrolling and touch handling
    func gestureRecognizer(_ g: UIGestureRecognizer, shouldRecognizeSimultaneouslyWith other: UIGestureRecognizer) -> Bool {
        !(other is UIScreenEdgePanGestureRecognizer)
    }
}
