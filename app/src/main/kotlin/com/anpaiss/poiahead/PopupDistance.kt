package com.anpaiss.poiahead

import kotlin.math.roundToInt

/**
 * The distance line of the popup, kept live while the popup is on screen.
 *
 * Until 20/09 the popup showed the distance of the moment it fired and kept it
 * for its 25 seconds: at 25 km/h that is 170 m of road, so "in 190 m" was still
 * on screen with the fountain already behind. Pure functions, no Android.
 */
object PopupDistance {

    /**
     * How much farther than the closest point seen the rider must be before the
     * line says so. GPS noise at a standstill is a few metres; 20 m is a fountain
     * really left behind, not a fix that wobbled.
     */
    const val AWAY_HYSTERESIS_M = 20.0

    /** Steps of 10 m from 100 m up, 5 m below: a number that changes every fix is unreadable. */
    fun rounded(distanceM: Double): Int =
        if (distanceM >= 100.0) (distanceM / 10.0).roundToInt() * 10
        else (distanceM / 5.0).roundToInt() * 5

    fun format(distanceM: Double): String {
        val r = rounded(distanceM)
        return if (r < 1000) "$r m" else "%.1f km".format(java.util.Locale.ROOT, r / 1000.0)
    }

    /** True once the rider is clearly past the closest point reached so far. */
    fun movingAway(distanceM: Double, minSeenM: Double): Boolean =
        distanceM > minSeenM + AWAY_HYSTERESIS_M

    /** "in 150 m" while approaching, "150 m · moving away" once past it. */
    fun label(distanceM: Double, minSeenM: Double): String =
        if (movingAway(distanceM, minSeenM)) "${format(distanceM)} · moving away"
        else "in ${format(distanceM)}"
}
