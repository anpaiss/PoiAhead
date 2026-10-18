package com.anpaiss.poiahead

import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import kotlin.math.ceil

/**
 * Client minimale per Overpass API: solo nodi amenity=drinking_water in un bbox.
 * Chiamare fuori dal main thread. Lancia IOException se nessun transport riesce.
 *
 * Transports are tried in order. One with a response cap (the SDK channel, which
 * can use the phone's BT link) gets the bbox as tiles small enough to stay
 * under it; an unbounded one (direct WiFi) gets the whole bbox in one request.
 * For each transport the public Overpass servers are tried in turn: the main
 * one answers 502/504 or hangs at busy hours (12/10 and 18/10/2026), the
 * mirrors usually do not fail at the same time.
 */
object OverpassClient {

    internal val ENDPOINTS = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://overpass.osm.ch/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter",
        "https://overpass.private.coffee/api/interpreter",
    )
    private const val TIMEOUT_MS = 25_000

    /**
     * Tile edge for capped transports, in degrees (~11 km N-S, ~8 km E-W at 45°).
     * A drinking_water node is ~150 bytes of Overpass JSON: even the densest
     * city (Rome, ~2 fountains/km²) stays around a third of the 100 KB cap.
     */
    internal const val TILE_DEG = 0.1

    /** bbox = (south, west, north, east). [log] receives one line per attempt. */
    fun fetchDrinkingWater(
        bbox: DoubleArray,
        transports: List<HttpTransport>,
        log: (String) -> Unit = {},
    ): List<Poi> {
        var last: IOException? = null
        for (t in transports) for (endpoint in ENDPOINTS) {
            val host = endpoint.removePrefix("https://").substringBefore('/')
            try {
                return if (t.maxResponseBytes == null) fetchWhole(bbox, t, endpoint, log)
                       else fetchTiled(bbox, t, endpoint, log)
            } catch (e: IOException) {
                last = e
                log("Overpass via ${t.name} @$host fallita: ${e.message}")
            }
        }
        throw last ?: IOException("no transport")
    }

    private fun fetchWhole(bbox: DoubleArray, t: HttpTransport, endpoint: String, log: (String) -> Unit): List<Poi> {
        val body = t.post(endpoint, form(bbox), TIMEOUT_MS)
        log("Overpass via ${t.name} @${endpoint.removePrefix("https://").substringBefore('/')}: ${body.size} B per l'intero bbox")
        return parse(String(body))
    }

    private fun fetchTiled(bbox: DoubleArray, t: HttpTransport, endpoint: String, log: (String) -> Unit): List<Poi> {
        val tiles = tiles(bbox)
        val out = LinkedHashMap<String, Poi>()
        var bytes = 0L
        for (tile in tiles) {
            val body = t.post(endpoint, form(tile), TIMEOUT_MS)
            bytes += body.size
            parse(String(body)).forEach { out[it.id] = it }
        }
        log("Overpass via ${t.name}: ${tiles.size} tile, $bytes B, ${out.size} POI")
        return out.values.toList()
    }

    /** Splits a bbox into tiles of at most [TILE_DEG] on each side. Pure, for tests. */
    internal fun tiles(bbox: DoubleArray): List<DoubleArray> {
        val (s, w, n, e) = listOf(bbox[0], bbox[1], bbox[2], bbox[3])
        val rows = maxOf(1, ceil((n - s) / TILE_DEG).toInt())
        val cols = maxOf(1, ceil((e - w) / TILE_DEG).toInt())
        val dLat = (n - s) / rows
        val dLng = (e - w) / cols
        val out = ArrayList<DoubleArray>(rows * cols)
        for (r in 0 until rows) for (c in 0 until cols) {
            out.add(doubleArrayOf(s + r * dLat, w + c * dLng, s + (r + 1) * dLat, w + (c + 1) * dLng))
        }
        return out
    }

    private fun form(bbox: DoubleArray): String {
        // drinking_water!=no: a tap mapped as drinking water but later marked not
        // potable (dried up, "acqua non potabile" sign) must not raise an alert.
        val query = "[out:json][timeout:25];" +
            "node[\"amenity\"=\"drinking_water\"][\"drinking_water\"!=\"no\"]" +
            "(${bbox[0]},${bbox[1]},${bbox[2]},${bbox[3]});out;"
        return "data=" + URLEncoder.encode(query, "UTF-8")
    }

    /** @throws IOException on a 200 with a non-JSON body (HTML error page), so the next transport is tried. */
    private fun parse(json: String): List<Poi> {
        val elements = try {
            JSONObject(json).optJSONArray("elements") ?: return emptyList()
        } catch (e: JSONException) {
            throw IOException("risposta non JSON: ${e.message}", e)
        }
        val result = ArrayList<Poi>(elements.length())
        for (i in 0 until elements.length()) {
            val el = elements.getJSONObject(i)
            if (el.optString("type") != "node") continue
            val tags = el.optJSONObject("tags")
            result.add(
                Poi(
                    id   = el.getLong("id").toString(),
                    lat  = el.getDouble("lat"),
                    lng  = el.getDouble("lon"),
                    name = tags?.optString("name")?.takeIf { it.isNotBlank() },
                )
            )
        }
        return result
    }
}
