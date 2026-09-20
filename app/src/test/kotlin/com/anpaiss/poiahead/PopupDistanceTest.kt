package com.anpaiss.poiahead

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The popup's distance line: live, rounded so it is readable, and honest once the fountain is behind. */
class PopupDistanceTest {

    @Test
    fun `distances are rounded to steps a rider can read`() {
        assertEquals(190, PopupDistance.rounded(188.0))
        assertEquals(100, PopupDistance.rounded(101.0))
        assertEquals(95, PopupDistance.rounded(97.0))
        assertEquals(5, PopupDistance.rounded(6.0))
        assertEquals(0, PopupDistance.rounded(2.0))
        assertEquals("190 m", PopupDistance.format(188.0))
        assertEquals("1.2 km", PopupDistance.format(1234.0))
    }

    @Test
    fun `approaching reads as in N metres`() {
        assertEquals("in 190 m", PopupDistance.label(188.0, 188.0))
        assertEquals("in 80 m", PopupDistance.label(80.0, 80.0))
    }

    @Test
    fun `gps wobble at the closest point is not moving away`() {
        assertFalse(PopupDistance.movingAway(32.0, 15.0))     // 17 m past the minimum: still noise
        assertEquals("in 30 m", PopupDistance.label(32.0, 15.0))
    }

    @Test
    fun `past the fountain the line says so`() {
        assertTrue(PopupDistance.movingAway(60.0, 15.0))
        assertEquals("60 m \u00B7 moving away", PopupDistance.label(60.0, 15.0))
    }
}
