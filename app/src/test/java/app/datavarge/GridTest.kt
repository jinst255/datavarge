package app.datavarge

import app.datavarge.analysis.Cells
import app.datavarge.analysis.Geo
import app.datavarge.analysis.Grid
import app.datavarge.analysis.NO_SERVICE_DBM
import app.datavarge.analysis.Obs
import app.datavarge.analysis.Quality
import app.datavarge.analysis.Stats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GridTest {
    @Test fun pointsInsideACellShareKey() {
        val k = Grid.keyOf(33.7490, -84.3880)
        val b = Grid.bounds(k)
        assertTrue(33.7490 in b.south..b.north)
        assertTrue(-84.3880 in b.west..b.east)
        assertEquals(k, Grid.keyOf((b.south + b.north) / 2, (b.west + b.east) / 2))
    }

    @Test fun cellIsAbout20m() {
        val b = Grid.bounds(Grid.keyOf(33.7490, -84.3880))
        val ns = Geo.distanceM(b.south, b.west, b.north, b.west)
        val ew = Geo.distanceM(b.south, b.west, b.south, b.east)
        assertEquals(20.0, ns, 0.5)
        assertEquals(20.0, ew, 0.5)
    }

    @Test fun pointsFarApartDiffer() {
        assertNotEquals(Grid.keyOf(33.7490, -84.3880), Grid.keyOf(33.7490 + Geo.latSpan(45.0), -84.3880))
    }

    @Test fun medianOddEvenEmpty() {
        assertEquals(-95.0, Stats.median(listOf(-100.0, -95.0, -80.0))!!, 1e-9)
        assertEquals(-92.5, Stats.median(listOf(-100.0, -95.0, -90.0, -80.0))!!, 1e-9)
        assertNull(Stats.median(emptyList()))
    }

    @Test fun medianResistsOutlier() {
        assertEquals(-90.0, Stats.median(listOf(-90.0, -91.0, -89.0, -140.0, -90.0))!!, 1e-9)
    }

    @Test fun qualityBins() {
        assertEquals(Quality.EXCELLENT, Quality.of(-70))
        assertEquals(Quality.EXCELLENT, Quality.of(-85))
        assertEquals(Quality.GOOD, Quality.of(-86))
        assertEquals(Quality.GOOD, Quality.of(-95))
        assertEquals(Quality.FAIR, Quality.of(-96))
        assertEquals(Quality.FAIR, Quality.of(-105))
        assertEquals(Quality.POOR, Quality.of(-106))
        assertEquals(Quality.POOR, Quality.of(-115))
        assertEquals(Quality.VERY_POOR, Quality.of(-116))
        assertEquals(Quality.NONE, Quality.of(null))
    }

    @Test fun cellMedianAndDeadZone() {
        val lat = 33.7490; val lon = -84.3880
        val obs = listOf(-90, -100, -95).map { Obs(lat, lon, 0, "Boost", it, false, 10f) }
        val cell = Cells.aggregate(obs).values.single()
        assertEquals(-95.0, cell.medianDbm!!, 1e-9)
        assertEquals(-100.0, cell.worstDbm!!, 1e-9)

        val dead = List(3) { Obs(lat, lon, 0, "Boost", null, true, 10f) }
        val dc = Cells.aggregate(dead).values.single()
        assertEquals(NO_SERVICE_DBM.toDouble(), dc.medianDbm!!, 1e-9)
        assertTrue(dc.isDeadZone)
    }

    @Test fun inaccurateSamplesExcludedFromCells() {
        val obs = listOf(Obs(33.0, -84.0, 0, "Boost", -90, false, 49f), Obs(33.0, -84.0, 0, "Boost", -120, false, 75f))
        val cell = Cells.aggregate(obs).values.single()
        assertEquals(1, cell.obs.size)
    }
}
