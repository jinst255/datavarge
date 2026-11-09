package app.datavarge.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** A sample joined with its speed test (if any), for analysis and export. */
data class SampleWithSpeed(
    val id: Long,
    val ts_utc_ms: Long,
    val lat: Double,
    val lon: Double,
    val loc_accuracy_m: Float?,
    val altitude_m: Double?,
    val pressure_hpa: Float?,
    val carrier_label: String,
    val network_type: String,
    val signal_dbm: Int?,
    val service_state: String,
    val trigger: String,
    val note: String?,
    val down_mbps: Double?,
    val up_mbps: Double?,
    val latency_ms: Double?,
    val ran_on_cellular: Boolean?,
)

data class LabelCount(val carrier_label: String, val n: Int)

@Dao
interface SampleDao {
    @Insert
    suspend fun insert(sample: Sample): Long

    @Query("UPDATE samples SET note = :note WHERE id = :id")
    suspend fun setNote(id: Long, note: String?)

    @Query("SELECT * FROM samples WHERE id = :id")
    suspend fun byId(id: Long): Sample?

    /** Automatic samples inside a lat/lon bounding box for one carrier label (density cap pre-filter). */
    @Query(
        """SELECT * FROM samples WHERE carrier_label = :label AND trigger != 'MANUAL'
           AND lat BETWEEN :minLat AND :maxLat AND lon BETWEEN :minLon AND :maxLon"""
    )
    suspend fun autoInBox(label: String, minLat: Double, maxLat: Double, minLon: Double, maxLon: Double): List<Sample>

    @Query("SELECT COUNT(*) FROM samples")
    fun countAll(): Flow<Int>

    @Query("SELECT COUNT(*) FROM samples WHERE ts_utc_ms >= :since")
    fun countSince(since: Long): Flow<Int>

    @Query("SELECT COUNT(*) FROM samples")
    suspend fun countAllNow(): Int

    @Query("SELECT MAX(ts_utc_ms) FROM samples")
    suspend fun newestTs(): Long?

    @Query("SELECT * FROM samples ORDER BY ts_utc_ms DESC LIMIT 1")
    fun latest(): Flow<Sample?>

    @Query(
        """SELECT s.id, s.ts_utc_ms, s.lat, s.lon, s.loc_accuracy_m, s.altitude_m, s.pressure_hpa,
                  s.carrier_label, s.network_type, s.signal_dbm, s.service_state, s.trigger, s.note,
                  t.down_mbps, t.up_mbps, t.latency_ms, t.ran_on_cellular
           FROM samples s LEFT JOIN speed_tests t ON t.sample_id = s.id
           ORDER BY s.ts_utc_ms"""
    )
    fun allWithSpeed(): Flow<List<SampleWithSpeed>>

    @Query(
        """SELECT s.id, s.ts_utc_ms, s.lat, s.lon, s.loc_accuracy_m, s.altitude_m, s.pressure_hpa,
                  s.carrier_label, s.network_type, s.signal_dbm, s.service_state, s.trigger, s.note,
                  t.down_mbps, t.up_mbps, t.latency_ms, t.ran_on_cellular
           FROM samples s LEFT JOIN speed_tests t ON t.sample_id = s.id
           ORDER BY s.ts_utc_ms"""
    )
    suspend fun allWithSpeedNow(): List<SampleWithSpeed>

    @Query("SELECT * FROM samples ORDER BY ts_utc_ms")
    suspend fun allNow(): List<Sample>

    @Query("SELECT carrier_label, COUNT(*) AS n FROM samples GROUP BY carrier_label")
    fun labelCounts(): Flow<List<LabelCount>>

    @Query("UPDATE samples SET carrier_label = :label WHERE sim_key = :simKey")
    suspend fun relabel(simKey: String, label: String): Int

    @Query("DELETE FROM samples")
    suspend fun deleteAll()
}

@Dao
interface SpeedTestDao {
    @Insert
    suspend fun insert(t: SpeedTest): Long

    @Query("SELECT * FROM speed_tests ORDER BY ts_utc_ms")
    suspend fun allNow(): List<SpeedTest>

    @Query("DELETE FROM speed_tests")
    suspend fun deleteAll()
}

@Dao
interface SimLabelDao {
    @Query("SELECT * FROM sim_labels WHERE sim_key = :simKey")
    suspend fun get(simKey: String): SimLabel?

    @Query("SELECT * FROM sim_labels ORDER BY first_seen_ms")
    fun all(): Flow<List<SimLabel>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(l: SimLabel)

    @Upsert
    suspend fun upsert(l: SimLabel)

    @Query("DELETE FROM sim_labels")
    suspend fun deleteAll()

    @Query("SELECT * FROM active_time")
    fun activeTimes(): Flow<List<ActiveTime>>

    @Query("INSERT OR IGNORE INTO active_time(sim_key, active_ms) VALUES (:simKey, 0)")
    suspend fun ensureActive(simKey: String)

    @Query("UPDATE active_time SET active_ms = active_ms + :ms WHERE sim_key = :simKey")
    suspend fun addActiveRaw(simKey: String, ms: Long)

    @Transaction
    suspend fun addActive(simKey: String, ms: Long) {
        ensureActive(simKey)
        addActiveRaw(simKey, ms)
    }

    @Query("DELETE FROM active_time")
    suspend fun deleteAllActive()
}
