package com.jarvis.assistant.data

import java.util.Locale

/**
 * Turning two numbers into a sentence about the sky.
 *
 * Open-Meteo is free and keyless, but it answers in WMO weather codes and a US AQI number — 61 and
 * 168 mean nothing to a person. This is the translation layer, and it is pure so the awkward cases
 * (a code the specification does not define, an AQI above the last band, a temperature that should
 * be described as "freezing") are decided in a test rather than discovered in the cold.
 */
object WeatherRules {

    /** What the sky is doing, in the few categories worth saying out loud. */
    enum class Sky(val id: String) {
        CLEAR("clear"),
        MAINLY_CLEAR("mainly clear"),
        CLOUDY("cloudy"),
        FOG("fog"),
        DRIZZLE("drizzle"),
        RAIN("rain"),
        HEAVY_RAIN("heavy rain"),
        SNOW("snow"),
        SHOWERS("showers"),
        STORM("a thunderstorm"),
        UNKNOWN("unsettled"),
    }

    /** The WMO code groups Open-Meteo returns. Anything else is honestly "unsettled". */
    fun skyOf(code: Int): Sky = when (code) {
        0 -> Sky.CLEAR
        1 -> Sky.MAINLY_CLEAR
        2, 3 -> Sky.CLOUDY
        45, 48 -> Sky.FOG
        51, 53, 55, 56, 57 -> Sky.DRIZZLE
        61, 63 -> Sky.RAIN
        65, 66, 67 -> Sky.HEAVY_RAIN
        71, 73, 75, 77 -> Sky.SNOW
        80, 81, 82 -> Sky.SHOWERS
        85, 86 -> Sky.SNOW
        95, 96, 99 -> Sky.STORM
        else -> Sky.UNKNOWN
    }

    /** True when carrying an umbrella is a reasonable idea. */
    fun wet(code: Int): Boolean = when (skyOf(code)) {
        Sky.DRIZZLE, Sky.RAIN, Sky.HEAVY_RAIN, Sky.SNOW, Sky.SHOWERS, Sky.STORM -> true
        else -> false
    }

    enum class Air(val label: String, val advice: String) {
        GOOD("good", "fine to be outside"),
        MODERATE("moderate", "fine for most people"),
        POOR("poor", "ease off on outdoor exercise"),
        BAD("bad", "keep windows shut if you can"),
        SEVERE("severe", "stay indoors if you can"),
        UNKNOWN("unknown", ""),
    }

    /**
     * US AQI bands, as the EPA defines them.
     *
     * A negative number means the reading was not available at all — which is different from clean
     * air, and must not be shown as "good".
     */
    fun airOf(aqi: Int): Air = when {
        aqi < 0 -> Air.UNKNOWN
        aqi <= 50 -> Air.GOOD
        aqi <= 100 -> Air.MODERATE
        aqi <= 150 -> Air.POOR
        aqi <= 200 -> Air.BAD
        else -> Air.SEVERE
    }

    /** Whole degrees, with the sign the person actually says. */
    fun degrees(celsius: Double): String =
        "${Math.round(celsius).toInt()}\u00B0C"

    /** A short line for the panel: "24°C · mainly clear". */
    fun headline(celsius: Double, code: Int): String = "${degrees(celsius)} \u00B7 ${skyOf(code).id}"

    /** High and low for the day: "H 31° L 22°". */
    fun range(high: Double, low: Double): String =
        "H ${Math.round(high).toInt()}\u00B0 L ${Math.round(low).toInt()}\u00B0"

    /**
     * The whole answer as one spoken sentence.
     *
     * Deliberately fixed for English, Hindi and Hinglish only — the three languages whose canned
     * replies the app owns — and falling back to English rather than half-translating.
     */
    fun spoken(
        langTag: String,
        celsius: Double,
        code: Int,
        high: Double,
        low: Double,
        aqi: Int,
    ): String {
        val temp = Math.round(celsius).toInt()
        val h = Math.round(high).toInt()
        val l = Math.round(low).toInt()
        val sky = skyOf(code)
        val air = airOf(aqi)

        return when (langTag.lowercase(Locale.US).substringBefore('-')) {
            "hi" -> buildString {
                append("अभी $temp डिग्री है, ${hindiSky(sky)}।")
                append(" आज $l से $h डिग्री तक।")
                if (air != Air.UNKNOWN && air != Air.GOOD) {
                    append(" हवा ${hindiAir(air)} है।")
                }
                if (wet(code)) append(" छाता साथ रखिए।")
            }

            else -> buildString {
                append("It is $temp degrees and ${sky.id}.")
                append(" Today runs from $l to $h.")
                if (air != Air.UNKNOWN && air != Air.GOOD) append(" Air quality is ${air.label}.")
                if (wet(code)) append(" Take an umbrella.")
            }
        }
    }

    private fun hindiSky(sky: Sky): String = when (sky) {
        Sky.CLEAR -> "आसमान साफ़ है"
        Sky.MAINLY_CLEAR -> "आसमान लगभग साफ़ है"
        Sky.CLOUDY -> "बादल हैं"
        Sky.FOG -> "कोहरा है"
        Sky.DRIZZLE -> "फुहारें पड़ रही हैं"
        Sky.RAIN -> "बारिश हो रही है"
        Sky.HEAVY_RAIN -> "तेज़ बारिश हो रही है"
        Sky.SNOW -> "बर्फ़ पड़ रही है"
        Sky.SHOWERS -> "बौछारें पड़ रही हैं"
        Sky.STORM -> "तूफ़ान है"
        Sky.UNKNOWN -> "मौसम ठीक नहीं है"
    }

    private fun hindiAir(air: Air): String = when (air) {
        Air.GOOD -> "अच्छी"
        Air.MODERATE -> "ठीक"
        Air.POOR -> "खराब"
        Air.BAD -> "बहुत खराब"
        Air.SEVERE -> "गंभीर"
        Air.UNKNOWN -> ""
    }
}
