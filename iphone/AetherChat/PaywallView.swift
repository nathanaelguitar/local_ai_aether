import StoreKit
import SwiftUI

struct PaywallView: View {
    @EnvironmentObject var subscription: CanopySubscriptionManager
    @Environment(\.colorScheme) private var colorScheme
    @State private var showingTestCodeField = false
    @State private var testAccessCode = ""

    private var isDark: Bool { colorScheme == .dark }

    var body: some View {
        OakBackground {
            ScrollView {
                VStack(spacing: 24) {
                    Spacer(minLength: 24)

                    VStack(spacing: 14) {
                        ZStack {
                            RoundedRectangle(cornerRadius: 24, style: .continuous)
                                .fill(
                                    LinearGradient(
                                        colors: [AetherColors.forestMedium, Color(hex: "2F5233")],
                                        startPoint: .top,
                                        endPoint: .bottom
                                    )
                                )
                                .frame(width: 96, height: 96)
                                .shadow(color: Color(hex: "2F5233").opacity(0.45), radius: 18, y: 8)
                            Image(systemName: "tree.fill")
                                .font(.system(size: 46, weight: .medium))
                                .foregroundColor(AetherColors.oakCream)
                        }

                        VStack(spacing: 4) {
                            Text("CanopyChat")
                                .font(.system(size: 38, weight: .light, design: .serif))
                                .foregroundColor(isDark ? AetherColors.oakCream : AetherColors.oakDark)
                            Text("Eco-Friendly Intelligence")
                                .font(.system(size: 16, weight: .regular, design: .serif))
                                .foregroundColor(AetherColors.oakLight)
                        }
                    }

                    VStack(alignment: .leading, spacing: 18) {
                        PaywallFeature(icon: "iphone.gen3", title: "On-device Intelligence", subtitle: "Run private chats locally on your iPhone.")
                        PaywallFeature(icon: "lock.shield", title: "Built for privacy", subtitle: "Your conversations stay on your device by default.")
                        PaywallFeature(icon: "magnifyingglass", title: "Search when needed", subtitle: "Web-grounded, location-aware answers when you ask.")
                    }
                    .padding(20)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(
                        RoundedRectangle(cornerRadius: 24, style: .continuous)
                            .fill(cardBackground)
                            .overlay(
                                RoundedRectangle(cornerRadius: 24, style: .continuous)
                                    .strokeBorder(
                                        LinearGradient(
                                            colors: isDark
                                                ? [Color.white.opacity(0.12), Color.white.opacity(0.03)]
                                                : [AetherColors.oakPale.opacity(0.8), AetherColors.oakPale.opacity(0.3)],
                                            startPoint: .top,
                                            endPoint: .bottom
                                        ),
                                        lineWidth: 1
                                    )
                            )
                            .shadow(color: AetherColors.oakDark.opacity(isDark ? 0.4 : 0.08), radius: 14, y: 5)
                    )

                    VStack(spacing: 12) {
                        // Yearly option
                        Button {
                            Task { await subscription.purchaseYearly() }
                        } label: {
                            ZStack {
                                RoundedRectangle(cornerRadius: 18, style: .continuous)
                                    .fill(
                                        LinearGradient(
                                            colors: [AetherColors.oakLight, AetherColors.oakMedium],
                                            startPoint: .top,
                                            endPoint: .bottom
                                        )
                                    )
                                    .shadow(color: AetherColors.oakDark.opacity(0.35), radius: 10, y: 4)
                                if subscription.isLoading {
                                    ProgressView()
                                        .tint(.white)
                                } else {
                                    VStack(spacing: 2) {
                                        Text(yearlyButtonTitle)
                                            .font(.system(size: 17, weight: .semibold))
                                        Text("Best value — save 25%")
                                            .font(.system(size: 12, weight: .medium))
                                            .opacity(0.85)
                                    }
                                    .foregroundColor(.white)
                                }
                            }
                            .frame(height: 64)
                        }
                        .disabled(subscription.isLoading)

                        // Monthly option
                        Button {
                            Task { await subscription.purchaseMonthly() }
                        } label: {
                            Text(monthlyButtonTitle)
                                .font(.system(size: 16, weight: .semibold))
                                .foregroundColor(AetherColors.oakMedium)
                                .frame(maxWidth: .infinity)
                                .frame(height: 52)
                                .background(
                                    RoundedRectangle(cornerRadius: 18, style: .continuous)
                                        .fill(isDark ? Color.white.opacity(0.04) : Color.white.opacity(0.5))
                                        .overlay(
                                            RoundedRectangle(cornerRadius: 18, style: .continuous)
                                                .strokeBorder(AetherColors.oakMedium.opacity(0.55), lineWidth: 1.5)
                                        )
                                )
                        }
                        .disabled(subscription.isLoading)

                        HStack(spacing: 22) {
                            Button("Restore Purchases") {
                                Task { await subscription.restorePurchases() }
                            }
                            .font(.system(size: 14, weight: .semibold))
                            .foregroundColor(AetherColors.oakMedium)
                            .disabled(subscription.isLoading)

                            if subscription.canRedeemTestAccessCode {
                                Button(showingTestCodeField ? "Hide Test Code" : "Have a test code?") {
                                    withAnimation(.snappy(duration: 0.22)) {
                                        showingTestCodeField.toggle()
                                        if !showingTestCodeField {
                                            testAccessCode = ""
                                        }
                                    }
                                }
                                .font(.system(size: 14, weight: .medium))
                                .foregroundColor(AetherColors.warmGray500)
                            }
                        }
                        .padding(.top, 4)

                        if showingTestCodeField {
                            HStack(spacing: 10) {
                                TextField("Access code", text: $testAccessCode)
                                    .textInputAutocapitalization(.characters)
                                    .autocorrectionDisabled()
                                    .font(.system(size: 15, weight: .medium))
                                    .padding(.horizontal, 14)
                                    .frame(height: 46)
                                    .foregroundColor(isDark ? AetherColors.oakCream : AetherColors.oakDark)
                                    .background(fieldBackground)
                                    .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                                    .overlay(
                                        RoundedRectangle(cornerRadius: 14, style: .continuous)
                                            .stroke(isDark ? Color.white.opacity(0.78) : Color.black.opacity(0.58), lineWidth: 1.5)
                                    )

                                Button("Redeem") {
                                    if subscription.redeemTestAccessCode(testAccessCode) {
                                        testAccessCode = ""
                                    }
                                }
                                .font(.system(size: 14, weight: .semibold))
                                .foregroundColor(.white)
                                .frame(height: 46)
                                .padding(.horizontal, 14)
                                .background(AetherColors.oakMedium)
                                .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                                .disabled(testAccessCode.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                            }
                            .transition(.opacity.combined(with: .move(edge: .top)))
                        }
                    }

                    if let error = subscription.errorMessage {
                        Text(error)
                            .font(.system(size: 13))
                            .foregroundColor(AetherColors.error)
                            .multilineTextAlignment(.center)
                            .padding(.horizontal, 8)
                    }

                    VStack(spacing: 10) {
                        Text(subscriptionDetailText)
                            .font(.system(size: 11))
                            .foregroundColor(isDark ? AetherColors.warmGray400 : AetherColors.warmGray600)
                            .multilineTextAlignment(.center)
                            .lineSpacing(2)

                        HStack(spacing: 18) {
                            Link("Privacy Policy", destination: CanopyLegal.privacyPolicyURL)
                            Link("Terms of Use", destination: CanopyLegal.termsOfUseURL)
                        }
                        .font(.system(size: 12, weight: .medium))
                        .foregroundColor(AetherColors.oakMedium)
                    }

                    Spacer(minLength: 32)
                }
                .padding(.horizontal, 24)
            }
        }
        .task {
            await subscription.refresh()
        }
    }

    private var subscriptionDetailText: String {
        let monthlyPrice = subscription.monthlyProduct?.displayPrice ?? "$9.99"
        let yearlyPrice = subscription.yearlyProduct?.displayPrice ?? "$89.99"
        return "CanopyChat Plus is available as a monthly (\(monthlyPrice)/month) or yearly (\(yearlyPrice)/year) auto-renewable subscription. It renews automatically unless cancelled at least 24 hours before the end of the current period. Manage or cancel anytime from your Apple ID subscriptions."
    }

    private var monthlyButtonTitle: String {
        guard let product = subscription.monthlyProduct else {
            return "$9.99/month"
        }
        return "\(product.displayPrice)/month"
    }

    private var yearlyButtonTitle: String {
        guard let product = subscription.yearlyProduct else {
            return "$89.99/year"
        }
        return "\(product.displayPrice)/year"
    }

    private var cardBackground: Color {
        colorScheme == .dark
            ? AetherColors.warmGray900.opacity(0.78)
            : Color.white.opacity(0.74)
    }

    private var fieldBackground: Color {
        colorScheme == .dark
            ? AetherColors.warmGray800.opacity(0.86)
            : Color.white.opacity(0.82)
    }
}

private struct PaywallFeature: View {
    let icon: String
    let title: String
    let subtitle: String

    var body: some View {
        HStack(alignment: .top, spacing: 14) {
            Image(systemName: icon)
                .font(.system(size: 20, weight: .semibold))
                .foregroundColor(AetherColors.oakMedium)
                .frame(width: 28)
            VStack(alignment: .leading, spacing: 3) {
                Text(title)
                    .font(.system(size: 16, weight: .semibold))
                Text(subtitle)
                    .font(.system(size: 13))
                    .foregroundColor(AetherColors.warmGray500)
            }
            Spacer()
        }
    }
}
