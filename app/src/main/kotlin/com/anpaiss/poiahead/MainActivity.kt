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
    private val logger by lazy { PoiAheadLogger(applicationContext) }
    private val repo   by lazy { PoiRepository(applicationContext, logger) }

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

        val rgQuiet = findViewById<RadioGroup>(R.id.rg_quiet_start)
        when (prefs.quietStartKm) {
            0    -> rgQuiet.check(R.id.rb_quiet_off)
            2    -> rgQuiet.check(R.id.rb_quiet_2)
            10   -> rgQuiet.check(R.id.rb_quiet_10)
            else -> rgQuiet.check(R.id.rb_quiet_5)
        }
        rgQuiet.setOnCheckedChangeListener { _, id ->
            prefs.quietStartKm = when (id) {
                R.id.rb_quiet_off -> 0
                R.id.rb_quiet_2   -> 2
                R.id.rb_quiet_10  -> 10
                else              -> 5
            }
        }

        val rgRadius = findViewById<RadioGroup>(R.id.rg_preload_radius)
        when (prefs.preloadRadiusKm) {
            10   -> rgRadius.check(R.id.rb_radius_10)
            50   -> rgRadius.check(R.id.rb_radius_50)
            else -> rgRadius.check(R.id.rb_radius_25)
        }
        rgRadius.setOnCheckedChangeListener { _, id ->
            prefs.preloadRadiusKm = when (id) {
                R.id.rb_radius_10 -> 10
                R.id.rb_radius_50 -> 50
                else              -> 25
            }
            checkHomeArea()  // raggio più ampio → può servire un nuovo download
        }
        findViewById<Button>(R.id.btn_preload_route).setOnClickListener {
            val bbox = prefs.currentRouteBbox
            if (bbox == null) {
                showPreloadResult(getString(R.string.preload_no_route))
            } else {
                runPreload("route attiva") { bbox }
            }
        }
    }

    private fun showPreloadResult(text: String) {
        findViewById<TextView>(R.id.tv_preload_result).apply {
            visibility = View.VISIBLE
            this.text = text
        }
    }

    private fun runPreload(what: String, bboxProvider: () -> DoubleArray?) {
        val btnRoute = findViewById<Button>(R.id.btn_preload_route)
        btnRoute.isEnabled = false
        showPreloadResult(getString(R.string.preload_running))

        Thread {
            val bbox  = bboxProvider()
            val ok    = bbox != null && repo.ensureCovered(bbox)
            val count = repo.allPois().size
            logger.log("FETCH", "Preload manuale ($what) ok=$ok — $count POI in cache")
            runOnUiThread {
                btnRoute.isEnabled = true
                showPreloadResult(
                    if (ok) getString(R.string.preload_done, count)
                    else getString(R.string.preload_failed)
                )
                refreshStatus()
            }
        }.start()
    }

    /**
     * Stato area Home + eventuale download automatico: se manca la copertura
     * la scarica subito (aprire l'app sul WiFi = occasione perfetta).
     */
    private fun checkHomeArea() {
        val tvHome = findViewById<TextView>(R.id.tv_home_area)
        val home = prefs.homeLocation
        if (home == null) {
            tvHome.text = getString(R.string.home_area_none)
            return
        }
        val radiusKm = prefs.preloadRadiusKm
        Thread {
            val bbox = GeoUtils.bboxOf(listOf(home), radiusKm * 1000.0)
            var covered = bbox != null && repo.isCovered(bbox)
            if (!covered && bbox != null) {
                runOnUiThread { tvHome.text = getString(R.string.preload_running) }
                covered = repo.ensureCovered(bbox)
            }
            val done = covered
            runOnUiThread {
                tvHome.text = if (done) getString(R.string.home_area_ok, radiusKm)
                              else getString(R.string.home_area_pending, radiusKm)
                refreshStatus()
            }
        }.start()
    }

    override fun onResume() {
        super.onResume()
        refreshOverlayUi()
        refreshStatus()
        checkHomeArea()
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
        val position = when {
            prefs.lastKnownLocation != null -> "known"
            prefs.homeLocation != null      -> "from Home POI"
            else                            -> "not yet (needs a GPS fix)"
        }
        val route    = if (prefs.currentRouteBbox != null) "loaded" else "none"
        findViewById<TextView>(R.id.tv_status).text =
            "Overlay permission: $overlay\nCached water POIs: $cached\n" +
            "Last position: $position\nActive route: $route"
    }
}
