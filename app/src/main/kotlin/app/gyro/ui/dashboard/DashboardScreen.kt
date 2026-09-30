package app.gyro.ui.dashboard

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.gyro.ble.ConnectionState
import app.gyro.data.Prefs
import app.gyro.data.RideSnapshot
import app.gyro.protocol.AlarmMetric
import app.gyro.protocol.AlarmRule
import app.gyro.protocol.BatteryEstimator
import app.gyro.protocol.Capability
import app.gyro.protocol.SupportLevel
import app.gyro.protocol.TiltbackPredictor
import app.gyro.ui.common.Fmt
import app.gyro.ui.common.KeyValue
import app.gyro.ui.common.MetricTile
import app.gyro.ui.common.SectionCard
import app.gyro.ui.common.TileRow
import app.gyro.ui.theme.GyroColors
import app.gyro.ui.theme.GyroIcons
import app.gyro.ui.theme.TabularNumbers
import java.text.DateFormat
import java.util.Date
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun DashboardScreen(
    vm: DashboardViewModel,
    onOpenHandlebar: () -> Unit,
    ensurePermissions: (then: () -> Unit) -> Unit,
    showMessage: (String) -> Unit,
) {
    val ride by vm.ride.collectAsStateWithLifecycle()
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    val rules by vm.alarmRules.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showScan by remember { mutableStateOf(false) }
    var editAlarm by remember { mutableStateOf<AlarmRule?>(null) }
    var editBattery by remember { mutableStateOf(false) }

    val t = ride.wheel?.telemetry
    val connected = ride.connection is ConnectionState.Connected

    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Header(
                ride = ride,
                overlayOn = prefs.overlayEnabled,
                onHandlebar = onOpenHandlebar,
                onOverlay = {
                    if (prefs.overlayEnabled) {
                        vm.setOverlay(false)
                    } else if (Settings.canDrawOverlays(context)) {
                        vm.setOverlay(true)
                    } else {
                        showMessage("Разрешите Gyro показ поверх других приложений, затем включите оверлей снова")
                        context.startActivity(
                            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                },
                onConnect = { ensurePermissions { showScan = true } },
                onDisconnect = { vm.disconnect(context) },
            )
        }
        if (!connected) {
            item {
                ConnectCard(
                    ride = ride,
                    lastName = prefs.lastWheelName ?: prefs.lastWheelAddress,
                    onScan = { ensurePermissions { showScan = true } },
                    onReconnect = { ensurePermissions { vm.reconnectLast(context) } },
                )
            }
        }
        item { TiltbackBanner(ride.tiltback, ride.tiltbackSpeedKmh, t?.absSpeedKmh) }
        if (ride.live && ride.activeAlarms.isNotEmpty()) {
            item { ActiveAlarmsBanner(ride.activeAlarms) }
        }
        val alerts = ride.wheel?.alerts.orEmpty()
        if (alerts.isNotEmpty()) {
            item {
                Surface(shape = RoundedCornerShape(16.dp), color = GyroColors.Danger.copy(alpha = 0.15f)) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Колесо сообщает", style = MaterialTheme.typography.labelLarge, color = GyroColors.Danger)
                        alerts.forEach { Text("• ${it.text}", style = MaterialTheme.typography.bodyMedium) }
                    }
                }
            }
        }
        item {
            SectionCard {
                Speedometer(
                    speedKmh = t?.absSpeedKmh ?: 0.0,
                    pwmPercent = t?.pwmPercent?.let { abs(it) },
                    tiltbackKmh = ride.tiltbackSpeedKmh,
                    level = ride.tiltback?.level,
                    live = ride.live,
                    pwmCaution = prefs.pwmCaution,
                    pwmWarning = prefs.pwmWarning,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    SmallStat("макс", Fmt.int(ride.topSpeedKmh), "км/ч")
                    SmallStat("tiltback", Fmt.int(ride.tiltbackSpeedKmh), "км/ч")
                    SmallStat("средняя", Fmt.int(ride.energy.averageSpeedKmh), "км/ч")
                }
            }
        }
        item {
            QuickActions(
                enabled = connected,
                canLight = vm.hasCapability(Capability.LIGHT),
                canBeep = vm.hasCapability(Capability.BEEP),
                canLock = vm.hasCapability(Capability.LOCK),
                lightOn = t?.lightOn == true,
                onBeep = vm::beep,
                onLight = vm::toggleLight,
                onLock = vm::lock,
                onHandlebar = onOpenHandlebar,
            )
        }
        item { LiveTiles(ride) }
        item { EnergyCard(ride, onReset = vm::resetSession) }
        item {
            SectionCard(title = "Мощность и нагрузка") {
                val power = t?.powerW
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        Fmt.watts(power),
                        style = MaterialTheme.typography.headlineMedium,
                        color = if ((power ?: 0.0) < 0) GyroColors.EnergyIn else GyroColors.EnergyOut,
                    )
                    Text(" Вт", color = GyroColors.TextDim, modifier = Modifier.padding(bottom = 4.dp))
                    Spacer(Modifier.weight(1f))
                    val headroom = ride.tiltback?.headroomPercent
                    Text(
                        headroom?.let { "запас мощности ${it.roundToInt()}%" } ?: "PWM не передаётся",
                        style = MaterialTheme.typography.labelLarge,
                        color = levelColor(ride.tiltback?.level),
                    )
                }
                PowerChart(ride.powerHistory, prefs.pwmWarning)
            }
        }
        item {
            AlarmsCard(
                rules = rules,
                activeIds = if (ride.live) ride.activeAlarms.map { it.id }.toSet() else emptySet(),
                onToggle = { vm.saveAlarm(it.copy(enabled = !it.enabled)) },
                onEdit = { editAlarm = it },
                onAdd = { editAlarm = newAlarmTemplate() },
                onDelete = { vm.deleteAlarm(it.id) },
            )
        }
        item { LoadWarningCard(prefs, vm) }
        item { WheelInfoCard(ride, onEditBattery = { editBattery = true }) }
    }

    if (showScan) {
        ScanSheet(
            vm = vm,
            onDismiss = {
                vm.stopScan()
                showScan = false
            },
            onPick = { address, name, family ->
                vm.connect(context, address, name, family)
                showScan = false
            },
        )
    }
    editAlarm?.let { rule ->
        AlarmEditorDialog(
            initial = rule,
            onDismiss = { editAlarm = null },
            onSave = {
                vm.saveAlarm(it)
                editAlarm = null
            },
        )
    }
    if (editBattery) {
        BatteryConfigDialog(
            ride = ride,
            onDismiss = { editBattery = false },
            onSave = { cells, wh ->
                vm.setCells(cells)
                vm.setCapacity(wh)
                editBattery = false
            },
        )
    }
}

@Composable
private fun Header(
    ride: RideSnapshot,
    overlayOn: Boolean,
    onHandlebar: () -> Unit,
    onOverlay: () -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val (statusText, statusColor) = connectionLabel(ride.connection, ride.live)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                ride.wheel?.info?.displayName ?: ride.connection.target?.name ?: "Gyro",
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).background(statusColor, CircleShape))
                Spacer(Modifier.width(6.dp))
                Text(statusText, style = MaterialTheme.typography.bodySmall, color = GyroColors.TextDim, maxLines = 1)
            }
        }
        IconButton(onClick = onHandlebar) { Icon(GyroIcons.Fullscreen, "Режим на руле") }
        IconButton(onClick = onOverlay) {
            Icon(GyroIcons.Overlay, "Оверлей", tint = if (overlayOn) GyroColors.Accent else GyroColors.TextDim)
        }
        if (ride.connection is ConnectionState.Idle || ride.connection is ConnectionState.Failed) {
            IconButton(onClick = onConnect) { Icon(GyroIcons.Bluetooth, "Подключить") }
        } else {
            IconButton(onClick = onDisconnect) { Icon(GyroIcons.Bluetooth, "Отключить", tint = GyroColors.Accent) }
        }
    }
}

fun connectionLabel(state: ConnectionState, live: Boolean): Pair<String, Color> = when (state) {
    is ConnectionState.Connected -> (if (live) "На связи · ${state.family.label}" else "Подключено, ждём данные") to
        (if (live) GyroColors.Accent else GyroColors.Caution)
    is ConnectionState.Connecting -> "Подключение…" to GyroColors.Caution
    is ConnectionState.Identifying -> "Определяем модель…" to GyroColors.Caution
    is ConnectionState.Reconnecting -> "Переподключение: ${state.reason}" to GyroColors.Warning
    is ConnectionState.Failed -> state.reason to GyroColors.Danger
    ConnectionState.Idle -> "Не подключено" to GyroColors.TextDim
}

@Composable
private fun ConnectCard(ride: RideSnapshot, lastName: String?, onScan: () -> Unit, onReconnect: () -> Unit) {
    SectionCard {
        Text("Подключите моноколесо", style = MaterialTheme.typography.titleMedium)
        Text(
            "KingSong, Begode, Extreme Bull, Veteran, Nosfet, InMotion и Ninebot Z. Модель и протокол определяются автоматически, новые модели — по формату пакетов.",
            style = MaterialTheme.typography.bodyMedium,
            color = GyroColors.TextDim,
        )
        (ride.connection as? ConnectionState.Failed)?.let {
            Text(it.reason, color = GyroColors.Danger, style = MaterialTheme.typography.bodyMedium)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onScan) { Text("Найти колесо") }
            if (lastName != null) OutlinedButton(onClick = onReconnect) { Text(lastName, maxLines = 1) }
        }
    }
}

@Composable
private fun TiltbackBanner(a: TiltbackPredictor.Assessment?, tiltbackKmh: Double?, speed: Double?) {
    val visible = a != null && a.level != TiltbackPredictor.Level.NORMAL
    AnimatedVisibility(visible) {
        val level = a?.level ?: TiltbackPredictor.Level.CAUTION
        val color = levelColor(level)
        val text = when (a?.reason) {
            TiltbackPredictor.Reason.SPEED -> {
                val gap = if (tiltbackKmh != null && speed != null) (tiltbackKmh - speed).roundToInt() else null
                if (gap != null && gap > 0) "До tiltback $gap км/ч — сбавь" else "Tiltback: колесо поднимает носок"
            }
            TiltbackPredictor.Reason.PWM_TREND -> "Нагрузка растёт: скоро ${a?.projectedPwmPercent?.roundToInt()}%"
            else -> "Нагрузка ${a?.pwmPercent?.roundToInt()}% — запас ${a?.headroomPercent?.roundToInt()}%"
        }
        Surface(shape = RoundedCornerShape(16.dp), color = color.copy(alpha = 0.18f)) {
            Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Warning, null, tint = color)
                Spacer(Modifier.width(10.dp))
                Text(text, style = MaterialTheme.typography.titleMedium, color = color)
            }
        }
    }
}

/** Alarms are silent: while a threshold is exceeded it is shown here, on the gauge and on the overlay. */
@Composable
private fun ActiveAlarmsBanner(alarms: List<AlarmRule>) {
    Surface(shape = RoundedCornerShape(16.dp), color = GyroColors.Danger.copy(alpha = 0.18f)) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Warning, null, tint = GyroColors.Danger)
            Spacer(Modifier.width(10.dp))
            Column {
                alarms.forEach { Text(it.title, style = MaterialTheme.typography.titleMedium, color = GyroColors.Danger) }
            }
        }
    }
}

@Composable
private fun SmallStat(label: String, value: String, unit: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = GyroColors.TextDim)
        Text("$value $unit", style = MaterialTheme.typography.titleSmall.merge(TabularNumbers))
    }
}

@Composable
private fun QuickActions(
    enabled: Boolean,
    canLight: Boolean,
    canBeep: Boolean,
    canLock: Boolean,
    lightOn: Boolean,
    onBeep: () -> Unit,
    onLight: () -> Unit,
    onLock: () -> Unit,
    onHandlebar: () -> Unit,
) {
    var confirmLock by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ActionButton(GyroIcons.Horn, "Сигнал", enabled && canBeep, Modifier.weight(1f), onBeep)
        ActionButton(GyroIcons.Light, if (lightOn) "Свет вкл" else "Свет", enabled && canLight, Modifier.weight(1f), onLight, highlighted = lightOn)
        ActionButton(Icons.Filled.Lock, "Блок", enabled && canLock, Modifier.weight(1f), { confirmLock = true })
        ActionButton(GyroIcons.Fullscreen, "На руль", true, Modifier.weight(1f), onHandlebar)
    }
    if (confirmLock) {
        AlertDialog(
            onDismissRequest = { confirmLock = false },
            title = { Text("Заблокировать колесо?") },
            text = { Text("Мотор будет заблокирован до разблокировки в приложении производителя. Работает только когда колесо стоит.") },
            confirmButton = {
                TextButton(onClick = { confirmLock = false; onLock() }) { Text("Заблокировать") }
            },
            dismissButton = { TextButton(onClick = { confirmLock = false }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun ActionButton(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
    highlighted: Boolean = false,
) {
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(64.dp),
        shape = RoundedCornerShape(16.dp),
        contentPadding = PaddingValues(4.dp),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, tint = if (highlighted) GyroColors.Accent else Color.Unspecified, modifier = Modifier.size(22.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
    }
}

@Composable
private fun LiveTiles(ride: RideSnapshot) {
    val t = ride.wheel?.telemetry
    val info = ride.wheel?.info
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        TileRow(
            {
                val estimated = t != null && !t.batteryPercentFromWheel
                MetricTile(
                    "Заряд", Fmt.int(t?.batteryPercent), "%", it,
                    sub = if (estimated) "оценка по напряжению" else "по данным колеса",
                    icon = GyroIcons.Battery,
                    valueColor = batteryColor(t?.batteryPercent),
                )
            },
            {
                val r = ride.range
                MetricTile(
                    "Запас хода", r?.km?.let { Fmt.int(it) } ?: Fmt.one(t?.wheelRangeKm), "км", it,
                    sub = when {
                        r == null && t?.wheelRangeKm != null -> "оценка колеса"
                        r == null -> "укажите ёмкость батареи"
                        r.basedOnRiding -> "по вашему стилю: ${Fmt.one(r.whPerKm)} Вт·ч/км"
                        else -> "пока средний расход"
                    },
                    icon = GyroIcons.Route,
                )
            },
        )
        TileRow(
            {
                val cells = info?.cellsSeries
                MetricTile(
                    "Напряжение", Fmt.one(t?.voltageV), "В", it,
                    sub = if (cells != null && t != null) "${Fmt.two(t.voltageV / cells)} В/ячейку · ${cells}S" else null,
                    icon = GyroIcons.Battery,
                )
            },
            {
                MetricTile(
                    "Ток батареи", Fmt.one(t?.batteryCurrentA), "А", it,
                    sub = when {
                        t?.batteryCurrentA == null -> null
                        t.batteryCurrentEstimated -> "расчёт: фаза ${Fmt.one(t.phaseCurrentA)} А × PWM"
                        t.phaseCurrentA != null -> "фазный ${Fmt.one(t.phaseCurrentA)} А"
                        else -> null
                    },
                    icon = GyroIcons.Bolt,
                    valueColor = if ((t?.batteryCurrentA ?: 0.0) < 0) GyroColors.EnergyIn else GyroColors.Text,
                )
            },
        )
        TileRow(
            {
                MetricTile(
                    "Температура", Fmt.int(t?.temperatureC), "°C", it,
                    sub = listOfNotNull(
                        t?.motorTemperatureC?.let { m -> "мотор ${m.roundToInt()}°" },
                        t?.temperature2C?.let { b -> "плата ${b.roundToInt()}°" },
                    ).joinToString(" · ").ifEmpty { null },
                    icon = GyroIcons.Thermometer,
                    valueColor = tempColor(t?.maxTemperatureC),
                )
            },
            {
                MetricTile("Мощность", Fmt.watts(t?.powerW), "Вт", it, sub = "пик ${Fmt.watts(ride.energy.peakPowerW)} Вт", icon = GyroIcons.Bolt)
            },
        )
        TileRow(
            { MetricTile("Поездка", Fmt.km(t?.tripDistanceM), "км", it, sub = "сессия ${Fmt.one(ride.energy.distanceKm)} км", icon = GyroIcons.Route) },
            { MetricTile("Пробег колеса", Fmt.km(t?.totalDistanceM), "км", it, icon = GyroIcons.Speed) },
        )
    }
}

private fun batteryColor(p: Double?): Color = when {
    p == null -> GyroColors.Text
    p < 15 -> GyroColors.Danger
    p < 30 -> GyroColors.Warning
    else -> GyroColors.Text
}

private fun tempColor(c: Double?): Color = when {
    c == null -> GyroColors.Text
    c >= 75 -> GyroColors.Danger
    c >= 60 -> GyroColors.Warning
    else -> GyroColors.Text
}

/** "How much goes in and out": energy drawn from the pack vs. returned by braking. */
@Composable
private fun EnergyCard(ride: RideSnapshot, onReset: () -> Unit) {
    val e = ride.energy
    SectionCard(
        title = "Энергия за сессию",
        trailing = {
            IconButton(onClick = onReset) { Icon(Icons.Filled.Refresh, "Сбросить", tint = GyroColors.TextDim) }
        },
    ) {
        Row(Modifier.fillMaxWidth()) {
            EnergyFigure("Ушло из батареи", Fmt.wh(e.whOut), "Вт·ч", "${Fmt.two(e.ahOut)} А·ч", GyroColors.EnergyOut, Modifier.weight(1f))
            EnergyFigure("Вернулось", Fmt.wh(e.whRegen), "Вт·ч", "${Fmt.two(e.ahIn)} А·ч", GyroColors.EnergyIn, Modifier.weight(1f))
        }
        EnergyBar(e.whOut, e.whRegen)
        HorizontalDivider(color = GyroColors.Outline)
        KeyValue("Итого потрачено", "${Fmt.wh(e.whNet)} Вт·ч")
        KeyValue("Доля рекуперации", e.regenPercent?.let { "${Fmt.one(it)} %" } ?: "—", GyroColors.EnergyIn)
        KeyValue("Расход", e.whPerKm?.let { "${Fmt.one(it)} Вт·ч/км" } ?: "—")
        KeyValue("Пик мощности / рекуперации", "${Fmt.watts(e.peakPowerW)} / ${Fmt.watts(e.peakRegenW)} Вт")
        if (e.whCharged > 0.05) KeyValue("Заряжено на стоянке", "${Fmt.wh(e.whCharged)} Вт·ч", GyroColors.EnergyIn)
        ride.capacity?.let { cap ->
            val left = ride.range?.remainingWh
            KeyValue("Осталось в батарее", left?.let { "${Fmt.wh(it)} из ${Fmt.wh(cap.wh)} Вт·ч" } ?: "—")
        }
        KeyValue("Время в движении", Fmt.duration(e.movingSeconds))
    }
}

@Composable
private fun EnergyFigure(label: String, value: String, unit: String, sub: String, color: Color, modifier: Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = GyroColors.TextDim)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, style = MaterialTheme.typography.headlineMedium, color = color)
            Text(" $unit", color = GyroColors.TextDim, modifier = Modifier.padding(bottom = 4.dp))
        }
        Text(sub, style = MaterialTheme.typography.bodySmall.merge(TabularNumbers), color = GyroColors.TextDim)
    }
}

@Composable
private fun EnergyBar(out: Double, regen: Double) {
    val total = out.coerceAtLeast(0.001)
    val regenShare = (regen / total).coerceIn(0.0, 1.0).toFloat()
    Row(Modifier.fillMaxWidth().height(8.dp)) {
        if (regenShare < 1f) {
            Box(Modifier.weight(1f - regenShare).height(8.dp).background(GyroColors.EnergyOut, RoundedCornerShape(4.dp)))
        }
        if (regenShare > 0f) {
            Spacer(Modifier.width(2.dp))
            Box(Modifier.weight(regenShare).height(8.dp).background(GyroColors.EnergyIn, RoundedCornerShape(4.dp)))
        }
    }
}

@Composable
private fun AlarmsCard(
    rules: List<AlarmRule>,
    activeIds: Set<Long>,
    onToggle: (AlarmRule) -> Unit,
    onEdit: (AlarmRule) -> Unit,
    onAdd: () -> Unit,
    onDelete: (AlarmRule) -> Unit,
) {
    SectionCard(
        title = "Алармы",
        trailing = { IconButton(onClick = onAdd) { Icon(Icons.Filled.Add, "Добавить", tint = GyroColors.Accent) } },
    ) {
        if (rules.isEmpty()) {
            Text("Нет алармов. Добавьте порог скорости, температуры или тока.", color = GyroColors.TextDim)
        }
        rules.forEach { rule ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(rule.title, style = MaterialTheme.typography.bodyLarge)
                    val active = rule.id in activeIds
                    Text(
                        when {
                            active -> "сработал"
                            rule.metric == AlarmMetric.BATTERY -> "на экране и тихим уведомлением"
                            else -> "на экране"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (active) GyroColors.Danger else GyroColors.TextDim,
                    )
                }
                IconButton(onClick = { onEdit(rule) }) { Icon(Icons.Filled.Edit, "Изменить", tint = GyroColors.TextDim) }
                IconButton(onClick = { onDelete(rule) }) { Icon(Icons.Filled.Delete, "Удалить", tint = GyroColors.TextDim) }
                Switch(checked = rule.enabled, onCheckedChange = { onToggle(rule) })
            }
        }
    }
}

@Composable
private fun LoadWarningCard(prefs: Prefs, vm: DashboardViewModel) {
    SectionCard(title = "Предупреждения о нагрузке") {
        Text("Подсвечивать нагрузку (PWM) заранее", style = MaterialTheme.typography.bodyMedium)
        var caution by remember(prefs.pwmCaution) { mutableStateOf(prefs.pwmCaution.toFloat()) }
        var warning by remember(prefs.pwmWarning) { mutableStateOf(prefs.pwmWarning.toFloat()) }
        KeyValue("Внимание с", "${caution.roundToInt()} %", GyroColors.Caution)
        Slider(
            value = caution, onValueChange = { caution = it.coerceAtMost(warning - 5) }, valueRange = 40f..90f,
            onValueChangeFinished = { vm.setPwmThresholds(caution.toDouble(), warning.toDouble()) },
        )
        KeyValue("Тревога с", "${warning.roundToInt()} %", GyroColors.Warning)
        Slider(
            value = warning, onValueChange = { warning = it.coerceAtLeast(caution + 5) }, valueRange = 50f..95f,
            onValueChangeFinished = { vm.setPwmThresholds(caution.toDouble(), warning.toDouble()) },
        )
        Text(
            "Tiltback и отключение мотора случаются, когда PWM подходит к 100%. Gyro смотрит и на скорость роста нагрузки и подсвечивает экран, спидометр и оверлей за 1–2 секунды.",
            style = MaterialTheme.typography.bodySmall,
            color = GyroColors.TextDim,
        )
    }
}

@Composable
private fun WheelInfoCard(ride: RideSnapshot, onEditBattery: () -> Unit) {
    val info = ride.wheel?.info ?: return
    SectionCard(
        title = "Колесо",
        trailing = { IconButton(onClick = onEditBattery) { Icon(Icons.Filled.Edit, "Батарея", tint = GyroColors.TextDim) } },
    ) {
        KeyValue("Марка", info.brand.displayName)
        KeyValue("Модель", info.model ?: "—")
        KeyValue("Прошивка", info.firmware ?: "—")
        KeyValue("Серийный номер", info.serial ?: "—")
        KeyValue("Протокол", info.family?.label ?: "—")
        KeyValue(
            "Батарея",
            info.cellsSeries?.let { "${it}S, ${Fmt.one(it * 4.2)} В (${info.cellsSource?.label ?: ""})" } ?: "—",
        )
        KeyValue("Ёмкость", ride.capacity?.let { "${Fmt.int(it.wh)} Вт·ч (${it.source.label})" } ?: "не задана")
        ride.factoryBackupAt?.let {
            KeyValue("Бэкап исходных настроек", DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it)))
        }
        if (info.supportLevel != SupportLevel.KNOWN) {
            Text(info.supportLevel.label, style = MaterialTheme.typography.bodySmall, color = GyroColors.Caution)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BatteryConfigDialog(ride: RideSnapshot, onDismiss: () -> Unit, onSave: (Int?, Double?) -> Unit) {
    val info = ride.wheel?.info
    var cells by remember { mutableStateOf(info?.cellsSeries.takeIf { info?.cellsSource == app.gyro.protocol.CellsSource.USER }) }
    var capacity by remember {
        mutableStateOf(ride.capacity?.takeIf { it.source == app.gyro.protocol.BatteryCapacity.Source.USER }?.wh?.roundToInt()?.toString() ?: "")
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Батарея колеса") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Последовательных ячеек (напряжение полного заряда)", style = MaterialTheme.typography.bodyMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = cells == null, onClick = { cells = null }, label = { Text("авто") })
                    BatteryEstimator.KNOWN_SERIES.forEach { s ->
                        FilterChip(selected = cells == s, onClick = { cells = s }, label = { Text("${s}S · ${(s * 4.2).roundToInt()}В") })
                    }
                }
                Text("Ёмкость, Вт·ч (пусто — из BMS или по модели)", style = MaterialTheme.typography.bodyMedium)
                androidx.compose.material3.OutlinedTextField(
                    value = capacity,
                    onValueChange = { v -> capacity = v.filter { it.isDigit() }.take(5) },
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
                    ),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(cells, capacity.toDoubleOrNull()?.takeIf { it > 0 }) }) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@Composable
fun BigValue(text: String, color: Color = GyroColors.Text) {
    Text(text, style = MaterialTheme.typography.displayLarge, color = color, fontWeight = FontWeight.Bold)
}
