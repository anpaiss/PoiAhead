package com.anpaiss.poiahead

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Structured per-tick CSV log, kept apart from the human-readable [PoiAheadLogger].
 * Its job is post-ride analysis: every alert decision sits next to what the
 * along-route criterion would have decided, so the switch from "nearby" to
 * "ahead" can be judged on real rides rather than argued.
 *
 * File:  /sdcard/Android/data/com.anpaiss.poiahead/files/poi_ahead_samples.csv
 *   adb pull /sdcard/Android/data/com.anpaiss.poiahead/files/poi_ahead_samples.csv
 *
 * The column set is fixed and already carries the columns later phases will
 * fill (along-route candidate, shadow decision, ETA). They stay empty until
 * then, so files recorded weeks apart stay comparable and can be concatenated.
 *
 * Writes go through an unbounded channel drained by an IO coroutine: the ride
 * tick never blocks on disk, and I/O failures are swallowed after one Logcat
 * warning. Numbers use [Locale.US]: an Italian default locale would render
 * decimals with a comma and split every row.
 */
class SampleLog(context: Context) {

    private val file: File
    private val tsFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val channel = Channel<String>(Channel.UNLIMITED)
    private var writeFailLogged = false

    companion object {
        private const val MAX_SIZE_BYTES = 4 * 1024 * 1024L
        private const val LOGCAT_TAG     = "PoiAheadSamples"

        const val COLUMNS =
            "t_ms,ride_state,pos_src,lat,lng," +
            "route_pos_karoo_m,on_route,rejoin_active,route_len_m," +
            "n_pois,n_route_pois," +
            "near_air_id,near_air_m,near_air_detour_m," +
            "near_ahead_id,near_ahead_along_m,near_ahead_detour_m," +
            "alert_id,alert_reason,shadow_alert_id,shadow_reason," +
            "eta_next_s"

        /** Pure and in the companion so the locale trap has a unit test without a Context. */
        fun toCsv(row: Row): String = with(row) {
            listOf(
                tMs.toString(),
                rideState,
                posSrc,
                num(lat, 5),
                num(lng, 5),
                num(routePosKarooM, 1),
                num(onRoute, 0),
                bool(rejoinActive),
                num(routeLenM, 0),
                nPois.toString(),
                nRoutePois?.toString().orEmpty(),
                text(nearAirId),
                num(nearAirM, 1),
                num(nearAirDetourM, 1),
                text(nearAheadId),
                num(nearAheadAlongM, 1),
                num(nearAheadDetourM, 1),
                text(alertId),
                text(alertReason),
                text(shadowAlertId),
                text(shadowReason),
                etaNextS?.toString().orEmpty(),
            ).joinToString(",")
        }

        private fun num(v: Double?, decimals: Int): String =
            v?.let { String.format(Locale.US, "%.${decimals}f", it) } ?: ""

        private fun bool(v: Boolean?): String = when (v) {
            true  -> "1"
            false -> "0"
            null  -> ""
        }

        /** Free text must never carry the separator or a line break. */
        private fun text(v: String?): String =
            v?.replace(',', ' ')?.replace('\n', ' ')?.replace('\r', ' ').orEmpty()
    }

    /** One CSV row. Null fields are written as empty cells. */
    data class Row(
        val tMs: Long,
        val rideState: String,
        /** gps / last / home — where the position used this tick came from. */
        val posSrc: String,
        val lat: Double,
        val lng: Double,
        val routePosKarooM: Double? = null,
        val onRoute: Double? = null,
        val rejoinActive: Boolean? = null,
        val routeLenM: Double? = null,
        val nPois: Int,
        val nRoutePois: Int? = null,
        val nearAirId: String? = null,
        val nearAirM: Double? = null,
        val nearAirDetourM: Double? = null,
        val nearAheadId: String? = null,
        val nearAheadAlongM: Double? = null,
        val nearAheadDetourM: Double? = null,
        val alertId: String? = null,
        val alertReason: String? = null,
        val shadowAlertId: String? = null,
        val shadowReason: String? = null,
        val etaNextS: Long? = null,
    )

    init {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        file = File(dir, "poi_ahead_samples.csv")
        startWriter()
    }

    val path: String get() = file.absolutePath

    fun ride(state: String) = enqueue("# ride ${tsFmt.format(Date())} $state")

    fun route(name: String, lengthM: Double, routePois: Int) =
        enqueue("# route ${tsFmt.format(Date())} name=${text(name)} len=${lengthM.toInt()} route_pois=$routePois")

    fun alert(kind: String, poiId: String, detail: String) =
        enqueue("# alert ${tsFmt.format(Date())} $kind poi=$poiId ${text(detail)}")

    fun note(msg: String) = enqueue("# ${text(msg)}")

    fun record(row: Row) = enqueue(toCsv(row))

    fun close() {
        channel.close()
        scope.cancel()
    }

    // ── private ──────────────────────────────────────────────────────────

    private fun enqueue(line: String) {
        channel.trySend(line)
    }

    private fun startWriter() = scope.launch {
        for (first in channel) {
            val batch = StringBuilder().append(first).append('\n')
            while (true) {
                val next = channel.tryReceive().getOrNull() ?: break
                batch.append(next).append('\n')
            }
            flush(batch.toString())
        }
    }

    private fun flush(textOut: String) {
        runCatching {
            if (!file.exists() || file.length() == 0L) file.appendText("$COLUMNS\n")
            if (file.length() > MAX_SIZE_BYTES) rotate()
            file.appendText(textOut)
            writeFailLogged = false
        }.onFailure { e ->
            if (!writeFailLogged) {
                writeFailLogged = true
                Log.e(LOGCAT_TAG, "Sample log write failed: ${file.absolutePath}", e)
            }
        }
    }

    private fun rotate() {
        val old = File(file.parent, "poi_ahead_samples.old.csv")
        old.delete()
        file.renameTo(old)
        file.createNewFile()
        file.appendText("$COLUMNS\n")
    }
}
