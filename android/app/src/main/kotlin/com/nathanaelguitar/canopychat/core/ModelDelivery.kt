package com.nathanaelguitar.canopychat.core

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.util.UUID

// Kotlin port of iphone/AetherChat/AetherModelDelivery.swift. The app never contains a
// Hugging Face token: it receives only short-lived object URLs from the Canopy delivery
// service (see docs/MODEL_DELIVERY_API.md).

class ModelDeliveryError(message: String) : Exception(message) {
    companion object {
        fun unavailable() = ModelDeliveryError("Private model delivery is not configured for this build.")
        fun invalidManifest(detail: String) =
            ModelDeliveryError("CanopyChat received an invalid model manifest: $detail")
        fun registrationFailed(detail: String) =
            ModelDeliveryError("CanopyChat could not register this contributor install: $detail")
        fun manifestRequestFailed(detail: String) =
            ModelDeliveryError("CanopyChat could not request the private model: $detail")
        fun downloadFailed(detail: String) =
            ModelDeliveryError("CanopyChat could not download the private model: $detail")
        fun integrityFailed(detail: String) =
            ModelDeliveryError("CanopyChat rejected the downloaded model: $detail")
    }
}

data class CanopyModelManifest(
    val schemaVersion: Int,
    val modelId: String,
    val version: String,
    val files: List<ModelFile>
) {
    data class ModelFile(
        val role: String,
        val filename: String,
        val downloadUrl: String,
        val sizeBytes: Long,
        val sha256: String,
        val expiresAt: Instant?
    )

    fun file(role: String): ModelFile? = files.firstOrNull { it.role.equals(role, ignoreCase = true) }

    companion object {
        /**
         * The deployed delivery service uses a deliberately compact flat response, but the
         * nested schema is still accepted so the downloader has one trusted internal shape
         * regardless of the wire representation.
         */
        fun parse(json: JSONObject): CanopyModelManifest {
            if (json.has("model")) {
                val model = json.getJSONObject("model")
                val rawFiles = model.getJSONArray("files")
                val files = (0 until rawFiles.length()).map { index ->
                    val entry = rawFiles.getJSONObject(index)
                    ModelFile(
                        role = entry.getString("role"),
                        filename = entry.getString("filename"),
                        downloadUrl = entry.getString("download_url"),
                        sizeBytes = entry.getLong("size_bytes"),
                        sha256 = entry.getString("sha256"),
                        expiresAt = entry.optString("expires_at").toInstantOrNull()
                    )
                }
                return CanopyModelManifest(
                    schemaVersion = json.getInt("schema_version"),
                    modelId = model.getString("id"),
                    version = model.getString("version"),
                    files = files
                ).validated()
            }

            return CanopyModelManifest(
                schemaVersion = 1,
                modelId = "canopy",
                version = json.getString("version"),
                files = listOf(
                    ModelFile(
                        role = "model",
                        filename = json.getString("filename"),
                        downloadUrl = json.getString("download_url"),
                        sizeBytes = json.getLong("size_bytes"),
                        sha256 = json.getString("sha256"),
                        expiresAt = json.optString("url_expires_at").toInstantOrNull()
                    )
                )
            ).validated()
        }

        private fun String?.toInstantOrNull(): Instant? =
            this?.takeIf { it.isNotBlank() }?.let { raw ->
                runCatching { Instant.parse(raw) }.getOrNull()
            }

        private fun isMalformedDeliveryHost(host: String?): Boolean {
            val normalized = host?.lowercase() ?: return true
            return normalized == "undefined.r2.cloudflarestorage.com" ||
                normalized.startsWith("undefined.") ||
                normalized.contains(" ")
        }
    }

    fun validated(): CanopyModelManifest {
        if (schemaVersion != 1) {
            throw ModelDeliveryError.invalidManifest("Unsupported manifest schema.")
        }
        if (modelId.isBlank() || version.isBlank()) {
            throw ModelDeliveryError.invalidManifest("The model id or version is missing.")
        }
        if (file("model") == null) {
            throw ModelDeliveryError.invalidManifest("The manifest does not include a language model.")
        }
        for (entry in files) {
            val url = runCatching { URL(entry.downloadUrl) }.getOrNull()
            // filename must be a bare path component: "../model.gguf" is rejected
            // because its last component differs from the raw value.
            if (url == null ||
                url.protocol.lowercase() != "https" ||
                isMalformedDeliveryHost(url.host) ||
                entry.filename.isEmpty() ||
                entry.filename != File(entry.filename).name ||
                entry.sizeBytes <= 0 ||
                !entry.sha256.matches(Regex("^[A-Fa-f0-9]{64}$"))
            ) {
                throw ModelDeliveryError.invalidManifest("A model file entry is invalid.")
            }
        }
        return this
    }
}

private data class ModelDeliveryConfiguration(
    val manifestEndpoint: String,
    val registrationEndpoint: String
) {
    companion object {
        fun current(): ModelDeliveryConfiguration? {
            val manifest = com.nathanaelguitar.canopychat.BuildConfig.MODEL_MANIFEST_ENDPOINT.trim()
            val registration = com.nathanaelguitar.canopychat.BuildConfig.MODEL_REGISTRATION_ENDPOINT.trim()
            if (manifest.isEmpty() || registration.isEmpty()) return null
            val manifestUrl = runCatching { URL(manifest) }.getOrNull() ?: return null
            val registrationUrl = runCatching { URL(registration) }.getOrNull() ?: return null
            if (manifestUrl.protocol.lowercase() != "https" || registrationUrl.protocol.lowercase() != "https") {
                return null
            }
            return ModelDeliveryConfiguration(manifest, registration)
        }
    }
}

/**
 * Android counterpart of AetherBuildChannel in iphone/AetherChat/CanopyFeedback.swift.
 * The channel comes from the CANOPY_BUILD_CHANNEL gradle property, matching how the
 * iOS build stamps AETHER_BUILD_CHANNEL into Info.plist.
 */
object CanopyBuildChannel {
    val name: String
        get() = if (com.nathanaelguitar.canopychat.BuildConfig.BUILD_CHANNEL.trim().lowercase()
            in listOf("contributor", "beta")
        ) "contributor" else "production"

    val isContributor: Boolean get() = name == "contributor"
}

/**
 * Keystore-backed store for opaque installation credentials, mirroring
 * AetherModelDeliveryKeychain. These credentials identify a beta install to the
 * delivery service; they are not Hugging Face keys.
 */
private class DeliveryCredentialStore(context: Context) {
    private val preferences: SharedPreferences = runCatching {
        EncryptedSharedPreferences.create(
            context,
            "canopy_model_delivery_secure",
            MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }.getOrElse {
        // A wiped or corrupted Keystore must not brick model downloads; app-private
        // storage is still not world-readable, it just loses device-bound encryption.
        context.getSharedPreferences("canopy_model_delivery_secure_fallback", Context.MODE_PRIVATE)
    }

    fun string(account: String): String? =
        preferences.getString(account, null)?.takeIf { it.isNotEmpty() }

    fun set(value: String, account: String) {
        preferences.edit().putString(account, value).apply()
    }

    fun remove(account: String) {
        preferences.edit().remove(account).apply()
    }
}

/**
 * The active version is used in contributor telemetry after a private model has been
 * activated. Production keeps the built-in catalog version because it does not collect
 * telemetry, even though it now uses the same private delivery path.
 */
object ActiveModelVersion {
    private const val KEY = "canopy.activePrivateModelVersion"
    private lateinit var appContext: Context

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    val current: String
        get() {
            if (!CanopyBuildChannel.isContributor || !::appContext.isInitialized) {
                return ModelCatalog.MODEL_VERSION
            }
            return appContext.getSharedPreferences("canopy_model_delivery", Context.MODE_PRIVATE)
                .getString(KEY, null) ?: ModelCatalog.MODEL_VERSION
        }

    fun set(context: Context, version: String) {
        if (!CanopyBuildChannel.isContributor) return
        context.getSharedPreferences("canopy_model_delivery", Context.MODE_PRIVATE)
            .edit().putString(KEY, version).apply()
    }
}

/**
 * A URL-free record of the last fully verified private model. Signed R2 URLs are bearer
 * credentials with a short lifetime, so they are intentionally not persisted. This
 * record lets the app keep using the verified on-device model while offline or while
 * the manifest service is temporarily unavailable.
 */
data class CachedPrivateModel(
    val modelId: String,
    val version: String,
    val files: List<CachedFile>,
    val activatedAt: Instant
) {
    data class CachedFile(
        val role: String,
        val filename: String,
        val sizeBytes: Long,
        val sha256: String
    )

    fun file(role: String): CachedFile? = files.firstOrNull { it.role.equals(role, ignoreCase = true) }

    fun toJson(): JSONObject = JSONObject().apply {
        put("modelID", modelId)
        put("version", version)
        put("activatedAt", activatedAt.toString())
        put("files", org.json.JSONArray().apply {
            files.forEach { file ->
                put(JSONObject().apply {
                    put("role", file.role)
                    put("filename", file.filename)
                    put("sizeBytes", file.sizeBytes)
                    put("sha256", file.sha256)
                })
            }
        })
    }

    companion object {
        val REFRESH_INTERVAL_MILLIS: Long = 12 * 60 * 60 * 1000

        fun from(manifest: CanopyModelManifest): CachedPrivateModel = CachedPrivateModel(
            modelId = manifest.modelId,
            version = manifest.version,
            files = manifest.files.map {
                CachedFile(it.role, it.filename, it.sizeBytes, it.sha256)
            },
            activatedAt = Instant.now()
        )

        fun fromJson(json: JSONObject): CachedPrivateModel {
            val rawFiles = json.getJSONArray("files")
            return CachedPrivateModel(
                modelId = json.getString("modelID"),
                version = json.getString("version"),
                activatedAt = runCatching { Instant.parse(json.getString("activatedAt")) }
                    .getOrDefault(Instant.EPOCH),
                files = (0 until rawFiles.length()).map { index ->
                    val entry = rawFiles.getJSONObject(index)
                    CachedFile(
                        role = entry.getString("role"),
                        filename = entry.getString("filename"),
                        sizeBytes = entry.getLong("sizeBytes"),
                        sha256 = entry.getString("sha256")
                    )
                }
            )
        }
    }
}

class PrivateModelDelivery private constructor(private val context: Context) {

    private val credentials = DeliveryCredentialStore(context)
    private val tokenMutex = Mutex()

    val isConfigured: Boolean get() = ModelDeliveryConfiguration.current() != null

    fun cachedModel(): CachedPrivateModel? {
        val file = cacheFile() ?: return null
        return runCatching { CachedPrivateModel.fromJson(JSONObject(file.readText())) }.getOrNull()
    }

    fun shouldRefresh(cached: CachedPrivateModel, now: Instant = Instant.now()): Boolean =
        Companion.shouldRefresh(cached, now)

    fun activate(manifest: CanopyModelManifest) {
        val file = cacheFile() ?: return
        runCatching {
            file.writeText(CachedPrivateModel.from(manifest).toJson().toString())
        }
        ActiveModelVersion.set(context, manifest.version)
    }

    suspend fun manifestIfConfigured(): CanopyModelManifest? {
        if (!isConfigured) return null
        return manifest()
    }

    suspend fun manifest(): CanopyModelManifest {
        val configuration = ModelDeliveryConfiguration.current()
            ?: throw ModelDeliveryError.unavailable()
        return manifest(configuration, refreshCredential = false)
    }

    /** The contributor telemetry Worker validates this same opaque, per-install credential. */
    suspend fun telemetryInstallationToken(refresh: Boolean = false): String {
        val configuration = ModelDeliveryConfiguration.current()
            ?: throw ModelDeliveryError.unavailable()
        if (refresh) credentials.remove(TOKEN_ACCOUNT)
        return installationToken(configuration, forceRegistration = refresh)
    }

    private suspend fun manifest(
        configuration: ModelDeliveryConfiguration,
        refreshCredential: Boolean
    ): CanopyModelManifest = withContext(Dispatchers.IO) {
        val token = installationToken(configuration, forceRegistration = refreshCredential)
        val connection = URL(configuration.manifestEndpoint).openConnection() as HttpURLConnection
        connection.connectTimeout = 30_000
        connection.readTimeout = 30_000
        connection.setRequestProperty("Authorization", "Bearer $token")
        connection.setRequestProperty("X-Canopy-Installation-ID", installationID)
        connection.setRequestProperty("X-Canopy-App-Version", appVersion)

        try {
            val code = connection.responseCode
            if ((code == HttpURLConnection.HTTP_UNAUTHORIZED || code == HttpURLConnection.HTTP_FORBIDDEN) &&
                !refreshCredential
            ) {
                credentials.remove(TOKEN_ACCOUNT)
                return@withContext manifest(configuration, refreshCredential = true)
            }
            if (code !in 200..299) {
                throw ModelDeliveryError.manifestRequestFailed("HTTP $code.")
            }
            val body = connection.inputStream.bufferedReader().readText()
            CanopyModelManifest.parse(JSONObject(body))
        } catch (error: ModelDeliveryError) {
            throw error
        } catch (error: Exception) {
            throw ModelDeliveryError.manifestRequestFailed(error.message ?: "Request failed.")
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun installationToken(
        configuration: ModelDeliveryConfiguration,
        forceRegistration: Boolean
    ): String = tokenMutex.withLock {
        if (!forceRegistration) {
            credentials.string(TOKEN_ACCOUNT)?.let { return@withLock it }
        }
        register(configuration)
    }

    private suspend fun register(configuration: ModelDeliveryConfiguration): String =
        withContext(Dispatchers.IO) {
            val connection = URL(configuration.registrationEndpoint).openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.connectTimeout = 30_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true

            try {
                val payload = JSONObject().put("install_id", installationID)
                connection.outputStream.use { it.write(payload.toString().toByteArray()) }
                val code = connection.responseCode
                if (code !in 200..299) {
                    throw ModelDeliveryError.registrationFailed("HTTP $code.")
                }
                val body = connection.inputStream.bufferedReader().readText()
                val json = JSONObject(body)
                val token = json.optString("installation_token").ifEmpty { json.optString("token") }
                if (token.length < 24) {
                    throw ModelDeliveryError.registrationFailed(
                        "The service returned an invalid installation credential."
                    )
                }
                credentials.set(token, TOKEN_ACCOUNT)
                token
            } catch (error: ModelDeliveryError) {
                throw error
            } catch (error: Exception) {
                throw ModelDeliveryError.registrationFailed(error.message ?: "Registration failed.")
            } finally {
                connection.disconnect()
            }
        }

    private val installationID: String
        get() = credentials.string(INSTALLATION_ACCOUNT) ?: UUID.randomUUID().toString().also {
            credentials.set(it, INSTALLATION_ACCOUNT)
        }

    private val appVersion: String
        get() = runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            "${info.versionName} (${info.longVersionCode})"
        }.getOrDefault("Unknown (0)")

    private fun cacheFile(): File? = runCatching {
        File(modelDirectory(context), CACHE_FILENAME)
    }.getOrNull()

    companion object {
        private const val TOKEN_ACCOUNT = "installation-token"
        private const val INSTALLATION_ACCOUNT = "installation-id"
        private const val CACHE_FILENAME = "active-private-model.json"

        @Volatile
        private var instance: PrivateModelDelivery? = null

        fun shared(context: Context): PrivateModelDelivery =
            instance ?: synchronized(this) {
                instance ?: PrivateModelDelivery(context.applicationContext).also { instance = it }
            }

        /** Mirrors AetherModelStore.safePathComponent on iOS. */
        fun safePathComponent(raw: String): String =
            raw.map { if (it.isLetterOrDigit() || it == '.' || it == '-' || it == '_') it else '-' }
                .joinToString("")
                .trim('-')

        fun shouldRefresh(cached: CachedPrivateModel, now: Instant = Instant.now()): Boolean =
            now.toEpochMilli() - cached.activatedAt.toEpochMilli() >= CachedPrivateModel.REFRESH_INTERVAL_MILLIS

        fun modelDirectory(context: Context): File =
            File(context.filesDir, "Models").apply { mkdirs() }
    }
}
