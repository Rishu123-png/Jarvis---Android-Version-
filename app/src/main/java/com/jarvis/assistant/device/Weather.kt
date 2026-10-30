package com.jarvis.assistant.device

import android.content.Context
import com.jarvis.assistant.data.WeatherRules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * The sky, from a source that asks for nothing.
 *
 * Open-Meteo needs no key, no account and no sign-up; the only thing it needs is a coordinate, which
 * the phone already has for distance and navigation. Two requests — the forecast and the air quality
 * — both free and both cached for half an hour, because weather does not change between glances and
 * a dashboard that fetches on every repaint would be both slow and rude.
 *
 * Everything here degrades honestly. No permission, no fix, no network: the caller gets a failure
 * with a reason, and the panel says which one instead of showing a friendly 24°C that is not real.
 */
object Weather {

    data class Sky(
        val celsius: Double,
        val code: Int,
        val high: Double,
        val low: Double,
        val humidity: Int,
        val windKph: Double,
        val aqi: Int,
        val at: Long,
        /** Where the reading is from, so the panel can say it. */
        val place: String = "",
        val fresh: Boolean = true,
    ) {
        val conditions: WeatherRules.Sky get() = WeatherRules.skyOf(code)
        val air: WeatherRules.Air get() = WeatherRules.airOf(aqi)
        val headline: String get() = WeatherRules.headline(celsius, code)
        val range: String get() = WeatherRules.range(high, low)
        val isWet: Boolean get() = WeatherRules.wet(code)
    }

    sealed class Outcome {
        data class Ok(val sky: Sky) : Outcome()

        /** No coordinate. The reason is what the panel should print. */
        data class NoLocation(val reason: String) : Outcome()

        /** A coordinate but no answer — offline, or both endpoints refused. */
        data class Unreachable(val reason: String) : Outcome()
    }

    private const val PREFS = "jarvis_weather"
    private const val CACHE = "cache"
    private const val CACHE_MINUTES = 30

    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 10_000

    private const val FORECAST = "https://api.open-meteo.com/v1/forecast"
    private const val AIR = "https://air-quality-api.open-meteo.com/v1/air-quality"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---------------------------------------------------------------------------------------------
    // URLs (pure, and covered by tests)
    // ---------------------------------------------------------------------------------------------

    fun forecastUrl(lat: Double, lon: Double): String = buildString {
        append(FORECAST)
        append("?latitude=").append(coord(lat))
        append("&longitude=").append(coord(lon))
        append("&current=temperature_2m,relative_humidity_2m,weather_code,wind_speed_10m")
        append("&daily=temperature_2m_max,temperature_2m_min")
        append("&timezone=auto&forecast_days=1")
    }

    fun airUrl(lat: Double, lon: Double): String = buildString {
        append(AIR)
        append("?latitude=").append(coord(lat))
        append("&longitude=").append(coord(lon))
        append("&current=us_aqi")
    }

    /** Four decimals is about eleven metres — far more than weather needs, and shorter on the wire. */
    private fun coord(value: Double): String =
        String.format(java.util.Locale.US, "%.4f", value)

    // ---------------------------------------------------------------------------------------------
    // Parsing (pure)
    // ---------------------------------------------------------------------------------------------

    /** The forecast half. Null when the payload is not the shape Open-Meteo documents. */
    fun parseForecast(body: String, at: Long = System.currentTimeMillis()): Sky? = try {
        val root = JSONObject(body)
        val current = root.optJSONObject("current")
        val daily = root.optJSONObject("daily")
        val celsius = current?.optDouble("temperature_2m", Double.NaN) ?: Double.NaN
        if (celsius.isNaN()) {
            null
        } else {
            Sky(
                celsius = celsius,
                code = current.optInt("weather_code", -1),
                high = daily?.optJSONArray("temperature_2m_max")?.optDouble(0, celsius) ?: celsius,
                low = daily?.optJSONArray("temperature_2m_min")?.optDouble(0, celsius) ?: celsius,
                humidity = current.optInt("relative_humidity_2m", -1),
                windKph = current.optDouble("wind_speed_10m", 0.0),
                aqi = -1,
                at = at,
            )
        }
    } catch (t: Throwable) {
        null
    }

    /** The air half. Returns -1 rather than 0, because clean air and unknown air are different. */
    fun parseAir(body: String): Int = try {
        val current = JSONObject(body).optJSONObject("current")
        val aqi = current?.optDouble("us_aqi", Double.NaN) ?: Double.NaN
        if (aqi.isNaN()) -1 else aqi.toInt()
    } catch (t: Throwable) {
        -1
    }

    // ---------------------------------------------------------------------------------------------
    // The lookup
    // ---------------------------------------------------------------------------------------------

    /**
     * The current sky at the phone, cached for [CACHE_MINUTES].
     *
     * [force] skips the cache — used when the operator asks out loud, because a spoken question
     * deserves a real answer even if the panel is showing a half-hour-old one.
     */
    suspend fun current(context: Context, force: Boolean = false): Outcome {
        if (!force) {
            cached(context)?.let { return Outcome.Ok(it) }
        }

        val fix = try {
            Whereabouts.lastKnown(context)
        } catch (t: Throwable) {
            null
        }
        if (fix == null) {
            return Outcome.NoLocation(
                if (Whereabouts.hasPermission(context)) {
                    "no location yet"
                } else {
                    "location permission is off"
                },
            )
        }

        val forecastBody = withContext(Dispatchers.IO) {
            request(forecastUrl(fix.latitude, fix.longitude))
        } ?: return staleOr(context, "no connection")

        val parsed = parseForecast(forecastBody) ?: return staleOr(context, "the forecast came back empty")

        // Air quality is a second host and a separate failure: missing air is not missing weather.
        val airBody = withContext(Dispatchers.IO) { request(airUrl(fix.latitude, fix.longitude)) }
        val aqi = if (airBody == null) -1 else parseAir(airBody)

        val sky = parsed.copy(aqi = aqi, at = System.currentTimeMillis())
        prefs(context).edit().putString(CACHE, encode(sky)).apply()
        return Outcome.Ok(sky)
    }

    /** A half-hour-old reading beats no reading, but it must be labelled as old. */
    private suspend fun staleOr(context: Context, reason: String): Outcome {
        val old = cached(context, maxAgeMinutes = 6 * 60)
        return if (old != null) {
            Outcome.Ok(old.copy(fresh = false))
        } else {
            Outcome.Unreachable(reason)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Cache
    // ---------------------------------------------------------------------------------------------

    private fun encode(sky: Sky): String = JSONObject()
        .put("c", sky.celsius)
        .put("code", sky.code)
        .put("h", sky.high)
        .put("l", sky.low)
        .put("hum", sky.humidity)
        .put("wind", sky.windKph)
        .put("aqi", sky.aqi)
        .put("at", sky.at)
        .toString()

    private fun decode(raw: String?): Sky? {
        if (raw.isNullOrBlank()) return null
        return try {
            val obj = JSONObject(raw)
            Sky(
                celsius = obj.optDouble("c", Double.NaN),
                code = obj.optInt("code", -1),
                high = obj.optDouble("h", 0.0),
                low = obj.optDouble("l", 0.0),
                humidity = obj.optInt("hum", -1),
                windKph = obj.optDouble("wind", 0.0),
                aqi = obj.optInt("aqi", -1),
                at = obj.optLong("at", 0L),
            ).takeIf { !it.celsius.isNaN() }
        } catch (t: Throwable) {
            null
        }
    }

    private fun cached(context: Context, maxAgeMinutes: Int = CACHE_MINUTES): Sky? {
        val stored = decode(prefs(context).getString(CACHE, null)) ?: return null
        val age = System.currentTimeMillis() - stored.at
        return if (age in 0..(maxAgeMinutes * 60_000L)) stored.copy(fresh = true) else null
    }

    fun forget(context: Context) {
        prefs(context).edit().remove(CACHE).apply()
    }

    // ---------------------------------------------------------------------------------------------
    // Transport
    // ---------------------------------------------------------------------------------------------

    private fun request(url: String): String? {
        var live: HttpURLConnection? = null
        try {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = false
                setRequestProperty("Accept", "application/json")
            }
            live = connection
            if (connection.responseCode !in 200..299) return null
            return connection.inputStream.bufferedReader().use { it.readText() }.take(200_000)
        } catch (t: Throwable) {
            return null
        } finally {
            try {
                live?.disconnect()
            } catch (t: Throwable) {
                // Already released.
            }
        }
    }
}
