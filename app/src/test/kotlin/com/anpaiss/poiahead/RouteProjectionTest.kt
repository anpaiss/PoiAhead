package com.anpaiss.poiahead

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Ahead" is only as right as this projection. The cases are the ones that
 * broke the straight-line criterion: a fountain just passed, an out-and-back,
 * a loop, and one that is close as the crow flies but off the route.
 */
class RouteProjectionTest {

    private val degLatM = 111_195.0
    private val lat0 = 45.0
    private val degLngM = degLatM * kotlin.math.cos(Math.toRadians(lat0))

    /** A point [eastM] east and [northM] north of (lat0, 9.0). */
    private fun pt(eastM: Double, northM: Double) = (lat0 + northM / degLatM) to (9.0 + eastM / degLngM)

    /** Straight 10 km route eastwards, one vertex every km. */
    private val straight = RouteProjection.Route((0..10).map { pt(it * 1000.0, 0.0) })

    private fun poi(id: String, eastM: Double, northM: Double) =
        pt(eastM, northM).let { Poi(id, it.first, it.second, null) }

    @Test
    fun `a fountain beside a straight route projects once at the right place`() {
        val hits = RouteProjection.project(straight, pt(3200.0, 50.0).first, pt(3200.0, 50.0).second, 500.0)
        assertEquals(1, hits.size)
        assertEquals(3200.0, hits[0].alongM, 5.0)
        assertEquals(50.0, hits[0].detourM, 1.0)
        assertEquals(10_000.0, straight.lengthM, 15.0)
    }

    @Test
    fun `outside the corridor there is no pass at all`() {
        val p = pt(3200.0, 800.0)
        assertTrue(RouteProjection.project(straight, p.first, p.second, 500.0).isEmpty())
    }

    @Test
    fun `an out-and-back yields one pass per leg`() {
        // 0 → 10 km east, then back to 0 along the same line: 20 km.
        val outAndBack = RouteProjection.Route((0..10).map { pt(it * 1000.0, 0.0) } + (9 downTo 0).map { pt(it * 1000.0, 0.0) })
        val p = pt(3200.0, 40.0)
        val hits = RouteProjection.project(outAndBack, p.first, p.second, 500.0).sortedBy { it.alongM }
        assertEquals(2, hits.size)
        assertEquals(3200.0,  hits[0].alongM, 5.0)          // outbound leg
        assertEquals(16800.0, hits[1].alongM, 5.0)          // return leg: 20 km - 3.2 km
        assertEquals(hits[0].detourM, hits[1].detourM, 0.5)
    }

    @Test
    fun `a loop passes a fountain near its start twice, once per lap`() {
        // 4 km square ridden twice.
        val square = listOf(pt(0.0, 0.0), pt(1000.0, 0.0), pt(1000.0, 1000.0), pt(0.0, 1000.0), pt(0.0, 0.0))
        val twoLaps = RouteProjection.Route(square + square.drop(1))
        val p = pt(500.0, -30.0)   // 30 m south of the first edge
        val hits = RouteProjection.project(twoLaps, p.first, p.second, 500.0).sortedBy { it.alongM }
        assertEquals(2, hits.size)
        assertEquals(500.0, hits[0].alongM, 5.0)
        assertEquals(4500.0, hits[1].alongM, 5.0)
    }

    @Test
    fun `adjacent segments inside the corridor are one pass, the closest`() {
        // Corridor 500 m: a fountain 100 m north of the 3 km vertex is inside the
        // corridor of segments 2-3 and 3-4 alike. Must be a single hit at 100 m.
        val p = pt(3000.0, 100.0)
        val hits = RouteProjection.project(straight, p.first, p.second, 500.0)
        assertEquals(1, hits.size)
        assertEquals(100.0, hits[0].detourM, 1.0)
        assertEquals(3000.0, hits[0].alongM, 5.0)
    }

    @Test
    fun `degenerate routes project nothing`() {
        val one = RouteProjection.Route(listOf(pt(0.0, 0.0)))
        assertTrue(RouteProjection.project(one, lat0, 9.0, 500.0).isEmpty())
        assertTrue(RouteProjection.project(RouteProjection.Route(emptyList()), lat0, 9.0, 500.0).isEmpty())
    }

    @Test
    fun `ahead keeps only passes still to come, nearest first`() {
        val pois = listOf(poi("behind", 1000.0, 20.0), poi("next", 4000.0, 20.0), poi("far", 8000.0, 20.0), poi("off", 5000.0, 900.0))
        val hits = RouteProjection.projectAll(straight, pois, 500.0)
        assertNull(hits["off"])
        val ahead = RouteProjection.ahead(hits, posM = 2500.0)
        assertEquals(listOf("next", "far"), ahead.map { it.poiId })
        assertEquals(1500.0, ahead[0].distanceFrom(2500.0), 5.0)
        // Standing right on it: not ahead any more.
        assertTrue(RouteProjection.ahead(hits, posM = 8000.0).none { it.poiId == "far" })
    }
}
