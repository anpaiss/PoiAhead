package com.anpaiss.poiahead

/** Un POI OSM. Per l'MVP solo fonti d'acqua (amenity=drinking_water). */
data class Poi(
    val id: String,
    val lat: Double,
    val lng: Double,
    val name: String?,
)

/** POI con distanza corrente calcolata (linea d'aria, metri). */
data class NearbyPoi(
    val poi: Poi,
    val distanceM: Double,
)
