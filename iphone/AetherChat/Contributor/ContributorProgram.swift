import Foundation

/// Contributor-only policy surface. Production builds must never call this API to
/// collect conversation content; it is compiled into the shared app target only so
/// the Contributor TestFlight scheme and the production scheme stay compatible.
@MainActor
enum CanopyContributorProgram {
    private static let disclosureAcknowledgedKey = "aether.contributorDisclosureAcknowledged"

    static var isContributorBuild: Bool { AetherBuildChannel.isContributor }
    static var hasAcknowledgedDisclosure: Bool {
        isContributorBuild && UserDefaults.standard.bool(forKey: disclosureAcknowledgedKey)
    }

    static func acknowledgeDisclosure() {
        guard isContributorBuild else { return }
        UserDefaults.standard.set(true, forKey: disclosureAcknowledgedKey)
    }

    static func join() {
        guard isContributorBuild else { return }
        acknowledgeDisclosure()
        AetherBetaTelemetry.shared.setEnabled(true)
    }

    static func stopContributing() {
        AetherBetaTelemetry.shared.setEnabled(false)
    }

    static let disclosure = """
    This Contributor Beta shares selected prompts and answers to help improve CanopyChat. \
    We collect failures, corrections, regenerations, and a small comparison sample. \
    Attachments and full chat histories are not included. You can stop contributing at any time; \
    unsent beta data will be deleted.
    """
}
