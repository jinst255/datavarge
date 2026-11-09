package app.datavarge.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sim_labels")
data class SimLabel(
    @PrimaryKey @ColumnInfo(name = "sim_key") val simKey: String,
    val label: String,
    @ColumnInfo(name = "first_seen_ms") val firstSeenMs: Long,
)

/** Minutes the logger was actively running per SIM, so "hours logged" is honest even when the cap skips samples. */
@Entity(tableName = "active_time")
data class ActiveTime(
    @PrimaryKey @ColumnInfo(name = "sim_key") val simKey: String,
    @ColumnInfo(name = "active_ms") val activeMs: Long,
)
