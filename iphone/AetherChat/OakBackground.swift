import SwiftUI

struct OakBackground<Content: View>: View {
    @Environment(\.colorScheme) var colorScheme
    let content: Content

    init(@ViewBuilder content: () -> Content) {
        self.content = content()
    }

    var body: some View {
        ZStack {
            OakInteriorCanvas(palette: colorScheme == .dark ? .darkOak : .lightOak)
                .ignoresSafeArea()

            content
        }
    }
}

/// Palette for the oak-interior wood rendering.
struct OakPalette {
    let baseTop: Color
    let baseMid: Color
    let baseBottom: Color
    let grain: Color
    let grainOpacity: Double
    let seam: Color
    let seamOpacity: Double
    let knot: Color
    let knotOpacity: Double
    let glow: Color
    let glowOpacity: Double
    let vignetteOpacity: Double

    static let lightOak = OakPalette(
        baseTop: Color(hex: "F5EDE0"),
        baseMid: Color(hex: "EADDC6"),
        baseBottom: Color(hex: "D8C5A4"),
        grain: Color(hex: "3D2914"),
        grainOpacity: 0.045,
        seam: Color(hex: "6B4423"),
        seamOpacity: 0.06,
        knot: Color(hex: "6B4423"),
        knotOpacity: 0.05,
        glow: Color(hex: "D4A017"),
        glowOpacity: 0.07,
        vignetteOpacity: 0.05
    )

    static let darkOak = OakPalette(
        baseTop: Color(hex: "201A14"),
        baseMid: Color(hex: "2C2318"),
        baseBottom: Color(hex: "3A2E1F"),
        grain: Color(hex: "D4B896"),
        grainOpacity: 0.035,
        seam: Color(hex: "D4B896"),
        seamOpacity: 0.05,
        knot: Color(hex: "D4B896"),
        knotOpacity: 0.04,
        glow: Color(hex: "D4A017"),
        glowOpacity: 0.045,
        vignetteOpacity: 0.16
    )
}

/// Warm oak-plank interior: vertical plank seams, long cathedral grain arcs,
/// knots with growth rings, and a soft late-afternoon glow.
struct OakInteriorCanvas: View {
    let palette: OakPalette

    var body: some View {
        Canvas { ctx, size in
            let w = size.width
            let h = size.height

            // Base warm wood gradient
            ctx.fill(
                Path(CGRect(origin: .zero, size: size)),
                with: .linearGradient(
                    Gradient(colors: [palette.baseTop, palette.baseMid, palette.baseBottom]),
                    startPoint: .zero, endPoint: CGPoint(x: w * 0.15, y: h)
                )
            )

            // Vertical plank seams
            let plankCount = 4
            for i in 1..<plankCount {
                let x = w * Double(i) / Double(plankCount) + sin(Double(i) * 3.7) * 8
                var seam = Path()
                seam.move(to: CGPoint(x: x, y: 0))
                seam.addCurve(
                    to: CGPoint(x: x + 3, y: h),
                    control1: CGPoint(x: x - 4, y: h * 0.35),
                    control2: CGPoint(x: x + 6, y: h * 0.7)
                )
                ctx.stroke(seam, with: .color(palette.seam.opacity(palette.seamOpacity)), lineWidth: 1.2)
                // Faint highlight beside the seam for depth
                ctx.stroke(
                    seam.offsetBy(dx: 1.6, dy: 0),
                    with: .color(palette.baseTop.opacity(palette.seamOpacity * 0.8)),
                    lineWidth: 0.8
                )
            }

            // Long vertical grain streaks inside each plank
            for i in 0..<14 {
                let t = Double(i) / 13.0
                let x = w * (0.04 + t * 0.92) + sin(Double(i) * 12.9898) * 10
                let sway = 6.0 + 8.0 * abs(sin(Double(i) * 4.31))
                var streak = Path()
                streak.move(to: CGPoint(x: x, y: -10))
                streak.addCurve(
                    to: CGPoint(x: x + sway * 0.4, y: h + 10),
                    control1: CGPoint(x: x + sway, y: h * 0.33),
                    control2: CGPoint(x: x - sway, y: h * 0.66)
                )
                let width = 0.8 + 0.9 * abs(sin(Double(i) * 7.7))
                ctx.stroke(streak, with: .color(palette.grain.opacity(palette.grainOpacity)), lineWidth: width)
            }

            // Cathedral grain arcs (the tall arch figures in oak planks)
            let cathedrals: [(cx: Double, cy: Double, s: Double)] = [
                (0.16, 0.24, 1.0),
                (0.62, 0.52, 1.3),
                (0.36, 0.80, 0.9)
            ]
            for figure in cathedrals {
                let cx = w * figure.cx
                let cy = h * figure.cy
                for ring in 0..<4 {
                    let rw = (26.0 + Double(ring) * 20.0) * figure.s
                    let rh = rw * 2.6
                    var arch = Path()
                    arch.move(to: CGPoint(x: cx - rw, y: cy + rh))
                    arch.addCurve(
                        to: CGPoint(x: cx + rw, y: cy + rh),
                        control1: CGPoint(x: cx - rw, y: cy - rh * 0.7),
                        control2: CGPoint(x: cx + rw, y: cy - rh * 0.7)
                    )
                    ctx.stroke(
                        arch,
                        with: .color(palette.grain.opacity(palette.grainOpacity * (1.0 - Double(ring) * 0.18))),
                        lineWidth: 1.1
                    )
                }
            }

            // Knots with growth rings
            let knots: [(cx: Double, cy: Double, r: Double)] = [
                (0.82, 0.18, 9),
                (0.12, 0.62, 7),
                (0.68, 0.88, 8)
            ]
            for knot in knots {
                let center = CGPoint(x: w * knot.cx, y: h * knot.cy)
                ctx.fill(
                    Path(ellipseIn: CGRect(x: center.x - knot.r * 0.6, y: center.y - knot.r * 0.45,
                                           width: knot.r * 1.2, height: knot.r * 0.9)),
                    with: .color(palette.knot.opacity(palette.knotOpacity * 2.2))
                )
                for ring in 1...3 {
                    let rx = knot.r * (1.0 + Double(ring) * 1.1)
                    let ry = rx * 0.72
                    ctx.stroke(
                        Path(ellipseIn: CGRect(x: center.x - rx, y: center.y - ry, width: rx * 2, height: ry * 2)),
                        with: .color(palette.knot.opacity(palette.knotOpacity * (1.0 - Double(ring) * 0.22))),
                        lineWidth: 1.0
                    )
                }
            }

            // Warm light falling from the upper left, like sun through a window
            ctx.fill(
                Path(ellipseIn: CGRect(x: -w * 0.3, y: -h * 0.25, width: w * 1.1, height: h * 0.75)),
                with: .color(palette.glow.opacity(palette.glowOpacity))
            )
            // Answering golden glow near the floor
            ctx.fill(
                Path(ellipseIn: CGRect(x: w * 0.35, y: h * 0.72, width: w * 0.95, height: h * 0.55)),
                with: .color(palette.glow.opacity(palette.glowOpacity * 0.8))
            )

            // Soft vignette to keep focus on the content
            ctx.fill(
                Path(CGRect(origin: .zero, size: size)),
                with: .radialGradient(
                    Gradient(colors: [.clear, Color.black.opacity(palette.vignetteOpacity)]),
                    center: CGPoint(x: w / 2, y: h / 2),
                    startRadius: min(w, h) * 0.45,
                    endRadius: max(w, h) * 0.85
                )
            )
        }
    }
}

// Kept for compatibility with previews or future use.
struct LightOakCanvas: View {
    var body: some View { OakInteriorCanvas(palette: .lightOak) }
}

struct DarkOakCanvas: View {
    var body: some View { OakInteriorCanvas(palette: .darkOak) }
}
