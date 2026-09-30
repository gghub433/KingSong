package app.gyro.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface AlarmRuleDao {
    @Query("SELECT * FROM alarm_rules ORDER BY id")
    fun observeAll(): Flow<List<AlarmRuleEntity>>

    @Upsert
    suspend fun upsert(rule: AlarmRuleEntity): Long

    @Insert
    suspend fun insertAll(rules: List<AlarmRuleEntity>)

    @Query("DELETE FROM alarm_rules WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface KnownWheelDao {
    @Query("SELECT * FROM known_wheels WHERE address = :address")
    suspend fun get(address: String): KnownWheelEntity?

    @Query("SELECT * FROM known_wheels WHERE address = :address")
    fun observe(address: String): Flow<KnownWheelEntity?>

    @Query("SELECT * FROM known_wheels ORDER BY lastConnectedAt DESC")
    fun observeAll(): Flow<List<KnownWheelEntity>>

    @Upsert
    suspend fun upsert(wheel: KnownWheelEntity)
}

@Dao
interface ConnectionLogDao {
    @Insert
    suspend fun insert(entry: ConnectionLogEntity): Long

    @Query("UPDATE connection_log SET disconnectedAt = :at WHERE id = :id")
    suspend fun close(id: Long, at: Long)

    @Query("UPDATE connection_log SET brand = :brand, model = :model WHERE id = :id")
    suspend fun describe(id: Long, brand: String?, model: String?)

    @Query("SELECT * FROM connection_log ORDER BY connectedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int = 100): Flow<List<ConnectionLogEntity>>
}

@Dao
interface SettingsSnapshotDao {
    @Insert
    suspend fun insert(snapshot: SettingsSnapshotEntity): Long

    @Query("SELECT * FROM settings_snapshots WHERE address = :address AND kind = 'FACTORY' ORDER BY createdAt LIMIT 1")
    suspend fun factory(address: String): SettingsSnapshotEntity?

    @Query("SELECT * FROM settings_snapshots WHERE address = :address ORDER BY createdAt DESC")
    fun observeForWheel(address: String): Flow<List<SettingsSnapshotEntity>>

    @Query("UPDATE settings_snapshots SET valuesJson = :json WHERE id = :id")
    suspend fun updateValues(id: Long, json: String)
}
