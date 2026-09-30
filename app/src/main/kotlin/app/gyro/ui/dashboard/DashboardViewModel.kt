package app.gyro.ui.dashboard

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.gyro.AppContainer
import app.gyro.ble.BleScanner
import app.gyro.ble.ConnectionState
import app.gyro.ble.ScannedWheel
import app.gyro.ble.SendResult
import app.gyro.ble.WheelTarget
import app.gyro.data.HandlebarMetric
import app.gyro.data.Prefs
import app.gyro.data.RideSnapshot
import app.gyro.data.db.AlarmRuleEntity
import app.gyro.data.db.KnownWheelEntity
import app.gyro.protocol.AlarmRule
import app.gyro.protocol.Capability
import app.gyro.protocol.ProtocolFamily
import app.gyro.protocol.WheelAction
import app.gyro.service.WheelService
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class DashboardViewModel(private val c: AppContainer) : ViewModel() {

    val ride: StateFlow<RideSnapshot> = c.rideMonitor.snapshot

    val prefs: StateFlow<Prefs> = c.prefs.prefs.stateIn(viewModelScope, SharingStarted.Eagerly, Prefs())

    val alarmRules: StateFlow<List<AlarmRule>> = c.db.alarmRules().observeAll()
        .map { list -> list.mapNotNull { it.toRule() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val knownWheels: StateFlow<List<KnownWheelEntity>> = c.db.knownWheels().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _scan = MutableStateFlow(ScanState())
    val scan: StateFlow<ScanState> = _scan.asStateFlow()
    private var scanJob: Job? = null

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages

    private var lightOn = false

    data class ScanState(val active: Boolean = false, val wheels: List<ScannedWheel> = emptyList(), val error: String? = null)

    fun startScan() {
        scanJob?.cancel()
        _scan.value = ScanState(active = true)
        scanJob = viewModelScope.launch {
            withTimeoutOrNull(SCAN_DURATION_MS) {
                c.scanner.scan().collect { update ->
                    _scan.value = when (update) {
                        is BleScanner.Update.Devices -> _scan.value.copy(wheels = update.wheels, error = null)
                        is BleScanner.Update.Error -> _scan.value.copy(active = false, error = update.message)
                    }
                }
            }
            _scan.value = _scan.value.copy(active = false)
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        _scan.value = _scan.value.copy(active = false)
    }

    fun connect(context: Context, address: String, name: String?, family: ProtocolFamily? = null) {
        stopScan()
        WheelService.connect(context.applicationContext, WheelTarget(address, name, family))
    }

    fun reconnectLast(context: Context) {
        val p = prefs.value
        val address = p.lastWheelAddress ?: return
        connect(context, address, p.lastWheelName)
    }

    fun disconnect(context: Context) = WheelService.stop(context.applicationContext)

    fun beep() = send(WheelAction.Beep, "Сигнал")

    fun toggleLight() {
        val current = ride.value.wheel?.telemetry?.lightOn ?: lightOn
        lightOn = !current
        send(WheelAction.Light(lightOn), if (lightOn) "Свет включён" else "Свет выключен")
    }

    /** Refuses to lock a moving wheel, whatever the wheel itself would do. */
    fun lock() {
        val speed = ride.value.wheel?.telemetry?.absSpeedKmh ?: 0.0
        if (speed > 1.0) {
            _messages.tryEmit("Блокировка доступна только на месте")
            return
        }
        send(WheelAction.Lock(true), "Колесо заблокировано")
    }

    fun hasCapability(capability: Capability): Boolean =
        ride.value.wheel?.info?.capabilities?.contains(capability) == true

    private fun send(action: WheelAction, done: String) {
        viewModelScope.launch {
            val message = when (c.connection.send(action)) {
                SendResult.SENT -> done
                SendResult.UNSUPPORTED -> "Это колесо не поддерживает команду"
                SendResult.NOT_CONNECTED -> "Колесо не подключено"
            }
            _messages.tryEmit(message)
        }
    }

    fun saveAlarm(rule: AlarmRule) {
        viewModelScope.launch { c.db.alarmRules().upsert(AlarmRuleEntity.from(rule)) }
    }

    fun deleteAlarm(id: Long) {
        viewModelScope.launch { c.db.alarmRules().delete(id) }
    }

    fun setVoice(enabled: Boolean) = viewModelScope.launch { c.prefs.setVoice(enabled) }
    fun setVoiceInterval(minutes: Int) = viewModelScope.launch { c.prefs.setVoiceInterval(minutes) }
    fun setVibration(enabled: Boolean) = viewModelScope.launch { c.prefs.setVibration(enabled) }
    fun setOverlay(enabled: Boolean) = viewModelScope.launch { c.prefs.setOverlay(enabled) }
    fun setHandlebarMetric(metric: HandlebarMetric) = viewModelScope.launch { c.prefs.setHandlebarMetric(metric) }

    fun setPwmThresholds(caution: Double, warning: Double) = viewModelScope.launch {
        c.prefs.setPwmThresholds(caution, warning, maxOf(warning + 5, prefs.value.pwmCritical).coerceAtMost(98.0))
    }

    fun resetSession() = c.rideMonitor.resetSession()

    fun setCapacity(wh: Double?) = c.rideMonitor.setCapacityOverride(wh)

    fun setCells(cells: Int?) {
        viewModelScope.launch {
            c.connection.setCellsOverride(cells)
            val address = (ride.value.connection as? ConnectionState.Connected)?.target?.address ?: return@launch
            c.db.knownWheels().get(address)?.let { c.db.knownWheels().upsert(it.copy(cellsOverride = cells)) }
        }
    }

    override fun onCleared() {
        stopScan()
    }

    private companion object {
        const val SCAN_DURATION_MS = 20_000L
    }
}
