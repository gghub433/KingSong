package app.gyro.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        AlarmRuleEntity::class,
        KnownWheelEntity::class,
        ConnectionLogEntity::class,
        SettingsSnapshotEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class GyroDatabase : RoomDatabase() {
    abstract fun alarmRules(): AlarmRuleDao
    abstract fun knownWheels(): KnownWheelDao
    abstract fun connectionLog(): ConnectionLogDao
    abstract fun settingsSnapshots(): SettingsSnapshotDao

    companion object {
        fun create(context: Context): GyroDatabase =
            Room.databaseBuilder(context, GyroDatabase::class.java, "gyro.db").build()
    }
}
