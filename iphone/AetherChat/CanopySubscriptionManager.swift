import Foundation
import StoreKit

@MainActor
final class CanopySubscriptionManager: ObservableObject {
    static let monthlyProductID = "com.nathanaelguitar.canopychat.monthly"
    static let yearlyProductID = "com.nathanaelguitar.canopychat.yearly"
    static let testAccessCode = "CANOPY-TEST"
    private static let testAccessKey = "canopy.testAccessUnlocked"

    @Published private(set) var products: [Product] = []
    @Published private(set) var isSubscribed = false
    @Published private(set) var isLoading = false
    @Published private(set) var testAccessUnlocked = UserDefaults.standard.bool(forKey: CanopySubscriptionManager.testAccessKey)
    @Published var errorMessage: String?

    #if DEBUG
    @Published var debugUnlocked = false
    #endif

    private var updatesTask: Task<Void, Never>?

    var monthlyProduct: Product? {
        products.first { $0.id == Self.monthlyProductID }
    }

    var yearlyProduct: Product? {
        products.first { $0.id == Self.yearlyProductID }
    }

    var monthlyProductIsAvailable: Bool {
        monthlyProduct != nil
    }

    var hasPremiumAccess: Bool {
        #if DEBUG
        return isSubscribed || debugUnlocked || testAccessActive
        #else
        return isSubscribed || testAccessActive
        #endif
    }

    var canRedeemTestAccessCode: Bool {
        isSandboxOrDebugBuild
    }

    private var testAccessActive: Bool {
        testAccessUnlocked && isSandboxOrDebugBuild
    }

    private var isSandboxOrDebugBuild: Bool {
        #if DEBUG
        return true
        #elseif targetEnvironment(simulator)
        return true
        #else
        return Bundle.main.appStoreReceiptURL?.lastPathComponent == "sandboxReceipt"
        #endif
    }

    init() {
        updatesTask = observeTransactionUpdates()
        Task {
            await refresh()
        }
    }

    deinit {
        updatesTask?.cancel()
    }

    func refresh() async {
        isLoading = true
        defer { isLoading = false }

        do {
            products = try await Product.products(for: [Self.monthlyProductID, Self.yearlyProductID])
            await updateSubscriptionStatus()
            errorMessage = nil
        } catch {
            errorMessage = "Could not load subscription products. Check App Store Connect or StoreKit testing setup."
        }
    }

    func purchaseMonthly() async {
        guard let product = monthlyProduct else {
            errorMessage = "Monthly subscription is temporarily unavailable. Please try again later."
            return
        }

        isLoading = true
        defer { isLoading = false }

        do {
            let result = try await product.purchase()
            switch result {
            case .success(.verified(let transaction)):
                await transaction.finish()
                await updateSubscriptionStatus()
                errorMessage = nil
            case .success(.unverified):
                errorMessage = "Purchase could not be verified."
            case .pending:
                errorMessage = "Purchase is pending approval."
            case .userCancelled:
                break
            @unknown default:
                errorMessage = "Purchase could not be completed."
            }
        } catch {
            errorMessage = "Purchase failed: \(error.localizedDescription)"
        }
    }

    func purchaseYearly() async {
        guard let product = yearlyProduct else {
            errorMessage = "Yearly subscription is temporarily unavailable. Please try again later."
            return
        }

        isLoading = true
        defer { isLoading = false }

        do {
            let result = try await product.purchase()
            switch result {
            case .success(.verified(let transaction)):
                await transaction.finish()
                await updateSubscriptionStatus()
                errorMessage = nil
            case .success(.unverified):
                errorMessage = "Purchase could not be verified."
            case .pending:
                errorMessage = "Purchase is pending approval."
            case .userCancelled:
                break
            @unknown default:
                errorMessage = "Purchase could not be completed."
            }
        } catch {
            errorMessage = "Purchase failed: \(error.localizedDescription)"
        }
    }

    func restorePurchases() async {
        isLoading = true
        defer { isLoading = false }

        do {
            try await AppStore.sync()
            await updateSubscriptionStatus()
            errorMessage = isSubscribed ? nil : "No active subscription was found for this Apple ID."
        } catch {
            errorMessage = "Restore failed: \(error.localizedDescription)"
        }
    }

    func redeemTestAccessCode(_ code: String) -> Bool {
        guard canRedeemTestAccessCode else {
            errorMessage = "Test access codes are only available in Debug, Simulator, or TestFlight sandbox builds."
            return false
        }

        let normalizedCode = code
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .uppercased()
        guard normalizedCode == Self.testAccessCode else {
            errorMessage = "That test access code was not recognized."
            return false
        }

        testAccessUnlocked = true
        UserDefaults.standard.set(true, forKey: Self.testAccessKey)
        errorMessage = nil
        return true
    }

    func resetTestAccess() {
        guard canRedeemTestAccessCode else { return }
        testAccessUnlocked = false
        UserDefaults.standard.removeObject(forKey: Self.testAccessKey)
        errorMessage = nil
    }

    private func observeTransactionUpdates() -> Task<Void, Never> {
        Task { [weak self] in
            for await result in Transaction.updates {
                guard case .verified(let transaction) = result else { continue }
                await transaction.finish()
                await self?.updateSubscriptionStatus()
            }
        }
    }

    private func updateSubscriptionStatus() async {
        var active = false
        for await result in Transaction.currentEntitlements {
            guard case .verified(let transaction) = result else { continue }
            guard transaction.productID == Self.monthlyProductID || transaction.productID == Self.yearlyProductID else { continue }
            guard transaction.revocationDate == nil else { continue }
            if let expirationDate = transaction.expirationDate, expirationDate <= Date() {
                continue
            }
            active = true
            break
        }
        isSubscribed = active
    }
}
