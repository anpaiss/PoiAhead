package com.anpaiss.poiahead

import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.KarooExtension
import io.hammerhead.karooext.models.ActiveRidePage
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
class PoiAheadExtension : KarooExtension("poi-ahead", BuildConfig.VERSION_NAME) {

    private lateinit var karooSystem: KarooSystemService
    private lateinit var logger: PoiAheadLogger
    private lateinit var prefs: Prefs
    private lateinit var repo: PoiRepository
    private lateinit var popup: PopupController
    private lateinit var sampleLog: SampleLog

    // Full data type ids on the ride page the user is looking at. null = unknown,
    // in which case the page keeps drawing (fail-open, see Extensions.whileVisible).
    internal val visibleDataTypeIds = MutableStateFlow<Set<String>?>(null)
    @Volatile private var lastVisibleOurs: Set<String>? = null

    // ── Karoo native references: recorded only, never consumed yet ─────────
    // routeDistance - DISTANCE_TO_DESTINATION is the Karoo's own position along
    // the route. Phase 2 logs it next to our straight-line picture; the switch
    // to an along-route "ahead" happens only once real rides show it holds up.
    @Volatile private var karooDistToDestM = -1.0
    @Volatile private var karooOnRoute: Double? = null
    @Volatile private var routeTotalDistM = -1.0
    @Volatile private var routeName = ""
    @Volatile private var rejoinActive = false
    @Volatile private var routePois: List<Symbol.POI> = emptyList()

    // ── Shadow "ahead along the route" (phase 2/3): computed, logged, never acted on ──
    @Volatile private var projRoute: RouteProjection.Route? = null
    @Volatile private var projections: Map<String, List<RouteProjection.Hit>> = emptyMap()
    @Volatile private var projectedKey = ""
    @Volatile private var routePoiList: List<Poi> = emptyList()            // typed, from NavigatingRoute.pois
    @Volatile private var routePoiAlong: Map<String, List<Double>> = emptyMap()  // the Karoo's own distancesAlongRoute
    @Volatile private var accountPois: List<Poi> = emptyList()             // OnGlobalPOIs minus Home
    @Volatile private var avgSpeedMs = -1.0
    @Volatile private var lastShadowLogged: String? = null

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

    // Anti-spam popup: per ogni POI l'orario dell'ultimo avviso (ri-arma dopo
    // REARM_MS: nei giri andata/ritorno la stessa fontana torna utile) + cooldown
    private val alertedAt = mutableMapOf<String, Long>()
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
        private const val COOLDOWN_MS         = 120_000L // tra un popup e il successivo
        private const val REARM_MS            = 30 * 60_000L // stesso POI ri-avvisabile dopo 30 min
        private const val ROUTE_MARGIN_M      = 1_000.0  // corridoio fetch attorno alla route
        private const val AROUND_RADIUS_M     = 5_000.0  // raggio fetch senza route
        private const val AROUND_REFRESH_MS   = 600_000L // ricontrolla copertura ogni 10 min
        private const val MAP_MAX_POIS        = 50
        private const val MAP_RANGE_M         = 10_000.0
        private const val POS_SAVE_MIN_MOVE_M = 500.0
        private const val SNAPSHOT_TICKS      = 12       // log timeline ogni ~60s
        // Corridor for the route projection: wider than any alert threshold so
        // the data page can still show "off route" fountains with their detour.
        private const val PROJECT_MAX_DETOUR_M = 500.0
    }

    override val types by lazy {
        listOf(PoiPageDataType(extension, pageState, visibleDataTypeIds))
    }

    /** What the alert logic decided this tick, and why — one word per reason for the CSV. */
    private data class AlertDecision(val poiId: String?, val reason: String)

    override fun onCreate() {
        super.onCreate()
        logger = PoiAheadLogger(applicationContext)
        prefs  = Prefs(applicationContext)
        karooSystem = KarooSystemService(applicationContext)
        // SDK channel first — it also works over the phone's BT link — direct HTTP as fallback.
        repo   = PoiRepository(applicationContext, logger, listOf(SdkTransport(karooSystem), DirectTransport()))
        popup  = PopupController(applicationContext, logger, ::onNavigateTo, ::onDismissed)
        sampleLog = SampleLog(applicationContext)
        logger.log("SYS  ", "Sample log: ${sampleLog.path}")

        runCatching {
            val pi = packageManager.getPackageInfo(packageName, 0)
            logger.log("SYS  ", "Versione: ${pi.versionName} (${pi.longVersionCode})")
        }
        val prevHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, t ->
            logger.logError("CRASH", "Eccezione non gestita su ${thread.name}", t)
            prevHandler?.uncaughtException(thread, t)
        }

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
            // Route metadata the Karoo already publishes. Logged, not consumed:
            // the along-route position and the route POIs (which come with their
            // own distancesAlongRoute) are the references phase 2 is judged on.
            // Captured before the early returns: a rejoin update arrives with
            // the same polyline and must still be seen.
            when (nav) {
                is OnNavigationState.NavigationState.NavigatingRoute -> {
                    routeName       = nav.name
                    routeTotalDistM = nav.routeDistance
                    rejoinActive    = nav.rejoinDistance != null || nav.rejoinPolyline != null
                    routePois       = nav.pois
                    routePoiList    = nav.pois.map { toPoi(it, PoiSource.ROUTE) }
                    routePoiAlong   = nav.pois.associate { "route-${it.id}" to it.distancesAlongRoute }
                }
                is OnNavigationState.NavigationState.NavigatingToDestination -> {
                    routeName       = nav.destination.name ?: "destination"
                    routeTotalDistM = -1.0
                    rejoinActive    = false
                    routePois       = emptyList()
                    routePoiList    = emptyList()
                    routePoiAlong   = emptyMap()
                }
                else -> Unit
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
            projRoute   = RouteProjection.Route(points)
            projectedKey = ""   // projections are rebuilt on the next tick
            logger.log("ROUTE", "Route '$routeName': ${points.size} punti, ${routeTotalDistM.toInt()}m, " +
                "${routePois.size} POI route" +
                if (routePois.isEmpty()) "" else " (${routePois.map { it.type }.distinct().joinToString()})")
            sampleLog.route(routeName, routeTotalDistM, routePois.size)
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
            popup.onPosition(event.lat, event.lng)   // distanza viva nel popup a schermo
        }

        // ── POI account (Home): area di casa scaricata in automatico ──────────
        consumerIds += karooSystem.addConsumer(
            OnGlobalPOIs.Params, { _ -> }, {}
        ) { event: OnGlobalPOIs ->
            val others = event.pois.filter { it.type != Symbol.POI.Types.HOME }
            if (others.size != accountPois.size) {
                logger.log("POI  ", "POI account: ${others.size} (${others.map { it.type }.distinct().joinToString()})")
            }
            accountPois = others.map { toPoi(it, PoiSource.ACCOUNT) }
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

        // ── Distanza a destinazione + on-route nativi (solo log, fase 2.1) ────
        consumerIds += karooSystem.addConsumer(
            OnStreamState.StartStreaming(DataType.Type.DISTANCE_TO_DESTINATION), { _ -> }, {}
        ) { event: OnStreamState ->
            (event.state as? StreamState.Streaming)?.dataPoint?.values?.let { v ->
                v[DataType.Field.DISTANCE_TO_DESTINATION]?.let {
                    // Unit asserted by the docs, not verified: TIME_TO_DESTINATION
                    // turned out to be milliseconds. First raw value goes to the log.
                    if (karooDistToDestM < 0) logger.log("DIST ", "DISTANCE_TO_DESTINATION: primo valore raw=$it")
                    karooDistToDestM = it
                }
                karooOnRoute = v[DataType.Field.ON_ROUTE]
            }
        }

        // ── Pagina attiva: gate di visibilità per i redraw della pagina dati ──
        consumerIds += karooSystem.addConsumer(
            ActiveRidePage.Params, { _ -> }, {}
        ) { event: ActiveRidePage ->
            val onPage = event.page.elements.map { it.dataTypeId }.toSet()
            val ours   = types.map { it.dataTypeId }.filter { it in onPage }.toSet()
            if (ours != lastVisibleOurs) {
                lastVisibleOurs = ours
                logger.log("VIEW ", if (ours.isEmpty()) "Pagina PoiAhead non visibile — redraw sospesi"
                                    else "Pagina PoiAhead visibile")
            }
            visibleDataTypeIds.value = onPage
        }

        // ── Velocità media del giro: base della colonna eta_next_s (solo log) ──
        consumerIds += karooSystem.addConsumer(
            OnStreamState.StartStreaming(DataType.Type.AVERAGE_SPEED), { _ -> }, {}
        ) { event: OnStreamState ->
            (event.state as? StreamState.Streaming)?.dataPoint?.singleValue?.let {
                if (avgSpeedMs < 0) logger.log("DIST ", "AVERAGE_SPEED: primo valore raw=$it (assunto m/s)")
                avgSpeedMs = it
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
                sampleLog.ride(if (recording) "recording" else "paused")
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

        // Water from every source: the OSM cache plus the route's own and the
        // account's water POIs. Other types are carried for the log only.
        val all = repo.allPois() + routePoiList.filter { it.isWater } + accountPois.filter { it.isWater }
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
        // Route and account POIs are already drawn by the Karoo: only OSM ones go on our layer.
        mapState.value = if (prefs.mapIconsEnabled) {
            nearby.filter { it.poi.source == PoiSource.OSM }
                .takeWhile { it.distanceM <= MAP_RANGE_M }.take(MAP_MAX_POIS).map { it.poi }
        } else emptyList()

        val decision = if (gpsPos != null) maybeAlert(nearby, now) else AlertDecision(null, "no gps fix")

        // ── Shadow: what the along-route criterion would do (never acts) ──────
        refreshProjections(all)
        val routePosM    = karooRoutePositionM()
        val aheadList    = routePosM?.let { RouteProjection.ahead(projections, it) } ?: emptyList()
        val nearestAhead = aheadList.firstOrNull()
        val shadow       = shadowDecision(aheadList, routePosM, now, decision)
        val etaNextS     = if (nearestAhead != null && routePosM != null && avgSpeedMs > 0.5)
            (nearestAhead.distanceFrom(routePosM) / avgSpeedMs).toLong() else null

        // One CSV row per tick while recording: what we saw, what we decided,
        // what the along-route criterion would have decided, and what the
        // Karoo says about our place on the route.
        if (rideActive) recordSample(now, gpsPos, pos, all.size, nearby, decision,
            routePosM, nearestAhead, shadow, etaNextS)

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

    private fun maybeAlert(nearby: List<NearbyPoi>, now: Long): AlertDecision {
        val inRadius = nearby.takeWhile { it.distanceM <= prefs.alertRadiusM }
        if (inRadius.isEmpty()) return AlertDecision(null, "none in radius")
        val firstId = inRadius.first().poi.id
        if (!prefs.popupEnabled) { logAlertSkip(firstId, "popup disattivati"); return AlertDecision(null, "popup off") }
        if (!rideActive)        { logAlertSkip(firstId, "registrazione non attiva"); return AlertDecision(null, "not recording") }

        // Quiet start: si parte con la borraccia piena — niente popup nei primi km.
        // Distanza sconosciuta (stream mai arrivato) = si tace, scelta conservativa.
        val quietKm = prefs.quietStartKm
        if (quietKm > 0 && rideDistanceM < quietKm * 1000.0) {
            logAlertSkip(firstId, "quiet start (${quietKm}km)")
            return AlertDecision(null, "quiet start")
        }
        if (now - lastAlertMs < COOLDOWN_MS) return AlertDecision(null, "cooldown")  // transitorio: niente log

        val route = routePoints
        // Fuori route (ON_ROUTE=0 o rientro in corso) la deviazione dalla polyline
        // non dice nulla su dove siamo: giro 12/09, passati a 6–26 m da cinque
        // fontane del paese, tutte scartate con "detour 179–863 m". Vale la
        // regola senza route: conta solo la distanza in aria.
        val offRoute = karooOnRoute == 0.0 || rejoinActive
        var rejected = "rearm"
        val candidate = inRadius.firstOrNull { np ->
            val lastAlert = alertedAt[np.poi.id]
            if (lastAlert != null && now - lastAlert < REARM_MS) return@firstOrNull false
            if (route.size >= 2 && !offRoute) {
                // Con route: POI vicino e con deviazione entro la soglia scelta
                val detour = GeoUtils.distanceToPolylineM(np.poi.lat, np.poi.lng, route)
                if (detour > prefs.maxDetourM) {
                    logAlertSkip(np.poi.id, "detour ${detour.toInt()}m > ${prefs.maxDetourM}m")
                    rejected = "detour"
                    false
                } else true
            } else {
                // Senza route (o fuori route): solo POI a cui stiamo passando davvero accanto
                (np.distanceM <= prefs.maxDetourM).also {
                    if (!it) rejected = if (offRoute) "off-route too far" else "too far"
                }
            }
        } ?: return AlertDecision(null, rejected)

        alertedAt[candidate.poi.id] = now
        lastAlertMs = now
        logger.log("ALERT", "POI ${candidate.poi.id} (${candidate.poi.name ?: "senza nome"}) " +
            "a ${candidate.distanceM.toInt()}m — popup=${popup.canShow()}")
        sampleLog.alert("fired", candidate.poi.id,
            "air=${candidate.distanceM.toInt()}m popup=${popup.canShow()} route=${route.size >= 2}")

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
        return AlertDecision(candidate.poi.id, "fired")
    }

    private fun toPoi(sym: Symbol.POI, source: PoiSource) = Poi(
        id     = "${if (source == PoiSource.ROUTE) "route" else "acct"}-${sym.id}",
        lat    = sym.lat,
        lng    = sym.lng,
        name   = sym.name,
        type   = sym.type,
        source = source,
    )

    /**
     * Rebuilds the POI → passes map when the route or the POI set changed.
     * Route POIs come with the Karoo's own distancesAlongRoute and skip the
     * projection; for them only the detour is ours.
     */
    private fun refreshProjections(water: List<Poi>) {
        val route = projRoute ?: run { projections = emptyMap(); projectedKey = ""; return }
        val key = "${lastRoutePolyline.hashCode()}:${water.size}:${routePoiList.size}"
        if (key == projectedKey) return
        projectedKey = key
        val map = HashMap(RouteProjection.projectAll(route, water.filter { it.source != PoiSource.ROUTE }, PROJECT_MAX_DETOUR_M))
        for (rp in routePoiList) {
            val along = routePoiAlong[rp.id]?.takeIf { it.isNotEmpty() } ?: continue
            val detour = GeoUtils.distanceToPolylineM(rp.lat, rp.lng, routePoints)
            map[rp.id] = along.map { RouteProjection.Hit(it, detour) }
        }
        projections = map
        logger.log("PROJ ", "Proiezioni: ${map.size} POI entro ${PROJECT_MAX_DETOUR_M.toInt()}m dalla route, " +
            "${map.values.sumOf { it.size }} passaggi (${routePoiList.size} POI route)")
    }

    /**
     * Where the Karoo says we are along the route, or null when that cannot be
     * trusted: no route, no distance yet, a rejoin in progress, or ON_ROUTE = 0.
     * routegraph itself is inconsistent about ON_ROUTE (compares it with 1.0 in
     * one place and 0.0 in another), so the CSV keeps the raw value to settle it.
     */
    private fun karooRoutePositionM(): Double? {
        if (routeTotalDistM <= 0.0 || karooDistToDestM < 0.0 || rejoinActive) return null
        if (karooOnRoute == 0.0) return null
        return (routeTotalDistM - karooDistToDestM).coerceIn(0.0, routeTotalDistM)
    }

    /**
     * What the along-route criterion would do this tick. Read-only: it never
     * fires and never touches the cooldown or re-arm state; it reads them so
     * the two decisions are comparable gate by gate. Reasons mirror maybeAlert.
     */
    private fun shadowDecision(
        ahead: List<RouteProjection.Ahead>, routePosM: Double?, now: Long, real: AlertDecision,
    ): AlertDecision {
        if (routePosM == null) return AlertDecision(null, "no route pos")
        val inRadius = ahead.filter { it.distanceFrom(routePosM) <= prefs.alertRadiusM }
        if (inRadius.isEmpty()) return AlertDecision(null, "none ahead in radius")
        if (!prefs.popupEnabled) return AlertDecision(null, "popup off")
        if (!rideActive) return AlertDecision(null, "not recording")
        val quietKm = prefs.quietStartKm
        if (quietKm > 0 && rideDistanceM < quietKm * 1000.0) return AlertDecision(null, "quiet start")
        if (now - lastAlertMs < COOLDOWN_MS) return AlertDecision(null, "cooldown")
        var rejected = "rearm"
        val pick = inRadius.firstOrNull { a ->
            val last = alertedAt[a.poiId]
            if (last != null && now - last < REARM_MS) return@firstOrNull false
            (a.hit.detourM <= prefs.maxDetourM).also { if (!it) rejected = "detour" }
        } ?: return AlertDecision(null, rejected)

        // A shadow fire that differs from the real decision is the event the
        // phase-2 verdict is built on: mark it, once per POI.
        if (pick.poiId != real.poiId && lastShadowLogged != pick.poiId) {
            lastShadowLogged = pick.poiId
            val d = pick.distanceFrom(routePosM).toInt()
            sampleLog.alert("shadow", pick.poiId, "along=${d}m detour=${pick.hit.detourM.toInt()}m real=${real.poiId ?: real.reason}")
            logger.log("SHADW", "Lungo-route avrebbe avvisato POI ${pick.poiId} a ${d}m " +
                "(dev ${pick.hit.detourM.toInt()}m); reale: ${real.poiId ?: real.reason}")
        }
        return AlertDecision(pick.poiId, "would fire")
    }

    /**
     * One row of the structured sample log. Our straight-line picture sits next
     * to the Karoo's own along-route position, so the phase 2 switch can be
     * judged after a ride instead of argued.
     */
    private fun recordSample(
        now: Long,
        gpsPos: Pair<Double, Double>?,
        pos: Pair<Double, Double>,
        totalPois: Int,
        nearby: List<NearbyPoi>,
        decision: AlertDecision,
        routePosM: Double?,
        nearestAhead: RouteProjection.Ahead?,
        shadow: AlertDecision,
        etaNextS: Long?,
    ) {
        val nearest   = nearby.firstOrNull()
        val route     = routePoints
        val hasRoute  = route.size >= 2
        val karooDist = karooDistToDestM.takeIf { it >= 0.0 }
        sampleLog.record(
            SampleLog.Row(
                tMs            = now,
                rideState      = if (rideActive) "Recording" else "Paused",
                posSrc         = when {
                    gpsPos != null                  -> "gps"
                    prefs.lastKnownLocation != null -> "last"
                    else                            -> "home"
                },
                lat            = pos.first,
                lng            = pos.second,
                routePosKarooM = if (routeTotalDistM > 0.0 && karooDist != null) routeTotalDistM - karooDist else null,
                onRoute        = karooOnRoute,
                rejoinActive   = if (hasRoute) rejoinActive else null,
                routeLenM      = routeTotalDistM.takeIf { it > 0.0 },
                nPois          = totalPois,
                nRoutePois     = routePois.size,
                nearAirId      = nearest?.poi?.id,
                nearAirM       = nearest?.distanceM,
                nearAirDetourM = nearest?.takeIf { hasRoute }?.let {
                    GeoUtils.distanceToPolylineM(it.poi.lat, it.poi.lng, route)
                },
                nearAheadId      = nearestAhead?.poiId,
                nearAheadAlongM  = if (nearestAhead != null && routePosM != null) nearestAhead.distanceFrom(routePosM) else null,
                nearAheadDetourM = nearestAhead?.hit?.detourM,
                alertId          = decision.poiId,
                alertReason      = decision.reason,
                shadowAlertId    = shadow.poiId,
                shadowReason     = shadow.reason,
                etaNextS         = etaNextS,
            )
        )
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
        sampleLog.close()
        popup.hide()
        tickerJob?.cancel()
        extensionScope.cancel()
        consumerIds.forEach { karooSystem.removeConsumer(it) }
        consumerIds.clear()
        karooSystem.disconnect()
        super.onDestroy()
    }
}
