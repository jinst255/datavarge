package app.datavarge.analysis

import app.datavarge.Config
import kotlin.math.roundToInt

/** A previously stored automatic sample, as far as the cap cares. */
data class CapPoint(
    val lat: Double,
    val lon: Double,
    val tsMs: Long,
    val carrierLabel: String,
    val isManual: Boolean,
    val pressureHpa: Float? = null,
)

enum class CapDecision { LOG, SKIP_FULL, SKIP_TOO_SOON }

/**
 * Density cap for automatic samples (MOVE / HEARTBEAT):
 * skip if there are already [Config.CAP_MAX_SAMPLES] automatic samples within [Config.CAP_RADIUS_M]
 * for the same carrier label, or the newest such sample is younger than [Config.CAP_MIN_SPACING_MS].
 * Per carrier label, never global. MANUAL samples bypass it, and existing manual samples never count.
 * If both the candidate and a neighbour have pressure readings, they must share a pressure band too.
 */
object DensityCap {
    fun pressureBand(hpa: Float): Int = (hpa / Config.PRESSURE_BAND_HPA).roundToInt()

    fun decide(
        lat: Double,
        lon: Double,
        nowMs: Long,
        carrierLabel: String,
        isManual: Boolean,
        neighbours: List<CapPoint>,
        pressureHpa: Float? = null,
    ): CapDecision {
        if (isManual) return CapDecision.LOG
        val band = pressureHpa?.let(::pressureBand)
        val near = neighbours.filter { p ->
            !p.isManual &&
                p.carrierLabel == carrierLabel &&
                Geo.distanceM(lat, lon, p.lat, p.lon) <= Config.CAP_RADIUS_M &&
                (band == null || p.pressureHpa == null || pressureBand(p.pressureHpa) == band)
        }
        if (near.size >= Config.CAP_MAX_SAMPLES) return CapDecision.SKIP_FULL
        val newest = near.maxOfOrNull { it.tsMs }
        if (newest != null && nowMs - newest < Config.CAP_MIN_SPACING_MS) return CapDecision.SKIP_TOO_SOON
        return CapDecision.LOG
    }
}
