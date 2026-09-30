package app.gyro.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private val Context.gyroDataStore: DataStore<Preferences> by preferencesDataStore(name = "gyro_prefs")

/** What the handlebar mode shows as its one huge number. */
enum class HandlebarMetric(val label: String) { SPEED("Скорость"), BATTERY("Заряд"), PWM("Нагрузка"), POWER("Мощность") }

data class Prefs(
    val pwmCaution: Double = 70.0,
    val pwmWarning: Double = 80.0,
    val pwmCritical: Double = 90.0,
    val speedMarginKmh: Double = 3.0,
    val overlayEnabled: Boolean = false,
    val handlebarMetric: HandlebarMetric = HandlebarMetric.SPEED,
    val lastWheelAddress: String? = null,
    val lastWheelName: String? = null,
    val alarmsSeeded: Boolean = false,
)

class UserPrefs(context: Context) {
    private val store = context.applicationContext.gyroDataStore

    val prefs: Flow<Prefs> = store.data
        .catch { emit(androidx.datastore.preferences.core.emptyPreferences()) }
        .map { p ->
            Prefs(
                pwmCaution = p[PWM_CAUTION] ?: 70.0,
                pwmWarning = p[PWM_WARNING] ?: 80.0,
                pwmCritical = p[PWM_CRITICAL] ?: 90.0,
                speedMarginKmh = p[SPEED_MARGIN] ?: 3.0,
                overlayEnabled = p[OVERLAY] ?: false,
                handlebarMetric = HandlebarMetric.entries.firstOrNull { it.name == p[HANDLEBAR] } ?: HandlebarMetric.SPEED,
                lastWheelAddress = p[LAST_ADDRESS],
                lastWheelName = p[LAST_NAME],
                alarmsSeeded = p[ALARMS_SEEDED] ?: false,
            )
        }

    suspend fun update(block: (MutablePreferences) -> Unit) {
        store.edit { block(it) }
    }

    suspend fun setOverlay(enabled: Boolean) = update { it[OVERLAY] = enabled }
    suspend fun setHandlebarMetric(metric: HandlebarMetric) = update { it[HANDLEBAR] = metric.name }
    suspend fun setAlarmsSeeded() = update { it[ALARMS_SEEDED] = true }

    suspend fun setPwmThresholds(caution: Double, warning: Double, critical: Double) = update {
        it[PWM_CAUTION] = caution
        it[PWM_WARNING] = warning
        it[PWM_CRITICAL] = critical
    }

    suspend fun setLastWheel(address: String, name: String?) = update {
        it[LAST_ADDRESS] = address
        if (name != null) it[LAST_NAME] = name else it.remove(LAST_NAME)
    }

    private companion object {
        val PWM_CAUTION = doublePreferencesKey("pwm_caution")
        val PWM_WARNING = doublePreferencesKey("pwm_warning")
        val PWM_CRITICAL = doublePreferencesKey("pwm_critical")
        val SPEED_MARGIN = doublePreferencesKey("speed_margin")
        val OVERLAY = booleanPreferencesKey("overlay_enabled")
        val HANDLEBAR = stringPreferencesKey("handlebar_metric")
        val LAST_ADDRESS = stringPreferencesKey("last_wheel_address")
        val LAST_NAME = stringPreferencesKey("last_wheel_name")
        val ALARMS_SEEDED = booleanPreferencesKey("alarms_seeded")
    }
}
