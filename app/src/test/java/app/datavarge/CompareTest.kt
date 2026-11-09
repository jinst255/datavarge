package app.datavarge

import app.datavarge.analysis.CellOutcome
import app.datavarge.analysis.Compare
import app.datavarge.analysis.Geo
import app.datavarge.analysis.Obs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class CompareTest {
    private val baseLat = 33.7490
    private val lon = -84.3880

    /** Cell i is placed 100 m north of cell i-1 so cells never overlap. */
    private fun cellObs(i: Int, label: String, vararg dbm: Int) =
        dbm.map { Obs(baseLat + Geo.latSpan(100.0 * i), lon, 0, label, it, false, 5f) }

    @Test fun tieThreshold() {
        assertEquals(CellOutcome.A_BETTER, Compare.outcome(-90.0, -93.0))
        assertEquals(CellOutcome.TIE, Compare.outcome(-90.0, -92.9))
        assertEquals(CellOutcome.TIE, Compare.outcome(-92.9, -90.0))
        assertEquals(CellOutcome.B_BETTER, Compare.outcome(-93.0, -90.0))
    }

    @Test fun onlySharedCellsWithThreeEachCount() {
        val obs = buildList {
            addAll(cellObs(0, "A", -90, -90, -90)); addAll(cellObs(0, "B", -100, -100, -100)) // A wins
            addAll(cellObs(1, "A", -95, -95, -95)); addAll(cellObs(1, "B", -94, -94, -94))    // tie
            addAll(cellObs(2, "A", -110, -110, -110)); addAll(cellObs(2, "B", -90, -90, -90)) // B wins
            addAll(cellObs(3, "A", -80, -80, -80)); addAll(cellObs(3, "B", -120, -120))        // B has only 2: excluded
            addAll(cellObs(4, "A", -80, -80, -80))                                            // A only: excluded
        }
        val h = Compare.headToHead("A", "B", obs)
        assertEquals(3, h.sharedCells)
        assertEquals(1, h.aWins)
        assertEquals(1, h.ties)
        assertEquals(1, h.bWins)
        assertFalse(h.enoughData)
    }

    @Test fun deadZoneLosesHeadToHead() {
        val dead = List(3) { Obs(baseLat, lon, 0, "B", null, true, 5f) }
        val obs = cellObs(0, "A", -110, -110, -110) + dead
        val h = Compare.headToHead("A", "B", obs)
        assertEquals(1, h.aWins)
    }

    @Test fun placeWeightedMedianIgnoresLongStops() {
        // 50 samples at home at -80, one sample each in 4 other places at -110.
        val home = cellObs(0, "A", *IntArray(50) { -80 })
        val away = (1..4).flatMap { cellObs(it, "A", -110) }
        val s = Compare.carrierStats("A", home + away, 3_600_000L)
        assertEquals(-110.0, s.placeWeightedMedianDbm!!, 1e-9)
        assertEquals(5, s.cells)
        assertEquals(20.0, s.usableCellPct!!, 1e-9)
        assertEquals(1.0, s.hoursLogged, 1e-9)
    }

    @Test fun wifiSpeedTestsExcluded() {
        val obs = listOf(
            Obs(baseLat, lon, 0, "A", -90, false, 5f, downMbps = 100.0, ranOnCellular = false),
            Obs(baseLat, lon, 0, "A", -90, false, 5f, downMbps = 20.0, ranOnCellular = true),
        )
        val s = Compare.carrierStats("A", obs, 0)
        assertEquals(1, s.speedTests)
        assertEquals(20.0, s.medianDown!!, 1e-9)
    }

    @Test fun noServicePercent() {
        val obs = cellObs(0, "A", -90, -90, -90) + Obs(baseLat, lon, 0, "A", null, true, 5f)
        assertEquals(25.0, Compare.carrierStats("A", obs, 0).noServicePct!!, 1e-9)
    }
}
