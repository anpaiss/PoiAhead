package com.anpaiss.poiahead

import android.content.Context
import android.content.SharedPreferences

/** Impostazioni utente. Le tre superfici (popup, mappa, pagina) sono indipendenti. */
class Prefs(context: Context) {

    val sp: SharedPreferences =
        context.getSharedPreferences("poi_ahead_settings", Context.MODE_PRIVATE)

    var popupEnabled: Boolean
        get() = sp.getBoolean(KEY_POPUP, true)
        set(v) { sp.edit().putBoolean(KEY_POPUP, v).apply() }

    var beepEnabled: Boolean
        get() = sp.getBoolean(KEY_BEEP, true)
        set(v) { sp.edit().putBoolean(KEY_BEEP, v).apply() }

    var mapIconsEnabled: Boolean
        get() = sp.getBoolean(KEY_MAP, true)
        set(v) { sp.edit().putBoolean(KEY_MAP, v).apply() }

    var pageEnabled: Boolean
        get() = sp.getBoolean(KEY_PAGE, true)
        set(v) { sp.edit().putBoolean(KEY_PAGE, v).apply() }

    /** Deviazione massima dalla route perché un POI generi l'avviso (metri). */
    var maxDetourM: Int
        get() = sp.getInt(KEY_DETOUR, 100)
        set(v) { sp.edit().putInt(KEY_DETOUR, v).apply() }

    /** Km dall'inizio registrazione prima dei quali i popup tacciono (0 = off). */
    var quietStartKm: Int
        get() = sp.getInt(KEY_QUIET_START, 5)
        set(v) { sp.edit().putInt(KEY_QUIET_START, v).apply() }

    /** Raggio del preload manuale POI (km). */
    var preloadRadiusKm: Int
        get() = sp.getInt(KEY_PRELOAD_RADIUS, 25)
        set(v) { sp.edit().putInt(KEY_PRELOAD_RADIUS, v).apply() }

    /** Bbox (già con margine) della route attiva, salvato dall'extension per il preload manuale. */
    var currentRouteBbox: DoubleArray?
        get() {
            val parts = sp.getString(KEY_ROUTE_BBOX, null)?.split(',') ?: return null
            if (parts.size != 4) return null
            val v = parts.mapNotNull { it.toDoubleOrNull() }
            return if (v.size == 4) v.toDoubleArray() else null
        }
        set(v) {
            sp.edit().putString(KEY_ROUTE_BBOX, v?.joinToString(",")).apply()
        }

    /** Posizione del POI "Home" dell'account Karoo (fallback preload senza fix GPS). */
    var homeLocation: Pair<Double, Double>?
        get() {
            val lat = sp.getString(KEY_HOME_LAT, null)?.toDoubleOrNull() ?: return null
            val lng = sp.getString(KEY_HOME_LNG, null)?.toDoubleOrNull() ?: return null
            return lat to lng
        }
        set(v) {
            sp.edit()
                .putString(KEY_HOME_LAT, v?.first?.toString())
                .putString(KEY_HOME_LNG, v?.second?.toString())
                .apply()
        }

    /** Ultima posizione nota, salvata dall'extension per il preload da fermo. */
    var lastKnownLocation: Pair<Double, Double>?
        get() {
            val lat = sp.getString(KEY_LAST_LAT, null)?.toDoubleOrNull() ?: return null
            val lng = sp.getString(KEY_LAST_LNG, null)?.toDoubleOrNull() ?: return null
            return lat to lng
        }
        set(v) {
            sp.edit()
                .putString(KEY_LAST_LAT, v?.first?.toString())
                .putString(KEY_LAST_LNG, v?.second?.toString())
                .apply()
        }

    companion object {
        const val KEY_POPUP  = "popup_enabled"
        const val KEY_BEEP   = "beep_enabled"
        const val KEY_MAP    = "map_icons_enabled"
        const val KEY_PAGE   = "page_enabled"
        const val KEY_DETOUR = "max_detour_m"
        const val KEY_QUIET_START = "quiet_start_km"
        const val KEY_PRELOAD_RADIUS = "preload_radius_km"
        const val KEY_LAST_LAT = "last_lat"
        const val KEY_LAST_LNG = "last_lng"
        const val KEY_ROUTE_BBOX = "route_bbox"
        const val KEY_HOME_LAT = "home_lat"
        const val KEY_HOME_LNG = "home_lng"
    }
}
