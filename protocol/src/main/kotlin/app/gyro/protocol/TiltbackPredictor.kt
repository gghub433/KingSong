package app.gyro.protocol

import kotlin.math.abs

/**
 * Warns before the wheel pushes the pedals back (tiltback) or runs out of power headroom.
 *
 * Two signals: PWM (duty cycle; at ~100% the motor cannot push harder and the wheel can cut out) and
 * speed approaching the configured tiltback speed. PWM is also projected [horizonMs] ahead from its
 * recent slope, so a hard acceleration or a steep climb warns before the threshold is crossed.
 */
class TiltbackPredictor(var config: Config = Config()) {

    data class Config(
        val pwmCaution: Double = 70.0,
        val pwmWarning: Double = 80.0,
        val pwmCritical: Double = 90.0,
        val speedMarginKmh: Double = 3.0,
        val horizonMs: Long = 1_500,
    )

    enum class Level { NORMAL, CAUTION, WARNING, CRITICAL }

    enum class Reason { PWM, PWM_TREND, SPEED }

    data class Assessment(
        val level: Level,
        val reason: Reason?,
        val pwmPercent: Double?,
        val projectedPwmPercent: Double?,
        /** Speed at which the wheel will tilt back, when known. */
        val tiltbackSpeedKmh: Double?,
        /** How much PWM headroom is left (100 − PWM). */
        val headroomPercent: Double?,
    )

    private val history = ArrayDeque<Pair<Long, Double>>()

    fun reset() = history.clear()

    fun assess(t: Telemetry, tiltbackSpeedKmh: Double?): Assessment {
        val pwm = t.pwmPercent?.let { abs(it) }
        if (pwm != null) {
            history.addLast(t.timestampMs to pwm)
            while (history.size > 2 && t.timestampMs - history.first().first > 1_000) history.removeFirst()
        }
        val projected = pwm?.let { current -> slopePerMs()?.let { current + it * config.horizonMs } }

        var level = Level.NORMAL
        var reason: Reason? = null
        fun raise(candidate: Level, why: Reason) {
            if (candidate.ordinal > level.ordinal) {
                level = candidate
                reason = why
            }
        }

        if (pwm != null) {
            when {
                pwm >= config.pwmCritical -> raise(Level.CRITICAL, Reason.PWM)
                pwm >= config.pwmWarning -> raise(Level.WARNING, Reason.PWM)
                pwm >= config.pwmCaution -> raise(Level.CAUTION, Reason.PWM)
            }
            // Only escalate on the trend when PWM is already meaningful, to ignore bumps at walking pace.
            if (projected != null && pwm >= config.pwmCaution - 15) {
                when {
                    projected >= config.pwmCritical -> raise(Level.WARNING, Reason.PWM_TREND)
                    projected >= config.pwmWarning -> raise(Level.CAUTION, Reason.PWM_TREND)
                }
            }
        }
        val validTiltback = tiltbackSpeedKmh?.takeIf { it > 5 }
        if (validTiltback != null) {
            val gap = validTiltback - t.absSpeedKmh
            when {
                gap <= 0 -> raise(Level.CRITICAL, Reason.SPEED)
                gap <= config.speedMarginKmh -> raise(Level.WARNING, Reason.SPEED)
                gap <= config.speedMarginKmh * 2 -> raise(Level.CAUTION, Reason.SPEED)
            }
        }
        return Assessment(level, reason, pwm, projected, validTiltback, pwm?.let { 100 - it })
    }

    /** Least-squares slope of PWM over the last second, in percent per millisecond. */
    private fun slopePerMs(): Double? {
        if (history.size < 3) return null
        val t0 = history.first().first
        val span = history.last().first - t0
        if (span < 300) return null
        val n = history.size
        val meanX = history.sumOf { (it.first - t0).toDouble() } / n
        val meanY = history.sumOf { it.second } / n
        var num = 0.0
        var den = 0.0
        for ((t, y) in history) {
            val dx = (t - t0) - meanX
            num += dx * (y - meanY)
            den += dx * dx
        }
        return if (den > 0) num / den else null
    }
}
