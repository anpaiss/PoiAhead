package com.anpaiss.poiahead

import android.content.Context
import com.anpaiss.karooswitches.SwitchReceiver

/**
 * Interruttore "Water alerts" esposto al popup Switches (libreria KarooSwitches):
 * accende/spegne i popup delle fontane durante il giro. Dichiarato nel manifest.
 */
class WaterAlertsSwitch : SwitchReceiver() {

    override fun isOn(context: Context): Boolean = Prefs(context).popupEnabled

    override fun setOn(context: Context, on: Boolean) {
        Prefs(context).popupEnabled = on
        (PoiAheadLogger.shared ?: PoiAheadLogger(context))
            .log("SWTCH", "Interruttore Water alerts: popup ${if (on) "ON" else "OFF"}")
    }
}
