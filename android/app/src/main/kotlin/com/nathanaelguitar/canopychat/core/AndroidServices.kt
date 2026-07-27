package com.nathanaelguitar.canopychat.core

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume

object CanopyLegal {
    const val PRIVACY_POLICY_URL = "https://nathanaelguitar.github.io/canopy_publicsite/privacy.html"
    const val TERMS_OF_USE_URL = "https://nathanaelguitar.github.io/canopy_publicsite/terms.html"
    const val SUPPORT_URL = "https://nathanaelguitar.github.io/canopy_publicsite/support.html"
    // Matches CanopyFeedback.supportEmail in iphone/AetherChat/CanopyFeedback.swift.
    const val SUPPORT_EMAIL = "support@canopychat.app"
}

/**
 * Port of CanopyFeedback in iphone/AetherChat/CanopyFeedback.swift. The templates
 * mirror the iOS structure so support emails carry the same sections and fields.
 */
object CanopyFeedback {
    fun modelFeedback(message: ChatMessage, conversation: Conversation?): String {
        val prompt = promptText(message, conversation)
        val cleanedResponse = plainText(message.content.trim())
        return """
        CanopyChat Model Feedback

        Thanks for taking a moment to report this. Your feedback helps us improve the model and make CanopyChat more useful. We work hard to provide the best service to our customers.

        WHAT WENT WRONG?
        Please tell us what was incorrect, confusing, incomplete, or unexpected.

        WHAT WERE YOU EXPECTING?
        If you can, describe the answer or behavior you wanted instead.


        USER PROMPT
        $prompt


        MODEL RESPONSE
        $cleanedResponse

        Thank you for helping us make CanopyChat better.

        TECHNICAL DETAILS FOR SUPPORT
        Conversation: ${conversation?.title ?: "Unknown"}
        Assistant: ${conversation?.persona?.name ?: "Unknown"}
        Message ID: ${message.id}
        Timestamp: ${timestamp()}
        App Version: $appVersion
        Device: ${Build.MODEL}
        Android: ${Build.VERSION.RELEASE}
        """.trimIndent()
    }

    /** Port of CanopyFeedback.promptText(for:conversation:) on iOS. */
    private fun promptText(message: ChatMessage, conversation: Conversation?): String {
        val responseIndex = conversation?.messages?.indexOfFirst { it.id == message.id } ?: -1
        val userMessage = if (responseIndex >= 0) {
            conversation!!.messages.subList(0, responseIndex).lastOrNull { it.role == MessageRole.USER }
        } else {
            null
        }
        if (userMessage == null) return "(Prompt text unavailable.)"

        val text = plainText(userMessage.content.trim())
        if (text.isNotEmpty()) return text

        val attachmentNames = userMessage.attachments.joinToString(", ") { it.displayName }
        return if (attachmentNames.isEmpty()) {
            "(No text prompt.)"
        } else {
            "(Attachment-only prompt: $attachmentNames)"
        }
    }

    fun appIssue(conversation: Conversation? = null): String =
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
        Conversation: ${conversation?.title ?: "Not provided"}
        Timestamp: ${timestamp()}
        App Version: $appVersion
        Device: ${Build.MODEL}
        Android: ${Build.VERSION.RELEASE}
        """.trimIndent()

    /** Port of CanopyFeedback.plainText on iOS — strips markdown emphasis/links/headers. */
    private fun plainText(text: String): String =
        text
            .replace(Regex("\\*\\*(.*?)\\*\\*"), "$1")
            .replace(Regex("__(.*?)__"), "$1")
            .replace(Regex("`([^`]+)`"), "$1")
            .replace(Regex("\\[([^\\]]+)\\]\\([^\\)]+\\)"), "$1")
            .replace(Regex("^\\s{0,3}#{1,6}\\s*", RegexOption.IGNORE_CASE), "")

    private fun timestamp(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(Date())

    /** Android counterpart of the iOS "CFBundleShortVersionString (CFBundleVersion)". */
    private val appVersion: String
        get() = "${com.nathanaelguitar.canopychat.BuildConfig.VERSION_NAME} " +
            "(${com.nathanaelguitar.canopychat.BuildConfig.VERSION_CODE})"
}

/**
 * Android substitute for AetherNotifications in iphone/AetherChat/Models.swift.
 * UNUserNotificationCenter has no Android equivalent; this uses NotificationManager
 * with a dedicated channel. Posting is best-effort — replies still arrive in-app if
 * the POST_NOTIFICATIONS runtime permission was denied.
 */
object CanopyNotifications {
    private const val CHANNEL_ID = "canopy.replies"
    private const val CHANNEL_NAME = "Assistant replies"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Tells you when CanopyChat finishes a reply while the app is in the background."
            }
        )
    }

    fun hasPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    fun notifyReplyReady(context: Context, title: String, body: String) {
        if (!hasPermission(context)) return
        ensureChannel(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(com.nathanaelguitar.canopychat.R.drawable.ic_canopy_tree)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        runCatching {
            NotificationManagerCompat.from(context).notify(System.nanoTime().toInt(), notification)
        }
    }
}

class CanopyNetworkMonitor(context: Context) {
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val _isConnected = MutableStateFlow(currentlyConnected())
    val isConnected: StateFlow<Boolean> = _isConnected

    /**
     * Mirrors AetherNetworkMonitor.hasReceivedStatus on iOS. Callers must not treat the
     * initial optimistic value as evidence the device is genuinely offline.
     */
    @Volatile
    var hasReceivedStatus: Boolean = false
        private set

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            hasReceivedStatus = true
            _isConnected.value = true
        }

        override fun onLost(network: Network) {
            hasReceivedStatus = true
            _isConnected.value = currentlyConnected()
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            hasReceivedStatus = true
            _isConnected.value = hasInternet(networkCapabilities)
        }
    }

    init {
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivityManager.registerNetworkCallback(request, callback)
    }

    fun close() {
        runCatching { connectivityManager.unregisterNetworkCallback(callback) }
    }

    private fun currentlyConnected(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return hasInternet(capabilities)
    }

    private fun hasInternet(capabilities: NetworkCapabilities): Boolean =
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}

class CanopyLocationService(private val context: Context) {
    suspend fun localizeSearchQuery(query: String, originalUserText: String = query): String {
        if (!needsLocation(query) && !needsLocation(originalUserText)) return query
        val place = currentPlace() ?: return "$query using my current city"
        return localizedQuery(query, originalUserText, place)
    }

    fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private suspend fun currentPlace(): String? {
        if (!hasLocationPermission()) return null
        // iOS asks CLLocationManager for a fresh fix (requestLocation); Android first
        // tries requestSingleUpdate with a timeout and falls back to the last known fix.
        val location = freshLocation() ?: lastKnownLocation() ?: return null
        return reverseGeocode(location) ?: String.format(Locale.US, "%.5f, %.5f", location.latitude, location.longitude)
    }

    @SuppressLint("MissingPermission")
    private suspend fun freshLocation(): Location? {
        if (!hasLocationPermission()) return null
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val provider = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            .firstOrNull { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
            ?: return null
        return withTimeoutOrNull(FRESH_FIX_TIMEOUT_MS) {
            suspendCancellableCoroutine<Location?> { continuation ->
                val listener = LocationListener { location ->
                    if (continuation.isActive) continuation.resume(location)
                }
                continuation.invokeOnCancellation {
                    runCatching { manager.removeUpdates(listener) }
                }
                try {
                    @Suppress("DEPRECATION")
                    manager.requestSingleUpdate(provider, listener, Looper.getMainLooper())
                } catch (_: Exception) {
                    if (continuation.isActive) continuation.resume(null)
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun lastKnownLocation(): Location? {
        if (!hasLocationPermission()) return null
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            .mapNotNull { provider ->
                runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
            }
            .maxByOrNull { it.time }
    }

    private suspend fun reverseGeocode(location: Location): String? =
        suspendCancellableCoroutine { continuation ->
            val geocoder = Geocoder(context, Locale.getDefault())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                geocoder.getFromLocation(location.latitude, location.longitude, 1) { addresses ->
                    val address = addresses.firstOrNull()
                    continuation.resume(formatAddress(address?.locality, address?.adminArea, address?.countryName))
                }
            } else {
                @Suppress("DEPRECATION")
                val address = runCatching {
                    geocoder.getFromLocation(location.latitude, location.longitude, 1)?.firstOrNull()
                }.getOrNull()
                continuation.resume(formatAddress(address?.locality, address?.adminArea, address?.countryName))
            }
        }

    private fun formatAddress(city: String?, region: String?, country: String?): String? {
        val place = listOfNotNull(city, region, country)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString(", ")
        return place.ifEmpty { null }
    }

    private fun localizedQuery(query: String, originalUserText: String, place: String): String {
        val replaced = replacingNearMe(query, place)
        val localized = if (replaced == query && needsLocation(originalUserText)) "$query near $place" else replaced
        if (!isLocalBusinessQuery(query) && !isLocalBusinessQuery(originalUserText)) return localized
        return "$localized restaurants reviews open now local recommendations in $place"
    }

    companion object {
        private const val FRESH_FIX_TIMEOUT_MS = 10_000L

        /** Port of AetherLocationService.needsLocation on iOS, including Spanish phrases. */
        fun needsLocation(query: String): Boolean {
            val lc = query.lowercase()
            return listOf(
                "near me", "nearby", "around me", "close to me", "around here",
                "near here", "local", "my area", "my location", "current location",
                "cerca de mí", "cerca de mi", "por aquí", "por aqui", "cerca"
            ).any { it in lc }
        }

        /** Port of AetherLocationService.replacingNearMe(in:with:) on iOS. */
        private fun replacingNearMe(query: String, place: String): String =
            query
                .replace(Regex("(?i)\\bnear\\s+me\\b"), "near $place")
                .replace(Regex("(?i)\\bnearby\\b"), "near $place")
                .replace(Regex("(?i)\\baround\\s+me\\b"), "near $place")
                .replace(Regex("(?i)\\bmy\\s+area\\b"), place)
                .replace(Regex("(?i)\\bmy\\s+location\\b"), place)
                .replace(Regex("(?i)\\bcurrent\\s+location\\b"), place)
                .replace(Regex("(?i)\\bcerca\\s+de\\s+m[ií]\\b"), "cerca de $place")
                .replace(Regex("(?i)\\bpor\\s+aqu[ií]\\b"), "cerca de $place")

        /** Port of AetherLocationService.isLocalBusinessQuery on iOS. */
        private fun isLocalBusinessQuery(query: String): Boolean {
            val lc = query.lowercase()
            return listOf(
                "food", "restaurant", "restaurants", "spots", "mexican", "taco", "tacos",
                "coffee", "bar", "bars", "lunch", "dinner", "breakfast", "brunch",
                "mcdonald", "mcdonald's", "fast food", "burger", "burgers"
            ).any { it in lc }
        }
    }
}
