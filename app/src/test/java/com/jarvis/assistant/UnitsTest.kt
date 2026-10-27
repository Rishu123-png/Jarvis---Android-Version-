package com.jarvis.assistant

import com.jarvis.assistant.data.Units
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

/** Arithmetic and units, offline. */
class UnitsTest {

    @Test
    fun percentagesReadTheWayPeopleSayThem() {
        val outcome = Units.tryCalculate("12 percent of 4500")
        assertNotNull(outcome)
        assertEquals("540", outcome?.text)
    }

    @Test
    fun spokenOperatorsWork() {
        assertEquals("50", Units.tryCalculate("what is 20 plus 30")?.text)
        assertEquals("60", Units.tryCalculate("20 times 3")?.text)
        assertEquals("5", Units.tryCalculate("10 divided by 2")?.text)
    }

    @Test
    fun percentagesCompose() {
        assertEquals("220", Units.tryCalculate("200 + 10%")?.text)
    }

    @Test
    fun unitConversions() {
        assertEquals("3.11 miles", Units.tryCalculate("5 km to miles")?.text)
        assertEquals("120 minutes", Units.tryCalculate("2 hours in minutes")?.text)
        assertEquals("122", Units.tryCalculate("50 c to f")?.text?.substringBefore(" "))
    }

    @Test
    fun nonsenseIsNotAnswered() {
        assertNull(Units.tryCalculate("hello there"))
        assertNull(Units.tryCalculate("open the gallery"))
    }

    @Test
    fun formattingDropsPointlessDecimals() {
        assertEquals("12", Units.format(12.0))
        assertEquals("12.5", Units.format(12.5))
        assertEquals("3.11", Units.format(3.1069))
    }
}
