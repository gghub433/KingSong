package app.gyro.protocol

/** One parameter change shown to the rider as "было 45 → станет 50". */
data class SettingChange(val param: WheelParam, val from: Int?, val to: Int) {
    override fun toString(): String = "${param.label}: ${from ?: "—"} → $to ${param.unit}".trim()
}

/**
 * Values Gyro saw on the wheel before it ever wrote to it. Called "factory backup" in the UI;
 * if the rider changed settings with another app earlier, these are the values as first seen.
 */
data class FactoryBackup(val wheelId: String, val values: Map<WheelParam, Int>, val capturedAtMs: Long)

sealed interface WriteBlocker {
    val message: String

    data object NoFactoryBackup : WriteBlocker {
        override val message = "Нет резервной копии исходных настроек. Подключитесь к колесу, чтобы Gyro её сохранил."
    }

    data class LowBattery(val percent: Double?) : WriteBlocker {
        override val message = if (percent == null) {
            "Заряд колеса неизвестен. Запись настроек разрешена только при заряде от ${SettingsWriteGuard.MIN_BATTERY_PERCENT}%."
        } else {
            "Заряд ${percent.toInt()}% — запись настроек разрешена только от ${SettingsWriteGuard.MIN_BATTERY_PERCENT}%."
        }
    }

    data class Unsupported(val param: WheelParam) : WriteBlocker {
        override val message = "${param.label}: колесо не поддерживает запись этого параметра."
    }

    data object NothingToWrite : WriteBlocker {
        override val message = "Значения не изменились."
    }
}

/** A value above what the wheel had originally. Always shown; there is no "don't show again". */
data class AboveStockWarning(val param: WheelParam, val stock: Int, val requested: Int) {
    val message: String
        get() = "${param.label}: $requested ${param.unit} выше исходного значения $stock ${param.unit}.".replace("  ", " ")
}

data class WriteReview(
    val wheelId: String,
    val changes: List<SettingChange>,
    val target: Map<WheelParam, Int>,
    val blockers: List<WriteBlocker>,
    val warnings: List<AboveStockWarning>,
) {
    val canProceed: Boolean get() = blockers.isEmpty()
}

/** Proof that a write passed every safety rule. Only [SettingsWriteGuard] can create one. */
class SettingsWritePermit internal constructor(
    val wheelId: String,
    val changes: List<SettingChange>,
    /** Full desired values (unchanged parameters included) for protocols that write several at once. */
    val target: Map<WheelParam, Int>,
)

class SettingsRejectedException(message: String) : IllegalStateException(message)

/**
 * Safety rules for writing wheel settings:
 * - a backup of the original values must exist before the first write;
 * - battery must be at least [MIN_BATTERY_PERCENT] (a brown-out mid-write can corrupt settings);
 * - every change is listed as a diff and must be confirmed one by one;
 * - values above the original always produce a warning the rider must acknowledge.
 */
object SettingsWriteGuard {
    const val MIN_BATTERY_PERCENT = 50

    fun review(
        wheelId: String,
        current: Map<WheelParam, Int>,
        desired: Map<WheelParam, Int>,
        writable: Set<WheelParam>,
        backup: FactoryBackup?,
        batteryPercent: Double?,
    ): WriteReview {
        val changes = desired
            .filter { (param, value) -> current[param] != value }
            .map { (param, value) -> SettingChange(param, current[param], value) }
        val blockers = buildList {
            if (backup == null || backup.wheelId != wheelId || backup.values.isEmpty()) add(WriteBlocker.NoFactoryBackup)
            if (batteryPercent == null || batteryPercent < MIN_BATTERY_PERCENT) add(WriteBlocker.LowBattery(batteryPercent))
            changes.filter { it.param !in writable }.forEach { add(WriteBlocker.Unsupported(it.param)) }
            if (changes.isEmpty()) add(WriteBlocker.NothingToWrite)
        }
        val warnings = changes.mapNotNull { change ->
            val stock = backup?.values?.get(change.param) ?: return@mapNotNull null
            if (change.param.limitType && change.to > stock) AboveStockWarning(change.param, stock, change.to) else null
        }
        return WriteReview(wheelId, changes, current + desired, blockers, warnings)
    }

    /**
     * Issues a permit only if nothing blocks the write, the rider ticked every change in the diff
     * and acknowledged every above-stock warning.
     */
    fun authorize(
        review: WriteReview,
        confirmedChanges: Set<SettingChange>,
        acknowledgedWarnings: Set<AboveStockWarning>,
    ): SettingsWritePermit {
        review.blockers.firstOrNull()?.let { throw SettingsRejectedException(it.message) }
        val unconfirmed = review.changes.filter { it !in confirmedChanges }
        if (unconfirmed.isNotEmpty()) {
            throw SettingsRejectedException("Не подтверждено: ${unconfirmed.joinToString()}")
        }
        val unacknowledged = review.warnings.filter { it !in acknowledgedWarnings }
        if (unacknowledged.isNotEmpty()) {
            throw SettingsRejectedException(unacknowledged.first().message)
        }
        return SettingsWritePermit(review.wheelId, review.changes, review.target)
    }

    /** Restoring the backup is a normal write, so it passes the same checks. */
    fun restoreReview(
        wheelId: String,
        current: Map<WheelParam, Int>,
        writable: Set<WheelParam>,
        backup: FactoryBackup,
        batteryPercent: Double?,
    ): WriteReview = review(
        wheelId = wheelId,
        current = current,
        desired = backup.values.filterKeys { it in writable },
        writable = writable,
        backup = backup,
        batteryPercent = batteryPercent,
    )
}
