package com.anpaiss.poiahead

import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Where along the route each POI is — the geometry behind "ahead".
 *
 * Straight-line distance from the rider says nothing about whether a fountain
 * is still to come or already passed, and on an out-and-back the same fountain
 * is "close" on both legs. Projecting the POI onto the route polyline gives two
 * numbers per pass: [Hit.alongM], how far along the route the closest point is,
 * and [Hit.detourM], how far the POI sits off the route there. A POI gets one
 * hit **per pass**: on a loop or an out-and-back the route comes near it more
 * than once, at different alongM. This is routegraph's `calculatePoiDistances`.
 *
 * Pure functions over immutable inputs, computed once per route (O(POIs × segments)),
 * never per tick.
 */
object RouteProjection {

    /** One place where the route passes near the POI. */
    data class Hit(val alongM: Double, val detourM: Double)

    /**
     * Two candidate passes closer than this along the route are the same pass:
     * an urban grid can keep a POI inside the corridor across several adjacent
     * segments, and only the closest of them is meaningful.
     */
    const val MIN_PASS_SEPARATION_M = 300.0

    /**
     * A route prepared for projection: local planar coordinates (equirectangular
     * around the route's mean latitude, good to well under 0.1 % at route scale)
     * and cumulative haversine distance, so alongM matches how the Karoo measures
     * its own routeDistance as closely as we can.
     */
    class Route(points: List<Pair<Double, Double>>) {
        val size = points.size
        val lengthM: Double
        internal val xs = DoubleArray(size)
        internal val ys = DoubleArray(size)
        internal val cum = DoubleArray(size)
        private val cosLat: Double
        private val earthR = 6_371_000.0

        init {
            cosLat = if (points.isEmpty()) 1.0
                else cos(Math.toRadians(points.sumOf { it.first } / points.size))
            for (i in points.indices) {
                xs[i] = Math.toRadians(points[i].second) * cosLat * earthR
                ys[i] = Math.toRadians(points[i].first) * earthR
                cum[i] = if (i == 0) 0.0 else cum[i - 1] +
                    GeoUtils.haversineM(points[i - 1].first, points[i - 1].second, points[i].first, points[i].second)
            }
            lengthM = if (size > 0) cum[size - 1] else 0.0
        }

        internal fun toX(lng: Double) = Math.toRadians(lng) * cosLat * earthR
        internal fun toY(lat: Double) = Math.toRadians(lat) * earthR
    }

    /**
     * Every pass of [route] within [maxDetourM] of the point, closest pass first.
     * Empty when the POI is outside the corridor or the route has < 2 points.
     */
    fun project(route: Route, lat: Double, lng: Double, maxDetourM: Double): List<Hit> {
        if (route.size < 2) return emptyList()
        val px = route.toX(lng)
        val py = route.toY(lat)

        // Every segment inside the corridor, with the foot of the perpendicular.
        val candidates = ArrayList<Hit>()
        for (i in 0 until route.size - 1) {
            val ax = route.xs[i]; val ay = route.ys[i]
            val bx = route.xs[i + 1]; val by = route.ys[i + 1]
            val dx = bx - ax; val dy = by - ay
            val len2 = dx * dx + dy * dy
            val t = if (len2 == 0.0) 0.0 else (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0.0, 1.0)
            val cx = ax + t * dx; val cy = ay + t * dy
            val d = sqrt((px - cx) * (px - cx) + (py - cy) * (py - cy))
            if (d <= maxDetourM) {
                val segLen = route.cum[i + 1] - route.cum[i]
                candidates.add(Hit(route.cum[i] + t * segLen, d))
            }
        }
        if (candidates.isEmpty()) return emptyList()

        // Greedy by closeness: keep a candidate only if it is a genuinely
        // different pass, i.e. far enough along the route from every kept one.
        candidates.sortBy { it.detourM }
        val hits = ArrayList<Hit>()
        for (c in candidates) {
            if (hits.none { kotlin.math.abs(it.alongM - c.alongM) < MIN_PASS_SEPARATION_M }) hits.add(c)
        }
        return hits
    }

    /** Projects every POI; POIs with no pass inside the corridor are absent from the map. */
    fun projectAll(route: Route, pois: List<Poi>, maxDetourM: Double): Map<String, List<Hit>> {
        val out = HashMap<String, List<Hit>>()
        for (p in pois) {
            val hits = project(route, p.lat, p.lng, maxDetourM)
            if (hits.isNotEmpty()) out[p.id] = hits
        }
        return out
    }

    /** A POI still to come: the next pass strictly ahead of [posM]. */
    data class Ahead(val poiId: String, val hit: Hit) {
        fun distanceFrom(posM: Double) = hit.alongM - posM
    }

    /**
     * Passes strictly ahead of [posM], nearest first. A POI already passed on
     * this leg is absent; on a loop its next pass reappears when it is ahead
     * again. [minAheadM] keeps the current position itself from counting.
     */
    fun ahead(hits: Map<String, List<Hit>>, posM: Double, minAheadM: Double = 1.0): List<Ahead> =
        hits.flatMap { (id, list) ->
            list.filter { it.alongM - posM >= minAheadM }.map { Ahead(id, it) }
        }.sortedBy { it.hit.alongM }
}
