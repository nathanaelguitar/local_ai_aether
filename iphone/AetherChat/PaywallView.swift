import StoreKit
import SwiftUI

struct PaywallView: View {
    @EnvironmentObject var subscription: CanopySubscriptionManager
    @Environment(\.colorScheme) private var colorScheme
    @State private var selectedPlan: PaywallPlan = .yearly
    @State private var showingTestCodeField = false
    @State private var testAccessCode = ""

    private enum PaywallPlan {
        case monthly, yearly
    }

    private var isDark: Bool { colorScheme == .dark }

    var body: some View {
        OakBackground {
            GeometryReader { geo in
                let compact = geo.size.height < 720
                ScrollView {
                    VStack(spacing: compact ? 16 : 22) {
                        Spacer(minLength: compact ? 8 : 12)

                        header(compact: compact)

                        featuresCard

                        planPicker

                        subscribeButton

                        secondaryActions

                        if showingTestCodeField {
                            testCodeField
                        }

                        if let error = subscription.errorMessage {
                            errorBanner(error)
                        }

                        legalFooter

                        Spacer(minLength: 24)
                    }
                    .padding(.horizontal, 24)
                }
                .scrollIndicators(.hidden)
            }
        }
        .task {
            await subscription.refresh()
        }
    }

    // MARK: - Header

    private func header(compact: Bool) -> some View {
        VStack(spacing: compact ? 10 : 12) {
            ZStack {
                RoundedRectangle(cornerRadius: 24, style: .continuous)
                    .fill(
                        LinearGradient(
                            colors: [AetherColors.forestMedium, Color(hex: "2F5233")],
                            startPoint: .top,
                            endPoint: .bottom
                        )
                    )
                    .frame(width: compact ? 64 : 88, height: compact ? 64 : 88)
                    .shadow(color: Color(hex: "2F5233").opacity(0.45), radius: 18, y: 8)
                Image(systemName: "tree.fill")
                    .font(.system(size: compact ? 30 : 42, weight: .medium))
                    .foregroundColor(AetherColors.oakCream)
            }
            .accessibilityHidden(true)

            VStack(spacing: 4) {
                Text("CanopyChat Plus")
                    .font(.system(size: compact ? 27 : 32, weight: .light, design: .serif))
                    .minimumScaleFactor(0.75)
                    .foregroundColor(isDark ? AetherColors.oakCream : AetherColors.oakDark)
                Text("On-device intelligence, without limits")
                    .font(.system(size: 15, weight: .regular, design: .serif))
                    .foregroundColor(AetherColors.oakLight)
            }
        }
    }

    // MARK: - Features

    private var featuresCard: some View {
        VStack(alignment: .leading, spacing: 16) {
            PaywallFeature(icon: "iphone.gen3", title: "On-device Intelligence",
                           subtitle: "Private local inference, right on your iPhone.")
            PaywallFeature(icon: "lock.shield.fill", title: "Built for privacy",
                           subtitle: "Your conversations stay on your device by default.")
            PaywallFeature(icon: "globe", title: "Search when needed",
                           subtitle: "Web-grounded, location-aware answers when you ask.")
        }
        .padding(18)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(
            RoundedRectangle(cornerRadius: 22, style: .continuous)
                .fill(cardBackground)
                .overlay(
                    RoundedRectangle(cornerRadius: 22, style: .continuous)
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
    }

    // MARK: - Plan picker

    private var planPicker: some View {
        VStack(spacing: 10) {
            planCard(
                plan: .yearly,
                title: "Yearly",
                price: yearlyPriceText,
                detail: "Billed once a year",
                badge: "Best value — save 25%"
            )
            planCard(
                plan: .monthly,
                title: "Monthly",
                price: monthlyPriceText,
                detail: "Billed monthly",
                badge: nil
            )
        }
    }

    private func planCard(plan: PaywallPlan, title: String, price: String, detail: String, badge: String?) -> some View {
        let isSelected = selectedPlan == plan
        return Button {
            withAnimation(.snappy(duration: 0.2)) {
                selectedPlan = plan
            }
        } label: {
            HStack(spacing: 14) {
                Image(systemName: isSelected ? "checkmark.circle.fill" : "circle")
                    .font(.system(size: 22, weight: .semibold))
                    .foregroundColor(isSelected ? AetherColors.oakMedium : AetherColors.warmGray400)
                    .accessibilityHidden(true)

                VStack(alignment: .leading, spacing: 2) {
                    HStack(spacing: 8) {
                        Text(title)
                            .font(.system(size: 16, weight: .semibold))
                            .foregroundColor(isDark ? AetherColors.oakCream : AetherColors.warmBlack)
                        if let badge {
                            Text(badge.uppercased())
                                .font(.system(size: 9, weight: .bold))
                                .tracking(0.6)
                                .foregroundColor(.white)
                                .padding(.horizontal, 8)
                                .padding(.vertical, 3)
                                .background(AetherColors.forestMedium)
                                .clipShape(Capsule())
                        }
                    }
                    Text(detail)
                        .font(.system(size: 12))
                        .foregroundColor(isDark ? AetherColors.warmGray400 : AetherColors.warmGray500)
                }

                Spacer()

                Text(price)
                    .font(.system(size: 17, weight: .bold))
                    .foregroundColor(isDark ? AetherColors.oakCream : AetherColors.oakDark)
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 14)
            .background(
                RoundedRectangle(cornerRadius: 18, style: .continuous)
                    .fill(
                        isSelected
                            ? (isDark ? AetherColors.warmGray800 : Color.white.opacity(0.9))
                            : (isDark ? Color.white.opacity(0.04) : Color.white.opacity(0.5))
                    )
                    .overlay(
                        RoundedRectangle(cornerRadius: 18, style: .continuous)
                            .strokeBorder(
                                isSelected
                                    ? AetherColors.oakMedium
                                    : (isDark ? Color.white.opacity(0.1) : AetherColors.oakPale.opacity(0.7)),
                                lineWidth: isSelected ? 1.5 : 1
                            )
                    )
                    .shadow(
                        color: AetherColors.oakDark.opacity(isSelected ? (isDark ? 0.35 : 0.1) : 0),
                        radius: 10, y: 4
                    )
            )
        }
        .buttonStyle(OakQuietButtonStyle())
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("\(title) plan, \(price), \(detail)")
        .accessibilityHint(isSelected ? "Selected" : "Double tap to select this plan")
        .accessibilityAddTraits(isSelected ? [.isButton, .isSelected] : .isButton)
    }

    // MARK: - Subscribe

    private var subscribeButton: some View {
        Button {
            Task {
                if selectedPlan == .yearly {
                    await subscription.purchaseYearly()
                } else {
                    await subscription.purchaseMonthly()
                }
            }
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
                    .shadow(color: AetherColors.oakDark.opacity(isDark ? 0.45 : 0.3), radius: 10, y: 4)
                if subscription.isLoading {
                    ProgressView()
                        .tint(.white)
                } else {
                    Text("Subscribe — \(selectedPlan == .yearly ? yearlyPriceText : monthlyPriceText)")
                        .font(.system(size: 17, weight: .semibold))
                        .minimumScaleFactor(0.8)
                        .foregroundColor(.white)
                        .padding(.horizontal, 12)
                }
            }
            .frame(maxWidth: .infinity)
            .frame(height: 56)
        }
        .buttonStyle(OakPrimaryButtonStyle())
        .disabled(subscription.isLoading)
        .accessibilityHint("Starts the App Store purchase for the selected plan")
    }

    // MARK: - Secondary actions

    private var secondaryActions: some View {
        HStack(spacing: 22) {
            Button {
                Task { await subscription.restorePurchases() }
            } label: {
                if subscription.isLoading {
                    ProgressView()
                        .controlSize(.small)
                        .tint(isDark ? AetherColors.oakLight : AetherColors.oakMedium)
                } else {
                    Text("Restore Purchases")
                }
            }
            .font(.system(size: 14, weight: .semibold))
            .foregroundColor(isDark ? AetherColors.oakLight : AetherColors.oakMedium)
            .buttonStyle(OakQuietButtonStyle())
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
                .foregroundColor(isDark ? AetherColors.warmGray400 : AetherColors.warmGray500)
                .buttonStyle(OakQuietButtonStyle())
            }
        }
        .padding(.top, 2)
    }

    private var testCodeField: some View {
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
                        .stroke(isDark ? Color.white.opacity(0.25) : AetherColors.oakMedium.opacity(0.45), lineWidth: 1)
                )

            Button {
                if subscription.redeemTestAccessCode(testAccessCode) {
                    testAccessCode = ""
                    withAnimation(.snappy(duration: 0.22)) {
                        showingTestCodeField = false
                    }
                }
            } label: {
                Text("Redeem")
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundColor(.white)
                    .frame(height: 46)
                    .padding(.horizontal, 16)
                    .background(AetherColors.oakMedium)
                    .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
            }
            .buttonStyle(OakPrimaryButtonStyle())
            .disabled(testAccessCode.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
        }
        .transition(.opacity.combined(with: .move(edge: .top)))
    }

    // MARK: - Error

    private func errorBanner(_ message: String) -> some View {
        VStack(spacing: 10) {
            HStack(alignment: .top, spacing: 10) {
                Image(systemName: "exclamationmark.triangle.fill")
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundColor(AetherColors.error)
                    .accessibilityHidden(true)
                Text(message)
                    .font(.system(size: 13))
                    .foregroundColor(isDark ? AetherColors.warmGray200 : AetherColors.warmGray700)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            if subscription.products.isEmpty && !subscription.isLoading {
                Button {
                    Task { await subscription.refresh() }
                } label: {
                    Text("Try Again")
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundColor(AetherColors.error)
                        .padding(.horizontal, 14)
                        .padding(.vertical, 7)
                        .background(AetherColors.error.opacity(0.12))
                        .clipShape(Capsule())
                }
                .buttonStyle(OakQuietButtonStyle())
            }
        }
        .padding(14)
        .background(
            RoundedRectangle(cornerRadius: 16, style: .continuous)
                .fill(AetherColors.error.opacity(isDark ? 0.16 : 0.08))
                .overlay(
                    RoundedRectangle(cornerRadius: 16, style: .continuous)
                        .strokeBorder(AetherColors.error.opacity(0.35), lineWidth: 1)
                )
        )
        .accessibilityElement(children: .combine)
        .transition(.opacity)
    }

    // MARK: - Legal

    private var legalFooter: some View {
        VStack(spacing: 10) {
            Text(subscriptionDetailText)
                .font(.system(size: 11))
                .foregroundColor(isDark ? AetherColors.warmGray400 : AetherColors.warmGray600)
                .multilineTextAlignment(.center)
                .lineSpacing(2)
                .fixedSize(horizontal: false, vertical: true)

            HStack(spacing: 18) {
                Link("Privacy Policy", destination: CanopyLegal.privacyPolicyURL)
                Link("Terms of Use", destination: CanopyLegal.termsOfUseURL)
            }
            .font(.system(size: 12, weight: .medium))
            .foregroundColor(isDark ? AetherColors.oakLight : AetherColors.oakMedium)
        }
    }

    // MARK: - Helpers

    private var subscriptionDetailText: String {
        let monthlyPrice = subscription.monthlyProduct?.displayPrice ?? "$9.99"
        let yearlyPrice = subscription.yearlyProduct?.displayPrice ?? "$89.99"
        return "CanopyChat Plus is available as a monthly (\(monthlyPrice)/month) or yearly (\(yearlyPrice)/year) auto-renewable subscription. It renews automatically unless cancelled at least 24 hours before the end of the current period. Manage or cancel anytime from your Apple ID subscriptions."
    }

    private var monthlyPriceText: String {
        guard let product = subscription.monthlyProduct else {
            return "$9.99"
        }
        return product.displayPrice
    }

    private var yearlyPriceText: String {
        guard let product = subscription.yearlyProduct else {
            return "$89.99"
        }
        return product.displayPrice
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
    @Environment(\.colorScheme) private var colorScheme
    let icon: String
    let title: String
    let subtitle: String

    var body: some View {
        HStack(alignment: .top, spacing: 14) {
            Image(systemName: icon)
                .font(.system(size: 15, weight: .semibold))
                .foregroundColor(AetherColors.oakMedium)
                .frame(width: 34, height: 34)
                .background(AetherColors.oakMedium.opacity(colorScheme == .dark ? 0.18 : 0.1))
                .clipShape(RoundedRectangle(cornerRadius: 9, style: .continuous))
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 3) {
                Text(title)
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundColor(colorScheme == .dark ? AetherColors.oakCream : AetherColors.warmBlack)
                Text(subtitle)
                    .font(.system(size: 13))
                    .foregroundColor(colorScheme == .dark ? AetherColors.warmGray400 : AetherColors.warmGray500)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Spacer()
        }
        .accessibilityElement(children: .combine)
    }
}

#Preview {
    PaywallView()
        .environmentObject(CanopySubscriptionManager())
}
