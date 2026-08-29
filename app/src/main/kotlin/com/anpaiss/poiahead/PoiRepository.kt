package com.anpaiss.poiahead

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Cache locale dei POI con aree già coperte.
 *
 * Le fontanelle cambiano raramente: TTL lungo (30 giorni). Un'area coperta e
 * fresca evita la query Overpass — così il giro funziona anche senza tethering,
 * purché la zona sia stata scaricata almeno una volta.
 */
class PoiRepository(context: Context, private val logger: PoiAheadLogger) {

    private data class Area(val s: Double, val w: Double, val n: Double, val e: Double, val ts: Long)

    private val file  = File(context.filesDir, "poi_cache.json")
    private val pois  = LinkedHashMap<String, Poi>()   // id → poi
    private val areas = mutableListOf<Area>()
    private var loadedStamp = 0L  // lastModified del file al momento del load/save

    companion object {
        private const val TTL_MS = 30L * 24 * 3600 * 1000  // 30 giorni
        private const val MAX_AREAS = 30
    }

    init {
        load()
        logger.log("CACHE", "Cache: ${pois.size} POI, ${areas.size} aree coperte")
    }

    @Synchronized
    fun allPois(): List<Poi> {
        // Il preload manuale (MainActivity) scrive il file da un'altra istanza:
        // se è cambiato su disco, ricarica — così i POI arrivano senza riavvio
        if (file.exists() && file.lastModified() != loadedStamp) {
            pois.clear()
            areas.clear()
            load()
            logger.log("CACHE", "Cache ricaricata da disco: ${pois.size} POI, ${areas.size} aree")
        }
        return pois.values.toList()
    }

    /** true se il bbox (s,w,n,e) è interamente dentro un'area coperta e fresca. */
    @Synchronized
    fun isCovered(bbox: DoubleArray): Boolean {
        val now = System.currentTimeMillis()
        return areas.any { a ->
            now - a.ts < TTL_MS &&
                a.s <= bbox[0] && a.w <= bbox[1] && a.n >= bbox[2] && a.e >= bbox[3]
        }
    }

    @Volatile private var fetchInFlight = false

    /**
     * Scarica i POI per il bbox se non già coperto. Da chiamare fuori dal main
     * thread. Restituisce true se la copertura è disponibile (da cache o rete).
     * Un solo fetch alla volta: chiamate concorrenti sulla stessa area a cache
     * fredda (extension + activity all'avvio) non duplicano il download.
     */
    fun ensureCovered(bbox: DoubleArray): Boolean {
        if (isCovered(bbox)) return true
        synchronized(this) {
            if (fetchInFlight) return isCovered(bbox)
            fetchInFlight = true
        }
        try {
            return fetch(bbox)
        } finally {
            fetchInFlight = false
        }
    }

    private fun fetch(bbox: DoubleArray): Boolean {
        return try {
            val fetched = OverpassClient.fetchDrinkingWater(bbox)
            merge(bbox, fetched)
            logger.log("FETCH", "Overpass: ${fetched.size} POI per bbox " +
                "%.3f,%.3f,%.3f,%.3f".format(bbox[0], bbox[1], bbox[2], bbox[3]))
            true
        } catch (e: Exception) {
            logger.log("FETCH", "Overpass fallita (${e.message}) — si usa la cache (${pois.size} POI)")
            false
        }
    }

    @Synchronized
    private fun merge(bbox: DoubleArray, fetched: List<Poi>) {
        fetched.forEach { pois[it.id] = it }
        areas.add(Area(bbox[0], bbox[1], bbox[2], bbox[3], System.currentTimeMillis()))
        // Le aree più vecchie escono per prime; i POI restano (dedup per id)
        while (areas.size > MAX_AREAS) areas.removeAt(0)
        save()
    }

    private fun load() {
        runCatching {
            if (!file.exists()) return
            val root = JSONObject(file.readText())
            val ja = root.optJSONArray("areas") ?: JSONArray()
            for (i in 0 until ja.length()) {
                val a = ja.getJSONObject(i)
                areas.add(Area(a.getDouble("s"), a.getDouble("w"),
                    a.getDouble("n"), a.getDouble("e"), a.getLong("ts")))
            }
            val jp = root.optJSONArray("pois") ?: JSONArray()
            for (i in 0 until jp.length()) {
                val p = jp.getJSONObject(i)
                val poi = Poi(p.getString("id"), p.getDouble("lat"), p.getDouble("lng"),
                    p.optString("name").takeIf { it.isNotBlank() })
                pois[poi.id] = poi
            }
            loadedStamp = file.lastModified()
        }.onFailure { logger.logError("CACHE", "Cache illeggibile — si riparte vuoti", it) }
    }

    @Synchronized
    private fun save() {
        runCatching {
            val root = JSONObject()
            root.put("areas", JSONArray().apply {
                areas.forEach { a ->
                    put(JSONObject().put("s", a.s).put("w", a.w)
                        .put("n", a.n).put("e", a.e).put("ts", a.ts))
                }
            })
            root.put("pois", JSONArray().apply {
                pois.values.forEach { p ->
                    put(JSONObject().put("id", p.id).put("lat", p.lat)
                        .put("lng", p.lng).put("name", p.name ?: ""))
                }
            })
            file.writeText(root.toString())
            loadedStamp = file.lastModified()
        }.onFailure { logger.logError("CACHE", "Salvataggio cache fallito", it) }
    }
}
