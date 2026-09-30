package app.gyro.ui.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import app.gyro.data.PowerPoint
import app.gyro.ui.theme.GyroColors
import kotlin.math.abs
import kotlin.math.max

/**
 * Last 90 seconds of battery power (orange above zero: drawn from the pack; blue below: returned by
 * braking) with PWM load overlaid on its own 0–100 % scale and the warning level dashed.
 */
@Composable
fun PowerChart(points: List<PowerPoint>, pwmWarning: Double, modifier: Modifier = Modifier) {
    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().height(160.dp)) {
            val w = size.width
            val h = size.height
            if (points.size < 2) {
                drawLine(GyroColors.Outline, Offset(0f, h / 2), Offset(w, h / 2), strokeWidth = 1.dp.toPx())
                return@Canvas
            }
            val end = points.last().timeMs
            val start = end - WINDOW_MS
            val maxAbs = max(500f, points.maxOf { abs(it.powerW) } * 1.15f)
            val zeroY = h / 2
            fun x(t: Long) = ((t - start).toFloat() / WINDOW_MS) * w
            fun y(p: Float) = zeroY - (p / maxAbs) * (h / 2)

            // Grid: zero line and ±50 %.
            drawLine(GyroColors.Outline, Offset(0f, zeroY), Offset(w, zeroY), strokeWidth = 1.dp.toPx())
            val dash = PathEffect.dashPathEffect(floatArrayOf(6f, 8f))
            drawLine(GyroColors.Outline.copy(alpha = 0.5f), Offset(0f, h / 4), Offset(w, h / 4), pathEffect = dash)
            drawLine(GyroColors.Outline.copy(alpha = 0.5f), Offset(0f, h * 3 / 4), Offset(w, h * 3 / 4), pathEffect = dash)

            val visible = points.filter { it.timeMs >= start }
            val line = Path()
            val fillOut = Path()
            val fillIn = Path()
            visible.forEachIndexed { i, p ->
                val px = x(p.timeMs)
                val py = y(p.powerW)
                if (i == 0) {
                    line.moveTo(px, py)
                    fillOut.moveTo(px, zeroY); fillIn.moveTo(px, zeroY)
                } else {
                    line.lineTo(px, py)
                }
                fillOut.lineTo(px, minOf(py, zeroY))
                fillIn.lineTo(px, maxOf(py, zeroY))
            }
            val lastX = x(visible.last().timeMs)
            fillOut.lineTo(lastX, zeroY); fillOut.close()
            fillIn.lineTo(lastX, zeroY); fillIn.close()
            drawPath(fillOut, GyroColors.EnergyOut.copy(alpha = 0.25f))
            drawPath(fillIn, GyroColors.EnergyIn.copy(alpha = 0.35f))
            drawPath(line, GyroColors.EnergyOut, style = Stroke(2.dp.toPx()))

            // PWM on a 0–100 % scale over the full height.
            val warnY = h - (pwmWarning.toFloat() / 100f) * h
            drawLine(GyroColors.Warning.copy(alpha = 0.7f), Offset(0f, warnY), Offset(w, warnY), strokeWidth = 1.dp.toPx(), pathEffect = dash)
            val pwmPath = Path()
            var started = false
            visible.forEach { p ->
                val pwm = p.pwmPercent ?: return@forEach
                val px = x(p.timeMs)
                val py = h - (pwm.coerceIn(0f, 100f) / 100f) * h
                if (!started) { pwmPath.moveTo(px, py); started = true } else pwmPath.lineTo(px, py)
            }
            if (started) drawPath(pwmPath, GyroColors.Accent, style = Stroke(1.5.dp.toPx()))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Legend(GyroColors.EnergyOut, "из батареи")
            Spacer(Modifier.width(12.dp))
            Legend(GyroColors.EnergyIn, "рекуперация")
            Spacer(Modifier.width(12.dp))
            Legend(GyroColors.Accent, "нагрузка %")
            Spacer(Modifier.weight(1f))
            Text("90 с", style = MaterialTheme.typography.labelSmall, color = GyroColors.TextDim)
        }
    }
}

@Composable
private fun Legend(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.size(8.dp).background(color, CircleShape))
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = GyroColors.TextDim)
    }
}

private const val WINDOW_MS = 90_000L
