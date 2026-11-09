package app.datavarge.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

object Trigger {
    const val MOVE = "MOVE"
    const val HEARTBEAT = "HEARTBEAT"
    const val MANUAL = "MANUAL"
}

object ServiceStates {
    const val IN_SERVICE = "IN_SERVICE"
    const val OUT_OF_SERVICE = "OUT_OF_SERVICE"
    const val EMERGENCY_ONLY = "EMERGENCY_ONLY"
    const val POWER_OFF = "POWER_OFF"
    const val UNKNOWN = "UNKNOWN"

    fun isNoService(s: String) = s == OUT_OF_SERVICE || s == EMERGENCY_ONLY
}

const val UNLABELED = "Unlabeled"

@Entity(
    tableName = "samples",
    indices = [
        Index(value = ["carrier_label", "lat", "lon"]),
        Index(value = ["ts_utc_ms"]),
        Index(value = ["sim_key"]),
    ],
)
data class Sample(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "ts_utc_ms") val tsUtcMs: Long,
    val lat: Double,
    val lon: Double,
    @ColumnInfo(name = "loc_accuracy_m") val locAccuracyM: Float?,
    @ColumnInfo(name = "altitude_m") val altitudeM: Double?,
    @ColumnInfo(name = "altitude_accuracy_m") val altitudeAccuracyM: Float?,
    @ColumnInfo(name = "pressure_hpa") val pressureHpa: Float?,
    @ColumnInfo(name = "speed_mps") val speedMps: Float?,
    @ColumnInfo(name = "carrier_label") val carrierLabel: String,
    @ColumnInfo(name = "sim_key") val simKey: String,
    @ColumnInfo(name = "sim_operator_name") val simOperatorName: String?,
    @ColumnInfo(name = "mcc_mnc") val mccMnc: String?,
    @ColumnInfo(name = "network_type") val networkType: String,
    @ColumnInfo(name = "signal_dbm") val signalDbm: Int?,
    val rsrq: Int?,
    val snr: Int?,
    @ColumnInfo(name = "level_0_4") val level: Int,
    val band: String?,
    val pci: Int?,
    @ColumnInfo(name = "cell_id") val cellId: Long?,
    @ColumnInfo(name = "service_state") val serviceState: String,
    @ColumnInfo(name = "is_roaming") val isRoaming: Boolean,
    @ColumnInfo(name = "wifi_connected") val wifiConnected: Boolean,
    val trigger: String,
    val note: String?,
)
