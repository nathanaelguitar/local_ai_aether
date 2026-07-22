import StoreKit
import SwiftUI

struct PaywallView: View {
    @EnvironmentObject var subscription: CanopySubscriptionManager
    @Environment(\.colorScheme) private var colorScheme
    @State private var showingTestingOptions = false
    @State private var showingTestCodeField = false
    @State private var testAccessCode = ""

    var body: some View {
        OakBackground {
            ScrollView {
                VStack(spacing: 28) {
                    Spacer(minLength: 44)

                    VStack(spacing: 16) {
                        ZStack {
                            RoundedRectangle(cornerRadius: 30, style: .continuous)
                                .fill(AetherColors.oakMedium)
                                .frame(width: 112, height: 112)
                            Image(systemName: "tree.fill")
                                .font(.system(size: 52, weight: .medium))
                                .foregroundColor(AetherColors.oakCream)
                        }

                        VStack(spacing: 8) {
                            Text("CanopyChat")
                                .font(.system(size: 44, weight: .thin, design: .serif))
                                .foregroundColor(colorScheme == .dark ? AetherColors.oakCream : AetherColors.oakDark)
                            Text("Eco-Friendly Intelligence")
                                .font(.system(size: 18, weight: .regular, design: .serif))
                                .foregroundColor(AetherColors.oakLight)
                        }
                    }

                    VStack(alignment: .leading, spacing: 16) {
                        PaywallFeature(icon: "iphone.gen3", title: "On-device LLM", subtitle: "Run private chats locally on your iPhone.")
                        PaywallFeature(icon: "lock.shield", title: "Built for privacy", subtitle: "Your conversations stay on your device by default.")
                        PaywallFeature(icon: "magnifyingglass", title: "Search when needed", subtitle: "Use web grounding and location-aware answers when you ask.")
                    }
                    .padding(20)
                    .background(cardBackground)
                    .clipShape(RoundedRectangle(cornerRadius: 24, style: .continuous))

                    VStack(spacing: 12) {
                        // Yearly option
                        Button {
                            Task { await subscription.purchaseYearly() }
                        } label: {
                            HStack {
                                Spacer()
                                if subscription.isLoading {
                                    ProgressView()
                                        .tint(.white)
                                } else {
                                    VStack(spacing: 2) {
                                        Text(yearlyButtonTitle)
                                            .font(.system(size: 17, weight: .semibold))
                                        Text("Save 25%")
                                            .font(.system(size: 12, weight: .medium))
                                            .opacity(0.8)
                                    }
                                }
                                Spacer()
                            }
                            .foregroundColor(.white)
                            .frame(height: 64)
                            .background(AetherColors.oakMedium)
                            .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
                        }
                        .disabled(subscription.isLoading)

                        // Monthly option
                        Button {
                            Task { await subscription.purchaseMonthly() }
                        } label: {
                            HStack {
                                Spacer()
                                Text(monthlyButtonTitle)
                                    .font(.system(size: 16, weight: .semibold))
                                Spacer()
                            }
                            .foregroundColor(AetherColors.oakMedium)
                            .frame(height: 52)
                            .background(Color.clear)
                            .overlay(
                                RoundedRectangle(cornerRadius: 18, style: .continuous)
                                    .stroke(AetherColors.oakMedium, lineWidth: 1.5)
                            )
                        }
                        .disabled(subscription.isLoading)

                        Button("Restore Purchases") {
                            Task { await subscription.restorePurchases() }
                        }
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundColor(AetherColors.oakMedium)
                        .disabled(subscription.isLoading)

                        if subscription.canRedeemTestAccessCode {
                            VStack(spacing: 10) {
                                Button(showingTestingOptions ? "Hide Testing Options" : "Testing Options") {
                                    withAnimation(.snappy(duration: 0.22)) {
                                        showingTestingOptions.toggle()
                                        if !showingTestingOptions {
                                            showingTestCodeField = false
                                            testAccessCode = ""
                                        }
                                    }
                                }
                                .font(.system(size: 12, weight: .medium))
                                .foregroundColor(AetherColors.warmGray500.opacity(0.72))

                                if showingTestingOptions {
                                    VStack(spacing: 10) {
                                        Button(showingTestCodeField ? "Hide Test Code" : "Have a test code?") {
                                            withAnimation(.snappy(duration: 0.22)) {
                                                showingTestCodeField.toggle()
                                            }
                                        }
                                        .font(.system(size: 13, weight: .semibold))
                                        .foregroundColor(AetherColors.warmGray500)

                                        if showingTestCodeField {
                                            HStack(spacing: 10) {
                                                TextField("Access code", text: $testAccessCode)
                                                    .textInputAutocapitalization(.characters)
                                                    .autocorrectionDisabled()
                                                    .font(.system(size: 15, weight: .medium))
                                                    .padding(.horizontal, 14)
                                                    .frame(height: 46)
                                                    .foregroundColor(colorScheme == .dark ? AetherColors.oakCream : AetherColors.oakDark)
                                                    .background(fieldBackground)
                                                    .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))

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
                                        }

                                        #if DEBUG
                                        Button("Continue in Debug") {
                                            subscription.debugUnlocked = true
                                        }
                                        .font(.system(size: 13, weight: .medium))
                                        .foregroundColor(AetherColors.warmGray500)
                                        #endif
                                    }
                                }
                            }
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
                            .font(.system(size: 12))
                            .foregroundColor(colorScheme == .dark ? AetherColors.warmGray400 : AetherColors.warmGray600)
                            .multilineTextAlignment(.center)

                        HStack(spacing: 18) {
                            Link("Privacy Policy", destination: CanopyLegal.privacyPolicyURL)
                            Link("Terms of Use", destination: CanopyLegal.termsOfUseURL)
                        }
                        .font(.system(size: 12, weight: .medium))
                        .foregroundColor(AetherColors.oakMedium)
                    }

                    Spacer(minLength: 44)
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
