import SwiftUI
import UIKit

/* -------------------------------- tokens -------------------------------- */
let Ink = Tone(0xFF141518)       // app background
let Clay = Tone(0xFF22242A)      // raised surfaces
let ClayWell = Tone(0xFF1A1B1F)  // pressed-in wells (inputs)
let Bone = Tone(0xFFECE7DE)      // primary text, primary button
let Muted = Tone(0xFF8C8880)
let Faint = Tone(0xFF5E5B56)
let Danger = Tone(0xFFF2705E)
let Caution = Tone(0xFFF2C14E)
private let ClayShadow = Tone(0xF0040405)
private let ClayLift = Tone(0x12FFFFFF)
private let White = Tone(0xFFFFFFFF)
private let Black = Tone(0xFF000000)

/// An sRGB colour that can be blended the way Compose's `lerp` does (in Oklab).
struct Tone: Equatable {
    var r: Double, g: Double, b: Double, a: Double

    init(_ argb: UInt32) {
        a = Double((argb >> 24) & 0xFF) / 255
        r = Double((argb >> 16) & 0xFF) / 255
        g = Double((argb >> 8) & 0xFF) / 255
        b = Double(argb & 0xFF) / 255
    }

    init(r: Double, g: Double, b: Double, a: Double) { self.r = r; self.g = g; self.b = b; self.a = a }

    var color: Color { Color(.sRGB, red: r, green: g, blue: b, opacity: a) }
    func alpha(_ v: Double) -> Tone { Tone(r: r, g: g, b: b, a: v) }

    static func lerp(_ x: Tone, _ y: Tone, _ t: Double) -> Tone {
        let t = min(max(t, 0), 1)
        let p = x.oklab, q = y.oklab
        func mix(_ u: Double, _ v: Double) -> Double { u + (v - u) * t }
        return Tone.fromOklab(mix(p.0, q.0), mix(p.1, q.1), mix(p.2, q.2), mix(x.a, y.a))
    }

    private var oklab: (Double, Double, Double) {
        func lin(_ c: Double) -> Double { c <= 0.04045 ? c / 12.92 : pow((c + 0.055) / 1.055, 2.4) }
        let lr = lin(r), lg = lin(g), lb = lin(b)
        let l = cbrt(0.4122214708 * lr + 0.5363325363 * lg + 0.0514459929 * lb)
        let m = cbrt(0.2119034982 * lr + 0.6806995451 * lg + 0.1073969566 * lb)
        let s = cbrt(0.0883024619 * lr + 0.2817188376 * lg + 0.6299787005 * lb)
        return (0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s,
                1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s,
                0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s)
    }

    private static func fromOklab(_ L: Double, _ A: Double, _ B: Double, _ alpha: Double) -> Tone {
        let l = pow(L + 0.3963377774 * A + 0.2158037573 * B, 3)
        let m = pow(L - 0.1055613458 * A - 0.0638541728 * B, 3)
        let s = pow(L - 0.0894841775 * A - 1.2914855480 * B, 3)
        func enc(_ c: Double) -> Double {
            let v = c <= 0.0031308 ? 12.92 * c : 1.055 * pow(c, 1 / 2.4) - 0.055
            return min(max(v, 0), 1)
        }
        return Tone(r: enc(4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s),
                    g: enc(-1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s),
                    b: enc(-0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s),
                    a: alpha)
    }
}

/// Bricolage Grotesque (OFL): the title and tab labels only.
enum Display {
    static func semiBold(_ size: CGFloat) -> UIFont { font(size, weight: 600, opsz: 14) }
    static func extraBold(_ size: CGFloat) -> UIFont { font(size, weight: 800, opsz: 48) }

    private static func font(_ size: CGFloat, weight: Double, opsz: Double) -> UIFont {
        let wght = 0x77676874, opszTag = 0x6F70737A // 'wght', 'opsz'
        let desc = UIFontDescriptor(fontAttributes: [
            .name: "BricolageGrotesque-96ptExtraBold",
            UIFontDescriptor.AttributeName(rawValue: kCTFontVariationAttribute as String): [wght: weight, opszTag: opsz],
        ])
        return UIFont(descriptor: desc, size: size)
    }
}

extension View {
    /// Compose's default text style (Material 3 bodyLarge): every line 24sp tall, letters spaced 0.5sp.
    func m3(_ size: CGFloat, weight: UIFont.Weight = .regular, lineHeight: CGFloat = 24) -> some View {
        m3(font: UIFont.systemFont(ofSize: size, weight: weight), lineHeight: lineHeight)
    }

    func m3(font: UIFont, lineHeight: CGFloat = 24) -> some View {
        let extra = max(lineHeight - font.lineHeight, 0)
        return self.font(Font(font as CTFont)).tracking(0.5).lineSpacing(extra).padding(.vertical, extra / 2)
    }
}

/// Compose's FastOutSlowInEasing tween.
func tween(_ seconds: Double) -> Animation { .timingCurve(0.4, 0, 0.2, 1, duration: seconds) }

/// A Compose `spring(dampingRatio, stiffness)` (unit mass).
func composeSpring(damping: Double = 1, stiffness: Double = 1500) -> Animation {
    .spring(response: 2 * .pi / stiffness.squareRoot(), dampingFraction: damping)
}

/* --------------------------------- clay --------------------------------- */
/// A claymorphic slab: soft dark drop shadow bottom-right, a faint lift top-left,
/// a body a touch lighter at the top, and a lit rim. `inset` draws a pressed-in
/// well instead (dark rim on top, no drop shadow).
struct ClaySlab: View {
    var color: Tone = Clay
    var radius: CGFloat = 22
    var depth: CGFloat = 8
    var inset = false

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: radius, style: .circular)
        let d = depth
        ZStack {
            if !inset && d > 0 {
                shape.fill(color.color)
                    .shadow(color: ClayShadow.color, radius: blur(d * 1.6), x: d * 0.5, y: d * 0.8)
                shape.fill(color.color)
                    .shadow(color: ClayLift.color, radius: blur(d * 1.2), x: -d * 0.35, y: -d * 0.45)
            }
            shape.fill(LinearGradient(
                colors: inset
                    ? [Tone.lerp(color, Black, 0.3).color, color.color]
                    : [Tone.lerp(color, White, 0.07).color, Tone.lerp(color, Black, 0.07).color],
                startPoint: .top, endPoint: .bottom))
            shape.inset(by: 0.5).stroke(LinearGradient(
                colors: inset
                    ? [Color.black.opacity(0.5), .clear, Color.white.opacity(0.07)]
                    : [Color.white.opacity(0.17), .clear, Color.black.opacity(0.3)],
                startPoint: .top, endPoint: .bottom), lineWidth: 1)
        }
    }

    /// Android's shadow-layer radius -> Core Animation's (Gaussian sigma) of the same softness.
    private func blur(_ r: CGFloat) -> CGFloat { r * 0.577 }
}

extension View {
    func clay(_ color: Tone = Clay, radius: CGFloat = 22, depth: CGFloat = 8, inset: Bool = false) -> some View {
        background(ClaySlab(color: color, radius: radius, depth: depth, inset: inset))
    }
}

struct ClayBackground: View {
    var body: some View {
        Canvas { ctx, size in
            ctx.fill(Path(CGRect(origin: .zero, size: size)), with: .color(Ink.color))
            // one soft overhead light, so the clay has something to catch
            ctx.fill(Path(CGRect(origin: .zero, size: size)), with: .radialGradient(
                Gradient(colors: [Color.white.opacity(0.04), .clear]),
                center: CGPoint(x: size.width * 0.25, y: 0), startRadius: 0, endRadius: size.width * 1.2))
        }
    }
}

/* ------------------------------ components ------------------------------ */
struct ClayCard<Content: View>: View {
    @ViewBuilder let content: Content
    var body: some View {
        VStack(alignment: .leading, spacing: 0) { content }
            .padding(20)
            .frame(maxWidth: .infinity, alignment: .leading)
            .clay(radius: 28, depth: 14)
    }
}

/// A filled text field in a pressed-in clay well, with a label that floats up
/// when focused or filled (Material 3 behaviour).
struct Field<Trailing: View>: View {
    @Binding var text: String
    let label: String
    let placeholder: String
    var secure = false
    var contentType: UITextContentType? = nil
    @ViewBuilder var trailing: Trailing

    @FocusState private var focused: Bool

    var body: some View {
        let floated = focused || !text.isEmpty
        let shape = RoundedRectangle(cornerRadius: 18, style: .circular)
        HStack(spacing: 0) {
            ZStack(alignment: .leading) {
                Text(label)
                    .font(.system(size: 16))
                    .tracking(0.5)
                    .foregroundStyle((focused ? Bone : Muted).color)
                    .scaleEffect(floated ? 0.75 : 1, anchor: .leading)
                    .offset(y: floated ? -12 : 0)
                    .allowsHitTesting(false)
                Group {
                    if secure {
                        SecureField("", text: $text)
                    } else {
                        TextField("", text: $text)
                    }
                }
                .font(.system(size: 16))
                .tracking(0.5)
                .foregroundStyle(Bone.color)
                .tint(Bone.color)
                .keyboardType(.asciiCapable)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .textContentType(contentType)
                .focused($focused)
                .background(alignment: .leading) {
                    Text(placeholder)
                        .font(.system(size: 16))
                        .tracking(0.5)
                        .foregroundStyle(Faint.color)
                        .opacity(focused && text.isEmpty ? 1 : 0)
                        .allowsHitTesting(false)
                }
                .offset(y: 8)
            }
            .padding(.leading, 16)
            .frame(maxWidth: .infinity, alignment: .leading)
            trailing
        }
        .frame(height: 56)
        .contentShape(shape)
        .onTapGesture { focused = true }
        .clay(ClayWell, radius: 18, inset: true)
        .overlay(shape.strokeBorder(focused ? Bone.alpha(0.35).color : .clear, lineWidth: 1))
        .animation(tween(0.15), value: floated)
        .animation(tween(0.15), value: focused)
        .onChange(of: secure) { _, _ in
            // swapping between secure and plain fields drops focus; keep the keyboard up
            if focused { DispatchQueue.main.async { focused = true } }
        }
    }
}

/// A clay slab button; it sinks into the surface while pressed.
struct ClayButton: View {
    let text: String
    var color: Tone = Clay
    var contentColor: Tone = Bone
    var enabled = true
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(text)
        }
        .buttonStyle(ClayButtonStyle(color: enabled ? color : Clay, contentColor: enabled ? contentColor : Faint, enabled: enabled))
        .disabled(!enabled)
    }
}

private struct ClayButtonStyle: ButtonStyle {
    let color: Tone
    let contentColor: Tone
    let enabled: Bool

    func makeBody(configuration: Configuration) -> some View {
        let pressed = configuration.isPressed
        configuration.label
            .m3(16, weight: .semibold)
            .foregroundStyle(contentColor.color)
            .lineLimit(1)
            .truncationMode(.tail)
            .padding(.vertical, 15)
            .frame(maxWidth: .infinity)
            .contentShape(RoundedRectangle(cornerRadius: 20))
            .background(
                ClaySlab(color: color, radius: 20, depth: pressed || !enabled ? 2 : 8)
                    .animation(composeSpring(damping: 0.6), value: pressed)
            )
            .scaleEffect(pressed ? 0.97 : 1)
            .animation(composeSpring(damping: 0.5), value: pressed)
    }
}

struct ClayIconButton: View {
    let icon: String
    let description: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(icon).renderingMode(.template).resizable().frame(width: 20, height: 20)
        }
        .buttonStyle(ClayIconStyle())
        .accessibilityLabel(description)
    }
}

private struct ClayIconStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        ClayIconFace(pressed: configuration.isPressed) { configuration.label }
    }
}

/// The round clay face of an icon button; also the label of the overflow menu.
struct ClayIconFace<Label: View>: View {
    var pressed = false
    @ViewBuilder let label: Label
    var body: some View {
        label
            .foregroundStyle(Bone.color)
            .frame(width: 44, height: 44)
            .contentShape(Circle())
            .background(
                ClaySlab(radius: 22, depth: pressed ? 1 : 6)
                    .animation(composeSpring(), value: pressed)
            )
    }
}

struct StatusPill: View {
    let label: String
    let dot: Tone

    var body: some View {
        HStack(spacing: 10) {
            ZStack {
                Circle().strokeBorder(dot.alpha(0.3).color, lineWidth: 2)
                ClaySlab(color: dot, radius: 3, depth: 0)
                    .clipShape(Circle())
                    .padding(2)
            }
            .frame(width: 10, height: 10)
            Text(label)
                .m3(13, weight: .medium)
                .foregroundStyle(Bone.color)
                .lineLimit(1)
                .truncationMode(.tail)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .clay(radius: 22, depth: 6)
    }
}

/* -------------------------------- toast -------------------------------- */
/// Short bottom-of-screen notices, like Android's Toast.
@Observable @MainActor
final class Toaster {
    static let shared = Toaster()
    private(set) var message: String?
    private var token = 0

    func show(_ text: String) {
        message = text
        token += 1
        let mine = token
        DispatchQueue.main.asyncAfter(deadline: .now() + 2) { [weak self] in
            if self?.token == mine { self?.message = nil }
        }
    }
}

struct ToastOverlay: View {
    private var toaster = Toaster.shared
    var body: some View {
        VStack {
            Spacer()
            if let m = toaster.message {
                Text(m)
                    .font(.system(size: 14))
                    .foregroundStyle(Color(white: 0.93))
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 18)
                    .padding(.vertical, 11)
                    .background(Capsule().fill(Color(white: 0.2)))
                    .padding(.horizontal, 32)
                    .padding(.bottom, 64)
                    .transition(.opacity)
            }
        }
        .animation(.easeInOut(duration: 0.2), value: toaster.message)
        .allowsHitTesting(false)
    }
}

/// The view controller on top, for presenting alerts and file previews.
@MainActor
func topViewController() -> UIViewController? {
    let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
    let scene = scenes.first { $0.activationState == .foregroundActive } ?? scenes.first
    var vc = scene?.keyWindow?.rootViewController
    while let p = vc?.presentedViewController { vc = p }
    return vc
}
