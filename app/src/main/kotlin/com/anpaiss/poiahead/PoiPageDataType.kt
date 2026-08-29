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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Stato pubblicato dall'extension verso la pagina dati. */
data class PoiPageState(
    val enabled: Boolean = true,
    val hasData: Boolean = false,
    val status: String = "No data",
    val nearby: List<NearbyPoi> = emptyList(),
)

/**
 * Pagina dati full-page: fino a 4 fonti d'acqua vicine con distanza.
 * 4 righe e non 5: la fascia bassa può essere coperta dal banner "next cue".
 */
class PoiPageDataType(
    extension: String,
    private val pageState: StateFlow<PoiPageState>,
) : DataTypeImpl(extension, "poi-page") {

    private val nameIds = listOf(R.id.tv_poi_name_1, R.id.tv_poi_name_2, R.id.tv_poi_name_3, R.id.tv_poi_name_4)
    private val distIds = listOf(R.id.tv_poi_dist_1, R.id.tv_poi_dist_2, R.id.tv_poi_dist_3, R.id.tv_poi_dist_4)
    private val rowIds  = listOf(R.id.row_poi_1, R.id.row_poi_2, R.id.row_poi_3, R.id.row_poi_4)

    override fun startStream(emitter: Emitter<StreamState>) {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scope.launch {
            pageState.collect { state ->
                emitter.onNext(
                    StreamState.Streaming(
                        DataPoint(dataTypeId, mapOf(DataType.Field.SINGLE to state.nearby.size.toDouble()))
                    )
                )
            }
        }
        emitter.setCancellable { scope.cancel() }
    }

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        scope.launch {
            emitter.onNext(UpdateGraphicConfig(showHeader = false))
            pageState.collect { state ->
                val views = RemoteViews(context.packageName, R.layout.view_poi_page)

                when {
                    !state.enabled -> {
                        views.setViewVisibility(R.id.tv_page_status, View.VISIBLE)
                        views.setTextViewText(R.id.tv_page_status, "Disabled in settings")
                        rowIds.forEach { views.setViewVisibility(it, View.GONE) }
                    }
                    state.nearby.isEmpty() -> {
                        views.setViewVisibility(R.id.tv_page_status, View.VISIBLE)
                        views.setTextViewText(R.id.tv_page_status, state.status)
                        rowIds.forEach { views.setViewVisibility(it, View.GONE) }
                    }
                    else -> {
                        views.setViewVisibility(R.id.tv_page_status, View.GONE)
                        for (i in rowIds.indices) {
                            val item = state.nearby.getOrNull(i)
                            if (item == null) {
                                views.setViewVisibility(rowIds[i], View.GONE)
                            } else {
                                views.setViewVisibility(rowIds[i], View.VISIBLE)
                                views.setTextViewText(nameIds[i], item.poi.name ?: "Water fountain")
                                views.setTextViewText(distIds[i], formatDistance(item.distanceM))
                            }
                        }
                    }
                }
                emitter.updateView(views)
            }
        }
        emitter.setCancellable { scope.cancel() }
    }

    private fun formatDistance(m: Double): String =
        if (m < 1000) "${m.toInt()} m" else "%.1f km".format(m / 1000.0)
}
