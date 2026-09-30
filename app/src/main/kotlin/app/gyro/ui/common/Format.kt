package app.gyro.ui.common

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** Number formatting for the dashboard: fixed decimals, "—" for unknown values. */
object Fmt {
    private val locale: Locale = Locale.forLanguageTag("ru-RU")

    fun int(v: Double?): String = v?.roundToInt()?.toString() ?: "—"

    fun one(v: Double?): String = v?.let { String.format(locale, "%.1f", it) } ?: "—"

    fun two(v: Double?): String = v?.let { String.format(locale, "%.2f", it) } ?: "—"

    fun km(meters: Long?): String = meters?.let { String.format(locale, "%.1f", it / 1000.0) } ?: "—"

    /** 950 → "950", 1530 → "1,53 к" for compact tiles. */
    fun watts(w: Double?): String = when {
        w == null -> "—"
        abs(w) >= 10_000 -> String.format(locale, "%.1f к", w / 1000)
        else -> w.roundToInt().toString()
    }

    fun wh(v: Double): String = if (abs(v) >= 100) v.roundToInt().toString() else String.format(locale, "%.1f", v)

    fun duration(seconds: Long): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        return if (h > 0) "$h ч $m мин" else "$m мин"
    }
}
