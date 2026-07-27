package com.nathanaelguitar.canopychat.core

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import kotlin.math.min
import kotlin.math.pow

// Port of AetherBetaTelemetry + CanopyContributorProgram from
// iphone/AetherChat/CanopyFeedback.swift and iphone/AetherChat/Contributor/ContributorProgram.swift.
// Contributor-beta-only telemetry queue: it retains events locally, selects
// failure-linked interactions plus a deterministic control sample, and only deletes
// an upload after the ingestion service acknowledges its receipt. Production builds
// never collect anything.

enum class TelemetryEventType(val rawValue: String) {
    RESPONSE_GENERATED("responseGenerated"),
    RESPONSE_RATED("responseRated"),
    RESPONSE_REGENERATED("responseRegenerated"),
    MESSAGE_RESENT("messageResent"),
    SEARCH_SUGGESTED("searchSuggested"),
    SEARCH_CHOSEN("searchChosen"),
    WEB_SEARCH_REQUESTED("webSearchRequested"),
    WEB_SEARCH_PERFORMED("webSearchPerformed"),
    WEB_SEARCH_EVALUATED("webSearchEvaluated"),
    ISSUE_REPORTED("issueReported"),
    RESPONSE_TRUNCATED("responseTruncated"),
    RESPONSE_EMPTY("responseEmpty"),
    INFERENCE_FAILED("inferenceFailed"),
    TOOL_FAILED("toolFailed"),
    OUTPUT_VALIDATION_FAILED("outputValidationFailed"),
    USER_CORRECTION("userCorrection");

    companion object {
        fun from(raw: String): TelemetryEventType? = entries.firstOrNull { it.rawValue == raw }
    }
}

data class TelemetryEvent(
    val id: UUID,
    val type: TelemetryEventType,
    val timestampMillis: Long,
    val channel: String,
    val appVersion: String,
    val modelVersion: String?,
    val conversationId: UUID?,
    val messageId: UUID?,
    val prompt: String?,
    val response: String?,
    val metadata: Map<String, String>
) {
    /** Local persistence format; the wire format is produced by toWireJson. */
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id.toString())
        put("type", type.rawValue)
        put("timestamp", timestampMillis)
        put("channel", channel)
        put("appVersion", appVersion)
        modelVersion?.let { put("modelVersion", it) }
        conversationId?.let { put("conversationID", it.toString()) }
        messageId?.let { put("messageID", it.toString()) }
        prompt?.let { put("prompt", it) }
        response?.let { put("response", it) }
        put("metadata", JSONObject(metadata))
    }

    /** Wire keys match Swift's synthesized Codable keys on AetherTelemetryEvent. */
    fun toWireJson(): JSONObject = JSONObject().apply {
        put("id", id.toString())
        put("type", type.rawValue)
        put("timestamp", Instant.ofEpochMilli(timestampMillis).toString())
        put("channel", channel)
        put("appVersion", appVersion)
        modelVersion?.let { put("modelVersion", it) }
        conversationId?.let { put("conversationID", it.toString()) }
        messageId?.let { put("messageID", it.toString()) }
        prompt?.let { put("prompt", it) }
        response?.let { put("response", it) }
        put("metadata", JSONObject(metadata))
    }

    companion object {
        fun fromJson(json: JSONObject): TelemetryEvent? {
            val type = TelemetryEventType.from(json.optString("type")) ?: return null
            val metadataJson = json.optJSONObject("metadata") ?: JSONObject()
            val metadata = mutableMapOf<String, String>()
            metadataJson.keys().forEach { metadata[it] = metadataJson.optString(it) }
            return TelemetryEvent(
                id = runCatching { UUID.fromString(json.getString("id")) }.getOrNull() ?: return null,
                type = type,
                timestampMillis = json.optLong("timestamp", System.currentTimeMillis()),
                channel = json.optString("channel", "contributor"),
                appVersion = json.optString("appVersion", "Unknown (0)"),
                modelVersion = json.optString("modelVersion").ifEmpty { null },
                conversationId = json.optString("conversationID")
                    .takeIf { it.isNotEmpty() }
                    ?.let { raw -> runCatching { UUID.fromString(raw) }.getOrNull() },
                messageId = json.optString("messageID")
                    .takeIf { it.isNotEmpty() }
                    ?.let { raw -> runCatching { UUID.fromString(raw) }.getOrNull() },
                prompt = json.optString("prompt").ifEmpty { null },
                response = json.optString("response").ifEmpty { null },
                metadata = metadata
            )
        }
    }
}

private data class PendingUpload(
    val batchId: UUID,
    val eventIds: List<UUID>,
    val timestamp: String,
    val body: String
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("batchID", batchId.toString())
        put("eventIDs", JSONArray().apply { eventIds.forEach { put(it.toString()) } })
        put("timestamp", timestamp)
        put("body", body)
    }

    companion object {
        fun fromJson(json: JSONObject): PendingUpload? {
            val batchId = runCatching { UUID.fromString(json.getString("batchID")) }.getOrNull() ?: return null
            val rawIds = json.optJSONArray("eventIDs") ?: JSONArray()
            return PendingUpload(
                batchId = batchId,
                eventIds = (0 until rawIds.length()).mapNotNull { index ->
                    runCatching { UUID.fromString(rawIds.getString(index)) }.getOrNull()
                },
                timestamp = json.optString("timestamp"),
                body = json.optString("body")
            )
        }
    }
}

class BetaTelemetry private constructor(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val events = mutableListOf<TelemetryEvent>()
    private var pendingUpload: PendingUpload? = null
    private var isFlushing = false
    private var retryAttempt = 0
    private var retryJob: Job? = null
    private var batchDeadlineJob: Job? = null

    var isEnabled: Boolean = false
        private set

    private val prefs = context.getSharedPreferences("canopy_beta_telemetry", Context.MODE_PRIVATE)
    private val eventsFile = File(context.filesDir, "beta-telemetry.json")
    private val pendingUploadFile = File(context.filesDir, "beta-contributor-pending-upload.json")

    init {
        runCatching {
            if (eventsFile.exists()) {
                val array = JSONArray(eventsFile.readText())
                (0 until array.length()).mapNotNullTo(events) { TelemetryEvent.fromJson(array.getJSONObject(it)) }
            }
        }
        pendingUpload = runCatching {
            if (pendingUploadFile.exists()) PendingUpload.fromJson(JSONObject(pendingUploadFile.readText())) else null
        }.getOrNull()

        if (CanopyBuildChannel.isContributor) {
            // Contributor distribution clearly discloses the model-improvement program.
            // Production remains permanently off. A tester can stop collection anytime.
            isEnabled = prefs.getBoolean(ENABLED_KEY, true)
        } else {
            isEnabled = false
        }
    }

    fun setEnabled(enabled: Boolean) {
        isEnabled = CanopyBuildChannel.isContributor && enabled
        prefs.edit().putBoolean(ENABLED_KEY, isEnabled).apply()
        if (isEnabled) {
            flushIfConfigured()
        } else {
            // Consent withdrawal stops future collection and clears unsent content.
            scope.launch {
                mutex.withLock {
                    events.clear()
                    pendingUpload = null
                    retryJob?.cancel()
                    retryJob = null
                    batchDeadlineJob?.cancel()
                    batchDeadlineJob = null
                    persist()
                    pendingUploadFile.delete()
                }
            }
        }
    }

    fun record(
        type: TelemetryEventType,
        conversationId: UUID? = null,
        messageId: UUID? = null,
        prompt: String? = null,
        response: String? = null,
        metadata: Map<String, String> = emptyMap()
    ) {
        if (!CanopyBuildChannel.isContributor || !isEnabled) return
        val event = TelemetryEvent(
            id = UUID.randomUUID(),
            type = type,
            timestampMillis = System.currentTimeMillis(),
            channel = CanopyBuildChannel.name,
            appVersion = appVersion,
            modelVersion = ActiveModelVersion.current,
            conversationId = conversationId,
            messageId = messageId,
            prompt = prompt?.take(20_000),
            response = response?.take(20_000),
            metadata = metadata
        )
        scope.launch {
            mutex.withLock {
                events.add(event)
                pruneExpiredEvents()
                while (events.size > 2_000) events.removeAt(0)
                persist()
            }
            flushIfConfigured()
        }
    }

    /** Called when the app becomes active so a queued 24-hour batch gets another opportunity. */
    fun flushPendingBatch() {
        if (!CanopyBuildChannel.isContributor || !isEnabled) return
        flushIfConfigured()
    }

    private fun flushIfConfigured() {
        if (isFlushing) return
        val endpoint = uploadEndpoint() ?: return
        scope.launch {
            val upload = mutex.withLock {
                if (isFlushing) return@withLock null
                val resolved = pendingUpload ?: makePendingUpload()
                if (resolved != null) {
                    isFlushing = true
                    pendingUpload = resolved
                    persistPendingUpload()
                }
                resolved
            } ?: return@launch

            try {
                val delivery = PrivateModelDelivery.shared(context)
                var token = delivery.telemetryInstallationToken()
                var result = upload(upload, endpoint, token)
                if (result.code == HttpURLConnection.HTTP_UNAUTHORIZED ||
                    result.code == HttpURLConnection.HTTP_FORBIDDEN
                ) {
                    // A revoked or expired install credential can be replaced once. The
                    // immutable batch ID preserves server-side idempotency if the first
                    // attempt actually reached it.
                    token = delivery.telemetryInstallationToken(refresh = true)
                    result = upload(upload, endpoint, token)
                }
                val receiptBatchId = if (result.code in 200..299) {
                    runCatching { JSONObject(result.body).getString("batch_id") }.getOrNull()
                } else {
                    null
                }
                val receiptId = if (result.code in 200..299) {
                    runCatching { JSONObject(result.body).getString("receipt_id") }.getOrNull()
                } else {
                    null
                }
                if (receiptBatchId == upload.batchId.toString() && !receiptId.isNullOrEmpty()) {
                    mutex.withLock {
                        events.removeAll { upload.eventIds.contains(it.id) }
                        pendingUpload = null
                        retryAttempt = 0
                        retryJob?.cancel()
                        retryJob = null
                        persist()
                        pendingUploadFile.delete()
                    }
                } else {
                    scheduleRetry()
                }
            } catch (_: Exception) {
                scheduleRetry()
            } finally {
                isFlushing = false
            }
        }
    }

    private fun makePendingUpload(): PendingUpload? {
        val selectedEvents = eligibleEventsForUpload()
        if (selectedEvents.isEmpty()) return null
        val batchId = UUID.randomUUID()
        val timestamp = Instant.now().toString()
        val body = JSONObject().apply {
            put("schema_version", 1)
            put("batch_id", batchId.toString())
            put("installation_id", installationId.toString())
            put("sent_at", timestamp)
            put("consent_for_model_improvement", true)
            put("events", JSONArray().apply { selectedEvents.forEach { put(it.toWireJson()) } })
        }.toString()
        return PendingUpload(batchId, selectedEvents.map { it.id }, timestamp, body)
    }

    private fun eligibleEventsForUpload(): List<TelemetryEvent> {
        val selected = mutableListOf<TelemetryEvent>()
        val selectedIds = mutableSetOf<UUID>()
        val responses = events.filter { it.type == TelemetryEventType.RESPONSE_GENERATED }
        for (response in responses) {
            val related = events.filter { event ->
                event.id == response.id ||
                    (response.messageId != null && event.messageId == response.messageId)
            }
            val isFailure = related.any(::isExplicitFailure)
            val hasHarnessSignal = related.any(::isHarnessSignal)
            // Keep every explicit web-search signal with the surrounding prompt and
            // response. This teaches the harness when a user wanted live grounding,
            // chose it, or was blocked because it was disabled.
            if (!isFailure && !hasHarnessSignal && !isControlSample(response.id)) continue
            for (event in related) {
                if (selected.size >= 100) break
                if (selectedIds.add(event.id)) selected.add(event)
            }
        }
        val firstCandidateAt = selected.minOfOrNull { it.timestampMillis } ?: return emptyList()
        // Explicit failures and web-search harness signals are time-sensitive and
        // high-value. Deliver them promptly; the 2% control sample is still batched.
        if (selected.none { isExplicitFailure(it) || isHarnessSignal(it) } && selected.size < 50) {
            val deadline = firstCandidateAt + 24 * 60 * 60 * 1000
            if (deadline > System.currentTimeMillis()) {
                scheduleBatchDeadline(deadline)
                return emptyList()
            }
        }
        batchDeadlineJob?.cancel()
        batchDeadlineJob = null
        return selected.take(100)
    }

    private fun isExplicitFailure(event: TelemetryEvent): Boolean = when (event.type) {
        TelemetryEventType.RESPONSE_RATED -> event.metadata["rating"] == "negative"
        TelemetryEventType.RESPONSE_REGENERATED,
        TelemetryEventType.RESPONSE_TRUNCATED,
        TelemetryEventType.RESPONSE_EMPTY,
        TelemetryEventType.INFERENCE_FAILED,
        TelemetryEventType.TOOL_FAILED,
        TelemetryEventType.OUTPUT_VALIDATION_FAILED,
        TelemetryEventType.USER_CORRECTION -> true
        else -> event.metadata["truncated"] == "true" || event.metadata["validation_failed"] == "true"
    }

    private fun isHarnessSignal(event: TelemetryEvent): Boolean = when (event.type) {
        TelemetryEventType.SEARCH_SUGGESTED,
        TelemetryEventType.SEARCH_CHOSEN,
        TelemetryEventType.WEB_SEARCH_REQUESTED,
        TelemetryEventType.WEB_SEARCH_PERFORMED,
        TelemetryEventType.WEB_SEARCH_EVALUATED -> true
        else -> false
    }

    private fun isControlSample(id: UUID): Boolean {
        // Stable 2% selection keeps the beta's success-control group bounded.
        val digest = MessageDigest.getInstance("SHA-256").digest(id.toString().toByteArray())
        return (digest[0].toInt() and 0xFF) % 100 < 2
    }

    private fun uploadEndpoint(): URL? {
        val raw = com.nathanaelguitar.canopychat.BuildConfig.BETA_TELEMETRY_ENDPOINT.trim()
        if (raw.isEmpty()) return null
        val url = runCatching { URL(raw) }.getOrNull() ?: return null
        return if (url.protocol.lowercase() == "https") url else null
    }

    private data class UploadResult(val code: Int, val body: String)

    private fun upload(upload: PendingUpload, endpoint: URL, token: String): UploadResult {
        val connection = endpoint.openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.connectTimeout = 30_000
        connection.readTimeout = 30_000
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("Authorization", "Bearer $token")
        connection.doOutput = true
        try {
            connection.outputStream.use { it.write(upload.body.toByteArray()) }
            val code = connection.responseCode
            val body = runCatching {
                (if (code in 200..299) connection.inputStream else connection.errorStream)
                    .bufferedReader().readText()
            }.getOrDefault("")
            return UploadResult(code, body)
        } finally {
            connection.disconnect()
        }
    }

    private fun scheduleRetry() {
        if (!isEnabled || retryJob?.isActive == true) return
        val delayMillis = (2.0.pow(retryAttempt.toDouble()) * 1_000).toLong().coerceAtMost(300_000)
        retryAttempt = min(retryAttempt + 1, 8)
        retryJob = scope.launch {
            delay(delayMillis)
            retryJob = null
            flushIfConfigured()
        }
    }

    private fun scheduleBatchDeadline(deadlineMillis: Long) {
        if (batchDeadlineJob?.isActive == true) return
        batchDeadlineJob = scope.launch {
            delay((deadlineMillis - System.currentTimeMillis()).coerceAtLeast(1))
            batchDeadlineJob = null
            flushIfConfigured()
        }
    }

    private fun pruneExpiredEvents() {
        val cutoff = System.currentTimeMillis() - 48L * 60 * 60 * 1000
        val protectedIds = pendingUpload?.eventIds?.toSet() ?: emptySet()
        events.removeAll { it.timestampMillis < cutoff && !protectedIds.contains(it.id) }
    }

    private val installationId: UUID
        get() {
            prefs.getString(INSTALLATION_ID_KEY, null)
                ?.let { raw -> runCatching { UUID.fromString(raw) }.getOrNull() }
                ?.let { return it }
            val id = UUID.randomUUID()
            prefs.edit().putString(INSTALLATION_ID_KEY, id.toString()).apply()
            return id
        }

    private fun persistPendingUpload() {
        val upload = pendingUpload ?: return
        runCatching { pendingUploadFile.writeText(upload.toJson().toString()) }
    }

    private fun persist() {
        runCatching {
            val array = JSONArray()
            events.forEach { array.put(it.toJson()) }
            eventsFile.writeText(array.toString())
        }
    }

    private val appVersion: String
        get() = runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            "${info.versionName} (${info.longVersionCode})"
        }.getOrDefault("Unknown (0)")

    companion object {
        private const val ENABLED_KEY = "aether.betaTelemetryEnabled"
        private const val INSTALLATION_ID_KEY = "aether.contributorInstallationID"

        @Volatile
        private var instance: BetaTelemetry? = null

        fun shared(context: Context): BetaTelemetry =
            instance ?: synchronized(this) {
                instance ?: BetaTelemetry(context.applicationContext).also { instance = it }
            }
    }
}

/**
 * Port of CanopyContributorProgram in iphone/AetherChat/Contributor/ContributorProgram.swift.
 * Production builds must never collect conversation content.
 */
object ContributorProgram {
    private const val DISCLOSURE_ACKNOWLEDGED_KEY = "aether.contributorDisclosureAcknowledged"

    val isContributorBuild: Boolean get() = CanopyBuildChannel.isContributor

    fun hasAcknowledgedDisclosure(context: Context): Boolean =
        isContributorBuild && context.getSharedPreferences("canopychat", Context.MODE_PRIVATE)
            .getBoolean(DISCLOSURE_ACKNOWLEDGED_KEY, false)

    fun acknowledgeDisclosure(context: Context) {
        if (!isContributorBuild) return
        context.getSharedPreferences("canopychat", Context.MODE_PRIVATE)
            .edit().putBoolean(DISCLOSURE_ACKNOWLEDGED_KEY, true).apply()
    }

    fun join(context: Context) {
        if (!isContributorBuild) return
        acknowledgeDisclosure(context)
        BetaTelemetry.shared(context).setEnabled(true)
    }

    fun stopContributing(context: Context) {
        BetaTelemetry.shared(context).setEnabled(false)
        context.getSharedPreferences("canopychat", Context.MODE_PRIVATE)
            .edit().remove(DISCLOSURE_ACKNOWLEDGED_KEY).apply()
    }

    const val DISCLOSURE: String =
        "This Contributor Beta shares selected prompts and answers to help improve CanopyChat. " +
            "We collect failures, corrections, regenerations, and a small comparison sample. " +
            "Attachments and full chat histories are not included. You can stop contributing at any time; " +
            "unsent beta data will be deleted."
}
