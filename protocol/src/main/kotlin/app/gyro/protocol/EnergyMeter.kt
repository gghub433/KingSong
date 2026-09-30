package app.gyro.protocol

import kotlin.math.max
import kotlin.math.min

/**
 * Energy flowing out of and back into the battery during a session.
 *
 * "Out" is energy the motor draws; "in" is energy returned by regenerative braking (downhill,
 * slowing down) and, separately, by a charger while parked. Integrates power over time with the
 * trapezoid rule and ignores gaps longer than [maxGapMs] (a dropped connection is not a ride).
 */
class EnergyMeter(private val maxGapMs: Long = 2_000) {

    private var lastMs = -1L
    private var lastPowerW = 0.0
    private var lastCurrentA = 0.0
    private var lastSpeedKmh = 0.0
    private var lastOdometerM: Long? = null

    private var whOut = 0.0
    private var whRegen = 0.0
    private var whCharged = 0.0
    private var ahOut = 0.0
    private var ahIn = 0.0
    private var distanceM = 0.0
    private var peakPowerW = 0.0
    private var peakRegenW = 0.0
    private var movingMs = 0L

    fun reset() {
        lastMs = -1
        lastOdometerM = null
        whOut = 0.0; whRegen = 0.0; whCharged = 0.0
        ahOut = 0.0; ahIn = 0.0
        distanceM = 0.0
        peakPowerW = 0.0; peakRegenW = 0.0
        movingMs = 0
    }

    fun add(t: Telemetry) {
        val power = t.powerW ?: return
        val current = t.batteryCurrentA ?: (if (t.voltageV > 1) power / t.voltageV else 0.0)
        val now = t.timestampMs
        val odometer = t.totalDistanceM
        if (lastMs >= 0) {
            val dt = now - lastMs
            if (dt in 1..maxGapMs) {
                val hours = dt / 3_600_000.0
                val parkedOnCharger = t.charging == true && t.absSpeedKmh < 1.0
                accumulateEnergy((lastPowerW + power) / 2.0 * hours, parkedOnCharger)
                accumulateCharge((lastCurrentA + current) / 2.0 * hours)
                distanceM += distanceStep(odometer, dt, t.absSpeedKmh)
                if (t.absSpeedKmh > 2.0) movingMs += dt
            }
        }
        peakPowerW = max(peakPowerW, power)
        peakRegenW = max(peakRegenW, -min(power, 0.0))
        lastMs = now
        lastPowerW = power
        lastCurrentA = current
        lastSpeedKmh = t.absSpeedKmh
        if (odometer != null && odometer > 0) lastOdometerM = odometer
    }

    private fun accumulateEnergy(wh: Double, parkedOnCharger: Boolean) {
        when {
            wh >= 0 -> whOut += wh
            parkedOnCharger -> whCharged += -wh
            else -> whRegen += -wh
        }
    }

    private fun accumulateCharge(ah: Double) {
        if (ah >= 0) ahOut += ah else ahIn += -ah
    }

    /**
     * Prefers the wheel odometer; falls back to integrating speed when the odometer is missing or
     * jumps (resets, rollover, glitches).
     */
    private fun distanceStep(odometer: Long?, dtMs: Long, speedKmh: Double): Double {
        val integrated = (lastSpeedKmh + speedKmh) / 2.0 / 3.6 * dtMs / 1000.0
        val previous = lastOdometerM
        if (odometer == null || previous == null || odometer <= 0) return integrated
        val delta = odometer - previous
        val plausible = delta >= 0 && delta <= max(50.0, integrated * 3 + 10)
        return if (plausible) delta.toDouble() else integrated
    }

    val stats: EnergyStats
        get() = EnergyStats(
            whOut = whOut,
            whRegen = whRegen,
            whCharged = whCharged,
            ahOut = ahOut,
            ahIn = ahIn,
            distanceKm = distanceM / 1000.0,
            peakPowerW = peakPowerW,
            peakRegenW = peakRegenW,
            movingSeconds = movingMs / 1000,
        )
}

data class EnergyStats(
    val whOut: Double = 0.0,
    val whRegen: Double = 0.0,
    val whCharged: Double = 0.0,
    val ahOut: Double = 0.0,
    val ahIn: Double = 0.0,
    val distanceKm: Double = 0.0,
    val peakPowerW: Double = 0.0,
    val peakRegenW: Double = 0.0,
    val movingSeconds: Long = 0,
) {
    /** Energy actually taken from the pack by riding. */
    val whNet: Double get() = whOut - whRegen

    /** Share of spent energy won back by braking. */
    val regenPercent: Double? get() = if (whOut > 1) whRegen / whOut * 100 else null

    val whPerKm: Double? get() = if (distanceKm > 0.2) whNet / distanceKm else null

    val averageSpeedKmh: Double? get() = if (movingSeconds > 30) distanceKm / (movingSeconds / 3600.0) else null
}
