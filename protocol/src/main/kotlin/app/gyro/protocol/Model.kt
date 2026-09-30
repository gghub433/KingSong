package app.gyro.protocol

import java.util.UUID
import kotlin.math.abs

/** Manufacturer shown to the rider. Several brands share one wire protocol. */
enum class WheelBrand(val displayName: String) {
    KINGSONG("KingSong"),
    ROCKWHEEL("Rockwheel"),
    BEGODE("Begode"),
    EXTREME_BULL("Extreme Bull"),
    VETERAN("Leaperkim Veteran"),
    NOSFET("Nosfet"),
    INMOTION("InMotion"),
    NINEBOT("Ninebot"),
    UNKNOWN("Неизвестно"),
}

/**
 * BLE GATT layout used to reach the wheel's serial stream. The wheel protocol is chosen after
 * connecting, because KingSong, Begode and Veteran all expose the same HM-10 style service.
 */
enum class BleTransport(
    val notifyService: UUID,
    val notifyCharacteristic: UUID,
    val writeService: UUID,
    val writeCharacteristic: UUID,
    /** Some controllers drop bytes when a write exceeds 20 bytes; they need chunking with a pause. */
    val chunkDelayMs: Long,
) {
    HM10_UART(
        notifyService = uuid16(0xFFE0), notifyCharacteristic = uuid16(0xFFE1),
        writeService = uuid16(0xFFE0), writeCharacteristic = uuid16(0xFFE1),
        chunkDelayMs = 0,
    ),
    NORDIC_UART(
        notifyService = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e"),
        notifyCharacteristic = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e"),
        writeService = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e"),
        writeCharacteristic = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e"),
        chunkDelayMs = 0,
    ),
    INMOTION_V1(
        notifyService = uuid16(0xFFE0), notifyCharacteristic = uuid16(0xFFE4),
        writeService = uuid16(0xFFE5), writeCharacteristic = uuid16(0xFFE9),
        chunkDelayMs = 20,
    );

    companion object {
        val CLIENT_CONFIG_DESCRIPTOR: UUID = uuid16(0x2902)
    }
}

/** Expands a 16-bit Bluetooth SIG short UUID. */
fun uuid16(short: Int): UUID = UUID.fromString("0000%04x-0000-1000-8000-00805f9b34fb".format(short))

/** Wire protocol implemented by a decoder. */
enum class ProtocolFamily(val label: String, val transport: BleTransport) {
    KINGSONG("KingSong", BleTransport.HM10_UART),
    BEGODE("Begode / Gotway / Extreme Bull", BleTransport.HM10_UART),
    VETERAN("Leaperkim Veteran / Nosfet", BleTransport.HM10_UART),
    INMOTION_V1("InMotion V5/V8/V10 (старый протокол)", BleTransport.INMOTION_V1),
    INMOTION_V2("InMotion V9–V14 и новее", BleTransport.NORDIC_UART),
    NINEBOT_Z("Ninebot Z", BleTransport.NORDIC_UART),
}

/**
 * How much the packet layout for the connected model can be trusted. Layouts are taken from the
 * open-source WheelLog project; models WheelLog itself marks as "not sure" are [EXPERIMENTAL].
 */
enum class SupportLevel(val label: String) {
    KNOWN("Разбор пакетов проверен сообществом"),
    EXPERIMENTAL("Экспериментально: часть полей не подтверждена"),
    FALLBACK("Новая/неизвестная модель: разбор по ближайшему известному формату"),
}

enum class Capability {
    BEEP, LIGHT, LOCK,
    HARDWARE_PWM, BATTERY_CURRENT, BATTERY_POWER, BMS_CELLS, WHEEL_RANGE,
    READ_SETTINGS,
}

/**
 * Where the series cell count ("20S", "30S") came from; it drives battery % and per-cell voltage.
 * Declared from most to least trustworthy.
 */
enum class CellsSource(val label: String) {
    USER("задано вручную"),
    MEASURED("измерено"),
    BMS("по данным BMS"),
    MODEL_TABLE("по модели"),
    VOLTAGE_GUESS("оценка по напряжению"),
    DEFAULT_GUESS("предположение"),
}

data class WheelInfo(
    val brand: WheelBrand = WheelBrand.UNKNOWN,
    val family: ProtocolFamily? = null,
    val model: String? = null,
    val name: String? = null,
    val serial: String? = null,
    val firmware: String? = null,
    val cellsSeries: Int? = null,
    val cellsSource: CellsSource? = null,
    val supportLevel: SupportLevel = SupportLevel.KNOWN,
    val capabilities: Set<Capability> = emptySet(),
) {
    val displayName: String
        get() = listOfNotNull(
            brand.takeIf { it != WheelBrand.UNKNOWN }?.displayName,
            model?.takeIf { m -> !m.startsWith(brand.displayName, ignoreCase = true) },
        ).joinToString(" ").ifBlank { name ?: "Моноколесо" }

    val nominalVoltage: Double? get() = cellsSeries?.let { it * 3.6 }
    val fullVoltage: Double? get() = cellsSeries?.let { it * 4.2 }
}

/**
 * One decoded telemetry sample. Units are SI-like doubles; null means the wheel does not report
 * the value. Current sign convention: positive = energy leaves the battery, negative = energy
 * flows into it (regenerative braking or charging).
 */
data class Telemetry(
    val timestampMs: Long = 0,
    val speedKmh: Double = 0.0,
    val voltageV: Double = 0.0,
    val batteryCurrentA: Double? = null,
    /** True when [batteryCurrentA] is derived from phase current × PWM, not measured. */
    val batteryCurrentEstimated: Boolean = false,
    val phaseCurrentA: Double? = null,
    val reportedPowerW: Double? = null,
    val pwmPercent: Double? = null,
    val batteryPercent: Double? = null,
    val batteryPercentFromWheel: Boolean = false,
    val temperatureC: Double? = null,
    val temperature2C: Double? = null,
    val motorTemperatureC: Double? = null,
    val batteryTemperatureC: Double? = null,
    val tripDistanceM: Long? = null,
    val totalDistanceM: Long? = null,
    val pitchDeg: Double? = null,
    val rollDeg: Double? = null,
    val dynamicSpeedLimitKmh: Double? = null,
    val dynamicCurrentLimitA: Double? = null,
    val topSpeedKmh: Double? = null,
    val charging: Boolean? = null,
    val lightOn: Boolean? = null,
    val fanOn: Boolean? = null,
    val lifted: Boolean? = null,
    val rideModeCode: Int? = null,
    val cpuLoadPercent: Int? = null,
    val wheelRangeKm: Double? = null,
) {
    val absSpeedKmh: Double get() = abs(speedKmh)

    /** Battery power: reported by the wheel when available, otherwise voltage × battery current. */
    val powerW: Double?
        get() = reportedPowerW ?: batteryCurrentA?.let { it * voltageV }

    /** The hottest reported sensor; used by temperature alarms. */
    val maxTemperatureC: Double?
        get() = listOfNotNull(temperatureC, temperature2C, motorTemperatureC, batteryTemperatureC).maxOrNull()
}

/** One battery management board; big wheels have two packs in parallel. */
data class BmsPack(
    val index: Int,
    val cellVoltages: List<Double> = emptyList(),
    val temperaturesC: List<Double> = emptyList(),
    val mosTemperatureC: Double? = null,
    val voltageV: Double? = null,
    val currentA: Double? = null,
    val remainingCapacityMah: Int? = null,
    val factoryCapacityMah: Int? = null,
    val fullCycles: Int? = null,
    val remainingPercent: Int? = null,
    val serial: String? = null,
    val firmware: String? = null,
) {
    private val validCells: List<IndexedValue<Double>>
        get() = cellVoltages.withIndex().filter { it.value > 0.5 }

    val cellCount: Int get() = validCells.size
    val minCell: IndexedValue<Double>? get() = validCells.minByOrNull { it.value }
    val maxCell: IndexedValue<Double>? get() = validCells.maxByOrNull { it.value }
    val spreadV: Double?
        get() {
            val min = minCell ?: return null
            val max = maxCell ?: return null
            return max.value - min.value
        }
}

/** A readable/writable wheel parameter. Values are integers in the unit given by [unit]. */
enum class WheelParam(val label: String, val unit: String, val limitType: Boolean) {
    TILTBACK_SPEED("Скорость tiltback", "км/ч", limitType = true),
    ALARM_1_SPEED("Сигнал 1", "км/ч", limitType = true),
    ALARM_2_SPEED("Сигнал 2", "км/ч", limitType = true),
    ALARM_3_SPEED("Сигнал 3", "км/ч", limitType = true),
    PEDALS_MODE("Жёсткость педалей", "", limitType = false),
    LIGHT_MODE("Свет", "", limitType = false),
    LED_MODE("Подсветка", "", limitType = false),
    ALARM_MODE("Режим сигналов", "", limitType = false),
    ROLL_ANGLE("Угол крена", "", limitType = false),
    AUTO_POWER_OFF("Автовыключение", "с", limitType = false),
    SPEED_ALERT("Предупреждение скорости", "км/ч", limitType = true),
}

/** Alert reported by the wheel itself (not by Gyro's own thresholds). */
data class WheelAlert(val code: String, val text: String)

data class WheelState(
    val info: WheelInfo = WheelInfo(),
    val telemetry: Telemetry = Telemetry(),
    val bms: List<BmsPack> = emptyList(),
    val settings: Map<WheelParam, Int> = emptyMap(),
    val alerts: Set<WheelAlert> = emptySet(),
    /** False until the first live telemetry frame has been decoded. */
    val hasTelemetry: Boolean = false,
)
