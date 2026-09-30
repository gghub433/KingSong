package app.gyro.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import app.gyro.protocol.AlarmMetric
import app.gyro.protocol.AlarmRule
import app.gyro.protocol.WheelParam
import org.json.JSONObject

@Entity(tableName = "alarm_rules")
data class AlarmRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val metric: String,
    val threshold: Double,
    val above: Boolean,
    val repeatSeconds: Int,
    val hysteresis: Double,
    val enabled: Boolean,
    val label: String?,
) {
    fun toRule(): AlarmRule? {
        val m = AlarmMetric.entries.firstOrNull { it.name == metric } ?: return null
        return AlarmRule(id, m, threshold, above, repeatSeconds, hysteresis, enabled, label)
    }

    companion object {
        fun from(rule: AlarmRule) = AlarmRuleEntity(
            id = rule.id, metric = rule.metric.name, threshold = rule.threshold, above = rule.above,
            repeatSeconds = rule.repeatSeconds,
            hysteresis = rule.hysteresis, enabled = rule.enabled, label = rule.label,
        )
    }
}

/** Every wheel Gyro has connected to, with what it learned about it. */
@Entity(tableName = "known_wheels")
data class KnownWheelEntity(
    @PrimaryKey val address: String,
    val name: String?,
    val brand: String?,
    val model: String?,
    val serial: String?,
    /** Detected protocol; reused on reconnect so detection is not repeated mid-ride. */
    val family: String?,
    val cellsOverride: Int?,
    val capacityWhOverride: Double?,
    val lastConnectedAt: Long,
)

/** Connection history: which wheel, when and for how long. */
@Entity(tableName = "connection_log", indices = [Index("address")])
data class ConnectionLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val address: String,
    val name: String?,
    val brand: String?,
    val model: String?,
    val family: String?,
    val connectedAt: Long,
    val disconnectedAt: Long?,
)

/**
 * A dated copy of wheel settings. [KIND_FACTORY] is captured automatically the first time Gyro sees
 * a wheel's settings, before any write is possible; restoring it works offline.
 */
@Entity(tableName = "settings_snapshots", indices = [Index("address")])
data class SettingsSnapshotEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val address: String,
    val kind: String,
    val label: String,
    val createdAt: Long,
    val valuesJson: String,
) {
    fun values(): Map<WheelParam, Int> = decodeValues(valuesJson)

    companion object {
        const val KIND_FACTORY = "FACTORY"
        const val KIND_USER = "USER"

        fun encodeValues(values: Map<WheelParam, Int>): String =
            JSONObject().apply { values.forEach { (k, v) -> put(k.name, v) } }.toString()

        fun decodeValues(json: String): Map<WheelParam, Int> {
            val obj = runCatching { JSONObject(json) }.getOrNull() ?: return emptyMap()
            return WheelParam.entries.filter { obj.has(it.name) }.associateWith { obj.getInt(it.name) }
        }
    }
}
