package com.anpaiss.poiahead

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tiling exists to keep each SDK response under its 100 KB cap; it must cover the bbox exactly. */
class OverpassClientTest {

    @Test
    fun `a small bbox is a single tile`() {
        val bbox = doubleArrayOf(45.0, 9.0, 45.05, 9.05)
        val tiles = OverpassClient.tiles(bbox)
        assertEquals(1, tiles.size)
        assertTrue(bbox.contentEquals(tiles[0]))
    }

    @Test
    fun `a large bbox is covered without gaps or overlap`() {
        val bbox = doubleArrayOf(45.0, 9.0, 45.35, 9.52)   // 0.35 x 0.52 deg
        val tiles = OverpassClient.tiles(bbox)
        assertEquals(4 * 6, tiles.size)
        for (t in tiles) {
            assertTrue(t[2] - t[0] <= OverpassClient.TILE_DEG + 1e-9)
            assertTrue(t[3] - t[1] <= OverpassClient.TILE_DEG + 1e-9)
        }
        // Union of the tiles is the bbox: the extreme edges match and areas add up.
        assertEquals(bbox[0], tiles.minOf { it[0] }, 1e-9)
        assertEquals(bbox[1], tiles.minOf { it[1] }, 1e-9)
        assertEquals(bbox[2], tiles.maxOf { it[2] }, 1e-9)
        assertEquals(bbox[3], tiles.maxOf { it[3] }, 1e-9)
        val area = tiles.sumOf { (it[2] - it[0]) * (it[3] - it[1]) }
        assertEquals((bbox[2] - bbox[0]) * (bbox[3] - bbox[1]), area, 1e-9)
    }
}
