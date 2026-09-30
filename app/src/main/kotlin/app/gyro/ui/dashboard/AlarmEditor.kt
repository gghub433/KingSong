package app.gyro.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.gyro.protocol.AlarmMetric
import app.gyro.protocol.AlarmRule

fun newAlarmTemplate() = AlarmRule(id = 0, metric = AlarmMetric.SPEED, threshold = 40.0)

/**
 * Edits one threshold alarm. A speed rule with only vibration and a single trigger doubles as the
 * "vibrate when I reach N km/h" feedback.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AlarmEditorDialog(initial: AlarmRule, onDismiss: () -> Unit, onSave: (AlarmRule) -> Unit) {
    var metric by remember { mutableStateOf(initial.metric) }
    var threshold by remember { mutableStateOf(AlarmRule.formatNumber(initial.threshold)) }
    var above by remember { mutableStateOf(initial.above) }
    var voice by remember { mutableStateOf(initial.voice) }
    var vibrate by remember { mutableStateOf(initial.vibrate) }
    var repeat by remember { mutableStateOf(initial.repeatSeconds) }
    var label by remember { mutableStateOf(initial.label ?: "") }
    val value = threshold.replace(',', '.').toDoubleOrNull()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.id == 0L) "Новый аларм" else "Аларм") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Что отслеживать", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AlarmMetric.entries.forEach { m ->
                        FilterChip(
                            selected = metric == m,
                            onClick = {
                                if (metric != m) {
                                    metric = m
                                    above = m.defaultAbove
                                    threshold = AlarmRule.formatNumber(defaultThreshold(m))
                                }
                            },
                            label = { Text(m.label) },
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = above, onClick = { above = true }, label = { Text("выше") })
                    FilterChip(selected = !above, onClick = { above = false }, label = { Text("ниже") })
                }
                OutlinedTextField(
                    value = threshold,
                    onValueChange = { threshold = it.take(7) },
                    label = { Text("Порог, ${metric.unit}") },
                    singleLine = true,
                    isError = value == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it.take(40) },
                    label = { Text("Фраза для голоса (необязательно)") },
                    singleLine = true,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = voice, onCheckedChange = { voice = it })
                    Text("Голос")
                    Checkbox(checked = vibrate, onCheckedChange = { vibrate = it })
                    Text("Вибрация")
                }
                Text("Повторять, пока условие держится", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0 to "один раз", 3 to "3 с", 10 to "10 с", 30 to "30 с", 60 to "1 мин").forEach { (s, text) ->
                        FilterChip(selected = repeat == s, onClick = { repeat = s }, label = { Text(text) })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = value != null && (voice || vibrate),
                onClick = {
                    onSave(
                        initial.copy(
                            metric = metric,
                            threshold = value ?: initial.threshold,
                            above = above,
                            voice = voice,
                            vibrate = vibrate,
                            repeatSeconds = repeat,
                            hysteresis = AlarmRule.defaultHysteresis(metric),
                            label = label.trim().ifEmpty { null },
                        ),
                    )
                },
            ) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

private fun defaultThreshold(metric: AlarmMetric): Double = when (metric) {
    AlarmMetric.SPEED -> 40.0
    AlarmMetric.PWM -> 80.0
    AlarmMetric.TEMPERATURE -> 65.0
    AlarmMetric.CURRENT -> 60.0
    AlarmMetric.POWER -> 3000.0
    AlarmMetric.BATTERY -> 20.0
    AlarmMetric.VOLTAGE -> 70.0
}
