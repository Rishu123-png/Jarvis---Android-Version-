package com.jarvis.assistant.device

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Looper
import android.provider.Settings
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Where am I, and how far is that place.
 *
 * Distance is the interesting part, because there are two honest answers:
 *
 *  - **By road**, from the public OSRM router — no API key, one request per question, which is what
 *    you actually mean by "how far is it".
 *  - **Straight line**, from the haversine formula, computed here with no network at all.
 *
 * When the road figure cannot be fetched (no data, server down, routing switched off) JARVIS says
 * *straight line* out loud. It never presents a crow-flight number as a driving distance — that is
 * the kind of small lie that makes an assistant untrustworthy.
 *
 * The coordinates never leave the phone except as the two endpoints of the route request.
 */
object Whereabouts {

    data class Fix(val latitude: Double, val longitude: Double, val accuracyMeters: Float, val at: Long)

    private const val PREFS = "jarvis_whereabouts"
    private const val KEY_ROAD_ROUTING = "road_routing"
    private const val OSRM = "https://router.project-osrm.org/route/v1/driving/"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Road distance needs a third-party demo server; some operators prefer to keep it off. */
    fun roadRoutingEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ROAD_ROUTING, true)

    fun setRoadRouting(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ROAD_ROUTING, enabled).apply()
    }

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    fun settingsIntent(): Intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** The freshest location any provider already knows, without asking for a new fix. */
    fun lastKnown(context: Context): Fix? {
        if (!hasPermission(context)) return null
        val manager = context.getSystemService(LocationManager::class.java) ?: return null
        val providers = try {
            manager.getProviders(true)
        } catch (t: Throwable) {
            emptyList()
        }

        var best: Location? = null
        for (provider in providers) {
            val candidate = try {
                manager.getLastKnownLocation(provider)
            } catch (t: Throwable) {
                null
            } ?: continue
            if (best == null || candidate.time > best.time) best = candidate
        }
        return best?.let { Fix(it.latitude, it.longitude, it.accuracy, it.time) }
    }

    /**
     * A fresh fix, bounded by [timeoutMs].
     *
     * Falls back to the last known location when nothing arrives in time — an answer that is a few
     * minutes old is still a real answer, and "I could not get GPS indoors" is not helpful.
     */
    suspend fun currentFix(context: Context, timeoutMs: Long = 6_000L): Fix? {
        if (!hasPermission(context)) return null
        val fresh = withTimeoutOrNull(timeoutMs) { requestFix(context) }
        return fresh ?: lastKnown(context)
    }

    private suspend fun requestFix(context: Context): Fix? {
        val manager = context.getSystemService(LocationManager::class.java) ?: return null
        val provider = when {
            manager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> return null
        }

        return suspendCancellableCoroutine { continuation ->
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    try {
                        manager.removeUpdates(this)
                    } catch (t: Throwable) {
                        Unit
                    }
                    if (continuation.isActive) {
                        continuation.resume(
                            Fix(location.latitude, location.longitude, location.accuracy, location.time),
                        )
                    }
                }
            }

            try {
                manager.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
            } catch (t: Throwable) {
                if (continuation.isActive) continuation.resume(null)
            }

            continuation.invokeOnCancellation {
                try {
                    manager.removeUpdates(listener)
                } catch (t: Throwable) {
                    Unit
                }
            }
        }
    }

    /** Turns "India Gate" into coordinates. Returns null when the place is not a place. */
    suspend fun geocode(context: Context, place: String): Pair<Double, Double>? =
        withContext(Dispatchers.IO) {
            val query = place.trim()
            if (query.isEmpty()) return@withContext null
            try {
                @Suppress("DEPRECATION")
                val results = Geocoder(context, Locale.getDefault()).getFromLocationName(query, 1)
                val first = results?.firstOrNull() ?: return@withContext null
                first.latitude to first.longitude
            } catch (e: IOException) {
                null
            } catch (t: Throwable) {
                null
            }
        }

    /** "Near India Gate, New Delhi" — a place name for a coordinate, or null if nothing is known. */
    suspend fun describe(context: Context, fix: Fix): String? = withContext(Dispatchers.IO) {
        try {
            @Suppress("DEPRECATION")
            val results = Geocoder(context, Locale.getDefault())
                .getFromLocation(fix.latitude, fix.longitude, 1)
            val address = results?.firstOrNull() ?: return@withContext null
            val parts = listOfNotNull(
                address.thoroughfare ?: address.featureName,
                address.locality ?: address.subAdminArea,
                address.adminArea,
            ).filter { it.isNotBlank() }.distinct()
            if (parts.isEmpty()) null else parts.joinToString(", ")
        } catch (t: Throwable) {
            null
        }
    }

    /** Great-circle distance in kilometres. Pure, tested in `DistanceTest`. */
    fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0088
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * r * atan2(sqrt(a), sqrt(1 - a))
    }

    /**
     * Road distance in kilometres from OSRM, or null when it cannot be had.
     *
     * One request, eight seconds, no retries.
     */
    suspend fun roadKm(from: Fix, to: Pair<Double, Double>): Double? =
        withContext(Dispatchers.IO) {
            val url = buildString {
                append(OSRM)
                append(String.format(Locale.US, "%.6f,%.6f;", from.longitude, from.latitude))
                append(String.format(Locale.US, "%.6f,%.6f", to.second, to.first))
                append("?overview=false")
            }
            var connection: HttpURLConnection? = null
            try {
                connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 6_000
                    readTimeout = 8_000
                    requestMethod = "GET"
                }
                if (connection.responseCode != 200) return@withContext null
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                val routes = JSONObject(body).optJSONArray("routes") ?: return@withContext null
                val first = routes.optJSONObject(0) ?: return@withContext null
                val meters = first.optDouble("distance", -1.0)
                if (meters <= 0.0) null else meters / 1000.0
            } catch (t: Throwable) {
                null
            } finally {
                try {
                    connection?.disconnect()
                } catch (t: Throwable) {
                    Unit
                }
            }
        }

    /** "850 metres" / "12.4 kilometres" — never "12.43 km", nobody speaks like that. */
    fun formatKm(km: Double): String = when {
        km < 1.0 -> "${(km * 1000 / 10).roundToInt() * 10} metres"
        km < 10.0 -> String.format(Locale.US, "%.1f kilometres", km)
        else -> "${km.roundToInt()} kilometres"
    }

    /** Opens turn-by-turn navigation, preferring Google Maps when it is installed. */
    fun navigate(context: Context, label: String, lat: Double, lon: Double): String? {
        val encoded = Uri.encode(label)
        val attempts = listOf(
            "google.navigation:q=$lat,$lon",
            "geo:$lat,$lon?q=$lat,$lon($encoded)",
            "geo:$lat,$lon?q=$encoded",
        )
        for (raw in attempts) {
            try {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(raw))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                return null
            } catch (t: Throwable) {
                continue
            }
        }
        return "No maps app could take that destination."
    }

    fun searchUrl(label: String): String =
        "https://www.google.com/maps/search/?api=1&query=" + URLEncoder.encode(label, "UTF-8")
}
