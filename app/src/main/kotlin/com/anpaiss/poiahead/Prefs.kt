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

    companion object {
        const val KEY_POPUP  = "popup_enabled"
        const val KEY_BEEP   = "beep_enabled"
        const val KEY_MAP    = "map_icons_enabled"
        const val KEY_PAGE   = "page_enabled"
        const val KEY_DETOUR = "max_detour_m"
    }
}
