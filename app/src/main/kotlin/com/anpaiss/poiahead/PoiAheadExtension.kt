package com.anpaiss.poiahead

import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.KarooExtension
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.models.HideSymbols
import io.hammerhead.karooext.models.InRideAlert
import io.hammerhead.karooext.models.LaunchPinDrop
import io.hammerhead.karooext.models.MapEffect
import io.hammerhead.karooext.models.OnLocationChanged
import io.hammerhead.karooext.models.OnNavigationState
import io.hammerhead.karooext.models.PlayBeepPattern
import io.hammerhead.karooext.models.RideState
import io.hammerhead.karooext.models.ShowSymbols
import io.hammerhead.karooext.models.Symbol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * PoiAhead — fonti d'acqua (OSM) lungo il percorso.
 *
 * Tre superfici indipendenti, tutte attivabili nelle impostazioni:
 *  - popup overlay con NAVIGATE/DISMISS (primaria; fallback InRideAlert senza permesso)
 *  - icone Symbol.POI (Types.WATER) sulla mappa
 *  - pagina dati full-page con le 4 fonti più vicine
 */
class PoiAheadExtension : KarooExtension("poi-ahead", "0.1.0") {

    private lateinit var karooSystem: KarooSystemService
    private lateinit var logger: PoiAheadLogger
    private lateinit var prefs: Prefs
    private lateinit var repo: PoiRepository
    private lateinit var popup: PopupController

    private val extensionScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val consumerIds = mutableListOf<String>()
    private var tickerJob: Job? = null

    private val pageState = MutableStateFlow(PoiPageState())
    private val mapState  = MutableStateFlow<List<Poi>>(emptyList())

    @Volatile private var lastLocation: Pair<Double, Double>? = null
    @Volatile private var routePoints: List<Pair<Double, Double>> = emptyList()
    @Volatile private var rideActive = false
    private var lastRoutePolyline = ""

    // Anti-spam popup: ogni POI avvisa una volta per sessione + cooldown globale
    private val alertedIds = mutableSetOf<String>()
    @Volatile private var lastAlertMs = 0L
    @Volatile private var lastAroundFetchMs = 0L

    companion object {
        private const val TICK_MS             = 5_000L
        private const val ALERT_RADIUS_M      = 400.0    // distanza a cui scatta l'avviso (con route)
        private const val COOLDOWN_MS         = 120_000L // tra un popup e il successivo
        private const val ROUTE_MARGIN_M      = 1_000.0  // corridoio fetch attorno alla route
        private const val AROUND_RADIUS_M     = 5_000.0  // raggio fetch senza route
        private const val AROUND_REFRESH_MS   = 600_000L // ricontrolla copertura ogni 10 min
        private const val MAP_MAX_POIS        = 50
        private const val MAP_RANGE_M         = 10_000.0
    }

    override val types by lazy {
        listOf(PoiPageDataType(extension, pageState))
    }

    override fun onCreate() {
        super.onCreate()
        logger = PoiAheadLogger(applicationContext)
        prefs  = Prefs(applicationContext)
        repo   = PoiRepository(applicationContext, logger)
        popup  = PopupController(applicationContext, ::onNavigateTo, ::onDismissed)

        runCatching {
            val pi = packageManager.getPackageInfo(packageName, 0)
            logger.log("SYS  ", "Versione: ${pi.versionName} (${pi.longVersionCode})")
        }
        val prevHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, t ->
            logger.logError("CRASH", "Eccezione non gestita su ${thread.name}", t)
            prevHandler?.uncaughtException(thread, t)
        }

        karooSystem = KarooSystemService(applicationContext)
        karooSystem.connect { connected ->
            logger.log("SYS  ", "Karoo connected=$connected")
        }

        // ── Route attiva ──────────────────────────────────────────────────────
        consumerIds += karooSystem.addConsumer(
            OnNavigationState.Params, { _ -> }, {}
        ) { event: OnNavigationState ->
            val nav = event.state
            val polyline = when (nav) {
                is OnNavigationState.NavigationState.NavigatingRoute -> nav.routePolyline.orEmpty()
                is OnNavigationState.NavigationState.NavigatingToDestination -> nav.polyline.orEmpty()
                else -> {
                    // route in memoria: i POI restano validi anche in Idle
                    return@addConsumer
                }
            }
            if (polyline.isBlank() || polyline == lastRoutePolyline) return@addConsumer
            lastRoutePolyline = polyline
            val points = PolylineDecoder.decodeLatLng(polyline)
            routePoints = points
            logger.log("ROUTE", "Route: ${points.size} punti")
            val bbox = GeoUtils.bboxOf(points, ROUTE_MARGIN_M) ?: return@addConsumer
            extensionScope.launch { repo.ensureCovered(bbox) }
        }

        // ── Posizione ─────────────────────────────────────────────────────────
        consumerIds += karooSystem.addConsumer({ _ -> }, {}) { event: OnLocationChanged ->
            lastLocation = event.lat to event.lng
        }

        // ── Stato uscita (mai resettare nulla sulle transizioni: churn continuo)
        consumerIds += karooSystem.addConsumer(
            RideState.Params, { _ -> }, {}
        ) { state: RideState ->
            rideActive = state is RideState.Recording
        }

        // ── Ticker ────────────────────────────────────────────────────────────
        tickerJob = extensionScope.launch {
            while (true) {
                delay(TICK_MS)
                tick()
            }
        }
    }

    private fun tick() {
        val pos = lastLocation
        if (pos == null) {
            pageState.value = PoiPageState(enabled = prefs.pageEnabled, status = "Waiting for GPS")
            return
        }

        // Copertura attorno alla posizione (per giri senza route)
        val now = System.currentTimeMillis()
        if (now - lastAroundFetchMs > AROUND_REFRESH_MS) {
            lastAroundFetchMs = now
            val bbox = GeoUtils.bboxOf(listOf(pos), AROUND_RADIUS_M)
            if (bbox != null) extensionScope.launch { repo.ensureCovered(bbox) }
        }

        val all = repo.allPois()
        if (all.isEmpty()) {
            pageState.value = PoiPageState(enabled = prefs.pageEnabled,
                status = "No POI data — ride with phone connected once")
            mapState.value = emptyList()
            return
        }

        val nearby = all
            .map { NearbyPoi(it, GeoUtils.haversineM(pos.first, pos.second, it.lat, it.lng)) }
            .sortedBy { it.distanceM }

        pageState.value = PoiPageState(
            enabled = prefs.pageEnabled,
            hasData = true,
            status  = "No water nearby",
            nearby  = nearby.take(4),
        )
        mapState.value = if (prefs.mapIconsEnabled) {
            nearby.takeWhile { it.distanceM <= MAP_RANGE_M }.take(MAP_MAX_POIS).map { it.poi }
        } else emptyList()

        maybeAlert(nearby, now)
    }

    private fun maybeAlert(nearby: List<NearbyPoi>, now: Long) {
        if (!prefs.popupEnabled || !rideActive) return
        if (now - lastAlertMs < COOLDOWN_MS) return

        val route = routePoints
        val candidate = nearby.firstOrNull { np ->
            np.poi.id !in alertedIds && if (route.size >= 2) {
                // Con route: POI vicino e con deviazione entro la soglia scelta
                np.distanceM <= ALERT_RADIUS_M &&
                    GeoUtils.distanceToPolylineM(np.poi.lat, np.poi.lng, route) <= prefs.maxDetourM
            } else {
                // Senza route: solo POI a cui stiamo passando davvero accanto
                np.distanceM <= prefs.maxDetourM
            }
        } ?: return

        alertedIds += candidate.poi.id
        lastAlertMs = now
        logger.log("ALERT", "POI ${candidate.poi.id} (${candidate.poi.name ?: "senza nome"}) " +
            "a ${candidate.distanceM.toInt()}m — popup=${popup.canShow()}")

        if (popup.canShow()) {
            popup.show(candidate.poi, candidate.distanceM)
        } else {
            // Fallback senza permesso overlay: alert nativo, solo informativo
            karooSystem.dispatch(
                InRideAlert(
                    id            = "poi-ahead-${candidate.poi.id}",
                    icon          = R.drawable.ic_launcher,
                    title         = candidate.poi.name ?: "Water fountain",
                    detail        = "in ${formatDistance(candidate.distanceM)}",
                    autoDismissMs = 15_000L,
                    backgroundColor = R.color.alert_bg,
                    textColor     = R.color.alert_text,
                )
            )
        }
        if (prefs.beepEnabled) {
            karooSystem.dispatch(
                PlayBeepPattern(listOf(
                    PlayBeepPattern.Tone(800, 200),
                    PlayBeepPattern.Tone(null, 100),
                    PlayBeepPattern.Tone(800, 200),
                ))
            )
        }
    }

    private fun onNavigateTo(poi: Poi) {
        logger.log("NAV  ", "Deviazione richiesta verso POI ${poi.id}")
        karooSystem.dispatch(
            LaunchPinDrop(
                Symbol.POI(
                    id   = "poi-ahead-${poi.id}",
                    lat  = poi.lat,
                    lng  = poi.lng,
                    type = Symbol.POI.Types.WATER,
                    name = poi.name ?: "Water fountain",
                )
            )
        )
    }

    private fun onDismissed(poi: Poi) {
        logger.log("ALERT", "POI ${poi.id} dismesso")
    }

    // ── Layer icone sulla mappa ──────────────────────────────────────────────
    override fun startMap(emitter: Emitter<MapEffect>) {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        var shownIds = emptySet<String>()
        scope.launch {
            mapState.collect { pois ->
                val symbols = pois.map { p ->
                    Symbol.POI(
                        id   = "poi-ahead-${p.id}",
                        lat  = p.lat,
                        lng  = p.lng,
                        type = Symbol.POI.Types.WATER,
                        name = p.name ?: "Water",
                    )
                }
                val ids = symbols.map { it.id }.toSet()
                val removed = shownIds - ids
                if (removed.isNotEmpty()) emitter.onNext(HideSymbols(removed.toList()))
                if (symbols.isNotEmpty()) emitter.onNext(ShowSymbols(symbols))
                shownIds = ids
            }
        }
        emitter.setCancellable { scope.cancel() }
    }

    private fun formatDistance(m: Double): String =
        if (m < 1000) "${m.toInt()} m" else "%.1f km".format(m / 1000.0)

    override fun onDestroy() {
        logger.log("SYS  ", "=== PoiAhead fermato ===")
        popup.hide()
        tickerJob?.cancel()
        extensionScope.cancel()
        consumerIds.forEach { karooSystem.removeConsumer(it) }
        consumerIds.clear()
        karooSystem.disconnect()
        super.onDestroy()
    }
}
