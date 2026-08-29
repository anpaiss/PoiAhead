package com.anpaiss.poiahead

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Popup overlay "Water in XXX m" con NAVIGATE / DISMISS.
 *
 * Tecnica overlay già provata sul k24 (AxsRatio + bottone AutoLoop beta2).
 * Posizionato in fascia alta: la fascia bassa è coperta dal banner "next cue".
 * Un solo popup alla volta; auto-dismiss dopo AUTO_DISMISS_MS.
 */
class PopupController(
    private val context: Context,
    private val logger: PoiAheadLogger,
    private val onNavigate: (Poi) -> Unit,
    /** secondo parametro: true se auto-dismiss per timeout, false se tap utente */
    private val onDismiss: (Poi, Boolean) -> Unit,
) {

    private val handler = Handler(Looper.getMainLooper())
    private var windowManager: WindowManager? = null
    private var currentView: View? = null
    private var currentPoi: Poi? = null

    companion object {
        private const val AUTO_DISMISS_MS = 25_000L
    }

    fun canShow(): Boolean = Settings.canDrawOverlays(context)

    /** Mostra il popup per [poi]. Chiamabile da qualsiasi thread. */
    fun show(poi: Poi, distanceM: Double) {
        handler.post { showOnMain(poi, distanceM) }
    }

    fun hide() {
        handler.post { removeCurrent() }
    }

    private fun showOnMain(poi: Poi, distanceM: Double) {
        if (!canShow()) return
        removeCurrent()

        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager = wm

        val dist = formatDistance(distanceM)
        // Ombra sul testo chiaro per la leggibilità in pieno sole (lezione AxsRatio:
        // il popup sta sopra la mappa, come i tile)
        val title = TextView(context).apply {
            text = poi.name ?: "Water fountain"
            setTextColor(Color.WHITE)
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            maxLines = 1
            setShadowLayer(dp(2f), 0f, dp(1f), 0xB3000000.toInt())
        }
        val subtitle = TextView(context).apply {
            text = "in $dist"
            setTextColor(Color.parseColor("#90CAF9"))
            textSize = 18f
            gravity = Gravity.CENTER
            setShadowLayer(dp(2f), 0f, dp(1f), 0xB3000000.toInt())
        }

        val btnNavigate = Button(context).apply {
            text = context.getString(R.string.popup_navigate)
            setTextColor(Color.WHITE)
            textSize = 16f
            setShadowLayer(dp(2f), 0f, dp(1f), 0xB3000000.toInt())
            background = GradientDrawable().apply {
                cornerRadius = dp(6f)
                // #1565C0: bianco su questo blu = 5.8:1 (il #1976D2 era 4.6:1, borderline)
                setColor(Color.parseColor("#1565C0"))
            }
            setOnClickListener {
                val p = currentPoi ?: return@setOnClickListener
                removeCurrent()
                onNavigate(p)
            }
        }
        val btnDismiss = Button(context).apply {
            text = context.getString(R.string.popup_dismiss)
            setTextColor(Color.WHITE)
            textSize = 16f
            background = GradientDrawable().apply {
                cornerRadius = dp(6f)
                setColor(Color.parseColor("#555555"))
            }
            setOnClickListener {
                val p = currentPoi ?: return@setOnClickListener
                removeCurrent()
                onDismiss(p, false)
            }
        }

        val buttons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            lp.setMargins(dp(4f).toInt(), dp(8f).toInt(), dp(4f).toInt(), 0)
            addView(btnNavigate, lp)
            addView(btnDismiss, LinearLayout.LayoutParams(lp))
        }

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(12f).toInt()
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                cornerRadius = dp(10f)
                setColor(Color.parseColor("#EE1A1A1A"))
                setStroke(dp(2f).toInt(), Color.parseColor("#2196F3"))
            }
            addView(title)
            addView(subtitle)
            addView(buttons)
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // NOT_FOCUSABLE: i tocchi fuori dal popup passano al ride-app;
            // i bottoni dentro restano cliccabili
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            x = 0
            y = dp(40f).toInt()
            horizontalMargin = 0.04f
        }

        runCatching { wm.addView(root, params) }
            .onSuccess {
                currentView = root
                currentPoi  = poi
                logger.log("POPUP", "Mostrato: POI ${poi.id} a ${dist}")
                handler.postDelayed({ autoDismiss(poi) }, AUTO_DISMISS_MS)
            }
            .onFailure { e ->
                logger.logError("POPUP", "addView fallita per POI ${poi.id}", e)
            }
    }

    private fun autoDismiss(poi: Poi) {
        if (currentPoi?.id == poi.id) {
            removeCurrent()
            onDismiss(poi, true)
        }
    }

    private fun removeCurrent() {
        currentView?.let { v -> runCatching { windowManager?.removeView(v) } }
        currentView = null
        currentPoi  = null
    }

    private fun formatDistance(m: Double): String =
        if (m < 1000) "${m.toInt()} m" else "%.1f km".format(m / 1000.0)

    private fun dp(v: Float): Float = v * context.resources.displayMetrics.density
}
