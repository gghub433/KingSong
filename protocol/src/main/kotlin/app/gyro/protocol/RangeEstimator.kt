package app.gyro.protocol

/**
 * Remaining range from the rider's recent consumption.
 *
 * Keeps (distance, net energy) samples and measures Wh/km over the last [windowKm], so a switch from
 * relaxed cruising to hard riding shows up within a few kilometres. Until enough distance is
 * covered, the estimate blends towards [defaultWhPerKm] (a typical value for a mid-size wheel).
 */
class RangeEstimator(
    private val windowKm: Double = 3.0,
    private val defaultWhPerKm: Double = 22.0,
    /** Energy kept in reserve: wheels tilt back or cut power well before 0%. */
    private val reservePercent: Double = 10.0,
) {
    private val samples = ArrayDeque<Pair<Double, Double>>() // cumulative km to cumulative net Wh

    fun reset() = samples.clear()

    fun add(stats: EnergyStats) {
        val km = stats.distanceKm
        val last = samples.lastOrNull()
        if (last != null && km - last.first < 0.02) return
        samples.addLast(km to stats.whNet)
        while (samples.size > 2 && km - samples[1].first >= windowKm) samples.removeFirst()
    }

    /** Wh per km at the current riding style, or null before the first 200 m. */
    fun recentWhPerKm(): Double? {
        if (samples.size < 2) return null
        val (km0, wh0) = samples.first()
        val (km1, wh1) = samples.last()
        val km = km1 - km0
        if (km < 0.2) return null
        val measured = ((wh1 - wh0) / km).coerceAtLeast(3.0)
        // Trust grows with distance: after `windowKm` the measurement fully wins.
        val weight = (km / windowKm).coerceIn(0.0, 1.0)
        return measured * weight + defaultWhPerKm * (1 - weight)
    }

    fun estimate(capacityWh: Double?, batteryPercent: Double?): RangeEstimate? {
        if (capacityWh == null || capacityWh <= 0 || batteryPercent == null) return null
        val usablePercent = (batteryPercent - reservePercent).coerceAtLeast(0.0)
        val remainingWh = capacityWh * usablePercent / 100.0
        val rate = recentWhPerKm()
        return RangeEstimate(
            remainingWh = remainingWh,
            whPerKm = rate ?: defaultWhPerKm,
            km = remainingWh / (rate ?: defaultWhPerKm),
            basedOnRiding = rate != null,
        )
    }

    /** Can the rider reach a point [distanceKm] away (calculator for a map destination)? */
    fun canReach(estimate: RangeEstimate, distanceKm: Double): Boolean = estimate.km >= distanceKm * 1.1
}

data class RangeEstimate(
    val remainingWh: Double,
    val whPerKm: Double,
    val km: Double,
    /** False while the default consumption is used (not enough riding yet). */
    val basedOnRiding: Boolean,
)
