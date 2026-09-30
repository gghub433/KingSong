package app.gyro.protocol

import kotlin.math.abs

enum class AlarmMetric(val label: String, val unit: String, val defaultAbove: Boolean) {
    SPEED("Скорость", "км/ч", true),
    PWM("Нагрузка (PWM)", "%", true),
    TEMPERATURE("Температура", "°C", true),
    CURRENT("Ток", "А", true),
    POWER("Мощность", "Вт", true),
    BATTERY("Заряд", "%", false),
    VOLTAGE("Напряжение", "В", false),
    ;

    fun read(t: Telemetry): Double? = when (this) {
        SPEED -> t.absSpeedKmh
        PWM -> t.pwmPercent?.let { abs(it) }
        TEMPERATURE -> t.maxTemperatureC
        CURRENT -> t.batteryCurrentA?.let { abs(it) } ?: t.phaseCurrentA?.let { abs(it) }
        POWER -> t.powerW?.let { abs(it) }
        BATTERY -> t.batteryPercent
        VOLTAGE -> t.voltageV.takeIf { it > 0 }
    }
}

/**
 * A rider-defined threshold, shown on screen while it holds. [repeatSeconds] = 0 fires one event
 * per crossing.
 */
data class AlarmRule(
    val id: Long,
    val metric: AlarmMetric,
    val threshold: Double,
    val above: Boolean = metric.defaultAbove,
    val repeatSeconds: Int = 0,
    val hysteresis: Double = defaultHysteresis(metric),
    val enabled: Boolean = true,
    val label: String? = null,
) {
    val title: String
        get() = label ?: "${metric.label} ${if (above) ">" else "<"} ${formatNumber(threshold)} ${metric.unit}"

    companion object {
        fun defaultHysteresis(metric: AlarmMetric) = when (metric) {
            AlarmMetric.SPEED -> 2.0
            AlarmMetric.PWM -> 5.0
            AlarmMetric.TEMPERATURE -> 3.0
            AlarmMetric.CURRENT -> 5.0
            AlarmMetric.POWER -> 200.0
            AlarmMetric.BATTERY -> 2.0
            AlarmMetric.VOLTAGE -> 1.0
        }

        fun formatNumber(v: Double): String = if (v == v.toLong().toDouble()) v.toLong().toString() else "%.1f".format(v)
    }
}

data class AlarmEvent(val rule: AlarmRule, val value: Double, val firstTrigger: Boolean) {
    /** Short text for a notification, e.g. "Скорость 42". */
    val message: String
        get() = rule.label ?: "${rule.metric.label} ${value.toInt()}"
}

/**
 * Edge-triggered threshold alarms with hysteresis (no flapping around the threshold) and optional
 * repetition while the condition holds.
 */
class AlarmEngine {
    private data class RuleState(var active: Boolean = false, var lastFiredMs: Long = 0)

    private val states = mutableMapOf<Long, RuleState>()

    fun reset() = states.clear()

    /** Rules whose condition holds right now (until the value moves back past the hysteresis). */
    val activeRuleIds: Set<Long>
        get() = states.filterValues { it.active }.keys.toSet()

    fun evaluate(rules: List<AlarmRule>, t: Telemetry, nowMs: Long): List<AlarmEvent> {
        val events = mutableListOf<AlarmEvent>()
        states.keys.retainAll(rules.map { it.id }.toSet())
        for (rule in rules) {
            if (!rule.enabled) {
                states.remove(rule.id)
                continue
            }
            val value = rule.metric.read(t) ?: continue
            val state = states.getOrPut(rule.id) { RuleState() }
            val triggered = if (rule.above) value >= rule.threshold else value <= rule.threshold
            val cleared = if (rule.above) value < rule.threshold - rule.hysteresis else value > rule.threshold + rule.hysteresis
            when {
                !state.active && triggered -> {
                    state.active = true
                    state.lastFiredMs = nowMs
                    events += AlarmEvent(rule, value, firstTrigger = true)
                }
                state.active && cleared -> state.active = false
                state.active && rule.repeatSeconds > 0 && nowMs - state.lastFiredMs >= rule.repeatSeconds * 1000L -> {
                    state.lastFiredMs = nowMs
                    events += AlarmEvent(rule, value, firstTrigger = false)
                }
            }
        }
        return events
    }

    companion object {
        /** Built-in defaults for a new install; the rider edits or removes them on the dashboard. */
        val DEFAULT_RULES = listOf(
            AlarmRule(id = 1, metric = AlarmMetric.PWM, threshold = 80.0, label = "Нагрузка высокая"),
            AlarmRule(id = 2, metric = AlarmMetric.TEMPERATURE, threshold = 70.0),
            AlarmRule(id = 3, metric = AlarmMetric.BATTERY, threshold = 30.0, above = false, label = "Поставь колесо на зарядку"),
        )
    }
}
