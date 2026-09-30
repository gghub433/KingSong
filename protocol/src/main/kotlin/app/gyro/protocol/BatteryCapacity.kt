package app.gyro.protocol

/** Pack energy in Wh, needed to turn a battery percentage into kilometres. */
object BatteryCapacity {

    enum class Source(val label: String) { USER("задано вручную"), BMS("по данным BMS"), MODEL("по модели") }

    data class Capacity(val wh: Double, val source: Source)

    /** Published pack sizes; only models whose capacity is unambiguous (no battery options). */
    private val MODEL_WH: Map<String, Double> = mapOf(
        "KS-S18" to 1110.0, "KS-S22" to 2220.0, "KS-S20" to 2220.0, "KS-16X" to 1554.0,
        "KS-18XL" to 1554.0, "KS-18L" to 1036.0, "KS-16S" to 840.0,
        "V11" to 1500.0, "V12 HS" to 1750.0, "V12 HT" to 1750.0, "V12 Pro" to 1750.0,
        "V13" to 3024.0, "V13 Pro" to 3024.0, "V14 50GB" to 2400.0, "V14 50S" to 2400.0,
        "Sherman" to 3200.0, "Sherman S" to 3600.0, "Patton" to 2220.0, "Lynx" to 2700.0,
        "Master" to 2400.0,
    )

    fun resolve(info: WheelInfo, bms: List<BmsPack>, userWh: Double?): Capacity? {
        userWh?.takeIf { it > 0 }?.let { return Capacity(it, Source.USER) }
        val cells = info.cellsSeries
        val bmsMah = bms.mapNotNull { it.factoryCapacityMah }.filter { it > 0 }
        if (cells != null && bmsMah.isNotEmpty()) {
            // Packs are wired in parallel: capacities add up at the same voltage.
            return Capacity(bmsMah.sum() / 1000.0 * cells * 3.6, Source.BMS)
        }
        val model = info.model ?: return null
        MODEL_WH[model]?.let { return Capacity(it, Source.MODEL) }
        return MODEL_WH.entries.firstOrNull { model.equals(it.key, ignoreCase = true) }?.let { Capacity(it.value, Source.MODEL) }
    }
}
