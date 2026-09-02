package com.anpaiss.poiahead

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos

/**
 * The geometry every alert decision rests on — and, from the route projection
 * onwards, the geometry "ahead" is computed with. Never had a test.
 */
class GeoUtilsTest {

    private val degLatM = 111_195.0   // metres per degree of latitude (haversine, R = 6371 km)

    @Test
    fun `haversine matches one degree of latitude`() {
        assertEquals(degLatM, GeoUtils.haversineM(0.0, 0.0, 1.0, 0.0), 50.0)
        assertEquals(0.0, GeoUtils.haversineM(45.0, 9.0, 45.0, 9.0), 1e-9)
        // Symmetric
        assertEquals(GeoUtils.haversineM(45.0, 9.0, 46.0, 10.0), GeoUtils.haversineM(46.0, 10.0, 45.0, 9.0), 1e-6)
    }

    @Test
    fun `distance to a polyline is zero on the line and perpendicular beside it`() {
        // Segment along the equator from lng 0 to lng 0.01 (~1.1 km)
        val line = listOf(0.0 to 0.0, 0.0 to 0.01)
        assertEquals(0.0, GeoUtils.distanceToPolylineM(0.0, 0.005, line), 0.5)
        // 100 m north of the middle
        val lat100 = 100.0 / degLatM
        assertEquals(100.0, GeoUtils.distanceToPolylineM(lat100, 0.005, line), 1.0)
    }

    @Test
    fun `beyond the endpoints the distance is to the endpoint, not the extended line`() {
        val line = listOf(0.0 to 0.0, 0.0 to 0.01)
        // 200 m past the east end, on the line's extension: distance is 200 m, not 0
        val lng200 = 200.0 / (degLatM * cos(0.0))
        assertEquals(200.0, GeoUtils.distanceToPolylineM(0.0, 0.01 + lng200, line), 2.0)
    }

    @Test
    fun `a polyline with fewer than two points is infinitely far`() {
        assertEquals(Double.MAX_VALUE, GeoUtils.distanceToPolylineM(0.0, 0.0, emptyList()), 0.0)
        assertEquals(Double.MAX_VALUE, GeoUtils.distanceToPolylineM(0.0, 0.0, listOf(0.0 to 0.0)), 0.0)
    }

    @Test
    fun `bbox margin widens with latitude in longitude only`() {
        val b = GeoUtils.bboxOf(listOf(45.0 to 9.0), 1000.0)!!
        val dLat = 1000.0 / 111_320.0
        val dLng = dLat / cos(Math.toRadians(45.0))
        assertEquals(45.0 - dLat, b[0], 1e-9)   // south
        assertEquals(9.0 - dLng,  b[1], 1e-9)   // west
        assertEquals(45.0 + dLat, b[2], 1e-9)   // north
        assertEquals(9.0 + dLng,  b[3], 1e-9)   // east
        assertTrue(b[3] - b[1] > b[2] - b[0])
    }

    @Test
    fun `bbox of an empty list is null`() {
        assertEquals(null, GeoUtils.bboxOf(emptyList(), 100.0))
    }
}
