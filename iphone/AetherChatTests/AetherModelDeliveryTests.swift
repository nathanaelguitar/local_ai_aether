import XCTest
@testable import AetherChat

final class AetherModelDeliveryTests: XCTestCase {
    func testVerifiedModelRefreshesOnlyAfterTwelveHours() {
        let now = Date()
        let cached = AetherCachedPrivateModel(
            modelID: "canopy",
            version: "1.1.2",
            files: [],
            activatedAt: now.addingTimeInterval(-(60 * 60 * 11))
        )

        XCTAssertFalse(AetherPrivateModelCache.shouldRefresh(cached, now: now))
        XCTAssertTrue(AetherPrivateModelCache.shouldRefresh(
            cached,
            now: now.addingTimeInterval(60 * 60 * 2)
        ))
    }

    func testDeployedFlatManifestNormalizesToModelFile() throws {
        let manifest = try decodeManifest("""
        {
          "version": "1.1.2",
          "filename": "canopy-1.1.2.Q4_K_M.gguf",
          "download_url": "https://models.example.test/canopy.gguf",
          "size_bytes": 12345,
          "sha256": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
          "url_expires_at": "2026-07-21T00:00:00Z"
        }
        """)

        let validated = try manifest.validated()
        XCTAssertEqual(validated.model.id, "canopy")
        XCTAssertEqual(validated.model.version, "1.1.2")
        XCTAssertEqual(validated.file(role: "model")?.filename, "canopy-1.1.2.Q4_K_M.gguf")
    }

    func testValidManifestDecodesAndFindsRequiredModel() throws {
        let manifest = try decodeManifest("""
        {
          "schema_version": 1,
          "model": {
            "id": "canopy",
            "version": "1.1.2",
            "files": [
              {
                "role": "model",
                "filename": "canopy-1.1.2.Q4_K_M.gguf",
                "download_url": "https://models.example.test/canopy.gguf",
                "size_bytes": 12345,
                "sha256": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
              }
            ]
          }
        }
        """)

        XCTAssertEqual(try manifest.validated().file(role: "model")?.filename, "canopy-1.1.2.Q4_K_M.gguf")
    }

    func testManifestRejectsInsecureOrUnsafeFile() throws {
        let manifest = try decodeManifest("""
        {
          "schema_version": 1,
          "model": {
            "id": "canopy",
            "version": "1.1.2",
            "files": [
              {
                "role": "model",
                "filename": "../model.gguf",
                "download_url": "http://models.example.test/model.gguf",
                "size_bytes": 1,
                "sha256": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
              }
            ]
          }
        }
        """)

        XCTAssertThrowsError(try manifest.validated())
    }

    func testManifestRejectsUnsupportedSchema() throws {
        let manifest = try decodeManifest("""
        {
          "schema_version": 2,
          "model": {
            "id": "canopy",
            "version": "1.1.2",
            "files": [
              {
                "role": "model",
                "filename": "model.gguf",
                "download_url": "https://models.example.test/model.gguf",
                "size_bytes": 1,
                "sha256": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
              }
            ]
          }
        }
        """)

        XCTAssertThrowsError(try manifest.validated())
    }

    private func decodeManifest(_ source: String) throws -> AetherModelManifest {
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        return try decoder.decode(AetherModelManifest.self, from: Data(source.utf8))
    }
}
