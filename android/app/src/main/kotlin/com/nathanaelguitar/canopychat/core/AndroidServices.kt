package com.nathanaelguitar.canopychat.core

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume

object CanopyLegal {
    const val PRIVACY_POLICY_URL = "https://nathanaelguitar.github.io/canopy_publicsite/privacy.html"
    const val TERMS_OF_USE_URL = "https://nathanaelguitar.github.io/canopy_publicsite/terms.html"
    const val SUPPORT_URL = "https://nathanaelguitar.github.io/canopy_publicsite/support.html"
    const val SUPPORT_EMAIL = "consulting.nathanael@gmail.com"
}

object CanopyFeedback {
    fun modelFeedback(message: ChatMessage, conversation: Conversation?): String =
        """
        CanopyChat Model Feedback

        Conversation: ${conversation?.title ?: "Unknown"}
        Assistant: ${conversation?.persona?.name ?: "Unknown"}
        Message ID: ${message.id}
        Timestamp: ${timestamp()}
        Device: ${Build.MANUFACTURER} ${Build.MODEL}
        Android: ${Build.VERSION.RELEASE}

        What went wrong?


        Model response:
        ${message.content.trim()}
        """.trimIndent()

    fun appIssue(conversation: Conversation? = null): String =
        """
        CanopyChat Issue Report

        Conversation: ${conversation?.title ?: "Not provided"}
        Timestamp: ${timestamp()}
        Device: ${Build.MANUFACTURER} ${Build.MODEL}
        Android: ${Build.VERSION.RELEASE}

        What happened?


        What did you expect?


        Steps to reproduce:
        1.
        2.
        3.
        """.trimIndent()

    private fun timestamp(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(Date())
}

class CanopyNetworkMonitor(context: Context) {
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val _isConnected = MutableStateFlow(currentlyConnected())
    val isConnected: StateFlow<Boolean> = _isConnected

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            _isConnected.value = true
        }

        override fun onLost(network: Network) {
            _isConnected.value = currentlyConnected()
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
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
        val location = lastKnownLocation() ?: return null
        return reverseGeocode(location) ?: String.format(Locale.US, "%.5f, %.5f", location.latitude, location.longitude)
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
        fun needsLocation(query: String): Boolean {
            val lc = query.lowercase()
            return listOf("near me", "nearby", "around me", "my area", "my location", "current location")
                .any { it in lc }
        }

        private fun replacingNearMe(query: String, place: String): String =
            query
                .replace(Regex("(?i)\\bnear\\s+me\\b"), "near $place")
                .replace(Regex("(?i)\\bnearby\\b"), "near $place")
                .replace(Regex("(?i)\\baround\\s+me\\b"), "near $place")
                .replace(Regex("(?i)\\bmy\\s+area\\b"), place)
                .replace(Regex("(?i)\\bmy\\s+location\\b"), place)
                .replace(Regex("(?i)\\bcurrent\\s+location\\b"), place)

        private fun isLocalBusinessQuery(query: String): Boolean {
            val lc = query.lowercase()
            return listOf(
                "food", "restaurant", "restaurants", "spots", "mexican", "taco", "tacos",
                "coffee", "bar", "bars", "lunch", "dinner", "breakfast", "brunch"
            ).any { it in lc }
        }
    }
}
