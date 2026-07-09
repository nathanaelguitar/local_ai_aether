import Foundation
import UIKit

enum CanopyLegal {
    static let privacyPolicyURL = URL(string: "https://nathanaelguitar.github.io/canopy_publicsite/privacy.html")!
    static let termsOfUseURL = URL(string: "https://nathanaelguitar.github.io/canopy_publicsite/terms.html")!
    static let supportURL = URL(string: "https://nathanaelguitar.github.io/canopy_publicsite/support.html")!
}

@MainActor
enum CanopyFeedback {
    nonisolated static let supportEmail = "consulting.nathanael@gmail.com"

    static func modelFeedback(message: ChatMessage, conversation: Conversation?) -> String {
        """
        CanopyChat Model Feedback

        Conversation: \(conversation?.title ?? "Unknown")
        Assistant: \(conversation?.persona.name ?? "Unknown")
        Message ID: \(message.id.uuidString)
        Timestamp: \(ISO8601DateFormatter().string(from: Date()))
        App Version: \(appVersion)
        Device: \(UIDevice.current.model)
        iOS: \(UIDevice.current.systemVersion)

        What went wrong?


        Model response:
        \(message.content.trimmingCharacters(in: .whitespacesAndNewlines))
        """
    }

    static func appIssue(conversation: Conversation? = nil) -> String {
        """
        CanopyChat Issue Report

        Conversation: \(conversation?.title ?? "Not provided")
        Timestamp: \(ISO8601DateFormatter().string(from: Date()))
        App Version: \(appVersion)
        Device: \(UIDevice.current.model)
        iOS: \(UIDevice.current.systemVersion)

        What happened?


        What did you expect?


        Steps to reproduce:
        1.
        2.
        3.
        """
    }

    private static var appVersion: String {
        let version = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "Unknown"
        let build = Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "Unknown"
        return "\(version) (\(build))"
    }
}
