package com.nathanaelguitar.canopychat

import com.nathanaelguitar.canopychat.core.CachedPrivateModel
import com.nathanaelguitar.canopychat.core.CanopyModelManifest
import com.nathanaelguitar.canopychat.core.ModelDeliveryError
import com.nathanaelguitar.canopychat.core.PrivateModelDelivery
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Port of iphone/AetherChatTests/AetherModelDeliveryTests.swift.
 */
class ModelDeliveryTest {

    @Test
    fun verifiedModelRefreshesOnlyAfterTwelveHours() {
        val now = Instant.now()
        val cached = CachedPrivateModel(
            modelId = "canopy",
            version = "1.1.2",
            files = emptyList(),
            activatedAt = now.minusMillis(11 * 60 * 60 * 1000)
        )

        assertFalse(PrivateModelDelivery.shouldRefresh(cached, now))
        assertTrue(PrivateModelDelivery.shouldRefresh(cached, now.plusMillis(2 * 60 * 60 * 1000)))
    }

    @Test
    fun deployedFlatManifestNormalizesToModelFile() {
        val manifest = CanopyModelManifest.parse(
            JSONObject(
                """
                {
                  "version": "1.1.2",
                  "filename": "canopy-1.1.2.Q4_K_M.gguf",
                  "download_url": "https://models.example.test/canopy.gguf",
                  "size_bytes": 12345,
                  "sha256": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                  "url_expires_at": "2026-07-21T00:00:00Z"
                }
                """.trimIndent()
            )
        )

        assertEquals("canopy", manifest.modelId)
        assertEquals("1.1.2", manifest.version)
        assertEquals("canopy-1.1.2.Q4_K_M.gguf", manifest.file("model")?.filename)
        assertEquals(Instant.parse("2026-07-21T00:00:00Z"), manifest.file("model")?.expiresAt)
    }

    @Test
    fun nestedManifestDecodesAndFindsRequiredModel() {
        val manifest = CanopyModelManifest.parse(
            JSONObject(
                """
                {
                  "schema_version": 1,
                  "model": {
                    "id": "canopy",
                    "version": "1.1.2",
                    "files": [
                      {
                        "role": "model",
                        "filename": "canopy-1.1.2.Q4_K_M.gguf",
                        "download_url": "https://models.example.test/canopy-1.1.2.Q4_K_M.gguf",
                        "size_bytes": 12345,
                        "sha256": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
                      }
                    ]
                  }
                }
                """.trimIndent()
            )
        )

        assertEquals("canopy-1.1.2.Q4_K_M.gguf", manifest.file("model")?.filename)
    }

    @Test
    fun manifestRejectsInsecureOrUnsafeFile() {
        assertThrows(ModelDeliveryError::class.java) {
            CanopyModelManifest.parse(
                JSONObject(
                    """
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
                    """.trimIndent()
                )
            )
        }
    }

    @Test
    fun manifestRejectsMalformedDeliveryHost() {
        assertThrows(ModelDeliveryError::class.java) {
            CanopyModelManifest.parse(
                JSONObject(
                    """
                    {
                      "version": "1.1.2",
                      "filename": "canopy.gguf",
                      "download_url": "https://undefined.r2.cloudflarestorage.com/canopy.gguf",
                      "size_bytes": 12345,
                      "sha256": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
                    }
                    """.trimIndent()
                )
            )
        }
    }

    @Test
    fun manifestRejectsUnsupportedSchema() {
        assertThrows(ModelDeliveryError::class.java) {
            CanopyModelManifest.parse(
                JSONObject(
                    """
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
                    """.trimIndent()
                )
            )
        }
    }

    @Test
    fun cachedModelSurvivesJsonRoundTrip() {
        val cached = CachedPrivateModel(
            modelId = "canopy",
            version = "1.1.2",
            files = listOf(
                CachedPrivateModel.CachedFile("model", "canopy.gguf", 12345, "ab".repeat(32)),
                CachedPrivateModel.CachedFile("projector", "mmproj.gguf", 678, "cd".repeat(32))
            ),
            activatedAt = Instant.parse("2026-07-21T13:15:00Z")
        )

        val restored = CachedPrivateModel.fromJson(JSONObject(cached.toJson().toString()))

        assertEquals(cached.modelId, restored.modelId)
        assertEquals(cached.version, restored.version)
        assertEquals(cached.activatedAt, restored.activatedAt)
        assertEquals(cached.files, restored.files)
    }

    @Test
    fun safePathComponentSanitizesLikeIOS() {
        assertEquals("canopy-1.1.2", PrivateModelDelivery.safePathComponent("canopy-1.1.2"))
        assertEquals("canopy-1.1.2", PrivateModelDelivery.safePathComponent("canopy/1.1.2"))
        assertEquals("a-b-c", PrivateModelDelivery.safePathComponent("a b:c"))
    }
}
