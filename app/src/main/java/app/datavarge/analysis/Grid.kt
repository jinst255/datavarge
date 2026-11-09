package app.datavarge.analysis

import app.datavarge.Config
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor

/**
 * Fixed 20 m grid. Row = floor(lat metres / 20). Column width uses the cosine of the row's centre
 * latitude, so the same point always lands in the same cell. heatmap_template.html mirrors this exactly.
 */
data class CellKey(val row: Long, val col: Long) {
    val id: String get() = "$row:$col"
}

data class CellBounds(val south: Double, val west: Double, val north: Double, val east: Double)

object Grid {
    private val cell = Config.GRID_CELL_M

    fun rowCenterLat(row: Long): Double = (row + 0.5) * cell / Geo.M_PER_DEG_LAT

    private fun lonMetersPerDeg(row: Long) = Geo.M_PER_DEG_LAT * cos(rowCenterLat(row) * PI / 180.0)

    fun keyOf(lat: Double, lon: Double): CellKey {
        val row = floor(lat * Geo.M_PER_DEG_LAT / cell).toLong()
        val col = floor(lon * lonMetersPerDeg(row) / cell).toLong()
        return CellKey(row, col)
    }

    fun bounds(k: CellKey): CellBounds {
        val south = k.row * cell / Geo.M_PER_DEG_LAT
        val north = (k.row + 1) * cell / Geo.M_PER_DEG_LAT
        val m = lonMetersPerDeg(k.row)
        return CellBounds(south, k.col * cell / m, north, (k.col + 1) * cell / m)
    }
}

object Stats {
    fun median(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        val s = values.sorted()
        val n = s.size
        return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2.0
    }

    /** Percentile with linear interpolation, p in 0..100. */
    fun percentile(values: List<Double>, p: Double): Double? {
        if (values.isEmpty()) return null
        val s = values.sorted()
        if (s.size == 1) return s[0]
        val rank = p / 100.0 * (s.size - 1)
        val lo = floor(rank).toInt()
        val hi = minOf(lo + 1, s.size - 1)
        return s[lo] + (s[hi] - s[lo]) * (rank - lo)
    }
}
