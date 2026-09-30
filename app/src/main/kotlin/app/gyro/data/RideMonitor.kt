package app.gyro.data

import android.os.SystemClock
import app.gyro.ble.ConnectionState
import app.gyro.ble.WheelConnectionManager
import app.gyro.data.db.AlarmRuleEntity
import app.gyro.data.db.ConnectionLogEntity
import app.gyro.data.db.GyroDatabase
import app.gyro.data.db.KnownWheelEntity
import app.gyro.data.db.SettingsSnapshotEntity
import app.gyro.protocol.AlarmEngine
import app.gyro.protocol.AlarmEvent
import app.gyro.protocol.AlarmRule
import app.gyro.protocol.BatteryCapacity
import app.gyro.protocol.EnergyMeter
import app.gyro.protocol.EnergyStats
import app.gyro.protocol.RangeEstimate
import app.gyro.protocol.RangeEstimator
import app.gyro.protocol.TiltbackPredictor
import app.gyro.protocol.WheelAlert
import app.gyro.protocol.WheelParam
import app.gyro.protocol.WheelState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** One point of the live power chart. */
data class PowerPoint(val timeMs: Long, val powerW: Float, val pwmPercent: Float?)

/** Everything the dashboard, overlay and notification show. */
data class RideSnapshot(
    val connection: ConnectionState = ConnectionState.Idle,
    val wheel: WheelState? = null,
    val energy: EnergyStats = EnergyStats(),
    val range: RangeEstimate? = null,
    val capacity: BatteryCapacity.Capacity? = null,
    val tiltback: TiltbackPredictor.Assessment? = null,
    val tiltbackSpeedKmh: Double? = null,
    val powerHistory: List<PowerPoint> = emptyList(),
    val topSpeedKmh: Double = 0.0,
    /** Alarms whose threshold is exceeded right now; shown on screen instead of sound or vibration. */
    val activeAlarms: List<AlarmRule> = emptyList(),
    /** Connected and the last sample is fresh. */
    val live: Boolean = false,
    val factoryBackupAt: Long? = null,
)

/** One-off happenings the service turns into silent notifications. */
sealed interface RideEvent {
    data class Alarm(val event: AlarmEvent) : RideEvent
    data class WheelWarning(val alert: WheelAlert) : RideEvent
}

/**
 * Turns the raw wheel state into ride information: energy in/out, range, tiltback warnings and
 * alarm events. Also keeps the connection history and captures the original wheel settings the
 * first time it sees them (the backup that must exist before any settings write).
 */
class RideMonitor(
    private val scope: CoroutineScope,
    private val connection: WheelConnectionManager,
    private val prefs: UserPrefs,
    private val db: GyroDatabase,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    private val context = Dispatchers.Default.limitedParallelism(1)

    private val _snapshot = MutableStateFlow(RideSnapshot())
    val snapshot: StateFlow<RideSnapshot> = _snapshot.asStateFlow()

    private val _events = MutableSharedFlow<RideEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<RideEvent> = _events.asSharedFlow()

    private val energy = EnergyMeter()
    private val range = RangeEstimator()
    private val predictor = TiltbackPredictor()
    private val alarms = AlarmEngine()
    private var rules: List<AlarmRule> = emptyList()

    private var sessionAddress: String? = null
    private var capacityOverrideWh: Double? = null
    private val history = ArrayDeque<PowerPoint>()
    private var lastSampleMs = -1L
    private var lastSampleWallMs = 0L
    private var lastAlerts: Set<WheelAlert> = emptySet()
    private var topSpeed = 0.0
    private var logId: Long? = null
    private var describedModel: String? = null
    private var lastBackupCheckMs = 0L
    private var wasConnected = false

    init {
        scope.launch(context) { seedDefaultAlarms() }
        scope.launch(context) {
            db.alarmRules().observeAll().collect { list -> rules = list.mapNotNull { it.toRule() } }
        }
        scope.launch(context) {
            prefs.prefs.collect { p ->
                predictor.config = TiltbackPredictor.Config(
                    pwmCaution = p.pwmCaution, pwmWarning = p.pwmWarning, pwmCritical = p.pwmCritical,
                    speedMarginKmh = p.speedMarginKmh,
                )
            }
        }
        scope.launch(context) { connection.state.collect { onConnection(it) } }
        scope.launch(context) { connection.wheel.collect { onWheel(it) } }
        // Marks data as stale when the stream stops without a disconnect event.
        scope.launch(context) {
            while (isActive) {
                delay(1_000)
                val fresh = lastSampleWallMs > 0 && SystemClock.elapsedRealtime() - lastSampleWallMs < 2_500
                val live = fresh && _snapshot.value.connection is ConnectionState.Connected
                if (live != _snapshot.value.live) _snapshot.value = _snapshot.value.copy(live = live)
            }
        }
    }

    /** Clears trip counters (energy, range history, top speed) without disconnecting. */
    fun resetSession() {
        scope.launch(context) {
            resetCounters()
            _snapshot.value = _snapshot.value.copy(activeAlarms = emptyList())
            publish(_snapshot.value.wheel)
        }
    }

    fun setCapacityOverride(wh: Double?) {
        scope.launch(context) {
            capacityOverrideWh = wh
            val address = sessionAddress ?: return@launch
            db.knownWheels().get(address)?.let { db.knownWheels().upsert(it.copy(capacityWhOverride = wh)) }
            publish(_snapshot.value.wheel)
        }
    }

    private fun resetCounters() {
        energy.reset()
        range.reset()
        predictor.reset()
        alarms.reset()
        history.clear()
        topSpeed = 0.0
        lastSampleMs = -1
    }

    private suspend fun seedDefaultAlarms() {
        val p = prefs.prefs.first()
        if (p.alarmsSeeded) return
        db.alarmRules().insertAll(AlarmEngine.DEFAULT_RULES.map { AlarmRuleEntity.from(it.copy(id = 0)) })
        prefs.setAlarmsSeeded()
    }

    private suspend fun onConnection(state: ConnectionState) {
        val connected = state is ConnectionState.Connected
        if (state is ConnectionState.Connected) {
            val target = state.target
            if (target.address != sessionAddress) {
                resetCounters()
                sessionAddress = target.address
                describedModel = null
                lastBackupCheckMs = 0
                lastAlerts = emptySet()
                val known = db.knownWheels().get(target.address)
                capacityOverrideWh = known?.capacityWhOverride
                _snapshot.value = _snapshot.value.copy(
                    factoryBackupAt = db.settingsSnapshots().factory(target.address)?.createdAt,
                )
            }
            if (!wasConnected) {
                logId = db.connectionLog().insert(
                    ConnectionLogEntity(
                        address = target.address, name = target.name, brand = null, model = null,
                        family = target.family?.name, connectedAt = System.currentTimeMillis(), disconnectedAt = null,
                    ),
                )
                describedModel = null
                prefs.setLastWheel(target.address, target.name)
            }
        } else if (wasConnected) {
            logId?.let { db.connectionLog().close(it, System.currentTimeMillis()) }
            logId = null
        }
        wasConnected = connected
        _snapshot.value = _snapshot.value.copy(connection = state, live = connected && _snapshot.value.live)
    }

    private suspend fun onWheel(state: WheelState?) {
        if (state == null) return
        val t = state.telemetry
        if (state.hasTelemetry && t.timestampMs != lastSampleMs) {
            lastSampleMs = t.timestampMs
            lastSampleWallMs = SystemClock.elapsedRealtime()
            processSample(state)
        }
        rememberWheel(state)
        backupSettings(state)
        publish(state)
    }

    private fun processSample(state: WheelState) {
        val t = state.telemetry
        energy.add(t)
        range.add(energy.stats)
        topSpeed = maxOf(topSpeed, t.absSpeedKmh)

        val tiltbackSpeed = tiltbackSpeed(state)
        val assessment = predictor.assess(t, tiltbackSpeed)
        val now = t.timestampMs
        alarms.evaluate(rules, t, now).forEach { _events.tryEmit(RideEvent.Alarm(it)) }
        val activeIds = alarms.activeRuleIds

        (state.alerts - lastAlerts).forEach { _events.tryEmit(RideEvent.WheelWarning(it)) }
        lastAlerts = state.alerts

        val power = t.powerW
        if (power != null && (history.isEmpty() || now - history.last().timeMs >= 250)) {
            history.addLast(PowerPoint(now, power.toFloat(), t.pwmPercent?.let { kotlin.math.abs(it).toFloat() }))
            while (history.isNotEmpty() && now - history.first().timeMs > HISTORY_MS) history.removeFirst()
        }
        _snapshot.value = _snapshot.value.copy(
            tiltback = assessment,
            tiltbackSpeedKmh = tiltbackSpeed,
            activeAlarms = rules.filter { it.id in activeIds },
        )
    }

    /** The lower of the configured tiltback speed and the wheel's live speed limit (drops with battery). */
    private fun tiltbackSpeed(state: WheelState): Double? {
        val configured = state.settings[WheelParam.TILTBACK_SPEED]?.takeIf { it in 6..150 }?.toDouble()
        val dynamic = state.telemetry.dynamicSpeedLimitKmh?.takeIf { it in 6.0..150.0 }
        return listOfNotNull(configured, dynamic).minOrNull()
    }

    private fun publish(state: WheelState?) {
        val capacity = state?.let { BatteryCapacity.resolve(it.info, it.bms, capacityOverrideWh) }
        val stats = energy.stats
        _snapshot.value = _snapshot.value.copy(
            wheel = state,
            energy = stats,
            capacity = capacity,
            range = range.estimate(capacity?.wh, state?.telemetry?.batteryPercent),
            powerHistory = history.toList(),
            topSpeedKmh = topSpeed,
        )
    }

    private suspend fun rememberWheel(state: WheelState) {
        val address = sessionAddress ?: return
        val model = state.info.model ?: return
        if (model == describedModel) return
        describedModel = model
        logId?.let { db.connectionLog().describe(it, state.info.brand.displayName, model) }
        val old = db.knownWheels().get(address)
        db.knownWheels().upsert(
            (old ?: KnownWheelEntity(address, state.info.name, null, null, null, null, null, null, System.currentTimeMillis()))
                .copy(brand = state.info.brand.displayName, model = model, serial = state.info.serial ?: old?.serial),
        )
    }

    /**
     * Saves the first-seen settings as the factory backup, then only adds parameters that arrive later
     * (wheels report settings in several frames). Existing values are never overwritten.
     */
    private suspend fun backupSettings(state: WheelState) {
        val address = sessionAddress ?: return
        if (state.settings.isEmpty()) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastBackupCheckMs < 5_000) return
        lastBackupCheckMs = now
        val dao = db.settingsSnapshots()
        val existing = dao.factory(address)
        if (existing == null) {
            dao.insert(
                SettingsSnapshotEntity(
                    address = address,
                    kind = SettingsSnapshotEntity.KIND_FACTORY,
                    label = "Исходные настройки (сохранены автоматически)",
                    createdAt = System.currentTimeMillis(),
                    valuesJson = SettingsSnapshotEntity.encodeValues(state.settings),
                ),
            )
            _snapshot.value = _snapshot.value.copy(factoryBackupAt = System.currentTimeMillis())
        } else {
            val stored = existing.values()
            val missing = state.settings.filterKeys { it !in stored }
            if (missing.isNotEmpty()) {
                dao.updateValues(existing.id, SettingsSnapshotEntity.encodeValues(stored + missing))
            }
        }
    }

    private companion object {
        const val HISTORY_MS = 90_000L
    }
}
