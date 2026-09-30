package app.gyro.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** One accent colour on a dark background; status colours are reserved for warnings and energy flow. */
object GyroColors {
    val Background = Color(0xFF0B0F14)
    val Surface = Color(0xFF121821)
    val SurfaceHigh = Color(0xFF1A222D)
    val Outline = Color(0xFF2A3441)
    val Accent = Color(0xFF00E0B8)
    val OnAccent = Color(0xFF00241D)
    val Text = Color(0xFFE8EEF4)
    val TextDim = Color(0xFF8C99A8)
    val Caution = Color(0xFFFFD166)
    val Warning = Color(0xFFFFB547)
    val Danger = Color(0xFFFF5A5F)

    /** Energy leaving the battery. */
    val EnergyOut = Color(0xFFFF9F43)

    /** Energy returning to the battery (regenerative braking, charging). */
    val EnergyIn = Color(0xFF4DA3FF)
}

private val scheme = darkColorScheme(
    primary = GyroColors.Accent,
    onPrimary = GyroColors.OnAccent,
    primaryContainer = Color(0xFF00382E),
    onPrimaryContainer = GyroColors.Accent,
    secondary = GyroColors.Accent,
    onSecondary = GyroColors.OnAccent,
    secondaryContainer = GyroColors.SurfaceHigh,
    onSecondaryContainer = GyroColors.Text,
    background = GyroColors.Background,
    onBackground = GyroColors.Text,
    surface = GyroColors.Background,
    onSurface = GyroColors.Text,
    surfaceVariant = GyroColors.SurfaceHigh,
    onSurfaceVariant = GyroColors.TextDim,
    surfaceContainerLowest = GyroColors.Background,
    surfaceContainerLow = GyroColors.Surface,
    surfaceContainer = GyroColors.Surface,
    surfaceContainerHigh = GyroColors.SurfaceHigh,
    surfaceContainerHighest = GyroColors.SurfaceHigh,
    outline = GyroColors.Outline,
    outlineVariant = GyroColors.Outline,
    error = GyroColors.Danger,
)

/** Digits of equal width so live numbers do not jitter. */
val TabularNumbers = TextStyle(fontFeatureSettings = "tnum")

private val typography = Typography().let { base ->
    base.copy(
        displayLarge = base.displayLarge.copy(fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum"),
        headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        labelSmall = base.labelSmall.copy(letterSpacing = 0.6.sp),
    )
}

@Composable
fun GyroTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = typography, content = content)
}
