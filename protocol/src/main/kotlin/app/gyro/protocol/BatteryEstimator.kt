package app.gyro.protocol

import kotlin.math.roundToInt

/**
 * State of charge from resting voltage, for wheels that do not report it.
 *
 * Uses the per-cell form of WheelLog's "better percents" curve: linear 3.325–4.175 V/cell with a
 * steeper tail below 3.4 V/cell. Under load the voltage sags, so the estimate is lower while
 * accelerating — the UI labels it as an estimate.
 */
object BatteryEstimator {

    /** Series counts found in production wheels (16S = 67.2 V … 42S = 176.4 V). */
    val KNOWN_SERIES = listOf(16, 20, 24, 30, 32, 36, 40, 42)

    fun percentFromCellVoltage(cellV: Double): Double = when {
        cellV > 4.175 -> 100.0
        cellV > 3.4 -> (cellV - 3.325) / 0.0085
        cellV > 3.2 -> (cellV - 3.2) / 0.0225
        else -> 0.0
    }.coerceIn(0.0, 100.0)

    fun percentFromPackVoltage(packV: Double, cells: Int): Double =
        percentFromCellVoltage(packV / cells)

    /**
     * Picks the smallest known series count that keeps the cell voltage at or below 4.25 V.
     * Ambiguous for a nearly empty pack (80 V could be a flat 24S or a healthy 20S), so it is only
     * a last resort and the UI asks the rider to confirm.
     */
    fun guessCells(packV: Double): Int? {
        if (packV < 40.0) return null
        return KNOWN_SERIES.firstOrNull { packV / it <= 4.25 && packV / it >= 2.9 }
    }

    /** Snaps a measured ratio (e.g. true voltage / 16S-scaled voltage × 16) to a real series count. */
    fun snapCells(estimate: Double): Int? {
        val nearest = KNOWN_SERIES.minByOrNull { kotlin.math.abs(it - estimate) } ?: return null
        return nearest.takeIf { kotlin.math.abs(it - estimate) <= 1.0 }
    }

    fun percentInt(value: Double): Int = value.roundToInt().coerceIn(0, 100)
}
