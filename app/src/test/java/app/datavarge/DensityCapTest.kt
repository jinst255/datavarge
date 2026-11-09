package app.datavarge

import app.datavarge.analysis.CapDecision
import app.datavarge.analysis.CapPoint
import app.datavarge.analysis.DensityCap
import app.datavarge.analysis.Geo
import org.junit.Assert.assertEquals
import org.junit.Test

class DensityCapTest {
    private val lat = 33.7490
    private val lon = -84.3880
    private val now = 1_800_000_000_000L
    private val hour = 3_600_000L

    private fun pt(label: String, ageMs: Long, dM: Double = 0.0, manual: Boolean = false, hpa: Float? = null) =
        CapPoint(lat + Geo.latSpan(dM), lon, now - ageMs, label, manual, hpa)

    private fun decide(label: String, n: List<CapPoint>, manual: Boolean = false, hpa: Float? = null) =
        DensityCap.decide(lat, lon, now, label, manual, n, hpa)

    @Test fun emptyLogs() = assertEquals(CapDecision.LOG, decide("Boost", emptyList()))

    @Test fun sixSamplesWithin20mBlocks() {
        val six = (1..6).map { pt("Boost", it * hour, dM = 5.0) }
        assertEquals(CapDecision.SKIP_FULL, decide("Boost", six))
    }

    @Test fun fiveOldSamplesStillLogs() {
        val five = (1..5).map { pt("Boost", it * hour, dM = 5.0) }
        assertEquals(CapDecision.LOG, decide("Boost", five))
    }

    @Test fun newestUnder30MinBlocks() {
        assertEquals(CapDecision.SKIP_TOO_SOON, decide("Boost", listOf(pt("Boost", 29 * 60_000L))))
        assertEquals(CapDecision.LOG, decide("Boost", listOf(pt("Boost", 31 * 60_000L))))
    }

    @Test fun samplesBeyond20mDontCount() {
        val far = (1..10).map { pt("Boost", 60_000L, dM = 25.0) }
        assertEquals(CapDecision.LOG, decide("Boost", far))
    }

    @Test fun manualBypassesCap() {
        val six = (1..6).map { pt("Boost", 60_000L) }
        assertEquals(CapDecision.LOG, decide("Boost", six, manual = true))
    }

    @Test fun manualNeighboursDontCount() {
        val manual = (1..6).map { pt("Boost", 60_000L, manual = true) }
        assertEquals(CapDecision.LOG, decide("Boost", manual))
    }

    @Test fun capIsPerLabel_simSwapNotBlocked() {
        val boostFull = (1..6).map { pt("Boost", 60_000L) }
        assertEquals(CapDecision.SKIP_FULL, decide("Boost", boostFull))
        assertEquals(CapDecision.LOG, decide("Tello", boostFull))
    }

    @Test fun pressureBandsSeparateFloors() {
        val level1 = (1..6).map { pt("Boost", hour, hpa = 1000.0f) }
        assertEquals(CapDecision.SKIP_FULL, decide("Boost", level1, hpa = 1000.0f))
        // ~1.2 hPa lower is about 10 m higher: a different band, so not capped.
        assertEquals(CapDecision.LOG, decide("Boost", level1, hpa = 998.8f))
    }
}
