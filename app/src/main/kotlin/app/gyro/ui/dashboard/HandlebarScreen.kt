package app.gyro.ui.dashboard

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.gyro.data.HandlebarMetric
import app.gyro.protocol.TiltbackPredictor
import app.gyro.ui.theme.GyroColors
import app.gyro.ui.theme.TabularNumbers
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Phone on the handlebar: one huge number, black background, screen kept on. Tap to switch the
 * metric; the background turns amber/red before tiltback and red while an alarm is active.
 */
@Composable
fun HandlebarScreen(vm: DashboardViewModel, onExit: () -> Unit) {
    val ride by vm.ride.collectAsStateWithLifecycle()
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    val t = ride.wheel?.telemetry
    val metric = prefs.handlebarMetric
    val (value, unit) = when (metric) {
        HandlebarMetric.SPEED -> (t?.absSpeedKmh?.roundToInt()?.toString() ?: "—") to "км/ч"
        HandlebarMetric.BATTERY -> (t?.batteryPercent?.roundToInt()?.toString() ?: "—") to "% заряда"
        HandlebarMetric.PWM -> (t?.pwmPercent?.let { abs(it).roundToInt().toString() } ?: "—") to "% нагрузки"
        HandlebarMetric.POWER -> (t?.powerW?.roundToInt()?.toString() ?: "—") to "Вт"
    }
    val level = ride.tiltback?.level
    val background by animateColorAsState(
        when {
            level == TiltbackPredictor.Level.CRITICAL -> GyroColors.Danger.copy(alpha = 0.55f)
            ride.live && ride.activeAlarms.isNotEmpty() -> GyroColors.Danger.copy(alpha = 0.40f)
            level == TiltbackPredictor.Level.WARNING -> GyroColors.Warning.copy(alpha = 0.35f)
            else -> Color.Black
        },
        label = "bg",
    )
    Box(
        Modifier
            .fillMaxSize()
            .background(background)
            .clickable {
                val next = HandlebarMetric.entries[(metric.ordinal + 1) % HandlebarMetric.entries.size]
                vm.setHandlebarMetric(next)
            }
            .systemBarsPadding(),
    ) {
        IconButton(onClick = onExit, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)) {
            Icon(Icons.Filled.Close, "Выйти", tint = GyroColors.TextDim)
        }
        if (ride.live && ride.activeAlarms.isNotEmpty()) {
            Text(
                ride.activeAlarms.joinToString(" · ") { it.title },
                style = MaterialTheme.typography.headlineMedium,
                color = Color.White,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 56.dp, start = 16.dp, end = 16.dp),
            )
        }
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            // Size the digits to the screen: about 0.6 em per digit.
            val digits = value.length.coerceAtLeast(2)
            val fontSize = with(LocalDensity.current) { minOf(maxWidth * 0.95f / (digits * 0.6f), maxHeight * 0.55f).toSp() }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    value,
                    style = TabularNumbers.copy(fontSize = fontSize, fontWeight = FontWeight.Bold, lineHeight = fontSize),
                    color = if (ride.live) Color.White else GyroColors.TextDim,
                    maxLines = 1,
                )
                Text(unit, style = MaterialTheme.typography.headlineMedium, color = GyroColors.TextDim)
            }
        }
        Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(24.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("${t?.batteryPercent?.roundToInt() ?: "—"}%", style = MaterialTheme.typography.headlineMedium, color = GyroColors.Text)
            Text(
                "${t?.maxTemperatureC?.roundToInt() ?: "—"}°",
                style = MaterialTheme.typography.headlineMedium,
                color = GyroColors.Text,
            )
            Text(
                ride.range?.km?.let { "${it.roundToInt()} км" } ?: "—",
                style = MaterialTheme.typography.headlineMedium,
                color = GyroColors.Text,
            )
        }
    }
}
