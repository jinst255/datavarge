package app.datavarge.analysis

import app.datavarge.Config

/** One observation, decoupled from Room so the analysis is unit-testable. */
data class Obs(
    val lat: Double,
    val lon: Double,
    val tsMs: Long,
    val label: String,
    val dbm: Int?,
    val noService: Boolean,
    val accuracyM: Float?,
    val networkType: String = "",
    val note: String? = null,
    val downMbps: Double? = null,
    val upMbps: Double? = null,
    val latencyMs: Double? = null,
    val ranOnCellular: Boolean? = null,
    val id: Long = 0,
)

/**
 * No-service samples take this value in medians, so a dead zone pulls a cell (and a carrier) down
 * instead of silently disappearing. A cell whose median is this value is drawn as "No service".
 */
const val NO_SERVICE_DBM = -150

fun Obs.signalValue(): Double? = when {
    noService -> NO_SERVICE_DBM.toDouble()
    dbm != null -> dbm.toDouble()
    else -> null
}

fun Obs.mappable(): Boolean = (accuracyM ?: Float.MAX_VALUE) <= Config.MAX_ACCURACY_MAP_M

data class CellAgg(
    val key: CellKey,
    val obs: List<Obs>,
) {
    val signalValues: List<Double> = obs.mapNotNull { it.signalValue() }
    val medianDbm: Double? = Stats.median(signalValues)
    val worstDbm: Double? = signalValues.minOrNull()
    val noServiceCount: Int = obs.count { it.noService }
    val isDeadZone: Boolean get() = medianDbm != null && medianDbm <= NO_SERVICE_DBM
    val medianDown: Double? = Stats.median(obs.filter { it.ranOnCellular == true }.mapNotNull { it.downMbps })
    val lastTs: Long = obs.maxOf { it.tsMs }
}

object Cells {
    /** Groups mappable observations into 20 m cells. */
    fun aggregate(obs: List<Obs>): Map<CellKey, CellAgg> =
        obs.filter { it.mappable() }
            .groupBy { Grid.keyOf(it.lat, it.lon) }
            .mapValues { (k, v) -> CellAgg(k, v) }
}

data class CarrierStats(
    val label: String,
    val samples: Int,
    val cells: Int,
    val hoursLogged: Double,
    val placeWeightedMedianDbm: Double?,
    val p10Dbm: Double?,
    val usableCellPct: Double?,
    val noServicePct: Double?,
    val speedTests: Int,
    val medianDown: Double?,
    val medianUp: Double?,
    val medianLatency: Double?,
)

data class HeadToHead(
    val a: String,
    val b: String,
    val sharedCells: Int,
    val aWins: Int,
    val ties: Int,
    val bWins: Int,
) {
    val enoughData: Boolean get() = sharedCells >= Config.MIN_SHARED_CELLS
}

enum class CellOutcome { A_BETTER, TIE, B_BETTER }

object Compare {
    fun outcome(aMedian: Double, bMedian: Double): CellOutcome {
        val d = aMedian - bMedian
        return when {
            d >= Config.TIE_THRESHOLD_DB -> CellOutcome.A_BETTER
            d <= -Config.TIE_THRESHOLD_DB -> CellOutcome.B_BETTER
            else -> CellOutcome.TIE
        }
    }

    fun carrierStats(label: String, all: List<Obs>, activeMs: Long): CarrierStats {
        val mine = all.filter { it.label == label }
        val cells = Cells.aggregate(mine).values
        val cellMedians = cells.mapNotNull { it.medianDbm }
        val usable = if (cellMedians.isEmpty()) null
        else 100.0 * cellMedians.count { it >= Config.USABLE_DBM } / cellMedians.size
        val noSvc = if (mine.isEmpty()) null else 100.0 * mine.count { it.noService } / mine.size
        val tests = mine.filter { it.ranOnCellular == true && (it.downMbps != null || it.upMbps != null || it.latencyMs != null) }
        return CarrierStats(
            label = label,
            samples = mine.size,
            cells = cells.size,
            hoursLogged = activeMs / 3_600_000.0,
            placeWeightedMedianDbm = Stats.median(cellMedians),
            p10Dbm = Stats.percentile(cellMedians, 10.0),
            usableCellPct = usable,
            noServicePct = noSvc,
            speedTests = tests.size,
            medianDown = Stats.median(tests.mapNotNull { it.downMbps }),
            medianUp = Stats.median(tests.mapNotNull { it.upMbps }),
            medianLatency = Stats.median(tests.mapNotNull { it.latencyMs }),
        )
    }

    /** Per-cell medians for one label, only for cells with at least [minSamples] signal values. */
    private fun qualifyingCells(label: String, all: List<Obs>, minSamples: Int): Map<CellKey, Double> =
        Cells.aggregate(all.filter { it.label == label })
            .filterValues { it.signalValues.size >= minSamples && it.medianDbm != null }
            .mapValues { it.value.medianDbm!! }

    /** Per shared cell: median(a) - median(b). Only cells with >= 3 samples from each carrier. */
    fun sharedCellDiffs(a: String, b: String, all: List<Obs>): Map<CellKey, Double> {
        val ca = qualifyingCells(a, all, Config.HEAD_TO_HEAD_MIN_SAMPLES)
        val cb = qualifyingCells(b, all, Config.HEAD_TO_HEAD_MIN_SAMPLES)
        return ca.keys.intersect(cb.keys).associateWith { ca.getValue(it) - cb.getValue(it) }
    }

    fun headToHead(a: String, b: String, all: List<Obs>): HeadToHead {
        val diffs = sharedCellDiffs(a, b, all)
        var aw = 0; var t = 0; var bw = 0
        diffs.values.forEach { d ->
            when (outcome(d, 0.0)) {
                CellOutcome.A_BETTER -> aw++
                CellOutcome.TIE -> t++
                CellOutcome.B_BETTER -> bw++
            }
        }
        return HeadToHead(a, b, diffs.size, aw, t, bw)
    }
}
