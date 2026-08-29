package com.anpaiss.poiahead

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.io.File

/** Impostazioni: superfici attive, deviazione massima, permesso overlay. */
class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)

        val cbPopup = findViewById<CheckBox>(R.id.cb_popup)
        val cbBeep  = findViewById<CheckBox>(R.id.cb_beep)
        val cbMap   = findViewById<CheckBox>(R.id.cb_map)
        val cbPage  = findViewById<CheckBox>(R.id.cb_page)

        cbPopup.isChecked = prefs.popupEnabled
        cbBeep.isChecked  = prefs.beepEnabled
        cbMap.isChecked   = prefs.mapIconsEnabled
        cbPage.isChecked  = prefs.pageEnabled
        cbBeep.isEnabled  = prefs.popupEnabled

        cbPopup.setOnCheckedChangeListener { _, v ->
            prefs.popupEnabled = v
            cbBeep.isEnabled = v
            refreshOverlayUi()
        }
        cbBeep.setOnCheckedChangeListener { _, v -> prefs.beepEnabled = v }
        cbMap.setOnCheckedChangeListener { _, v -> prefs.mapIconsEnabled = v }
        cbPage.setOnCheckedChangeListener { _, v -> prefs.pageEnabled = v }

        val rg = findViewById<RadioGroup>(R.id.rg_detour)
        when (prefs.maxDetourM) {
            50   -> rg.check(R.id.rb_detour_50)
            200  -> rg.check(R.id.rb_detour_200)
            else -> rg.check(R.id.rb_detour_100)
        }
        rg.setOnCheckedChangeListener { _, id ->
            prefs.maxDetourM = when (id) {
                R.id.rb_detour_50  -> 50
                R.id.rb_detour_200 -> 200
                else               -> 100
            }
        }

        findViewById<Button>(R.id.btn_grant_overlay).setOnClickListener {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        }
    }

    override fun onResume() {
        super.onResume()
        refreshOverlayUi()
        refreshStatus()
    }

    private fun refreshOverlayUi() {
        val needed = prefs.popupEnabled && !Settings.canDrawOverlays(this)
        val vis = if (needed) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.tv_overlay_warning).visibility = vis
        findViewById<Button>(R.id.btn_grant_overlay).visibility = vis
    }

    private fun refreshStatus() {
        val overlay = if (Settings.canDrawOverlays(this)) "granted" else "missing"
        val cached = runCatching {
            val f = File(filesDir, "poi_cache.json")
            if (f.exists()) JSONObject(f.readText()).optJSONArray("pois")?.length() ?: 0 else 0
        }.getOrDefault(0)
        findViewById<TextView>(R.id.tv_status).text =
            "Overlay permission: $overlay\nCached water POIs: $cached"
    }
}
