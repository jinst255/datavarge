package app.datavarge.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Point
import android.graphics.RectF
import android.view.MotionEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.datavarge.Config
import app.datavarge.analysis.CellAgg
import app.datavarge.analysis.CellBounds
import app.datavarge.analysis.CellKey
import app.datavarge.analysis.CellOutcome
import app.datavarge.analysis.Cells
import app.datavarge.analysis.Compare
import app.datavarge.analysis.DensityCap
import app.datavarge.analysis.Grid
import app.datavarge.analysis.Quality
import app.datavarge.service.Recorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import java.util.Calendar
import kotlin.math.max

private enum class Metric { SIGNAL, DOWNLOAD }
private enum class Range(val label: String) { ALL("All"), TODAY("Today"), DAY("Last 24 h") }

private data class DrawCell(
    val key: CellKey,
    val bounds: CellBounds,
    val color: Int,
    val onTop: Boolean,
    val agg: CellAgg? = null,
    val diffText: String? = null,
)

private val TIE_GRAY = Color(0xFF9E9E9E)

/** Draws grid cells directly on the canvas; much faster than one osmdroid Polygon per cell. */
private class CellOverlay(var onTap: (DrawCell) -> Unit) : Overlay() {
    var cells: List<DrawCell> = emptyList()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f; color = 0xFF000000.toInt() }
    private val p1 = Point(); private val p2 = Point(); private val r = RectF()

    override fun draw(canvas: Canvas, map: MapView, shadow: Boolean) {
        if (shadow) return
        val proj = map.projection
        val bb = map.boundingBox
        val minPx = 5f
        for (pass in 0..1) for (c in cells) {
            if ((pass == 1) != c.onTop) continue
            val b = c.bounds
            if (b.north < bb.latSouth || b.south > bb.latNorth || b.east < bb.lonWest || b.west > bb.lonEast) continue
            proj.toPixels(GeoPoint(b.north, b.west), p1)
            proj.toPixels(GeoPoint(b.south, b.east), p2)
            r.set(p1.x.toFloat(), p1.y.toFloat(), p2.x.toFloat(), p2.y.toFloat())
            if (r.width() < minPx) { val cx = r.centerX(); r.left = cx - minPx / 2; r.right = cx + minPx / 2 }
            if (r.height() < minPx) { val cy = r.centerY(); r.top = cy - minPx / 2; r.bottom = cy + minPx / 2 }
            fill.color = c.color
            fill.alpha = if (c.onTop) 220 else 150
            canvas.drawRect(r, fill)
            if (c.onTop) canvas.drawRect(r, stroke)
        }
    }

    override fun onSingleTapConfirmed(e: MotionEvent, map: MapView): Boolean {
        val g = map.projection.fromPixels(e.x.toInt(), e.y.toInt())
        val k = Grid.keyOf(g.latitude, g.longitude)
        val hit = cells.lastOrNull { it.key == k } ?: run {
            // At low zoom cells are tiny; accept the nearest within ~12 px.
            val tol = map.projection.metersToPixels(1f).let { if (it > 0) 12f / it else 50f }.toDouble()
            cells.minByOrNull { app.datavarge.analysis.Geo.distanceM(g.latitude, g.longitude, (it.bounds.north + it.bounds.south) / 2, (it.bounds.east + it.bounds.west) / 2) }
                ?.takeIf { app.datavarge.analysis.Geo.distanceM(g.latitude, g.longitude, (it.bounds.north + it.bounds.south) / 2, (it.bounds.east + it.bounds.west) / 2) < max(tol, Config.GRID_CELL_M) }
        }
        if (hit != null) { onTap(hit); return true }
        return false
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen() {
    val c = LocalContext.current
    val all by remember { AppData.observations(c) }.collectAsState(initial = null)
    val data = all
    if (data == null) return
    if (data.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text("No data yet. Keep the app logging and check back after you've moved around.", style = MaterialTheme.typography.bodyLarge)
        }
        return
    }

    val labels = remember(data) { data.map { it.obs.label }.distinct() }
    var selected by rememberSaveable { mutableStateOf(listOf<String>()) }
    LaunchedEffect(labels) {
        selected = selected.filter { it in labels }
        if (selected.isEmpty()) selected = listOf(data.last().obs.label)
    }
    var metric by rememberSaveable { mutableStateOf(Metric.SIGNAL) }
    var range by rememberSaveable { mutableStateOf(Range.ALL) }
    var level by rememberSaveable { mutableStateOf<Int?>(null) }
    var sheet by remember { mutableStateOf<DrawCell?>(null) }
    val bands = remember(data) { if (Recorder.hasBarometer) data.mapNotNull { it.pressureHpa?.let(DensityCap::pressureBand) }.distinct().sortedDescending() else emptyList() }

    val cells by produceState(emptyList<DrawCell>(), data, selected, metric, range, level) {
        value = withContext(Dispatchers.Default) { buildCells(data, selected, metric, range, level) }
    }

    val overlay = remember { CellOverlay { sheet = it } }
    var centered by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        // Compact controls
        Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                labels.forEach { l ->
                    FilterChip(selected = l in selected, onClick = {
                        selected = when {
                            l in selected && selected.size > 1 -> selected - l
                            l in selected -> selected
                            selected.size >= 2 -> listOf(selected.last(), l)
                            else -> selected + l
                        }
                    }, label = { Text(l) })
                }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (selected.size < 2) {
                    FilterChip(selected = metric == Metric.SIGNAL, onClick = { metric = Metric.SIGNAL }, label = { Text("Signal") })
                    FilterChip(selected = metric == Metric.DOWNLOAD, onClick = { metric = Metric.DOWNLOAD }, label = { Text("Download") })
                    Text("|", Modifier.align(Alignment.CenterVertically))
                }
                Range.entries.forEach { r -> FilterChip(selected = range == r, onClick = { range = r }, label = { Text(r.label) }) }
                if (bands.size > 1) {
                    Text("|", Modifier.align(Alignment.CenterVertically))
                    FilterChip(selected = level == null, onClick = { level = null }, label = { Text("All levels") })
                    bands.forEachIndexed { i, b ->
                        val meters = ((bands.first() - b) * Config.PRESSURE_BAND_HPA * 8.3).toInt()
                        FilterChip(selected = level == b, onClick = { level = b }, label = { Text(if (i == 0) "Lowest" else "+$meters m") })
                    }
                }
            }
            Legend(difference = selected.size >= 2, metric = metric, a = selected.getOrNull(0), b = selected.getOrNull(1))
        }

        Box(Modifier.fillMaxSize()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    MapView(ctx).apply {
                        setTileSource(TileSourceFactory.MAPNIK)
                        setMultiTouchControls(true)
                        isTilesScaledToDpi = true
                        minZoomLevel = 4.0
                        maxZoomLevel = 20.0
                        overlays.add(overlay)
                    }
                },
                update = { mv ->
                    overlay.cells = cells
                    if (!centered && data.isNotEmpty()) {
                        val last = data.last().obs
                        mv.controller.setZoom(16.0)
                        mv.controller.setCenter(GeoPoint(last.lat, last.lon))
                        centered = true
                    }
                    mv.invalidate()
                },
                onRelease = { it.onDetach() },
            )
            if (cells.isEmpty()) {
                Surface(Modifier.align(Alignment.TopCenter).padding(12.dp), shape = RoundedCornerShape(8.dp), tonalElevation = 3.dp) {
                    Text(
                        when {
                            selected.size >= 2 -> "No shared cells yet (needs ${Config.HEAD_TO_HEAD_MIN_SAMPLES}+ samples from each carrier in the same 20 m cell)."
                            metric == Metric.DOWNLOAD -> "No speed tests in this view yet. Use Test now."
                            else -> "Nothing to show for this filter."
                        },
                        Modifier.padding(10.dp),
                    )
                }
            }
        }
    }
    sheet?.let { cell ->
        ModalBottomSheet(onDismissRequest = { sheet = null }) {
            CellDetails(cell)
        }
    }
}

@Composable
private fun Legend(difference: Boolean, metric: Metric, a: String?, b: String?) {
    val items: List<Pair<Color, String>> = when {
        difference -> listOf(Quality.EXCELLENT.color() to "$a better", TIE_GRAY to "Tie (< 3 dB)", Quality.VERY_POOR.color() to "$b better")
        metric == Metric.DOWNLOAD -> listOf(
            Quality.EXCELLENT.color() to "≥50", Quality.GOOD.color() to "25–50", Quality.FAIR.color() to "10–25",
            Quality.POOR.color() to "3–10", Quality.VERY_POOR.color() to "<3 Mbps",
        )
        else -> listOf(
            Quality.EXCELLENT.color() to "≥-85", Quality.GOOD.color() to "-95", Quality.FAIR.color() to "-105",
            Quality.POOR.color() to "-115", Quality.VERY_POOR.color() to "<-115", Quality.NONE.color() to "None",
        )
    }
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEach { (col, t) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(12.dp).background(col, RoundedCornerShape(2.dp)))
                Text(" $t", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun CellDetails(cell: DrawCell) {
    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (cell.diffText != null) {
            Text("Difference", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(cell.diffText)
            return
        }
        val a = cell.agg ?: return
        Text("${a.obs.first().label} • 20 m cell", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text("Samples: ${a.obs.size}")
        Text("Median: ${fmtDbm(a.medianDbm)}   Worst: ${fmtDbm(a.worstDbm)}")
        if (a.noServiceCount > 0) Text("No-service samples: ${a.noServiceCount}")
        a.medianDown?.let { Text("Median download: ${fmt1(it)} Mbps") }
        Text("Networks seen: ${a.obs.map { it.networkType }.distinct().joinToString(", ")}")
        Text("Last sample: ${fmtTime(a.lastTs)}")
        a.obs.mapNotNull { it.note }.distinct().forEach { Text("Note: $it", style = MaterialTheme.typography.bodySmall) }
    }
}

private fun buildCells(data: List<MapObs>, selected: List<String>, metric: Metric, range: Range, level: Int?): List<DrawCell> {
    val now = System.currentTimeMillis()
    val since = when (range) {
        Range.ALL -> 0L
        Range.DAY -> now - 24 * 3600_000L
        Range.TODAY -> Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0) }.timeInMillis
    }
    val obs = data.asSequence()
        .filter { it.obs.tsMs >= since }
        .filter { level == null || it.pressureHpa?.let(DensityCap::pressureBand) == level }
        .map { it.obs }.toList()

    if (selected.size >= 2) {
        val (a, b) = selected
        val ca = Cells.aggregate(obs.filter { it.label == a })
        val cb = Cells.aggregate(obs.filter { it.label == b })
        return Compare.sharedCellDiffs(a, b, obs).map { (k, d) ->
            val col = when (Compare.outcome(d, 0.0)) {
                CellOutcome.A_BETTER -> Quality.EXCELLENT.color()
                CellOutcome.B_BETTER -> Quality.VERY_POOR.color()
                CellOutcome.TIE -> TIE_GRAY
            }
            DrawCell(
                k, Grid.bounds(k), col.toArgb(), false,
                diffText = "$a: ${fmtDbm(ca[k]?.medianDbm)} (${ca[k]?.signalValues?.size} samples)\n" +
                    "$b: ${fmtDbm(cb[k]?.medianDbm)} (${cb[k]?.signalValues?.size} samples)\n" +
                    "Difference: ${fmt1(d)} dB " + when (Compare.outcome(d, 0.0)) {
                        CellOutcome.A_BETTER -> "($a better)"; CellOutcome.B_BETTER -> "($b better)"; CellOutcome.TIE -> "(tie)"
                    },
            )
        }
    }
    val label = selected.firstOrNull() ?: return emptyList()
    return Cells.aggregate(obs.filter { it.label == label }).values.mapNotNull { agg ->
        when (metric) {
            Metric.SIGNAL -> {
                val m = agg.medianDbm ?: return@mapNotNull null
                if (agg.isDeadZone) DrawCell(agg.key, Grid.bounds(agg.key), Quality.NONE.color().toArgb(), true, agg)
                else DrawCell(agg.key, Grid.bounds(agg.key), Quality.of(kotlin.math.floor(m).toInt()).color().toArgb(), false, agg)
            }
            Metric.DOWNLOAD -> agg.medianDown?.let { DrawCell(agg.key, Grid.bounds(agg.key), Quality.ofMbps(it).color().toArgb(), false, agg) }
        }
    }
}
