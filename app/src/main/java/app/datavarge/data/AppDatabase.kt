package app.datavarge.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [Sample::class, SpeedTest::class, SimLabel::class, ActiveTime::class],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun samples(): SampleDao
    abstract fun speedTests(): SpeedTestDao
    abstract fun sims(): SimLabelDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "datavarge.db")
                    .build().also { instance = it }
            }
    }
}
