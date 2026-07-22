import SwiftUI

struct WelcomeView: View {
    @State private var visible = false
    var onEnter: () -> Void

    private var isContributorBeta: Bool { CanopyContributorProgram.isContributorBuild }

    private var introduction: String {
        isContributorBeta
            ? "Efficient on-device intelligence.\nHelp shape a better CanopyChat."
            : "Private conversations that stay close.\nOn-device intelligence, built to tread lightly."
    }

    private var primaryFeature: (icon: String, title: String, subtitle: String) {
        isContributorBeta
            ? ("🌿", "Efficient On-Device LLM", "Run the model already in your hand instead of a remote data center")
            : ("🔒", "Privacy First", "CanopyChat runs locally on your iPhone by default")
    }

    private var secondaryFeature: (icon: String, title: String, subtitle: String) {
        isContributorBeta
            ? ("🧪", "Help Improve the Model", "Selected interactions are shared to evaluate and improve future versions")
            : ("🌿", "Eco-Friendly Intelligence", "Use the model already in your hand instead of a data center")
    }

    var body: some View {
        OakBackground {
            ScrollView {
                VStack(spacing: 0) {
                    Spacer(minLength: 80)

                    // Logo
                    VStack(spacing: 16) {
                        ZStack {
                            RoundedRectangle(cornerRadius: 28, style: .continuous)
                                .fill(AetherColors.oakMedium)
                                .frame(width: 110, height: 110)
                            Image(systemName: "tree.fill")
                                .font(.system(size: 52))
                                .foregroundColor(AetherColors.oakCream)
                        }
                        .opacity(visible ? 1 : 0)
                        .offset(y: visible ? 0 : 30)
                        .animation(.spring(response: 0.7, dampingFraction: 0.8).delay(0.1), value: visible)

                        Text("CanopyChat")
                            .font(.system(size: 48, weight: .thin, design: .serif))
                            .foregroundColor(AetherColors.warmBlack)
                            .opacity(visible ? 1 : 0)
                            .animation(.easeOut(duration: 0.6).delay(0.25), value: visible)

                        Text("Rooted Intelligence")
                            .font(.system(size: 18, weight: .regular, design: .serif))
                            .foregroundColor(AetherColors.oakLight)
                            .opacity(visible ? 1 : 0)
                            .animation(.easeOut(duration: 0.6).delay(0.35), value: visible)

                        Text(introduction)
                            .font(.system(size: 15))
                            .foregroundColor(AetherColors.warmGray600)
                            .multilineTextAlignment(.center)
                            .padding(.top, 8)
                            .opacity(visible ? 1 : 0)
                            .animation(.easeOut(duration: 0.6).delay(0.45), value: visible)
                    }
                    .padding(.horizontal, 32)

                    Spacer(minLength: 48)

                    // Features
                    VStack(spacing: 20) {
                        FeatureRow(icon: primaryFeature.icon, title: primaryFeature.title,
                                   subtitle: primaryFeature.subtitle)
                        FeatureRow(icon: secondaryFeature.icon, title: secondaryFeature.title,
                                   subtitle: secondaryFeature.subtitle)
                        FeatureRow(icon: "🌳", title: "Organized by Workspace",
                                   subtitle: "Separate Personal, Work, Creative, and Research conversations")
                    }
                    .padding(.horizontal, 32)
                    .opacity(visible ? 1 : 0)
                    .offset(y: visible ? 0 : 20)
                    .animation(.easeOut(duration: 0.6).delay(0.55), value: visible)

                    Spacer(minLength: 48)

                    // CTA
                    Button(action: onEnter) {
                        Text(isContributorBeta ? "Join the Contributor Beta" : "Enter Your Grove")
                            .font(.system(size: 17, weight: .medium))
                            .foregroundColor(.white)
                            .frame(maxWidth: .infinity)
                            .frame(height: 56)
                            .background(AetherColors.oakMedium)
                            .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                    }
                    .padding(.horizontal, 32)
                    .opacity(visible ? 1 : 0)
                    .animation(.easeOut(duration: 0.6).delay(0.7), value: visible)

                    Spacer(minLength: 60)
                }
            }
        }
        .onAppear { visible = true }
    }
}

struct FeatureRow: View {
    @Environment(\.colorScheme) private var colorScheme
    let icon: String
    let title: String
    let subtitle: String

    var body: some View {
        HStack(alignment: .top, spacing: 16) {
            Text(icon).font(.system(size: 32))
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundColor(colorScheme == .dark ? AetherColors.oakPale : AetherColors.oakDark)
                Text(subtitle)
                    .font(.system(size: 13))
                    .foregroundColor(colorScheme == .dark ? AetherColors.warmGray400 : AetherColors.warmGray600)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}
