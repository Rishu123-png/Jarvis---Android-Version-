package com.jarvis.assistant

import com.jarvis.assistant.device.Whereabouts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Haversine and the way a distance is spoken. */
class DistanceTest {

    @Test
    fun delhiToAgraIsAboutOneHundredAndEightyKilometres() {
        // India Gate -> Taj Mahal.
        val km = Whereabouts.haversineKm(28.6129, 77.2295, 27.1751, 78.0421)
        assertTrue("was $km", km in 160.0..200.0)
    }

    @Test
    fun theSamePointIsZero() {
        assertEquals(0.0, Whereabouts.haversineKm(28.6, 77.2, 28.6, 77.2), 0.0001)
    }

    @Test
    fun shortDistancesAreSpokenInMetres() {
        assertEquals("850 metres", Whereabouts.formatKm(0.85))
        assertEquals("400 metres", Whereabouts.formatKm(0.4))
    }

    @Test
    fun longDistancesAreRoundedLikeAPersonWouldSayThem() {
        assertEquals("3.1 kilometres", Whereabouts.formatKm(3.14))
        assertEquals("12 kilometres", Whereabouts.formatKm(12.4))
    }
}
