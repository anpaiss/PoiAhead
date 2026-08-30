package com.anpaiss.poiahead

import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.KarooExtension
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.HideSymbols
import io.hammerhead.karooext.models.InRideAlert
import io.hammerhead.karooext.models.LaunchPinDrop
import io.hammerhead.karooext.models.MapEffect
import io.hammerhead.karooext.models.OnGlobalPOIs
import io.hammerhead.karooext.models.OnLocationChanged
import io.hammerhead.karooext.models.OnNavigationState
import io.hammerhead.karooext.models.OnStreamState
import io.hammerhead.karooext.models.PlayBeepPattern
import io.hammerhead.karooext.models.RideState
import io.hammerhead.karooext.models.ShowSymbols
import io.hammerhead.karooext.models.StreamState
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
    @Volatile private var rideDistanceM = -1.0
    private var lastRoutePolyline = ""

    // Anti-spam popup: ogni POI avvisa una volta per sessione + cooldown globale
    private val alertedIds = mutableSetOf<String>()
    @Volatile private var lastAlertMs = 0L
    @Volatile private var lastAroundFetchMs = 0L
    private var lastSavedPos: Pair<Double, Double>? = null
    private var lastNavStateName = ""
    private var snapshotTickCounter = 0
    // Diagnostica alert: un log per (poi, motivo), per capire dai log post-giro
    // perché un popup atteso non è scattato
    private val alertDiagLogged = mutableSetOf<String>()

    companion object {
        private const val TICK_MS             = 5_000L
        private const val ALERT_RADIUS_M      = 400.0    // distanza a cui scatta l'avviso (con route)
        private const val COOLDOWN_MS         = 120_000L // tra un popup e il successivo
        private const val ROUTE_MARGIN_M      = 1_000.0  // corridoio fetch attorno alla route
        private const val AROUND_RADIUS_M     = 5_000.0  // raggio fetch senza route
        private const val AROUND_REFRESH_MS   = 600_000L // ricontrolla copertura ogni 10 min
        private const val MAP_MAX_POIS        = 50
        private const val MAP_RANGE_M         = 10_000.0
        private const val POS_SAVE_MIN_MOVE_M = 500.0
        private const val SNAPSHOT_TICKS      = 12       // log timeline ogni ~60s
    }

    override val types by lazy {
        listOf(PoiPageDataType(extension, pageState))
    }

    override fun onCreate() {
        super.onCreate()
        logger = PoiAheadLogger(applicationContext)
        prefs  = Prefs(applicationContext)
        repo   = PoiRepository(applicationContext, logger)
        popup  = PopupController(applicationContext, logger, ::onNavigateTo, ::onDismissed)

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
            val navName = nav::class.simpleName ?: "?"
            if (navName != lastNavStateName) {
                lastNavStateName = navName
                logger.log("ROUTE", "Stato navigazione: $navName")
            }
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
            prefs.currentRouteBbox = bbox  // per il pulsante di preload manuale
            extensionScope.launch { repo.ensureCovered(bbox) }
        }

        // ── Posizione ─────────────────────────────────────────────────────────
        consumerIds += karooSystem.addConsumer({ _ -> }, {}) { event: OnLocationChanged ->
            if (lastLocation == null) {
                logger.log("GPS  ", "Primo fix: %.5f, %.5f".format(event.lat, event.lng))
            }
            lastLocation = event.lat to event.lng
        }

        // ── POI account (Home): area di casa scaricata in automatico ──────────
        consumerIds += karooSystem.addConsumer(
            OnGlobalPOIs.Params, { _ -> }, {}
        ) { event: OnGlobalPOIs ->
            val home = event.pois.firstOrNull { it.type == Symbol.POI.Types.HOME }
            if (home != null) {
                if (prefs.homeLocation?.first != home.lat) {
                    logger.log("POI  ", "Home dell'account: %.5f, %.5f (${event.pois.size} POI account)"
                        .format(home.lat, home.lng))
                }
                prefs.homeLocation = home.lat to home.lng
                ensureHomeCoverage()
            }
        }

        // ── Distanza giro (per il "quiet start": niente popup nei primi km) ───
        consumerIds += karooSystem.addConsumer(
            OnStreamState.StartStreaming(DataType.Type.DISTANCE), { _ -> }, {}
        ) { event: OnStreamState ->
            (event.state as? StreamState.Streaming)?.dataPoint?.singleValue?.let {
                // Unità da validare empiricamente (lezione TIME_TO_DESTINATION in ms):
                // qui si assume metri, il log del primo valore lo conferma
                if (rideDistanceM < 0) logger.log("DIST ", "DISTANCE: primo valore raw=$it (assunto metri)")
                rideDistanceM = it
            }
        }

        // ── Stato uscita (mai resettare nulla sulle transizioni: churn continuo)
        consumerIds += karooSystem.addConsumer(
            RideState.Params, { _ -> }, {}
        ) { state: RideState ->
            val recording = state is RideState.Recording
            if (recording != rideActive) {
                logger.log("RIDE ", if (recording) "Registrazione attiva — popup armati"
                                    else "Registrazione sospesa — popup in pausa")
            }
            rideActive = recording
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
        // Per mappa e pagina dati basta una posizione qualsiasi (fallback Home);
        // i popup invece richiedono un fix GPS reale (gpsPos), vedi maybeAlert.
        val gpsPos = lastLocation
        val pos = gpsPos ?: prefs.lastKnownLocation ?: prefs.homeLocation
        if (pos == null) {
            pageState.value = PoiPageState(enabled = prefs.pageEnabled, status = "Waiting for position")
            return
        }

        // Ultima posizione nota: solo fix GPS reali (mai il fallback Home),
        // subito al primo fix e poi a ogni spostamento significativo
        val saved = lastSavedPos
        if (gpsPos != null && (saved == null ||
            GeoUtils.haversineM(saved.first, saved.second, gpsPos.first, gpsPos.second) > POS_SAVE_MIN_MOVE_M)
        ) {
            lastSavedPos = gpsPos
            prefs.lastKnownLocation = gpsPos
        }

        // Copertura attorno alla posizione (per giri senza route) e refresh
        // periodico dell'area di casa (no-op finché la cache è fresca)
        val now = System.currentTimeMillis()
        if (now - lastAroundFetchMs > AROUND_REFRESH_MS) {
            lastAroundFetchMs = now
            val bbox = GeoUtils.bboxOf(listOf(pos), AROUND_RADIUS_M)
            if (bbox != null) extensionScope.launch { repo.ensureCovered(bbox) }
            ensureHomeCoverage()
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
            nearby  = nearby.take(10),
        )
        mapState.value = if (prefs.mapIconsEnabled) {
            nearby.takeWhile { it.distanceM <= MAP_RANGE_M }.take(MAP_MAX_POIS).map { it.poi }
        } else emptyList()

        if (gpsPos != null) maybeAlert(nearby, now)

        // Snapshot periodico (1/min): timeline per l'analisi post-giro
        snapshotTickCounter++
        if (snapshotTickCounter >= SNAPSHOT_TICKS) {
            snapshotTickCounter = 0
            val nearest = nearby.firstOrNull()
            logger.log("TICK ", "pos=${if (gpsPos != null) "gps" else "fallback"} " +
                "ride=$rideActive dist=${if (rideDistanceM >= 0) "${(rideDistanceM / 1000).toInt()}km" else "?"} " +
                "poi=${all.size} " +
                "nearest=${nearest?.let { "${it.poi.id}@${it.distanceM.toInt()}m" } ?: "-"}")
        }
    }

    /** Un log per coppia (poi, motivo): spiega nei log perché un popup non è scattato. */
    private fun logAlertSkip(poiId: String, reason: String) {
        if (alertDiagLogged.add("$poiId|$reason")) {
            logger.log("ALERT", "Skip POI $poiId: $reason")
        }
    }

    private fun maybeAlert(nearby: List<NearbyPoi>, now: Long) {
        val inRadius = nearby.takeWhile { it.distanceM <= ALERT_RADIUS_M }
        if (inRadius.isEmpty()) return
        if (!prefs.popupEnabled) { logAlertSkip(inRadius.first().poi.id, "popup disattivati"); return }
        if (!rideActive)        { logAlertSkip(inRadius.first().poi.id, "registrazione non attiva"); return }

        // Quiet start: si parte con la borraccia piena — niente popup nei primi km.
        // Distanza sconosciuta (stream mai arrivato) = si tace, scelta conservativa.
        val quietKm = prefs.quietStartKm
        if (quietKm > 0 && rideDistanceM < quietKm * 1000.0) {
            logAlertSkip(inRadius.first().poi.id, "quiet start (${quietKm}km)")
            return
        }
        if (now - lastAlertMs < COOLDOWN_MS) return  // transitorio: niente log

        val route = routePoints
        val candidate = inRadius.firstOrNull { np ->
            if (np.poi.id in alertedIds) return@firstOrNull false
            if (route.size >= 2) {
                // Con route: POI vicino e con deviazione entro la soglia scelta
                val detour = GeoUtils.distanceToPolylineM(np.poi.lat, np.poi.lng, route)
                if (detour > prefs.maxDetourM) {
                    logAlertSkip(np.poi.id, "detour ${detour.toInt()}m > ${prefs.maxDetourM}m")
                    false
                } else true
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

    /** Copre l'area attorno al POI Home col raggio scelto. No-op se già in cache. */
    private fun ensureHomeCoverage() {
        val home = prefs.homeLocation ?: return
        val bbox = GeoUtils.bboxOf(listOf(home), prefs.preloadRadiusKm * 1000.0) ?: return
        if (repo.isCovered(bbox)) return
        extensionScope.launch {
            if (repo.ensureCovered(bbox)) {
                logger.log("FETCH", "Area Home coperta (r=${prefs.preloadRadiusKm}km)")
            }
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

    private fun onDismissed(poi: Poi, auto: Boolean) {
        logger.log("ALERT", "POI ${poi.id} ${if (auto) "auto-dismiss per timeout" else "dismesso dall'utente"}")
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
