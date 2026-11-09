package app.datavarge.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "speed_tests",
    foreignKeys = [ForeignKey(
        entity = Sample::class,
        parentColumns = ["id"],
        childColumns = ["sample_id"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("sample_id")],
)
data class SpeedTest(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "sample_id") val sampleId: Long,
    @ColumnInfo(name = "ts_utc_ms") val tsUtcMs: Long,
    @ColumnInfo(name = "down_mbps") val downMbps: Double?,
    @ColumnInfo(name = "up_mbps") val upMbps: Double?,
    @ColumnInfo(name = "latency_ms") val latencyMs: Double?,
    @ColumnInfo(name = "bytes_used") val bytesUsed: Long,
    @ColumnInfo(name = "ran_on_cellular") val ranOnCellular: Boolean,
    val error: String?,
)
