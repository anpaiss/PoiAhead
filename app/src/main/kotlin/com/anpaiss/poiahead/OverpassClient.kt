package com.anpaiss.poiahead

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Client minimale per Overpass API: solo nodi amenity=drinking_water in un bbox.
 * Chiamare fuori dal main thread. Lancia IOException su errori di rete/HTTP.
 */
object OverpassClient {

    private const val ENDPOINT = "https://overpass-api.de/api/interpreter"
    private const val TIMEOUT_MS = 25_000

    /** bbox = (south, west, north, east). */
    fun fetchDrinkingWater(bbox: DoubleArray): List<Poi> {
        val (s, w, n, e) = listOf(bbox[0], bbox[1], bbox[2], bbox[3])
        val query = "[out:json][timeout:25];" +
            "node[\"amenity\"=\"drinking_water\"]($s,$w,$n,$e);out;"

        val conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod   = "POST"
            connectTimeout  = TIMEOUT_MS
            readTimeout     = TIMEOUT_MS
            doOutput        = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            setRequestProperty("User-Agent", "PoiAhead/0.1 (Karoo extension)")
        }
        try {
            conn.outputStream.use {
                it.write("data=${URLEncoder.encode(query, "UTF-8")}".toByteArray())
            }
            if (conn.responseCode != 200) {
                throw IOException("Overpass HTTP ${conn.responseCode}")
            }
            val body = conn.inputStream.bufferedReader().readText()
            return parse(body)
        } finally {
            conn.disconnect()
        }
    }

    private fun parse(json: String): List<Poi> {
        val elements = JSONObject(json).optJSONArray("elements") ?: return emptyList()
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
