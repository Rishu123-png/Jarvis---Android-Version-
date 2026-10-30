package com.jarvis.assistant

import com.jarvis.assistant.data.WeatherRules
import com.jarvis.assistant.device.Weather
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sky, without a network.
 *
 * Weather codes are the kind of mapping that is silently wrong for months: 45 quietly showing as
 * "clear" because the case fell through. So every group is pinned, along with the two decisions that
 * matter more than accuracy — that *unknown* is never shown as *good*, and that an unreadable
 * payload produces nothing at all rather than a zero.
 */
class WeatherTest {

    // ---------------------------------------------------------------------------------------------
    // Codes
    // ---------------------------------------------------------------------------------------------

    @Test
    fun wmoCodesLandInTheRightGroups() {
        assertEquals(WeatherRules.Sky.CLEAR, WeatherRules.skyOf(0))
        assertEquals(WeatherRules.Sky.MAINLY_CLEAR, WeatherRules.skyOf(1))
        assertEquals(WeatherRules.Sky.CLOUDY, WeatherRules.skyOf(3))
        assertEquals(WeatherRules.Sky.FOG, WeatherRules.skyOf(45))
        assertEquals(WeatherRules.Sky.DRIZZLE, WeatherRules.skyOf(53))
        assertEquals(WeatherRules.Sky.RAIN, WeatherRules.skyOf(63))
        assertEquals(WeatherRules.Sky.HEAVY_RAIN, WeatherRules.skyOf(65))
        assertEquals(WeatherRules.Sky.SNOW, WeatherRules.skyOf(73))
        assertEquals(WeatherRules.Sky.SHOWERS, WeatherRules.skyOf(81))
        assertEquals(WeatherRules.Sky.SNOW, WeatherRules.skyOf(86))
        assertEquals(WeatherRules.Sky.STORM, WeatherRules.skyOf(95))
    }

    @Test
    fun aCodeTheSpecificationDoesNotDefineIsHonest() {
        assertEquals(WeatherRules.Sky.UNKNOWN, WeatherRules.skyOf(-1))
        assertEquals(WeatherRules.Sky.UNKNOWN, WeatherRules.skyOf(199))
        assertFalse(WeatherRules.wet(199))
    }

    @Test
    fun wetWeatherIsRecognised() {
        assertFalse(WeatherRules.wet(0))
        assertTrue(WeatherRules.wet(61))
        assertTrue(WeatherRules.wet(80))
        assertTrue(WeatherRules.wet(95))
        assertFalse(WeatherRules.wet(2))
    }

    // ---------------------------------------------------------------------------------------------
    // Air
    // ---------------------------------------------------------------------------------------------

    @Test
    fun aqiBandsFollowTheEpa() {
        assertEquals(WeatherRules.Air.GOOD, WeatherRules.airOf(25))
        assertEquals(WeatherRules.Air.MODERATE, WeatherRules.airOf(100))
        assertEquals(WeatherRules.Air.POOR, WeatherRules.airOf(101))
        assertEquals(WeatherRules.Air.BAD, WeatherRules.airOf(180))
        assertEquals(WeatherRules.Air.SEVERE, WeatherRules.airOf(320))
    }

    @Test
    fun unknownAirIsNotCleanAir() {
        // The whole point of the -1 sentinel: a missing reading must never read as "good".
        assertEquals(WeatherRules.Air.UNKNOWN, WeatherRules.airOf(-1))
    }

    // ---------------------------------------------------------------------------------------------
    // Wording
    // ---------------------------------------------------------------------------------------------

    @Test
    fun theSentenceSaysWhatThePersonNeeds() {
        val said = WeatherRules.spoken("en-IN", 24.4, 61, high = 31.2, low = 22.4, aqi = 168)
        assertTrue(said.contains("24"))          // rounded, not 24.4
        assertTrue(said.contains("rain"))
        assertTrue(said.contains("22"))
        assertTrue(said.contains("31"))
        assertTrue(said.contains("bad"))          // 168 is a BAD band
        assertTrue(said.contains("umbrella"))
    }

    @Test
    fun cleanAirAndClearSkyStayShort() {
        val said = WeatherRules.spoken("en-IN", 30.0, 0, 34.0, 25.0, aqi = 30)
        assertFalse(said.contains("Air quality"))
        assertFalse(said.contains("umbrella"))
    }

    @Test
    fun hindiIsSpokenInHindi() {
        val said = WeatherRules.spoken("hi-IN", 28.0, 61, 32.0, 24.0, aqi = 120)
        assertTrue(said.contains("डिग्री"))
        assertTrue(said.contains("बारिश"))
        assertTrue(said.contains("छाता"))
    }

    @Test
    fun formattingIsWholeDegrees() {
        assertEquals("24°C", WeatherRules.degrees(24.4))
        assertEquals("25°C", WeatherRules.degrees(24.6))
        assertEquals("H 31° L 22°", WeatherRules.range(31.2, 22.4))
        assertEquals("24°C · rain", WeatherRules.headline(24.0, 61))
    }

    // ---------------------------------------------------------------------------------------------
    // URLs and payloads
    // ---------------------------------------------------------------------------------------------

    @Test
    fun theUrlsAreKeylessAndCorrect() {
        val forecast = Weather.forecastUrl(28.6139, 77.2090)
        assertTrue(forecast.startsWith("https://api.open-meteo.com/v1/forecast?"))
        assertTrue(forecast.contains("latitude=28.6139"))
        assertTrue(forecast.contains("longitude=77.2090"))
        assertFalse(forecast.lowercase().contains("key"))
        assertTrue(forecast.contains("timezone=auto"))

        val air = Weather.airUrl(28.6139, 77.209)
        assertTrue(air.contains("us_aqi"))
        assertFalse(air.lowercase().contains("apikey"))
    }

    @Test
    fun aForecastPayloadIsReadCorrectly() {
        val body = """
            {"latitude":28.6,"longitude":77.2,"timezone":"Asia/Kolkata",
             "current":{"temperature_2m":24.4,"relative_humidity_2m":61,
                        "weather_code":61,"wind_speed_10m":11.2},
             "daily":{"temperature_2m_max":[31.2],"temperature_2m_min":[22.4]}}
        """.trimIndent()
        val sky = Weather.parseForecast(body, at = 1_700_000_000_000L)
        assertNotNull(sky)
        assertEquals(24.4, sky!!.celsius, 0.001)
        assertEquals(61, sky.code)
        assertEquals(61, sky.humidity)
        assertEquals(31.2, sky.high, 0.001)
        assertEquals(22.4, sky.low, 0.001)
        assertEquals(-1, sky.aqi)                 // air is a separate request
        assertTrue(sky.isWet)
    }

    @Test
    fun aPayloadWithoutACurrentBlockIsNothing() {
        assertNull(Weather.parseForecast("""{"latitude":28.6}"""))
        assertNull(Weather.parseForecast("not json"))
        assertNull(Weather.parseForecast("{}"))
    }

    @Test
    fun airIsReadAndAMissingReadingIsMinusOne() {
        assertEquals(168, Weather.parseAir("""{"current":{"us_aqi":168.3}}"""))
        assertEquals(-1, Weather.parseAir("""{"current":{}}"""))
        assertEquals(-1, Weather.parseAir("nonsense"))
        assertEquals(-1, Weather.parseAir("{}"))
    }
}
