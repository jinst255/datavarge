package app.datavarge.ui

import android.content.Context
import app.datavarge.analysis.Obs
import app.datavarge.data.AppDatabase
import app.datavarge.data.ServiceStates
import app.datavarge.data.SimLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/** Observation with the barometer value attached (only used for the Level filter on phones that have one). */
data class MapObs(val obs: Obs, val pressureHpa: Float?, val trigger: String)

object AppData {
    fun observations(c: Context): Flow<List<MapObs>> =
        AppDatabase.get(c).samples().allWithSpeed().map { rows ->
            rows.filter { it.service_state != ServiceStates.POWER_OFF }.map { r ->
                MapObs(
                    Obs(
                        lat = r.lat, lon = r.lon, tsMs = r.ts_utc_ms, label = r.carrier_label,
                        dbm = r.signal_dbm, noService = ServiceStates.isNoService(r.service_state),
                        accuracyM = r.loc_accuracy_m, networkType = r.network_type, note = r.note,
                        downMbps = r.down_mbps, upMbps = r.up_mbps, latencyMs = r.latency_ms,
                        ranOnCellular = r.ran_on_cellular, id = r.id,
                    ),
                    r.pressure_hpa, r.trigger,
                )
            }
        }.flowOn(Dispatchers.Default)

    /** Logged time per carrier label, summed over that label's SIMs. */
    fun activeMsByLabel(c: Context): Flow<Map<String, Long>> {
        val sims = AppDatabase.get(c).sims()
        return combine(sims.activeTimes(), sims.all()) { times, labels ->
            val byKey = labels.associateBy(SimLabel::simKey)
            times.groupBy { byKey[it.simKey]?.label ?: app.datavarge.data.UNLABELED }
                .mapValues { (_, v) -> v.sumOf { it.activeMs } }
        }
    }
}
