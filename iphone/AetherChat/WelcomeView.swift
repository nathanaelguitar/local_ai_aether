import SwiftUI

struct WelcomeView: View {
    @Environment(\.colorScheme) private var colorScheme
    @State private var visible = false
    var onEnter: () -> Void

    private var isContributorBeta: Bool { CanopyContributorProgram.isContributorBuild }
    private var isDark: Bool { colorScheme == .dark }

    private var introduction: String {
        isContributorBeta
            ? "On-device intelligence that runs locally on your iPhone — eco-friendly by design. Join the beta and help shape a better CanopyChat."
            : "Private conversations that stay on your iPhone. On-device intelligence, with web search when you need something current."
    }

    private var features: [(icon: String, tint: Color, title: String, subtitle: String)] {
        if isContributorBeta {
            return [
                ("leaf.fill", AetherColors.forestMedium, "Eco-Friendly Local Inference",
                 "Run the model already in your hand instead of a remote data center."),
                ("chart.line.uptrend.xyaxis", AetherColors.copper, "Help Improve CanopyChat",
                 "Selected prompts and responses may be shared to evaluate and improve future versions."),
                ("square.grid.2x2.fill", AetherColors.info, "Organized by Workspace",
                 "Separate Personal, Work, Creative, and Research conversations.")
            ]
        }
        return [
            ("lock.shield.fill", AetherColors.forestMedium, "Privacy First",
             "Conversations run locally on your iPhone by default — nothing leaves your device."),
            ("leaf.fill", AetherColors.copper, "Eco-Friendly Intelligence",
             "Use the model already in your hand instead of a data center."),
            ("globe", AetherColors.info, "Search When It Matters",
             "Web-grounded, location-aware answers when you ask.")
        ]
    }

    var body: some View {
        OakBackground {
            GeometryReader { geo in
                let compact = geo.size.height < 720
                ScrollView {
                    VStack(spacing: 0) {
                        Spacer(minLength: compact ? 20 : 56)

                        // Branding
                        VStack(spacing: compact ? 12 : 16) {
                            ZStack {
                                RoundedRectangle(cornerRadius: 30, style: .continuous)
                                    .fill(
                                        LinearGradient(
                                            colors: [AetherColors.oakLight, AetherColors.oakMedium],
                                            startPoint: .top,
                                            endPoint: .bottom
                                        )
                                    )
                                    .frame(width: compact ? 80 : 104, height: compact ? 80 : 104)
                                    .shadow(color: AetherColors.oakDark.opacity(isDark ? 0.5 : 0.3), radius: 18, y: 8)
                                Image(systemName: "tree.fill")
                                    .font(.system(size: compact ? 38 : 50, weight: .medium))
                                    .foregroundColor(AetherColors.oakCream)
                            }
                            .opacity(visible ? 1 : 0)
                            .offset(y: visible ? 0 : 30)
                            .animation(.spring(response: 0.7, dampingFraction: 0.8).delay(0.1), value: visible)

                            Text("CanopyChat")
                                .font(.system(size: compact ? 36 : 44, weight: .thin, design: .serif))
                            .minimumScaleFactor(0.7)
                            .foregroundColor(isDark ? AetherColors.oakCream : AetherColors.warmBlack)
                            .opacity(visible ? 1 : 0)
                            .animation(.easeOut(duration: 0.6).delay(0.25), value: visible)

                        if isContributorBeta {
                            Text("CONTRIBUTOR BETA")
                                .font(.system(size: 11, weight: .bold, design: .rounded))
                                .tracking(1.2)
                                .foregroundColor(AetherColors.forestMedium)
                                .padding(.horizontal, 12)
                                .padding(.vertical, 5)
                                .background(AetherColors.forestMedium.opacity(isDark ? 0.22 : 0.12))
                                .clipShape(Capsule())
                                .opacity(visible ? 1 : 0)
                                .animation(.easeOut(duration: 0.6).delay(0.3), value: visible)
                        } else {
                            Text("Rooted Intelligence")
                                .font(.system(size: 17, weight: .regular, design: .serif))
                                .foregroundColor(AetherColors.oakLight)
                                .opacity(visible ? 1 : 0)
                                .animation(.easeOut(duration: 0.6).delay(0.35), value: visible)
                        }

                        Text(introduction)
                            .font(.system(size: 15))
                            .foregroundColor(isDark ? AetherColors.warmGray400 : AetherColors.warmGray600)
                            .lineSpacing(3)
                            .multilineTextAlignment(.center)
                            .fixedSize(horizontal: false, vertical: true)
                            .padding(.top, 6)
                            .opacity(visible ? 1 : 0)
                            .animation(.easeOut(duration: 0.6).delay(0.45), value: visible)
                    }
                    .padding(.horizontal, 32)

                    Spacer(minLength: compact ? 20 : 40)

                    // Features
                    VStack(spacing: compact ? 10 : 12) {
                        ForEach(features, id: \.title) { feature in
                            WelcomeFeatureRow(
                                icon: feature.icon,
                                tint: feature.tint,
                                title: feature.title,
                                subtitle: feature.subtitle,
                                isDark: isDark
                            )
                        }
                    }
                    .padding(.horizontal, 24)
                    .opacity(visible ? 1 : 0)
                    .offset(y: visible ? 0 : 20)
                    .animation(.easeOut(duration: 0.6).delay(0.55), value: visible)

                    Spacer(minLength: compact ? 20 : 40)

                    // Primary action
                    Button(action: onEnter) {
                        Text(isContributorBeta ? "Join the Contributor Beta" : "Enter Your Grove")
                            .font(.system(size: 17, weight: .semibold))
                            .foregroundColor(.white)
                            .frame(maxWidth: .infinity)
                            .frame(height: 56)
                            .background(
                                LinearGradient(
                                    colors: [AetherColors.oakLight, AetherColors.oakMedium],
                                    startPoint: .top,
                                    endPoint: .bottom
                                )
                            )
                            .clipShape(RoundedRectangle(cornerRadius: 17, style: .continuous))
                            .shadow(color: AetherColors.oakDark.opacity(isDark ? 0.45 : 0.28), radius: 10, y: 4)
                    }
                    .buttonStyle(OakPrimaryButtonStyle())
                    .accessibilityHint(isContributorBeta
                                       ? "Shows the contributor consent details"
                                       : "Opens CanopyChat")
                    .padding(.horizontal, 24)
                    .opacity(visible ? 1 : 0)
                    .animation(.easeOut(duration: 0.6).delay(0.7), value: visible)

                    Spacer(minLength: compact ? 24 : 48)
                }
            }
            .scrollIndicators(.hidden)
        }
        }
        .onAppear { visible = true }
    }
}

private struct WelcomeFeatureRow: View {
    let icon: String
    let tint: Color
    let title: String
    let subtitle: String
    let isDark: Bool

    var body: some View {
        HStack(alignment: .top, spacing: 14) {
            Image(systemName: icon)
                .font(.system(size: 16, weight: .semibold))
                .foregroundColor(tint)
                .frame(width: 36, height: 36)
                .background(tint.opacity(isDark ? 0.2 : 0.12))
                .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))

            VStack(alignment: .leading, spacing: 3) {
                Text(title)
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundColor(isDark ? AetherColors.oakPale : AetherColors.oakDark)
                Text(subtitle)
                    .font(.system(size: 13))
                    .foregroundColor(isDark ? AetherColors.warmGray400 : AetherColors.warmGray600)
                    .lineSpacing(2)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityElement(children: .combine)
    }
}

#Preview {
    WelcomeView(onEnter: {})
}
