package com.anpaiss.poiahead

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

object GeoUtils {

    private const val EARTH_R = 6_371_000.0

    /** Distanza in metri tra due coordinate (haversine). */
    fun haversineM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return 2.0 * EARTH_R * asin(sqrt(a))
    }

    /**
     * Distanza minima in metri di un punto dalla polyline (lista di coppie lat,lng).
     * Proiezione equirettangolare locale: adeguata per distanze < qualche km.
     * Restituisce Double.MAX_VALUE se la polyline ha meno di 2 punti.
     */
    fun distanceToPolylineM(lat: Double, lng: Double, polyline: List<Pair<Double, Double>>): Double {
        if (polyline.size < 2) return Double.MAX_VALUE
        val cosLat = cos(Math.toRadians(lat))
        val px = Math.toRadians(lng) * cosLat * EARTH_R
        val py = Math.toRadians(lat) * EARTH_R

        var best = Double.MAX_VALUE
        var ax = Math.toRadians(polyline[0].second) * cosLat * EARTH_R
        var ay = Math.toRadians(polyline[0].first) * EARTH_R
        for (i in 1 until polyline.size) {
            val bx = Math.toRadians(polyline[i].second) * cosLat * EARTH_R
            val by = Math.toRadians(polyline[i].first) * EARTH_R
            val d = pointToSegment(px, py, ax, ay, bx, by)
            if (d < best) best = d
            ax = bx
            ay = by
        }
        return best
    }

    private fun pointToSegment(
        px: Double, py: Double,
        ax: Double, ay: Double,
        bx: Double, by: Double,
    ): Double {
        val dx = bx - ax
        val dy = by - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0.0) 0.0
            else (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0.0, 1.0)
        val cx = ax + t * dx
        val cy = ay + t * dy
        return sqrt((px - cx) * (px - cx) + (py - cy) * (py - cy))
    }

    /** Bounding box (south, west, north, east) di una polyline, allargato di [marginM] metri. */
    fun bboxOf(points: List<Pair<Double, Double>>, marginM: Double): DoubleArray? {
        if (points.isEmpty()) return null
        var s = Double.MAX_VALUE; var w = Double.MAX_VALUE
        var n = -Double.MAX_VALUE; var e = -Double.MAX_VALUE
        for ((lat, lng) in points) {
            if (lat < s) s = lat
            if (lat > n) n = lat
            if (lng < w) w = lng
            if (lng > e) e = lng
        }
        val dLat = marginM / 111_320.0
        val midLatCos = cos(Math.toRadians((s + n) / 2.0)).coerceAtLeast(0.01)
        val dLng = marginM / (111_320.0 * midLatCos)
        return doubleArrayOf(s - dLat, w - dLng, n + dLat, e + dLng)
    }
}
