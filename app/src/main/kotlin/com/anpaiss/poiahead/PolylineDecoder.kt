package com.anpaiss.poiahead

/**
 * Decodifica Google Encoded Polyline, precision 5 (come routePolyline/polyline
 * di karoo-ext). Portato da BetterEta, dove è validato su ride reali.
 */
object PolylineDecoder {

    /** Decodifica una polyline 2D in coppie (lat, lon). Lista vuota se blank/malformata. */
    fun decodeLatLng(encoded: String): List<Pair<Double, Double>> {
        if (encoded.isBlank()) return emptyList()
        val result = mutableListOf<Pair<Double, Double>>()
        val idx    = intArrayOf(0)
        var lat    = 0
        var lng    = 0
        try {
            while (idx[0] < encoded.length) {
                lat += nextDelta(encoded, idx)
                lng += nextDelta(encoded, idx)
                result.add(lat / 1e5 to lng / 1e5)
            }
        } catch (_: Exception) { /* stringa troncata: restituisce quello decodificato finora */ }
        return result
    }

    private fun nextDelta(encoded: String, idx: IntArray): Int {
        var result = 0
        var shift  = 0
        var b: Int
        do {
            b       = encoded[idx[0]++].code - 63
            result  = result or ((b and 0x1f) shl shift)
            shift  += 5
        } while (b >= 0x20 && idx[0] < encoded.length)
        return if (result and 1 != 0) (result shr 1).inv() else result shr 1
    }
}
