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
}

enum AetherBuildChannel {
    static var name: String {
        let configured = (Bundle.main.object(forInfoDictionaryKey: "AETHER_BUILD_CHANNEL") as? String)?
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .lowercased()
        return configured == "beta" ? "beta" : "production"
    }

    static var isBeta: Bool { name == "beta" }
}

struct AetherTelemetryEvent: Codable, Sendable {
    let id: UUID
    let type: AetherTelemetryEventType
    let timestamp: Date
    let channel: String
    let appVersion: String
    let device: String
    let osVersion: String
    let conversationID: UUID?
    let messageID: UUID?
    let prompt: String?
    let response: String?
    let metadata: [String: String]
}

/// Beta-only, opt-in telemetry queue. It persists locally first, then uploads a
/// batch when AETHER_BETA_TELEMETRY_ENDPOINT is configured for the beta build.
@MainActor
final class AetherBetaTelemetry {
    static let shared = AetherBetaTelemetry()

    private static let enabledKey = "aether.betaTelemetryEnabled"
    private var events: [AetherTelemetryEvent]
    private let encoder = JSONEncoder()
    private let decoder = JSONDecoder()
    private let fileURL: URL

    private(set) var isEnabled: Bool

    private init() {
        let base = try? FileManager.default.url(
            for: .applicationSupportDirectory,
            in: .userDomainMask,
            appropriateFor: nil,
            create: true
        )
        let directory = (base ?? FileManager.default.temporaryDirectory).appendingPathComponent("AetherChat", isDirectory: true)
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        fileURL = directory.appendingPathComponent("beta-telemetry.json")
        let storedEvents: [AetherTelemetryEvent]
        if let data = try? Data(contentsOf: fileURL),
           let decoded = try? JSONDecoder().decode([AetherTelemetryEvent].self, from: data) {
            storedEvents = decoded
        } else {
            storedEvents = []
        }
        events = storedEvents

        if AetherBuildChannel.isBeta {
            isEnabled = UserDefaults.standard.object(forKey: Self.enabledKey) as? Bool ?? true
        } else {
            isEnabled = false
        }
    }

    func setEnabled(_ enabled: Bool) {
        isEnabled = AetherBuildChannel.isBeta && enabled
        UserDefaults.standard.set(isEnabled, forKey: Self.enabledKey)
        if isEnabled { flushIfConfigured() }
    }

    func record(
        _ type: AetherTelemetryEventType,
        conversationID: UUID? = nil,
        messageID: UUID? = nil,
        prompt: String? = nil,
        response: String? = nil,
        metadata: [String: String] = [:]
    ) {
        guard AetherBuildChannel.isBeta, isEnabled else { return }
        let event = AetherTelemetryEvent(
            id: UUID(),
            type: type,
            timestamp: Date(),
            channel: AetherBuildChannel.name,
            appVersion: appVersion,
            device: UIDevice.current.model,
            osVersion: UIDevice.current.systemVersion,
            conversationID: conversationID,
            messageID: messageID,
            prompt: prompt.map { String($0.prefix(20_000)) },
            response: response.map { String($0.prefix(20_000)) },
            metadata: metadata
        )
        events.append(event)
        events = Array(events.suffix(2_000))
        persist()
        flushIfConfigured()
    }

    private func flushIfConfigured() {
        guard let rawEndpoint = Bundle.main.object(forInfoDictionaryKey: "AETHER_BETA_TELEMETRY_ENDPOINT") as? String,
              let endpoint = URL(string: rawEndpoint.trimmingCharacters(in: .whitespacesAndNewlines)),
              !events.isEmpty else { return }

        let batch = events
        let encoder = encoder
        Task { [weak self] in
            var request = URLRequest(url: endpoint)
            request.httpMethod = "POST"
            request.timeoutInterval = 20
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            guard let body = try? encoder.encode(["events": batch]) else { return }
            request.httpBody = body
            guard let (_, response) = try? await URLSession.shared.data(for: request),
                  let http = response as? HTTPURLResponse,
                  (200...299).contains(http.statusCode) else { return }
            await MainActor.run {
                guard let self else { return }
                let uploadedIDs = Set(batch.map(\.id))
                self.events.removeAll { uploadedIDs.contains($0.id) }
                self.persist()
            }
        }
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
