package app.gyro.ui.dashboard

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.gyro.protocol.TiltbackPredictor
import app.gyro.ui.theme.GyroColors
import app.gyro.ui.theme.TabularNumbers
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private const val START_ANGLE = 150f
private const val SWEEP = 240f

fun levelColor(level: TiltbackPredictor.Level?): Color = when (level) {
    TiltbackPredictor.Level.CRITICAL -> GyroColors.Danger
    TiltbackPredictor.Level.WARNING -> GyroColors.Warning
    TiltbackPredictor.Level.CAUTION -> GyroColors.Caution
    else -> GyroColors.Accent
}

fun pwmColor(pwm: Double?, caution: Double, warning: Double): Color = when {
    pwm == null -> GyroColors.TextDim
    pwm >= warning -> GyroColors.Danger
    pwm >= caution -> GyroColors.Warning
    else -> GyroColors.Accent
}

/**
 * Large gauge: outer arc is speed (with a notch at the tiltback speed), inner arc is PWM load,
 * the number in the middle is readable at arm's length.
 */
@Composable
fun Speedometer(
    speedKmh: Double,
    pwmPercent: Double?,
    tiltbackKmh: Double?,
    level: TiltbackPredictor.Level?,
    live: Boolean,
    pwmCaution: Double,
    pwmWarning: Double,
    modifier: Modifier = Modifier,
) {
    val scaleMax = gaugeMax(tiltbackKmh, speedKmh)
    val speedFraction by animateFloatAsState((speedKmh / scaleMax).coerceIn(0.0, 1.0).toFloat(), tween(200), label = "speed")
    val pwmFraction by animateFloatAsState(((pwmPercent ?: 0.0) / 100.0).coerceIn(0.0, 1.0).toFloat(), tween(200), label = "pwm")
    val arcColor = if (live) levelColor(level) else GyroColors.TextDim
    val loadColor = pwmColor(pwmPercent, pwmCaution, pwmWarning)

    BoxWithConstraints(modifier.fillMaxWidth().aspectRatio(1.2f), contentAlignment = Alignment.Center) {
        // Circle geometry: a 240° arc is 1.5 radii tall, so size the circle by both width and height.
        val strokeDp = 16.dp
        val marginDp = strokeDp / 2 + 14.dp
        val radiusDp = minOf((maxWidth - marginDp * 2) / 2, (maxHeight - marginDp * 2) / 1.5f)
        val centerYDp = marginDp + radiusDp
        Canvas(Modifier.fillMaxSize()) {
            val stroke = strokeDp.toPx()
            val inner = 7.dp.toPx()
            val radiusPx = radiusDp.toPx()
            val diameter = radiusPx * 2
            val topLeft = Offset(size.width / 2 - radiusPx, centerYDp.toPx() - radiusPx)
            val arcSize = Size(diameter, diameter)

            drawArc(GyroColors.SurfaceHigh, START_ANGLE, SWEEP, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            if (speedFraction > 0.001f) {
                drawArc(arcColor, START_ANGLE, SWEEP * speedFraction, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            }

            val gap = stroke + 10.dp.toPx()
            val innerTopLeft = Offset(topLeft.x + gap, topLeft.y + gap)
            val innerSize = Size(diameter - gap * 2, diameter - gap * 2)
            drawArc(GyroColors.SurfaceHigh, START_ANGLE, SWEEP, false, innerTopLeft, innerSize, style = Stroke(inner, cap = StrokeCap.Round))
            if (pwmPercent != null && pwmFraction > 0.001f) {
                drawArc(loadColor, START_ANGLE, SWEEP * pwmFraction, false, innerTopLeft, innerSize, style = Stroke(inner, cap = StrokeCap.Round))
            }
            // PWM warning notch on the inner arc.
            notch(innerTopLeft, innerSize, (pwmWarning / 100.0).toFloat(), GyroColors.Warning, inner * 1.6f, 2.dp.toPx())

            val center = Offset(topLeft.x + diameter / 2, topLeft.y + diameter / 2)
            val radius = diameter / 2
            var tick = 0
            while (tick <= scaleMax) {
                val angle = Math.toRadians((START_ANGLE + SWEEP * tick / scaleMax).toDouble())
                val major = tick % 20 == 0
                val outer = radius + stroke / 2 + 4.dp.toPx()
                val len = if (major) 8.dp.toPx() else 4.dp.toPx()
                drawLine(
                    GyroColors.Outline,
                    Offset(center.x + (outer * cos(angle)).toFloat(), center.y + (outer * sin(angle)).toFloat()),
                    Offset(center.x + ((outer + len) * cos(angle)).toFloat(), center.y + ((outer + len) * sin(angle)).toFloat()),
                    strokeWidth = if (major) 2.dp.toPx() else 1.dp.toPx(),
                )
                tick += 10
            }
            if (tiltbackKmh != null && tiltbackKmh > 0) {
                notch(topLeft, arcSize, (tiltbackKmh / scaleMax).toFloat(), GyroColors.Danger, stroke * 1.5f, 3.dp.toPx())
            }
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.offset(y = centerYDp - maxHeight / 2),
        ) {
            Text(
                if (live) speedKmh.roundToInt().toString() else "—",
                style = TabularNumbers.copy(fontSize = 96.sp, fontWeight = FontWeight.Bold, lineHeight = 96.sp),
                color = if (live) GyroColors.Text else GyroColors.TextDim,
            )
            Text("км/ч", style = MaterialTheme.typography.titleMedium, color = GyroColors.TextDim)
            Text(
                pwmPercent?.let { "нагрузка ${it.roundToInt()}%" } ?: "нагрузка —",
                style = MaterialTheme.typography.labelLarge.merge(TabularNumbers),
                color = loadColor,
            )
        }
    }
}

/** Scale end: next multiple of 10 above the tiltback speed (or the current speed), at least 50. */
private fun gaugeMax(tiltbackKmh: Double?, speedKmh: Double): Double {
    val reference = maxOf(tiltbackKmh ?: 0.0, speedKmh, 40.0)
    return (((reference / 10).toInt() + 1) * 10).toDouble().coerceAtLeast(50.0)
}

private fun DrawScope.notch(topLeft: Offset, arcSize: Size, fraction: Float, color: Color, length: Float, width: Float) {
    val f = fraction.coerceIn(0f, 1f)
    val angle = Math.toRadians((START_ANGLE + SWEEP * f).toDouble())
    val center = Offset(topLeft.x + arcSize.width / 2, topLeft.y + arcSize.height / 2)
    val r = arcSize.width / 2
    val from = r - length / 2
    val to = r + length / 2
    drawLine(
        color,
        Offset(center.x + (from * cos(angle)).toFloat(), center.y + (from * sin(angle)).toFloat()),
        Offset(center.x + (to * cos(angle)).toFloat(), center.y + (to * sin(angle)).toFloat()),
        strokeWidth = width,
        cap = StrokeCap.Round,
    )
}
