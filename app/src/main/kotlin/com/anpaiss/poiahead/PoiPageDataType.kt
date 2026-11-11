package com.anpaiss.poiahead

import android.content.Context
import android.graphics.Paint
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.karooext.models.UpdateGraphicConfig
import io.hammerhead.karooext.models.ViewConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Stato pubblicato dall'extension verso la pagina dati. */
data class PoiPageState(
    val enabled: Boolean = true,
    val hasData: Boolean = false,
    val status: String = "No data",
    val nearby: List<NearbyPoi> = emptyList(),
)

/**
 * Pagina dati full-page: fino a 10 fonti d'acqua vicine con distanza.
 *
 * Redraws only when a displayed line actually changes, only while the page is
 * the one on screen, and at most once per [REDRAW_THROTTLE_MS]. The engine
 * publishes every 5 s and the distances change every metre, so without this
 * the page redrew on every tick whether or not anything visible moved.
 */
class PoiPageDataType(
    extension: String,
    private val pageState: StateFlow<PoiPageState>,
    private val visibleDataTypeIds: StateFlow<Set<String>?> = MutableStateFlow(null),
) : DataTypeImpl(extension, "poi-page") {

    companion object {
        private const val REDRAW_THROTTLE_MS = 5_000L
        private const val MAX_ROWS = 10
        // view_poi_page: 6 dp top padding, the 14sp header line, rows of 18sp text 4 dp apart
        private const val TOP_DP = 6f
        private const val HEADER_DP = 19f
        private const val ROW_DP = 30.5f
        /** Half the page is 238-240 px (127 dp): too narrow for a name and a distance side by side. */
        private const val NARROW_BELOW_DP = 160f
        private const val HEADER_SP = 14f
        private const val NARROW_STATUS_SP = 14f
    }

    /** Exactly what ends up on screen; the redraw gate compares these. */
    private data class PageView(
        val status: String?,                 // non-null: show status, hide rows
        val rows: List<Pair<String, String>>,
    )

    private val nameIds = listOf(
        R.id.tv_poi_name_1, R.id.tv_poi_name_2, R.id.tv_poi_name_3, R.id.tv_poi_name_4, R.id.tv_poi_name_5,
        R.id.tv_poi_name_6, R.id.tv_poi_name_7, R.id.tv_poi_name_8, R.id.tv_poi_name_9, R.id.tv_poi_name_10,
    )
    private val distIds = listOf(
        R.id.tv_poi_dist_1, R.id.tv_poi_dist_2, R.id.tv_poi_dist_3, R.id.tv_poi_dist_4, R.id.tv_poi_dist_5,
        R.id.tv_poi_dist_6, R.id.tv_poi_dist_7, R.id.tv_poi_dist_8, R.id.tv_poi_dist_9, R.id.tv_poi_dist_10,
    )
    private val rowIds = listOf(
        R.id.row_poi_1, R.id.row_poi_2, R.id.row_poi_3, R.id.row_poi_4, R.id.row_poi_5,
        R.id.row_poi_6, R.id.row_poi_7, R.id.row_poi_8, R.id.row_poi_9, R.id.row_poi_10,
    )

    override fun startStream(emitter: Emitter<StreamState>) {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scope.launch {
            pageState
                .map { it.nearby.size.toDouble() }
                .distinctUntilChanged()
                .collect { count ->
                    emitter.onNext(
                        StreamState.Streaming(
                            DataPoint(dataTypeId, mapOf(DataType.Field.SINGLE to count))
                        )
                    )
                }
        }
        emitter.setCancellable { scope.cancel() }
    }

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        // Only the rows that fit whole: in a third of the page the third row was cut in half by the
        // border; in a half cell (238 px) the name next to the distance was cut to "Font…", so there
        // the nearest name goes on top in place of the header and the distance alone below
        // (field preview, 11/11/2026)
        val heightDp = config.viewSize.second / context.resources.displayMetrics.density
        val widthDp = config.viewSize.first / context.resources.displayMetrics.density
        val narrow = widthDp < NARROW_BELOW_DP
        val rowsThatFit = ((heightDp - TOP_DP - HEADER_DP) / ROW_DP).toInt().coerceIn(1, MAX_ROWS)
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scope.launch {
            emitter.onNext(UpdateGraphicConfig(showHeader = false))
            pageState
                .map { render(it) }
                .distinctUntilChanged()
                .whileVisible(visibleDataTypeIds, dataTypeId)
                .throttle(REDRAW_THROTTLE_MS)
                .collect { view ->
                    val views = RemoteViews(context.packageName, R.layout.view_poi_page)
                    if (view.status != null) {
                        views.setViewVisibility(R.id.tv_page_status, View.VISIBLE)
                        views.setTextViewText(R.id.tv_page_status, view.status)
                        rowIds.forEach { views.setViewVisibility(it, View.GONE) }
                        if (narrow) {
                            // The message takes the header's place, smaller: at 18sp under the header it ran out of the cell
                            views.setViewVisibility(R.id.tv_page_header, View.GONE)
                            views.setTextViewTextSize(R.id.tv_page_status, TypedValue.COMPLEX_UNIT_SP, NARROW_STATUS_SP)
                        }
                    } else {
                        views.setViewVisibility(R.id.tv_page_status, View.GONE)
                        if (narrow) {
                            val first = view.rows.first()
                            // Name on one line in place of the header, the distance centred below it: on two
                            // lines the name pushed the distance over the bottom border
                            // Shortened here: the TextView's own "…" with the letter spacing cut the start of the name too
                            views.setTextViewText(R.id.tv_page_header, fitOneLine(context, first.first, config.viewSize.first))
                            views.setInt(R.id.tv_page_header, "setMaxLines", 1)
                            views.setViewVisibility(rowIds[0], View.VISIBLE)
                            views.setTextViewText(nameIds[0], first.second)
                            views.setTextColor(nameIds[0], context.getColor(R.color.page_distance))
                            views.setInt(nameIds[0], "setGravity", android.view.Gravity.CENTER_HORIZONTAL)
                            views.setTextViewText(distIds[0], "")
                            for (i in 1 until rowIds.size) views.setViewVisibility(rowIds[i], View.GONE)
                            emitter.updateView(views)
                            return@collect
                        }
                        for (i in rowIds.indices) {
                            val row = if (i < rowsThatFit) view.rows.getOrNull(i) else null
                            if (row == null) {
                                views.setViewVisibility(rowIds[i], View.GONE)
                            } else {
                                views.setViewVisibility(rowIds[i], View.VISIBLE)
                                views.setTextViewText(nameIds[i], row.first)
                                views.setTextViewText(distIds[i], row.second)
                            }
                        }
                    }
                    emitter.updateView(views)
                }
        }
        emitter.setCancellable { scope.cancel() }
    }

    /** [text] cut with "…" to the header's width: 14sp, letter spacing 0.1, 10 dp padding each side. */
    private fun fitOneLine(context: Context, text: String, cellWidthPx: Int): String {
        val dm = context.resources.displayMetrics
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, HEADER_SP, dm)
            letterSpacing = 0.1f
        }
        val room = cellWidthPx - 2 * 10 * dm.density - 4 * dm.density   // a little air for rounding
        return TextUtils.ellipsize(text, paint, room, TextUtils.TruncateAt.END).toString()
    }

    private fun render(state: PoiPageState): PageView = when {
        !state.enabled         -> PageView("Disabled in settings", emptyList())
        state.nearby.isEmpty() -> PageView(state.status, emptyList())
        else -> PageView(
            null,
            state.nearby.take(MAX_ROWS).map { (it.poi.name ?: "Water fountain") to formatDistance(it.distanceM) },
        )
    }

    private fun formatDistance(m: Double): String =
        if (m < 1000) "${m.toInt()} m" else "%.1f km".format(m / 1000.0)
}
