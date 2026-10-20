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
 * others usually do not fail at the same time.
 *
 * Only worldwide servers with fresh data belong in the list, so that no list of
 * covered countries is needed. overpass.osm.ch (in 1.1) covers Switzerland
 * alone and answers an empty, successful result everywhere else: on 20/10/2026
 * it cached "no fountain" for an Austrian route for 30 days. kumi.systems is
 * the old name of private.coffee and is dead. maps.mail.ru (VK Maps) is
 * worldwide but its certificate chain is not trusted by the Karoo's Android 12
 * ("Trust anchor for certification path not found"), so it is left out.
 */
object OverpassClient {

    /**
     * Probed 20/10/2026 with the same query from the PC: the three FOSSGIS
     * endpoints answered Trento, Schafberg, Manhattan, San Francisco, London and
     * Sydney with identical counts (66, 20, 130, 92, 63, 239) and a timestamp of
     * the day; one of them still gives a 502 now and then, hence all three.
     */
    internal val ENDPOINTS = listOf(
        "https://overpass-api.de/api/interpreter",         // FOSSGIS front end: 502 when both machines behind it are busy
        "https://lz4.overpass-api.de/api/interpreter",     // the two machines behind it, reachable on their own
        "https://z.overpass-api.de/api/interpreter",
        "https://overpass.private.coffee/api/interpreter", // worldwide, often 504: last resort
    )
    /**
     * The wait for an answer on the direct transport. At busy hours the healthy
     * servers took 25-41 s for a tiny box (20/10/2026, 07:15). The Karoo's own
     * HTTP channel gives up by itself after about 30 s whatever this value.
     */
    internal const val TIMEOUT_MS = 60_000

    /**
     * Tile edge for capped transports, in degrees (~11 km N-S, ~8 km E-W at 45°).
     * A drinking_water node is ~150 bytes of Overpass JSON: even the densest
     * city (Rome, ~2 fountains/km²) stays around a third of the 100 KB cap.
     */
    internal const val TILE_DEG = 0.1

    /**
     * bbox = (south, west, north, east). [log] receives one line per attempt.
     * @throws OverpassFailure when every server failed on every transport, with one reason per server.
     */
    fun fetchDrinkingWater(
        bbox: DoubleArray,
        transports: List<HttpTransport>,
        log: (String) -> Unit = {},
    ): List<Poi> {
        val failures = LinkedHashMap<String, String>()   // host -> reason of the last attempt
        for (t in transports) for (endpoint in ENDPOINTS) {
            val host = hostOf(endpoint)
            try {
                return if (t.maxResponseBytes == null) fetchWhole(bbox, t, endpoint, log)
                       else fetchTiled(bbox, t, endpoint, log)
            } catch (e: IOException) {
                failures[host] = e.message ?: e.javaClass.simpleName
                log("Overpass via ${t.name} @$host fallita: ${e.message}")
            }
        }
        throw OverpassFailure(failures)
    }

    internal fun hostOf(endpoint: String): String = endpoint.removePrefix("https://").substringBefore('/')

    /**
     * A transport's error in words for the screen: "no network" (the DNS or the
     * route failed, the Karoo has no link), "no answer in N s", "unreachable", an
     * HTTP status with its meaning, or the message itself.
     */
    fun reasonWords(raw: String): String {
        val m = raw.removePrefix("sdk: ")
        return when {
            m.contains("resolve host") || m.contains("Network is unreachable") || m.contains("ENETUNREACH") -> "no network"
            m.contains("timeout", ignoreCase = true) -> "no answer in ${TIMEOUT_MS / 1000} s"
            m.contains("Failed to connect") || m.contains("ECONNREFUSED") || m.contains("connection abort") -> "unreachable"
            m.startsWith("HTTP 429") -> "HTTP 429, too many requests"
            m.startsWith("HTTP 502") || m.startsWith("HTTP 503") || m.startsWith("HTTP 504") -> "$m, server busy"
            m.startsWith("risposta non JSON") -> "not a JSON answer"
            else -> m
        }
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

/**
 * Every server failed on every transport. [failures] keeps, per server host, the
 * reason of the last attempt; [offline] is true when every reason is a missing
 * network (DNS or route failed), which on the Karoo means the ride screen
 * switched the Wi-Fi off or there is no phone link.
 */
class OverpassFailure(val failures: Map<String, String>) :
    IOException(failures.entries.joinToString("; ") { "${it.key}: ${OverpassClient.reasonWords(it.value)}" }) {

    val offline: Boolean
        get() = failures.isNotEmpty() && failures.values.all { OverpassClient.reasonWords(it) == "no network" }

    /** One line per server, in words, for the app screen. */
    fun lines(): String = failures.entries.joinToString("\n") { "${it.key}: ${OverpassClient.reasonWords(it.value)}" }
}
