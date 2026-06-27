import SwiftUI

struct OakBackground<Content: View>: View {
    @Environment(\.colorScheme) var colorScheme
    let content: Content

    init(@ViewBuilder content: () -> Content) {
        self.content = content()
    }

    var body: some View {
        ZStack {
            if colorScheme == .dark {
                DarkOakCanvas()
            } else {
                LightOakCanvas()
            }
            content
        }
        .ignoresSafeArea()
    }
}

struct LightOakCanvas: View {
    var body: some View {
        Canvas { ctx, size in
            // Base warm gradient
            ctx.fill(
                Path(CGRect(origin: .zero, size: size)),
                with: .linearGradient(
                    Gradient(colors: [Color(hex: "F5EDE0"), Color(hex: "E8DCC8"), Color(hex: "D4C4A8")]),
                    startPoint: .zero, endPoint: CGPoint(x: 0, y: size.height)
                )
            )

            // Oak grain lines
            for i in 0..<9 {
                let yOffset = size.height * (0.1 + Double(i) * 0.11)
                var path = Path()
                path.move(to: CGPoint(x: 0, y: yOffset))
                path.addCurve(
                    to: CGPoint(x: size.width, y: yOffset - 10),
                    control1: CGPoint(x: size.width * 0.3, y: yOffset - 20),
                    control2: CGPoint(x: size.width * 0.7, y: yOffset + 25)
                )
                ctx.stroke(path, with: .color(Color(hex: "3D2914").opacity(0.04)), lineWidth: 1.5)
            }

            // Knot rings
            let knotCenters = [
                CGPoint(x: size.width * 0.15, y: size.height * 0.25),
                CGPoint(x: size.width * 0.85, y: size.height * 0.6),
                CGPoint(x: size.width * 0.4,  y: size.height * 0.85)
            ]
            for center in knotCenters {
                for ring in stride(from: 3, through: 0, by: -1) {
                    let radius = 40.0 + Double(ring) * 25.0
                    let alpha  = 0.03 - Double(ring) * 0.006
                    ctx.fill(
                        Path(ellipseIn: CGRect(x: center.x - radius, y: center.y - radius, width: radius * 2, height: radius * 2)),
                        with: .color(Color(hex: "6B4423").opacity(max(alpha, 0)))
                    )
                }
            }

            // Bottom-left golden glow
            ctx.fill(
                Path(ellipseIn: CGRect(x: -size.width * 0.25, y: size.height * 0.6, width: size.width * 0.9, height: size.height * 0.6)),
                with: .color(Color(hex: "D4A017").opacity(0.06))
            )
        }
    }
}

struct DarkOakCanvas: View {
    var body: some View {
        Canvas { ctx, size in
            ctx.fill(
                Path(CGRect(origin: .zero, size: size)),
                with: .linearGradient(
                    Gradient(colors: [Color(hex: "1A1612"), Color(hex: "2C2420"), Color(hex: "3D342E")]),
                    startPoint: .zero, endPoint: CGPoint(x: 0, y: size.height)
                )
            )
            for i in 0..<7 {
                let yOffset = size.height * (0.15 + Double(i) * 0.14)
                var path = Path()
                path.move(to: CGPoint(x: 0, y: yOffset))
                path.addLine(to: CGPoint(x: size.width, y: yOffset + (Double(i % 2) - 0.5) * 15))
                ctx.stroke(path, with: .color(Color(hex: "D4B896").opacity(0.025)), lineWidth: 1.5)
            }
        }
    }
}
