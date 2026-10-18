package com.anpaiss.poiahead

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import io.hammerhead.karooext.KarooSystemService
import org.json.JSONObject
import java.io.File

/** Impostazioni: superfici attive, deviazione massima, permesso overlay. */
class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private val logger by lazy { PoiAheadLogger(applicationContext) }
    // The manual download and the Home-area check run from here: with the direct
    // transport alone they failed on a Karoo linked to the phone over Bluetooth
    // (issue #1, 17/10/2026: "Download failed" with a working connection). The SDK
    // channel is tried first, like in the extension.
    private val karoo  by lazy { KarooSystemService(applicationContext).also { it.connect { } } }
    private val repo   by lazy { PoiRepository(applicationContext, logger, listOf(SdkTransport(karoo), DirectTransport())) }
    private val testPopup by lazy { PopupController(applicationContext, logger, {}, { _, _ -> }) }

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

        val rgAlertRadius = findViewById<RadioGroup>(R.id.rg_alert_radius)
        when (prefs.alertRadiusM) {
            200  -> rgAlertRadius.check(R.id.rb_radius_200m)
            800  -> rgAlertRadius.check(R.id.rb_radius_800m)
            else -> rgAlertRadius.check(R.id.rb_radius_400m)
        }
        rgAlertRadius.setOnCheckedChangeListener { _, id ->
            prefs.alertRadiusM = when (id) {
                R.id.rb_radius_200m -> 200
                R.id.rb_radius_800m -> 800
                else                -> 400
            }
        }

        // Two rows of radio buttons for one choice (seven options do not fit one row):
        // checking in one row clears the other.
        val rgQuiet  = findViewById<RadioGroup>(R.id.rg_quiet_start)
        val rgQuiet2 = findViewById<RadioGroup>(R.id.rg_quiet_start_2)
        val quietIds = mapOf(
            0 to R.id.rb_quiet_off, 2 to R.id.rb_quiet_2, 5 to R.id.rb_quiet_5, 10 to R.id.rb_quiet_10,
            20 to R.id.rb_quiet_20, 30 to R.id.rb_quiet_30, 40 to R.id.rb_quiet_40,
        )
        val quietKm = prefs.quietStartKm.takeIf { it in quietIds } ?: 5
        (if (quietKm >= 20) rgQuiet2 else rgQuiet).check(quietIds.getValue(quietKm))
        var quietSyncing = false
        fun bindQuiet(row: RadioGroup, other: RadioGroup) = row.setOnCheckedChangeListener { _, id ->
            if (id == -1 || quietSyncing) return@setOnCheckedChangeListener
            quietSyncing = true
            other.clearCheck()
            quietSyncing = false
            prefs.quietStartKm = quietIds.entries.first { it.value == id }.key
        }
        bindQuiet(rgQuiet, rgQuiet2)
        bindQuiet(rgQuiet2, rgQuiet)

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
        // Sample popup after 3 s: time to go back to the ride screen and see it
        // where it will really appear. Also the quickest check of the overlay permission.
        findViewById<Button>(R.id.btn_test_popup).setOnClickListener {
            if (!Settings.canDrawOverlays(this)) { refreshOverlayUi(); return@setOnClickListener }
            Handler(Looper.getMainLooper()).postDelayed({ testPopup.show(SAMPLE_POI, 250.0) }, 3_000L)
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
            val reason = failureReason(repo.lastError)
            runOnUiThread {
                btnRoute.isEnabled = true
                showPreloadResult(
                    if (ok) getString(R.string.preload_done, count)
                    else getString(R.string.preload_failed, reason)
                )
                refreshStatus()
            }
        }.start()
    }

    /** The last error of the repository, in words: no link at all, or the Overpass servers. */
    private fun failureReason(err: String?): String = when {
        err == null -> getString(R.string.preload_reason_unknown)
        err.contains("resolve host") || err.contains("timeout") || err.contains("no response") ||
            err.contains("Network is unreachable") || err.contains("ENETUNREACH") || err.contains("ECONNREFUSED") ->
            getString(R.string.preload_reason_offline, err)
        err.startsWith("HTTP") -> getString(R.string.preload_reason_server, err)
        else -> err
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

    override fun onDestroy() {
        karoo.disconnect()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        refreshOverlayUi()
        refreshStatus()
        checkHomeArea()
    }

    private companion object {
        val SAMPLE_POI = Poi(id = "sample", lat = 0.0, lng = 0.0, name = "Fontana di piazza")
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
