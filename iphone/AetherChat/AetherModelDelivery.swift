import CryptoKit
import Foundation
import Security

/// The untrusted network boundary for a contributor-only private model. The app
/// never contains a Hugging Face token: it receives only short-lived object URLs
/// from the Canopy delivery service.
struct AetherModelManifest: Decodable, Sendable {
    struct Model: Decodable, Sendable {
        let id: String
        let version: String
        let files: [File]
    }

    struct File: Decodable, Sendable {
        let role: String
        let filename: String
        let downloadURL: URL
        let sizeBytes: Int64
        let sha256: String
        let expiresAt: Date?

        enum CodingKeys: String, CodingKey {
            case role
            case filename
            case downloadURL = "download_url"
            case sizeBytes = "size_bytes"
            case sha256
            case expiresAt = "expires_at"
        }
    }

    let schemaVersion: Int
    let model: Model

    private enum CodingKeys: String, CodingKey {
        case schemaVersion = "schema_version"
        case model
    }

    private enum FlatCodingKeys: String, CodingKey {
        case version
        case filename
        case downloadURL = "download_url"
        case sizeBytes = "size_bytes"
        case sha256
        case expiresAt = "url_expires_at"
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        if container.contains(.model) {
            schemaVersion = try container.decode(Int.self, forKey: .schemaVersion)
            model = try container.decode(Model.self, forKey: .model)
            return
        }

        // The deployed delivery service uses a deliberately compact flat
        // response. Normalize it immediately so the downloader has one trusted
        // internal manifest shape regardless of the wire representation.
        let flat = try decoder.container(keyedBy: FlatCodingKeys.self)
        let file = File(
            role: "model",
            filename: try flat.decode(String.self, forKey: .filename),
            downloadURL: try flat.decode(URL.self, forKey: .downloadURL),
            sizeBytes: try flat.decode(Int64.self, forKey: .sizeBytes),
            sha256: try flat.decode(String.self, forKey: .sha256),
            expiresAt: try flat.decodeIfPresent(Date.self, forKey: .expiresAt)
        )
        schemaVersion = 1
        model = Model(
            id: "canopy",
            version: try flat.decode(String.self, forKey: .version),
            files: [file]
        )
    }

    func validated() throws -> Self {
        guard schemaVersion == 1 else {
            throw AetherModelDeliveryError.invalidManifest("Unsupported manifest schema.")
        }
        guard !model.id.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              !model.version.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            throw AetherModelDeliveryError.invalidManifest("The model id or version is missing.")
        }
        guard file(role: "model") != nil else {
            throw AetherModelDeliveryError.invalidManifest("The manifest does not include a language model.")
        }
        for file in model.files {
            guard file.downloadURL.scheme?.lowercased() == "https",
                  !file.filename.isEmpty,
                  file.filename == URL(fileURLWithPath: file.filename).lastPathComponent,
                  file.sizeBytes > 0,
                  file.sha256.range(of: "^[A-Fa-f0-9]{64}$", options: .regularExpression) != nil else {
                throw AetherModelDeliveryError.invalidManifest("A model file entry is invalid.")
            }
        }
        return self
    }

    func file(role: String) -> File? {
        model.files.first { $0.role.caseInsensitiveCompare(role) == .orderedSame }
    }
}

enum AetherModelDeliveryError: LocalizedError {
    case unavailable
    case invalidManifest(String)
    case registrationFailed(String)
    case manifestRequestFailed(String)
    case downloadFailed(String)
    case integrityFailed(String)

    var errorDescription: String? {
        switch self {
        case .unavailable:
            return "Private model delivery is not configured for this build."
        case .invalidManifest(let detail):
            return "CanopyChat received an invalid model manifest: \(detail)"
        case .registrationFailed(let detail):
            return "CanopyChat could not register this contributor install: \(detail)"
        case .manifestRequestFailed(let detail):
            return "CanopyChat could not request the private model: \(detail)"
        case .downloadFailed(let detail):
            return "CanopyChat could not download the private model: \(detail)"
        case .integrityFailed(let detail):
            return "CanopyChat rejected the downloaded model: \(detail)"
        }
    }
}

private struct AetherModelDeliveryConfiguration {
    let manifestEndpoint: URL
    let registrationEndpoint: URL

    static var current: Self? {
        guard AetherBuildChannel.isContributor,
              let manifestRaw = Bundle.main.object(forInfoDictionaryKey: "AETHER_MODEL_MANIFEST_ENDPOINT") as? String,
              let registrationRaw = Bundle.main.object(forInfoDictionaryKey: "AETHER_MODEL_REGISTRATION_ENDPOINT") as? String,
              let manifestEndpoint = URL(string: manifestRaw.trimmingCharacters(in: .whitespacesAndNewlines)),
              let registrationEndpoint = URL(string: registrationRaw.trimmingCharacters(in: .whitespacesAndNewlines)),
              manifestEndpoint.scheme?.lowercased() == "https",
              registrationEndpoint.scheme?.lowercased() == "https" else {
            return nil
        }
        return Self(manifestEndpoint: manifestEndpoint, registrationEndpoint: registrationEndpoint)
    }
}

/// Small Keychain wrapper for opaque installation credentials. These credentials
/// identify a beta install to the delivery service; they are not Hugging Face keys.
private enum AetherModelDeliveryKeychain {
    private static let service = "app.canopychat.model-delivery"

    static func string(for account: String) -> String? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne
        ]
        var result: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess,
              let data = result as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }

    static func set(_ value: String, for account: String) throws {
        let data = Data(value.utf8)
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account
        ]
        let attributes: [String: Any] = [
            kSecValueData as String: data,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        ]
        let updateStatus = SecItemUpdate(query as CFDictionary, attributes as CFDictionary)
        if updateStatus == errSecItemNotFound {
            var addQuery = query
            attributes.forEach { addQuery[$0.key] = $0.value }
            let addStatus = SecItemAdd(addQuery as CFDictionary, nil)
            guard addStatus == errSecSuccess else { throw KeychainFailure.status(addStatus) }
        } else if updateStatus != errSecSuccess {
            throw KeychainFailure.status(updateStatus)
        }
    }

    static func remove(_ account: String) {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account
        ]
        SecItemDelete(query as CFDictionary)
    }

    private enum KeychainFailure: LocalizedError {
        case status(OSStatus)
        var errorDescription: String? { "Keychain error \(self)" }
    }
}

/// The active version is used in contributor telemetry after a private model has
/// been activated. Production never writes this value because it never requests a
/// private-model manifest.
enum AetherActiveModelVersion {
    private static let key = "aether.activePrivateModelVersion"

    static var current: String {
        guard AetherBuildChannel.isContributor else {
            return AetherModelCatalog.aetherV1ModelVersion
        }
        return UserDefaults.standard.string(forKey: key) ?? AetherModelCatalog.aetherV1ModelVersion
    }

    static func set(_ version: String) {
        guard AetherBuildChannel.isContributor else { return }
        UserDefaults.standard.set(version, forKey: key)
    }
}

actor AetherPrivateModelDelivery {
    static let shared = AetherPrivateModelDelivery()

    private struct RegistrationRequest: Encodable {
        let installationID: String

        enum CodingKeys: String, CodingKey {
            case installationID = "install_id"
        }
    }

    private struct RegistrationResponse: Decodable {
        let installationToken: String

        private enum CodingKeys: String, CodingKey {
            case installationToken = "installation_token"
            case token
        }

        init(from decoder: Decoder) throws {
            let container = try decoder.container(keyedBy: CodingKeys.self)
            installationToken = try container.decodeIfPresent(String.self, forKey: .installationToken)
                ?? container.decode(String.self, forKey: .token)
        }
    }

    private let tokenAccount = "installation-token"
    private let installationAccount = "installation-id"

    var isConfigured: Bool { AetherModelDeliveryConfiguration.current != nil }

    func manifestIfConfigured() async throws -> AetherModelManifest? {
        guard AetherModelDeliveryConfiguration.current != nil else { return nil }
        return try await manifest()
    }

    func manifest() async throws -> AetherModelManifest {
        guard let configuration = AetherModelDeliveryConfiguration.current else {
            throw AetherModelDeliveryError.unavailable
        }
        return try await manifest(using: configuration, refreshCredential: false)
    }

    private func manifest(
        using configuration: AetherModelDeliveryConfiguration,
        refreshCredential: Bool
    ) async throws -> AetherModelManifest {
        let token = try await installationToken(using: configuration, forceRegistration: refreshCredential)
        var request = URLRequest(url: configuration.manifestEndpoint)
        request.timeoutInterval = 30
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        request.setValue(installationID, forHTTPHeaderField: "X-Canopy-Installation-ID")
        request.setValue(appVersion, forHTTPHeaderField: "X-Canopy-App-Version")

        do {
            let (data, response) = try await URLSession.shared.data(for: request)
            guard let http = response as? HTTPURLResponse else {
                throw AetherModelDeliveryError.manifestRequestFailed("No HTTP response.")
            }
            if (http.statusCode == 401 || http.statusCode == 403), !refreshCredential {
                AetherModelDeliveryKeychain.remove(tokenAccount)
                return try await manifest(using: configuration, refreshCredential: true)
            }
            guard (200..<300).contains(http.statusCode) else {
                throw AetherModelDeliveryError.manifestRequestFailed("HTTP \(http.statusCode).")
            }
            let decoder = JSONDecoder()
            decoder.dateDecodingStrategy = .iso8601
            let manifest = try decoder.decode(AetherModelManifest.self, from: data).validated()
            AetherActiveModelVersion.set(manifest.model.version)
            return manifest
        } catch let error as AetherModelDeliveryError {
            throw error
        } catch {
            throw AetherModelDeliveryError.manifestRequestFailed(error.localizedDescription)
        }
    }

    private func installationToken(
        using configuration: AetherModelDeliveryConfiguration,
        forceRegistration: Bool
    ) async throws -> String {
        if !forceRegistration, let token = AetherModelDeliveryKeychain.string(for: tokenAccount), !token.isEmpty {
            return token
        }
        var request = URLRequest(url: configuration.registrationEndpoint)
        request.httpMethod = "POST"
        request.timeoutInterval = 30
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONEncoder().encode(
            RegistrationRequest(installationID: installationID)
        )

        do {
            let (data, response) = try await URLSession.shared.data(for: request)
            guard let http = response as? HTTPURLResponse,
                  (200..<300).contains(http.statusCode) else {
                let code = (response as? HTTPURLResponse)?.statusCode ?? -1
                throw AetherModelDeliveryError.registrationFailed("HTTP \(code).")
            }
            let token = try JSONDecoder().decode(RegistrationResponse.self, from: data).installationToken
            guard token.count >= 24 else {
                throw AetherModelDeliveryError.registrationFailed("The service returned an invalid installation credential.")
            }
            try AetherModelDeliveryKeychain.set(token, for: tokenAccount)
            return token
        } catch let error as AetherModelDeliveryError {
            throw error
        } catch {
            throw AetherModelDeliveryError.registrationFailed(error.localizedDescription)
        }
    }

    private var installationID: String {
        if let stored = AetherModelDeliveryKeychain.string(for: installationAccount), !stored.isEmpty {
            return stored
        }
        let identifier = UUID().uuidString
        try? AetherModelDeliveryKeychain.set(identifier, for: installationAccount)
        return identifier
    }

    private var appVersion: String {
        let version = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "Unknown"
        let build = Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "Unknown"
        return "\(version) (\(build))"
    }
}

enum AetherModelDownloadError: Error {
    case httpStatus(Int)
    case transport(String)
    case write(String)
}

/// Streams a Range request to disk. A partial file survives a suspended app or a
/// refreshed signed URL, so the next request resumes from its current byte count.
final class AetherRangeFileDownloader: NSObject, URLSessionDataDelegate, URLSessionTaskDelegate, @unchecked Sendable {
    private let lock = NSLock()
    private var continuation: CheckedContinuation<Void, Error>?
    private var session: URLSession?
    private var fileHandle: FileHandle?
    private var destination: URL?
    private var startingOffset: Int64 = 0
    private var receivedStatus: Int?
    private var writeError: Error?

    func download(request: URLRequest, to destination: URL) async throws {
        try await withCheckedThrowingContinuation { continuation in
            lock.lock()
            self.continuation = continuation
            self.destination = destination
            startingOffset = Self.fileSize(at: destination)
            let configuration = URLSessionConfiguration.ephemeral
            configuration.timeoutIntervalForRequest = 90
            configuration.timeoutIntervalForResource = 60 * 60 * 12
            let session = URLSession(configuration: configuration, delegate: self, delegateQueue: nil)
            self.session = session
            let task = session.dataTask(with: request)
            lock.unlock()
            task.resume()
        }
    }

    func urlSession(
        _ session: URLSession,
        dataTask: URLSessionDataTask,
        didReceive response: URLResponse,
        completionHandler: @escaping (URLSession.ResponseDisposition) -> Void
    ) {
        guard let http = response as? HTTPURLResponse else {
            finish(.failure(AetherModelDownloadError.transport("No HTTP response.")))
            completionHandler(.cancel)
            return
        }
        receivedStatus = http.statusCode
        guard (200..<300).contains(http.statusCode) else {
            completionHandler(.cancel)
            return
        }

        do {
            guard let destination else { throw AetherModelDownloadError.write("No download destination.") }
            let shouldAppend = http.statusCode == 206 && startingOffset > 0
            if !shouldAppend {
                try? FileManager.default.removeItem(at: destination)
                FileManager.default.createFile(atPath: destination.path, contents: nil)
            }
            let handle = try FileHandle(forWritingTo: destination)
            if shouldAppend {
                try handle.seekToEnd()
            } else {
                try handle.truncate(atOffset: 0)
            }
            fileHandle = handle
            completionHandler(.allow)
        } catch {
            writeError = error
            completionHandler(.cancel)
        }
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        do {
            try fileHandle?.write(contentsOf: data)
        } catch {
            writeError = error
            dataTask.cancel()
        }
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        try? fileHandle?.close()
        fileHandle = nil
        if let status = receivedStatus, !(200..<300).contains(status) {
            finish(.failure(AetherModelDownloadError.httpStatus(status)))
        } else if let writeError {
            finish(.failure(AetherModelDownloadError.write(writeError.localizedDescription)))
        } else if let error {
            finish(.failure(AetherModelDownloadError.transport(error.localizedDescription)))
        } else {
            finish(.success(()))
        }
    }

    private func finish(_ result: Result<Void, Error>) {
        lock.lock()
        let continuation = continuation
        self.continuation = nil
        let session = session
        self.session = nil
        lock.unlock()
        session?.finishTasksAndInvalidate()
        continuation?.resume(with: result)
    }

    private static func fileSize(at url: URL) -> Int64 {
        (try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize).map(Int64.init) ?? 0
    }
}
