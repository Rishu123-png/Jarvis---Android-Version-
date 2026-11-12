package com.jarvis.assistant

import com.jarvis.assistant.automation.Place
import com.jarvis.assistant.automation.PlaceTrigger
import com.jarvis.assistant.automation.TriggerKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A circle on a map that must not flap.
 *
 * A phone near the edge of its fence reports 195 m, then 205 m, then 190 m — three crossings in three
 * minutes if the rule is one comparison. So the tests are about the band between inside and outside,
 * about the first look never counting as an arrival, and about the parameter string surviving a
 * hand-edit in the routine editor.
 */
class PlaceTriggerTest {

    // Roughly Connaught Place, Delhi.
    private fun office() = Place.parse("28.63150,77.21670|200|Office")!!

    @Test
    fun aPlaceParsesFromTheStringTheEditorStores() {
        val place = office()
        assertEquals("Office", place.label)
        assertEquals(28.63150, place.latitude, 0.000001)
        assertEquals(77.21670, place.longitude, 0.000001)
        assertEquals(200, place.radiusM)
        // The name is optional; without one it is still a usable place.
        assertEquals(Place.FALLBACK_LABEL, Place.parse("28.63,77.21|150")?.label)
    }

    @Test
    fun nonsenseNeverBecomesAFence() {
        assertNull(Place.parse(""))
        assertNull(Place.parse("28.63"))
        assertNull(Place.parse("not,coords|200"))
        assertNull(Place.parse("28.63,77.21|wide"))
        // A point outside the world is the Atlantic, not a place.
        assertNull(Place.parse("99.0,77.21|200"))
        assertNull(Place.parse("28.63,999.0|200"))
    }

    @Test
    fun theRadiusIsClampedToSomethingAPhoneCanFeel() {
        // Ten metres is inside GPS noise; ten kilometres is a region.
        assertEquals(Place.MIN_RADIUS, Place.parse("28.63,77.21|10")?.radiusM)
        assertEquals(Place.MAX_RADIUS, Place.parse("28.63,77.21|99999")?.radiusM)
    }

    @Test
    fun distanceIsTheStraightLineBetweenTwoPoints() {
        val place = office()
        // Standing on the place is zero metres.
        assertEquals(0.0, PlaceTrigger.metresBetween(place.latitude, place.longitude, place.latitude, place.longitude), 0.5)
        // One thousandth of a degree of latitude is about 111 metres, anywhere on Earth.
        val north = PlaceTrigger.metresBetween(place.latitude, place.longitude, place.latitude + 0.001, place.longitude)
        assertEquals(111.0, north, 2.0)
    }

    @Test
    fun theFirstLookIsNeverAnArrival() {
        // Coming back to a phone that has been off all night must not announce arriving at the office.
        val inside = PlaceTrigger.observe(metres = 40.0, radiusM = 200, wasInside = null)
        assertTrue(inside.inside)
        assertFalse(inside.arrived)

        val outside = PlaceTrigger.observe(metres = 900.0, radiusM = 200, wasInside = null)
        assertFalse(outside.inside)
        assertFalse(outside.arrived)
        assertFalse(outside.left)
    }

    @Test
    fun aRealCrossingIsReportedOnce() {
        val arrived = PlaceTrigger.observe(metres = 120.0, radiusM = 200, wasInside = false)
        assertTrue(arrived.inside)
        assertTrue(arrived.arrived)

        // The next poll, a minute later, is still inside: the state has not changed again.
        val stillThere = PlaceTrigger.observe(metres = 100.0, radiusM = 200, wasInside = true)
        assertTrue(stillThere.inside)
        assertFalse(stillThere.arrived)

        val left = PlaceTrigger.observe(metres = 400.0, radiusM = 200, wasInside = true)
        assertFalse(left.inside)
        assertTrue(left.left)
    }

    @Test
    fun theBandBetweenTheTwoThresholdsHoldsItsAnswer() {
        // 200 m fence, released at 250 m. At 230 m the phone has not decided either way, so it keeps
        // the answer it gave last time — that is what stops the routine flapping.
        val stillInside = PlaceTrigger.observe(metres = 230.0, radiusM = 200, wasInside = true)
        assertTrue(stillInside.inside)
        assertFalse(stillInside.left)

        val stillOutside = PlaceTrigger.observe(metres = 230.0, radiusM = 200, wasInside = false)
        assertFalse(stillOutside.inside)
        assertFalse(stillOutside.arrived)
    }

    @Test
    fun onlyTheMatchingTriggerFiresForACrossing() {
        val arrived = PlaceTrigger.observe(metres = 50.0, radiusM = 200, wasInside = false)
        assertTrue(PlaceTrigger.fires(TriggerKind.ARRIVE, arrived))
        assertFalse(PlaceTrigger.fires(TriggerKind.LEAVE_PLACE, arrived))

        val left = PlaceTrigger.observe(metres = 500.0, radiusM = 200, wasInside = true)
        assertTrue(PlaceTrigger.fires(TriggerKind.LEAVE_PLACE, left))
        assertFalse(PlaceTrigger.fires(TriggerKind.ARRIVE, left))

        // A crossing says nothing to any other kind of trigger.
        assertFalse(PlaceTrigger.fires(TriggerKind.CHARGING, arrived))
    }

    @Test
    fun theTriggerIdsRoundTripThroughStoredJson() {
        assertNotNull(TriggerKind.fromId("arrive"))
        assertEquals(TriggerKind.ARRIVE, TriggerKind.fromId("arrive"))
        assertEquals(TriggerKind.LEAVE_PLACE, TriggerKind.fromId("leave_place"))
        assertTrue(TriggerKind.ARRIVE.isPlace)
        assertTrue(TriggerKind.LEAVE_PLACE.isPlace)
        assertFalse(TriggerKind.CHARGING.isPlace)
    }
}
