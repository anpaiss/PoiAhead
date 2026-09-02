package com.anpaiss.poiahead

/** Where a POI came from. Only OSM ones live in the on-disk cache. */
enum class PoiSource {
    OSM,      // Overpass, amenity=drinking_water
    ROUTE,    // NavigatingRoute.pois — placed by the rider on the route (Komoot etc.)
    ACCOUNT,  // OnGlobalPOIs — saved on the Karoo account
}

/**
 * Un POI. [type] uses the Karoo's own vocabulary ([io.hammerhead.karooext.models.Symbol.POI.Types])
 * so route and account POIs keep theirs; OSM fountains are [WATER_TYPE].
 * Alerts and the data page only act on water; other types are carried for
 * the log and for a later generalisation.
 */
data class Poi(
    val id: String,
    val lat: Double,
    val lng: Double,
    val name: String?,
    val type: String = WATER_TYPE,
    val source: PoiSource = PoiSource.OSM,
) {
    val isWater: Boolean get() = type == WATER_TYPE

    companion object {
        /** Symbol.POI.Types.WATER, spelled out so this file needs no karoo-ext import. */
        const val WATER_TYPE = "water"
    }
}

/** POI con distanza corrente calcolata (linea d'aria, metri). */
data class NearbyPoi(
    val poi: Poi,
    val distanceM: Double,
)
