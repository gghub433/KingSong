package app.gyro.ui.tabs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.gyro.AppContainer
import app.gyro.ble.ConnectionState
import app.gyro.data.RideSnapshot
import app.gyro.data.db.ConnectionLogEntity
import app.gyro.data.db.SettingsSnapshotEntity
import app.gyro.ui.common.Fmt
import app.gyro.ui.common.KeyValue
import app.gyro.ui.common.PlannedFeatures
import app.gyro.ui.common.SectionCard
import app.gyro.ui.common.gyroViewModel
import app.gyro.ui.theme.GyroColors
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.text.DateFormat
import java.util.Date

class TabsViewModel(c: AppContainer) : ViewModel() {
    val ride: StateFlow<RideSnapshot> = c.rideMonitor.snapshot

    val history: StateFlow<List<ConnectionLogEntity>> = c.db.connectionLog().observeRecent()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    @OptIn(ExperimentalCoroutinesApi::class)
    val snapshots: StateFlow<List<SettingsSnapshotEntity>> = c.rideMonitor.snapshot
        .map { (it.connection as? ConnectionState.Connected)?.target?.address ?: it.connection.target?.address }
        .distinctUntilChanged()
        .flatMapLatest { address -> if (address == null) flowOf(emptyList()) else c.db.settingsSnapshots().observeForWheel(address) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}

private val dateTime: DateFormat = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)

@Composable
fun BatteryTab() {
    val vm = gyroViewModel { TabsViewModel(it) }
    val ride by vm.ride.collectAsStateWithLifecycle()
    PlannedFeatures(
        title = "Батарея",
        subtitle = "Ячейки, разброс, деградация и расход",
        features = listOf(
            "История напряжения каждой ячейки и график деградации",
            "Профиль просадки напряжения под нагрузкой",
            "Счётчик циклов заряда и оценка остаточной ёмкости",
            "Расход Вт·ч/км в зависимости от скорости и рельефа",
            "Порог разброса ячеек с предупреждением",
        ),
        header = {
            val packs = ride.wheel?.bms.orEmpty().filter { it.cellCount > 0 }
            if (packs.isEmpty()) {
                SectionCard(title = "Ячейки сейчас") {
                    Text(
                        "Колесо не передаёт напряжения ячеек или не подключено. Их отдают KingSong с BMS (S18, S19, S20, S22, F-серия), Begode со смарт-BMS и Veteran Lynx/Sherman L/Patton S.",
                        color = GyroColors.TextDim,
                    )
                }
            }
            packs.forEach { pack ->
                val weakest = pack.minCell
                SectionCard(title = "Пакет ${pack.index}: ${pack.cellCount} ячеек") {
                    KeyValue("Разброс", pack.spreadV?.let { "${Fmt.one(it * 1000)} мВ" } ?: "—",
                        if ((pack.spreadV ?: 0.0) > 0.05) GyroColors.Warning else GyroColors.Text)
                    KeyValue("Слабейшая ячейка", weakest?.let { "№${it.index + 1}: ${Fmt.two(it.value)} В" } ?: "—", GyroColors.Warning)
                    pack.fullCycles?.let { KeyValue("Полных циклов", it.toString()) }
                    pack.remainingPercent?.let { KeyValue("Заряд по BMS", "$it %") }
                    pack.cellVoltages.chunked(4).forEachIndexed { row, cells ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            cells.forEachIndexed { i, v ->
                                val index = row * 4 + i
                                Text(
                                    "${index + 1}: ${Fmt.two(v)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (weakest?.index == index) GyroColors.Warning else GyroColors.Text,
                                )
                            }
                        }
                    }
                }
            }
        },
    )
}

@Composable
fun TripsTab() {
    PlannedFeatures(
        title = "Поездки",
        subtitle = "Запись, разбор и рекорды",
        features = listOf(
            "Автостарт записи при начале движения, автостоп при простое",
            "GPS-трек + полная телеметрия, экспорт GPX/CSV",
            "Разбор: просадка батареи, пики тока, максимальный наклон",
            "Температурная карта с привязкой к подъёмам",
            "Сравнение одного маршрута в разные даты",
            "Личные рекорды: скорость, дальность, длинный подъём",
            "Тепловая карта всех поездок и шеринг картинкой",
            "«Чёрный ящик»: 30 секунд телеметрии перед резким торможением или обрывом связи",
            "Детект падения с уведомлением на часы или SMS контакту",
            "Погода в месте старта, калькулятор запаса хода до точки на карте",
        ),
    )
}

@Composable
fun WheelSettingsTab() {
    val vm = gyroViewModel { TabsViewModel(it) }
    val ride by vm.ride.collectAsStateWithLifecycle()
    val snapshots by vm.snapshots.collectAsStateWithLifecycle()
    PlannedFeatures(
        title = "Настройки колеса",
        subtitle = "Чтение работает, запись — следующий этап",
        features = listOf(
            "Запись tiltback, света, жёсткости педалей",
            "Профили в одно касание: город / трейл / новичок",
            "Снапшоты настроек с датой и восстановление одной кнопкой (офлайн)",
            "Дифф перед записью «было 45 → станет 50» с подтверждением каждого пункта",
            "Предупреждение о значениях выше штатных — без «не показывать»",
            "Запись заблокирована при заряде ниже 50%",
            "Экспорт/импорт профиля в файл",
            "Режим новичка: мягкий лимит скорости на первые N км",
        ),
        header = {
            val settings = ride.wheel?.settings.orEmpty()
            SectionCard(title = "Текущие значения на колесе") {
                if (settings.isEmpty()) Text("Нет данных: подключите колесо.", color = GyroColors.TextDim)
                settings.forEach { (param, value) -> KeyValue(param.label, "$value ${param.unit}".trim()) }
            }
            val factory = snapshots.firstOrNull { it.kind == SettingsSnapshotEntity.KIND_FACTORY }
            SectionCard(title = "Резервная копия исходных настроек") {
                if (factory == null) {
                    Text(
                        "Будет сохранена автоматически при первом подключении, до любой записи. Без неё запись настроек заблокирована.",
                        color = GyroColors.TextDim,
                    )
                } else {
                    KeyValue("Сохранена", dateTime.format(Date(factory.createdAt)), GyroColors.Accent)
                    factory.values().forEach { (param, value) -> KeyValue(param.label, "$value ${param.unit}".trim()) }
                }
            }
        },
    )
}

@Composable
fun MoreTab() {
    val vm = gyroViewModel { TabsViewModel(it) }
    val history by vm.history.collectAsStateWithLifecycle()
    PlannedFeatures(
        title = "Ещё",
        subtitle = "Журнал, ТО, виджет, часы",
        features = listOf(
            "Журнал ошибок колеса с расшифровкой",
            "Напоминания о ТО по пробегу: болты, покрышка, подшипники",
            "Тема приложения под цвет подсветки колеса, ночной режим по времени суток",
            "Виджет на домашний экран с зарядом и пробегом",
            "Быстрое подключение по NFC-метке на колесе",
            "Wear OS: скорость и заряд на часах",
            "Таймер прогрева/остывания мотора",
            "Счётчик сэкономленных денег и CO₂ по сравнению с авто",
        ),
        header = {
            SectionCard(title = "История подключений") {
                if (history.isEmpty()) Text("Пока пусто.", color = GyroColors.TextDim)
                history.take(20).forEach { entry ->
                    val duration = entry.disconnectedAt?.let { Fmt.duration((it - entry.connectedAt) / 1000) } ?: "сейчас"
                    KeyValue(
                        listOfNotNull(entry.brand, entry.model ?: entry.name).joinToString(" ").ifEmpty { entry.address },
                        "${dateTime.format(Date(entry.connectedAt))} · $duration",
                    )
                }
            }
        },
    )
}
