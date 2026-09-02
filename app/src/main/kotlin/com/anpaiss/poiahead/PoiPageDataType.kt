package com.anpaiss.poiahead

import android.content.Context
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
                    } else {
                        views.setViewVisibility(R.id.tv_page_status, View.GONE)
                        for (i in rowIds.indices) {
                            val row = view.rows.getOrNull(i)
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
