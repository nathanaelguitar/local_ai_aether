import CryptoKit
import Foundation
import UIKit

enum CanopyLegal {
    static let privacyPolicyURL = URL(string: "https://nathanaelguitar.github.io/canopy_publicsite/privacy.html")!
    static let termsOfUseURL = URL(string: "https://nathanaelguitar.github.io/canopy_publicsite/terms.html")!
    static let supportURL = URL(string: "https://nathanaelguitar.github.io/canopy_publicsite/support.html")!
}

enum AetherFeedbackRating: String, Codable, Sendable {
    case positive
    case negative
}

enum AetherTelemetryEventType: String, Codable, Sendable {
    case responseGenerated
    case responseRated
    case responseRegenerated
    case messageResent
    case searchSuggested
    case searchChosen
    case webSearchPerformed
    case issueReported
    case responseTruncated
    case responseEmpty
    case inferenceFailed
    case toolFailed
    case outputValidationFailed
    case userCorrection
}

enum AetherBuildChannel {
    static var name: String {
        let configured = (Bundle.main.object(forInfoDictionaryKey: "AETHER_BUILD_CHANNEL") as? String)?
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .lowercased()
        return ["contributor", "beta"].contains(configured) ? "contributor" : "production"
    }

    static var isContributor: Bool { name == "contributor" }
}

struct AetherTelemetryEvent: Codable, Sendable {
    let id: UUID
    let type: AetherTelemetryEventType
    let timestamp: Date
    let channel: String
    let appVersion: String
    let modelVersion: String?
    let conversationID: UUID?
    let messageID: UUID?
    let prompt: String?
    let response: String?
    let metadata: [String: String]
}

private struct AetherContributorBatch: Encodable {
    let schemaVersion = 1
    let batchID: UUID
    let installationID: UUID
    let sentAt: Date
    let consentForModelImprovement = true
    let events: [AetherTelemetryEvent]

    enum CodingKeys: String, CodingKey {
        case schemaVersion = "schema_version"
        case batchID = "batch_id"
        case installationID = "installation_id"
        case sentAt = "sent_at"
        case consentForModelImprovement = "consent_for_model_improvement"
        case events
    }
}

private struct AetherPendingContributorUpload: Codable {
    let batchID: UUID
    let eventIDs: [UUID]
    let timestamp: String
    let body: Data
}

private struct AetherContributorReceipt: Decodable {
    let receiptID: String
    let batchID: UUID

    enum CodingKeys: String, CodingKey {
        case receiptID = "receipt_id"
        case batchID = "batch_id"
    }
}

/// Contributor-beta-only, explicit-opt-in telemetry queue. It retains events locally,
/// selects failure-linked interactions plus a deterministic control sample, and only
/// deletes an upload after the contributor ingestion service acknowledges its receipt.
@MainActor
final class AetherBetaTelemetry {
    static let shared = AetherBetaTelemetry()

    private static let enabledKey = "aether.betaTelemetryEnabled"
    private static let installationIDKey = "aether.contributorInstallationID"
    private var events: [AetherTelemetryEvent]
    private let encoder = JSONEncoder()
    private let wireEncoder: JSONEncoder
    private let decoder = JSONDecoder()
    private let fileURL: URL
    private let pendingUploadURL: URL
    private var pendingUpload: AetherPendingContributorUpload?
    private var isFlushing = false
    private var retryAttempt = 0
    private var retryTask: Task<Void, Never>?
    private var batchDeadlineTask: Task<Void, Never>?

    private(set) var isEnabled: Bool

    private init() {
        let configuredWireEncoder = JSONEncoder()
        configuredWireEncoder.dateEncodingStrategy = .iso8601
        wireEncoder = configuredWireEncoder
        let base = try? FileManager.default.url(
            for: .applicationSupportDirectory,
            in: .userDomainMask,
            appropriateFor: nil,
            create: true
        )
        let directory = (base ?? FileManager.default.temporaryDirectory).appendingPathComponent("AetherChat", isDirectory: true)
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        fileURL = directory.appendingPathComponent("beta-telemetry.json")
        pendingUploadURL = directory.appendingPathComponent("beta-contributor-pending-upload.json")
        let storedEvents: [AetherTelemetryEvent]
        if let data = try? Data(contentsOf: fileURL),
           let decoded = try? JSONDecoder().decode([AetherTelemetryEvent].self, from: data) {
            storedEvents = decoded
        } else {
            storedEvents = []
        }
        events = storedEvents
        let pendingDecoder = JSONDecoder()
        pendingUpload = (try? Data(contentsOf: pendingUploadURL)).flatMap { try? pendingDecoder.decode(AetherPendingContributorUpload.self, from: $0) }

        if AetherBuildChannel.isContributor {
            // Sending model-improvement data always requires an affirmative user choice.
            isEnabled = UserDefaults.standard.object(forKey: Self.enabledKey) as? Bool ?? false
        } else {
            isEnabled = false
        }
    }

    func setEnabled(_ enabled: Bool) {
        isEnabled = AetherBuildChannel.isContributor && enabled
        UserDefaults.standard.set(isEnabled, forKey: Self.enabledKey)
        if isEnabled {
            flushIfConfigured()
        } else {
            // Consent withdrawal stops future collection and clears unsent content.
            events.removeAll()
            pendingUpload = nil
            retryTask?.cancel()
            retryTask = nil
            batchDeadlineTask?.cancel()
            batchDeadlineTask = nil
            persist()
            try? FileManager.default.removeItem(at: pendingUploadURL)
        }
    }

    func record(
        _ type: AetherTelemetryEventType,
        conversationID: UUID? = nil,
        messageID: UUID? = nil,
        prompt: String? = nil,
        response: String? = nil,
        metadata: [String: String] = [:]
    ) {
        guard AetherBuildChannel.isContributor, isEnabled else { return }
        let event = AetherTelemetryEvent(
            id: UUID(),
            type: type,
            timestamp: Date(),
            channel: AetherBuildChannel.name,
            appVersion: appVersion,
            modelVersion: AetherModelCatalog.aetherV1ModelVersion,
            conversationID: conversationID,
            messageID: messageID,
            prompt: prompt.map { String($0.prefix(20_000)) },
            response: response.map { String($0.prefix(20_000)) },
            metadata: metadata
        )
        events.append(event)
        pruneExpiredEvents()
        events = Array(events.suffix(2_000))
        persist()
        flushIfConfigured()
    }

    /// Called when the contributor app becomes active so a queued 24-hour batch
    /// gets another upload opportunity without affecting production builds.
    func flushPendingBatch() {
        guard AetherBuildChannel.isContributor, isEnabled else { return }
        flushIfConfigured()
    }

    private func flushIfConfigured() {
        guard !isFlushing,
              let configuration = uploadConfiguration() else { return }
        guard let upload = pendingUpload ?? makePendingUpload() else { return }
        isFlushing = true
        pendingUpload = upload
        persistPendingUpload()

        Task { @MainActor [weak self] in
            guard let self else { return }
            defer { self.isFlushing = false }
            let backgroundTask = UIApplication.shared.beginBackgroundTask(withName: "Canopy contributor upload")
            defer { UIApplication.shared.endBackgroundTask(backgroundTask) }
            var request = URLRequest(url: configuration.endpoint)
            request.httpMethod = "POST"
            request.timeoutInterval = 30
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            request.setValue(upload.timestamp, forHTTPHeaderField: "X-Canopy-Timestamp")
            request.setValue(self.hmacSignature(secret: configuration.secret, timestamp: upload.timestamp, body: upload.body), forHTTPHeaderField: "X-Canopy-Signature")
            request.httpBody = upload.body

            do {
                let (data, response) = try await URLSession.shared.data(for: request)
                guard let http = response as? HTTPURLResponse,
                      (200...299).contains(http.statusCode),
                      let receipt = try? self.decoder.decode(AetherContributorReceipt.self, from: data),
                      receipt.batchID == upload.batchID,
                      !receipt.receiptID.isEmpty else {
                    self.scheduleRetry()
                    return
                }
                self.events.removeAll { upload.eventIDs.contains($0.id) }
                self.pendingUpload = nil
                self.retryAttempt = 0
                self.retryTask?.cancel()
                self.retryTask = nil
                self.persist()
                try? FileManager.default.removeItem(at: self.pendingUploadURL)
            } catch {
                self.scheduleRetry()
            }
        }
    }

    private func makePendingUpload() -> AetherPendingContributorUpload? {
        let selectedEvents = eligibleEventsForUpload()
        guard !selectedEvents.isEmpty else { return nil }
        let batchID = UUID()
        let timestamp = ISO8601DateFormatter().string(from: Date())
        let batch = AetherContributorBatch(
            batchID: batchID,
            installationID: installationID,
            sentAt: Date(),
            events: selectedEvents
        )
        guard let body = try? wireEncoder.encode(batch) else { return nil }
        return AetherPendingContributorUpload(
            batchID: batchID,
            eventIDs: selectedEvents.map(\.id),
            timestamp: timestamp,
            body: body
        )
    }

    private func eligibleEventsForUpload() -> [AetherTelemetryEvent] {
        var selected: [AetherTelemetryEvent] = []
        var selectedIDs = Set<UUID>()
        let responses = events.filter { $0.type == .responseGenerated }
        for response in responses {
            let related = events.filter { event in
                event.id == response.id || (response.messageID != nil && event.messageID == response.messageID)
            }
            let isFailure = related.contains(where: isExplicitFailure)
            guard isFailure || isControlSample(response.id) else { continue }
            for event in related where selected.count < 100 && selectedIDs.insert(event.id).inserted {
                selected.append(event)
            }
        }
        guard let firstCandidateAt = selected.map(\.timestamp).min() else { return [] }
        if selected.count < 50 {
            let deadline = firstCandidateAt.addingTimeInterval(24 * 60 * 60)
            guard deadline <= Date() else {
                scheduleBatchDeadline(at: deadline)
                return []
            }
        }
        batchDeadlineTask?.cancel()
        batchDeadlineTask = nil
        return Array(selected.prefix(100))
    }

    private func isExplicitFailure(_ event: AetherTelemetryEvent) -> Bool {
        switch event.type {
        case .responseRated:
            return event.metadata["rating"] == AetherFeedbackRating.negative.rawValue
        case .responseRegenerated, .responseTruncated, .responseEmpty, .inferenceFailed,
             .toolFailed, .outputValidationFailed, .userCorrection:
            return true
        default:
            return event.metadata["truncated"] == "true" || event.metadata["validation_failed"] == "true"
        }
    }

    private func isControlSample(_ id: UUID) -> Bool {
        let digest = Data(SHA256.hash(data: Data(id.uuidString.utf8)))
        // Stable 2% selection keeps the beta's success-control group bounded.
        return Int(digest.first ?? 100) % 100 < 2
    }

    private func uploadConfiguration() -> (endpoint: URL, secret: String)? {
        guard let rawEndpoint = Bundle.main.object(forInfoDictionaryKey: "AETHER_BETA_TELEMETRY_ENDPOINT") as? String,
              let endpoint = URL(string: rawEndpoint.trimmingCharacters(in: .whitespacesAndNewlines)),
              ["https", "http"].contains(endpoint.scheme?.lowercased() ?? ""),
              let secret = Bundle.main.object(forInfoDictionaryKey: "AETHER_BETA_TELEMETRY_HMAC_SECRET") as? String,
              secret.count >= 32,
              !secret.contains("$(") else { return nil }
        return (endpoint, secret)
    }

    private func hmacSignature(secret: String, timestamp: String, body: Data) -> String {
        var signed = Data(timestamp.utf8)
        signed.append(0x2E)
        signed.append(body)
        let key = SymmetricKey(data: Data(secret.utf8))
        let code = HMAC<SHA256>.authenticationCode(for: signed, using: key)
        return "sha256=" + Data(code).map { String(format: "%02x", $0) }.joined()
    }

    private func scheduleRetry() {
        guard isEnabled, retryTask == nil || retryTask?.isCancelled == true else { return }
        let delay = UInt64(min(pow(2.0, Double(retryAttempt)), 300.0) * 1_000_000_000)
        retryAttempt = min(retryAttempt + 1, 8)
        retryTask = Task { @MainActor [weak self] in
            try? await Task.sleep(nanoseconds: delay)
            guard !Task.isCancelled else { return }
            self?.retryTask = nil
            self?.flushIfConfigured()
        }
    }

    private func scheduleBatchDeadline(at deadline: Date) {
        guard batchDeadlineTask == nil else { return }
        let delay = UInt64(max(1, deadline.timeIntervalSinceNow) * 1_000_000_000)
        batchDeadlineTask = Task { @MainActor [weak self] in
            try? await Task.sleep(nanoseconds: delay)
            guard !Task.isCancelled else { return }
            self?.batchDeadlineTask = nil
            self?.flushIfConfigured()
        }
    }

    private func pruneExpiredEvents() {
        let cutoff = Date().addingTimeInterval(-48 * 60 * 60)
        let protectedIDs = Set(pendingUpload?.eventIDs ?? [])
        events.removeAll { $0.timestamp < cutoff && !protectedIDs.contains($0.id) }
    }

    private var installationID: UUID {
        if let rawValue = UserDefaults.standard.string(forKey: Self.installationIDKey),
           let id = UUID(uuidString: rawValue) {
            return id
        }
        let id = UUID()
        UserDefaults.standard.set(id.uuidString, forKey: Self.installationIDKey)
        return id
    }

    private func persistPendingUpload() {
        guard let pendingUpload,
              let data = try? encoder.encode(pendingUpload) else { return }
        try? data.write(to: pendingUploadURL, options: .atomic)
    }

    private func persist() {
        guard let data = try? encoder.encode(events) else { return }
        try? data.write(to: fileURL, options: .atomic)
    }

    private var appVersion: String {
        let version = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "Unknown"
        let build = Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "Unknown"
        return "\(version) (\(build))"
    }
}

@MainActor
enum CanopyFeedback {
    nonisolated static let supportEmail = "support@canopychat.app"

    static func modelFeedback(message: ChatMessage, conversation: Conversation?) -> String {
        let prompt = promptText(for: message, conversation: conversation)
        let cleanedResponse = plainText(message.content.trimmingCharacters(in: .whitespacesAndNewlines))
        return """
        CanopyChat Model Feedback

        Thanks for taking a moment to report this. Your feedback helps us improve the model and make CanopyChat more useful. We work hard to provide the best service to our customers.

        WHAT WENT WRONG?
        Please tell us what was incorrect, confusing, incomplete, or unexpected.

        WHAT WERE YOU EXPECTING?
        If you can, describe the answer or behavior you wanted instead.


        USER PROMPT
        \(prompt)


        MODEL RESPONSE
        \(cleanedResponse)

        Thank you for helping us make CanopyChat better.

        TECHNICAL DETAILS FOR SUPPORT
        Conversation: \(conversation?.title ?? "Unknown")
        Assistant: \(conversation?.persona.name ?? "Unknown")
        Message ID: \(message.id.uuidString)
        Timestamp: \(ISO8601DateFormatter().string(from: Date()))
        App Version: \(appVersion)
        Device: \(UIDevice.current.model)
        iOS: \(UIDevice.current.systemVersion)
        """
    }

    private static func promptText(for message: ChatMessage, conversation: Conversation?) -> String {
        guard let conversation,
              let responseIndex = conversation.messages.firstIndex(where: { $0.id == message.id }),
              let userMessage = conversation.messages[..<responseIndex].last(where: { $0.role == .user }) else {
            return "(Prompt text unavailable.)"
        }

        let text = plainText(userMessage.content.trimmingCharacters(in: .whitespacesAndNewlines))
        if !text.isEmpty {
            return text
        }

        let attachmentNames = userMessage.attachments.map(\.displayName).joined(separator: ", ")
        return attachmentNames.isEmpty
            ? "(No text prompt.)"
            : "(Attachment-only prompt: \(attachmentNames))"
    }

    static func appIssue(conversation: Conversation? = nil) -> String {
        """
        CanopyChat Issue Report

        Thanks for helping us improve CanopyChat. The details below will help us understand and fix the problem.

        WHAT HAPPENED?
        Please describe what went wrong.


        WHAT DID YOU EXPECT?
        Please describe the behavior you expected.


        STEPS TO REPRODUCE
        1.
        2.
        3.

        Thank you for helping us make CanopyChat better.

        TECHNICAL DETAILS FOR SUPPORT
        Conversation: \(conversation?.title ?? "Not provided")
        Timestamp: \(ISO8601DateFormatter().string(from: Date()))
        App Version: \(appVersion)
        Device: \(UIDevice.current.model)
        iOS: \(UIDevice.current.systemVersion)
        """
    }

    private static func plainText(_ text: String) -> String {
        text
            .replacingOccurrences(of: #"\*\*(.*?)\*\*"#, with: "$1", options: .regularExpression)
            .replacingOccurrences(of: #"__(.*?)__"#, with: "$1", options: .regularExpression)
            .replacingOccurrences(of: #"`([^`]+)`"#, with: "$1", options: .regularExpression)
            .replacingOccurrences(of: #"\[([^\]]+)\]\([^\)]+\)"#, with: "$1", options: .regularExpression)
            .replacingOccurrences(of: #"^\s{0,3}#{1,6}\s*"#, with: "", options: [.regularExpression, .caseInsensitive])
    }

    static func mailURL(subject: String, body: String) -> URL? {
        var components = URLComponents()
        components.scheme = "mailto"
        components.path = supportEmail
        components.queryItems = [
            URLQueryItem(name: "subject", value: subject),
            URLQueryItem(name: "body", value: body)
        ]
        return components.url
    }

    private static var appVersion: String {
        let version = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "Unknown"
        let build = Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "Unknown"
        return "\(version) (\(build))"
    }
}
