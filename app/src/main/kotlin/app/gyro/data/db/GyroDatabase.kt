package app.gyro.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        AlarmRuleEntity::class,
        KnownWheelEntity::class,
        ConnectionLogEntity::class,
        SettingsSnapshotEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
abstract class GyroDatabase : RoomDatabase() {
    abstract fun alarmRules(): AlarmRuleDao
    abstract fun knownWheels(): KnownWheelDao
    abstract fun connectionLog(): ConnectionLogDao
    abstract fun settingsSnapshots(): SettingsSnapshotDao

    companion object {
        /**
         * Version 2 dropped the voice and vibration flags from alarm rules. SQLite on older Android
         * cannot drop columns, so the table is rebuilt; the settings backup and history are untouched.
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `alarm_rules_new` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `metric` TEXT NOT NULL, " +
                        "`threshold` REAL NOT NULL, `above` INTEGER NOT NULL, `repeatSeconds` INTEGER NOT NULL, " +
                        "`hysteresis` REAL NOT NULL, `enabled` INTEGER NOT NULL, `label` TEXT)",
                )
                db.execSQL(
                    "INSERT INTO `alarm_rules_new` (`id`, `metric`, `threshold`, `above`, `repeatSeconds`, `hysteresis`, `enabled`, `label`) " +
                        "SELECT `id`, `metric`, `threshold`, `above`, `repeatSeconds`, `hysteresis`, `enabled`, `label` FROM `alarm_rules`",
                )
                db.execSQL("DROP TABLE `alarm_rules`")
                db.execSQL("ALTER TABLE `alarm_rules_new` RENAME TO `alarm_rules`")
            }
        }

        fun create(context: Context): GyroDatabase =
            Room.databaseBuilder(context, GyroDatabase::class.java, "gyro.db")
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
