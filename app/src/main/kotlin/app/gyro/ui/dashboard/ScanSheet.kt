package app.gyro.ui.dashboard

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.gyro.ble.ScannedWheel
import app.gyro.protocol.ProtocolFamily
import app.gyro.ui.theme.GyroColors

/** Nearby wheels; the protocol can be forced when an unusual name defeats auto-detection. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanSheet(vm: DashboardViewModel, onDismiss: () -> Unit, onPick: (String, String?, ProtocolFamily?) -> Unit) {
    val scan by vm.scan.collectAsStateWithLifecycle()
    val known by vm.knownWheels.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.startScan() }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = GyroColors.Surface) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Моноколёса рядом", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = { if (scan.active) vm.stopScan() else vm.startScan() }) {
                    Text(if (scan.active) "Стоп" else "Искать снова")
                }
            }
            if (scan.active) LinearProgressIndicator(Modifier.fillMaxWidth())
            scan.error?.let { Text(it, color = GyroColors.Danger, modifier = Modifier.padding(vertical = 8.dp)) }
            Spacer(Modifier.height(8.dp))
            val knownAddresses = known.associateBy { it.address }
            if (scan.wheels.isEmpty() && !scan.active && scan.error == null) {
                Text("Ничего не найдено. Включите колесо и держите телефон рядом.", color = GyroColors.TextDim)
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(scan.wheels, key = { it.address }) { wheel ->
                    val remembered = knownAddresses[wheel.address]
                    WheelRow(
                        wheel = wheel,
                        subtitle = remembered?.model?.let { "${remembered.brand ?: ""} $it".trim() },
                        onPick = { family -> onPick(wheel.address, wheel.name, family) },
                    )
                }
            }
        }
    }
}

@Composable
private fun WheelRow(wheel: ScannedWheel, subtitle: String?, onPick: (ProtocolFamily?) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clickable { onPick(null) }.padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(wheel.name ?: "Без имени", style = MaterialTheme.typography.bodyLarge)
            Text(
                listOfNotNull(subtitle, wheel.hint?.label ?: "марка определится после подключения", "${wheel.rssi} дБм")
                    .joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = if (wheel.hint != null) GyroColors.Accent else GyroColors.TextDim,
            )
        }
        Spacer(Modifier.width(8.dp))
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Выбрать протокол") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                Text(
                    "Подключить как…",
                    style = MaterialTheme.typography.labelMedium,
                    color = GyroColors.TextDim,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
                ProtocolFamily.entries.forEach { family ->
                    DropdownMenuItem(text = { Text(family.label) }, onClick = { menu = false; onPick(family) })
                }
            }
        }
    }
}
