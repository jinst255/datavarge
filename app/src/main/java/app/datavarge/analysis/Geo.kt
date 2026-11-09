package app.datavarge.analysis

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object Geo {
    const val EARTH_R = 6_371_000.0
    const val M_PER_DEG_LAT = 111_320.0

    fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).let { it * it } +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).let { it * it }
        return 2 * EARTH_R * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }

    /** Degrees of latitude / longitude spanning [meters] around [lat]. */
    fun latSpan(meters: Double) = meters / M_PER_DEG_LAT
    fun lonSpan(meters: Double, lat: Double) = meters / (M_PER_DEG_LAT * cos(lat * PI / 180.0).coerceAtLeast(0.01))
}
