import XCTest
@testable import AetherChat

final class AetherModelDeliveryTests: XCTestCase {
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
        try JSONDecoder().decode(AetherModelManifest.self, from: Data(source.utf8))
    }
}
