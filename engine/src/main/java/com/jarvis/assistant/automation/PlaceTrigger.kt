package com.jarvis.assistant.automation

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A saved place: where it is, how big its fence is, and what to call it.
 *
 * The stored form is a parameter string, because a trigger's shape is `kind + param` everywhere in
 * this app — the same reason `BATTERY_LOW` holds a percentage. `"28.6139,77.2090|200|Office"` is
 * human-editable, survives the routine JSON unchanged, and parses into this class at the edge.
 */
data class Place(
    val label: String,
    val latitude: Double,
    val longitude: Double,
    val radiusM: Int,
) {
    companion object {
        /** Below fifty metres, city GPS cannot tell the two sides of the fence apart. */
        const val MIN_RADIUS = 50

        /** Above two kilometres it is a region, not a place, and the battery cost is not worth it. */
        const val MAX_RADIUS = 2000

        const val FALLBACK_LABEL = "this place"

        /**
         * `"lat,lon|metres"`, with an optional `|name`.
         *
         * Returns null rather than a half-parsed place: a fence around 0,0 is the Atlantic, and a
         * routine that fires there is worse than a routine that never arms.
         */
        fun parse(raw: String): Place? {
            val parts = raw.split('|').map { it.trim() }
            if (parts.size < 2) return null

            val coords = parts[0].split(',').map { it.trim().toDoubleOrNull() ?: return null }
            if (coords.size != 2) return null
            val latitude = coords[0]
            val longitude = coords[1]
            if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return null

            val metres = parts[1].toIntOrNull() ?: return null
            val label = parts.getOrNull(2)?.takeIf { it.isNotBlank() } ?: FALLBACK_LABEL
            return Place(
                label = label.take(40),
                latitude = latitude,
                longitude = longitude,
                radiusM = metres.coerceIn(MIN_RADIUS, MAX_RADIUS),
            )
        }
    }
}

/**
 * Crossing a fence, without the fence flapping.
 *
 * Location is a noisy instrument. A phone on a desk near the edge of a 200 m circle will report 195 m,
 * then 205 m, then 190 m — three "crossings" in three minutes if the rule is a single comparison. So
 * there are two thresholds, not one: you are **inside** at or under the radius, and **outside** only
 * once you are a quarter again past it. In the band between, nothing changes, because nothing has
 * actually happened — that is hysteresis doing what a human would do.
 *
 * The other half is memory. [Observation] says whether this look *changed* the state, not merely what
 * the state is, because a routine must fire on the crossing and not on every poll afterwards. A first
 * look is never a crossing: coming back to a phone that has been off all night must not run "arrived
 * home" at the office.
 *
 * All of this is arithmetic, which is the point — [PlaceTriggerTest] can check the band, the edges and
 * the first look without a phone, a walk or a Tuesday.
 */
object PlaceTrigger {

    /**
     * How far past the fence you must get before you are considered gone: 25% of the radius, so a
     * 200 m fence releases at 250 m. A quarter is loose enough to absorb jitter and tight enough that
     * walking out of a building counts.
     */
    const val EXIT_RATIO = 1.25

    /** Mean Earth radius. The same constant the haversine formula is normally written with. */
    private const val EARTH_RADIUS_M = 6_371_000.0

    /**
     * Straight-line distance between two coordinates.
     *
     * Straight line, deliberately: a fence is a circle around a point, and the distance that matters is
     * the distance to that point. Road distance would be the wrong question — and would need a network.
     */
    fun metresBetween(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * EARTH_RADIUS_M * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }

    /** What one look at the distance told us. `arrived` and `left` are crossings, not states. */
    data class Observation(
        val inside: Boolean,
        val arrived: Boolean,
        val left: Boolean,
    )

    /**
     * @param metres distance from the place right now
     * @param radiusM the fence
     * @param wasInside the previous look, or null when this routine has never looked
     */
    fun observe(metres: Double, radiusM: Int, wasInside: Boolean?): Observation {
        val radius = radiusM.coerceIn(Place.MIN_RADIUS, Place.MAX_RADIUS).toDouble()
        val release = radius * EXIT_RATIO
        val inside = when {
            metres <= radius -> true
            metres >= release -> false
            // In the band: keep the answer we already had. With nothing to keep, the tighter
            // threshold decides, so a first look can never invent a crossing.
            else -> wasInside ?: (metres <= radius)
        }
        return Observation(
            inside = inside,
            arrived = wasInside == false && inside,
            left = wasInside == true && !inside,
        )
    }

    /** Does this trigger kind want the crossing that was just observed? */
    fun fires(kind: TriggerKind, observation: Observation): Boolean = when (kind) {
        TriggerKind.ARRIVE -> observation.arrived
        TriggerKind.LEAVE_PLACE -> observation.left
        else -> false
    }
}
