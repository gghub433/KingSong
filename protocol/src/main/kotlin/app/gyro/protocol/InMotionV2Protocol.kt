package app.gyro.protocol

import app.gyro.protocol.Bytes.ascii
import app.gyro.protocol.Bytes.revLe32
import app.gyro.protocol.Bytes.s16le
import app.gyro.protocol.Bytes.u16le
import app.gyro.protocol.Bytes.u32le
import app.gyro.protocol.Bytes.u8

/**
 * InMotion V9, V11, V11Y, V12 (HS/HT/Pro), V12S, V13 (Pro), V14 and newer models on the Nordic UART
 * service.
 *
 * Frames: `AA AA <flags> <len> <cmd> <data…> <xor>`, where 0xAA and 0xA5 inside the body are escaped
 * with 0xA5. The wheel only talks when polled. Each model generation moved the real-time fields, so
 * the layout is chosen by the model id; unknown (newer) ids use the newest known layout and are
 * flagged as a fallback. Layouts follow WheelLog's InmotionAdapterV2.
 */
class InMotionV2Protocol(bleName: String?) : BaseProtocol(ProtocolFamily.INMOTION_V2, bleName) {

    enum class Model(val id: Int, val title: String, val layout: Layout, val cells: Int?, val experimental: Boolean) {
        V11(61, "V11", Layout.V11, 20, false),
        V11Y(62, "V11Y", Layout.MODERN, null, true),
        V12HS(71, "V12 HS", Layout.V12, 24, false),
        V12HT(72, "V12 HT", Layout.V12, 24, false),
        V12PRO(73, "V12 Pro", Layout.V12, 24, false),
        V13(81, "V13", Layout.V13, 30, true),
        V13PRO(82, "V13 Pro", Layout.V13, 30, true),
        V14G(91, "V14 50GB", Layout.MODERN, null, true),
        V14S(92, "V14 50S", Layout.MODERN, null, true),
        V12S(111, "V12S", Layout.MODERN, null, true),
        V9(121, "V9", Layout.MODERN, null, true),
        ;

        companion object {
            fun byId(id: Int): Model? = entries.firstOrNull { it.id == id }
        }
    }

    enum class Layout { V11, V12, V13, MODERN }

    private enum class Stage { CAR_TYPE, SERIAL, VERSIONS, RUN }

    private val unpacker = Unpacker()
    private var model: Model? = null
    private var layout: Layout = Layout.MODERN
    private var legacyV11 = false // V11 main board < 1.4 has its own real-time layout and command codes
    private var stage = Stage.CAR_TYPE
    private var stageAttempts = 0
    private var lastRequestMs = 0L
    private var lastStatsMs = 0L
    private var answered = true

    init {
        info = info.copy(
            brand = WheelBrand.INMOTION,
            capabilities = setOf(
                Capability.BEEP, Capability.LIGHT, Capability.LOCK, Capability.HARDWARE_PWM,
                Capability.BATTERY_CURRENT, Capability.BATTERY_POWER, Capability.WHEEL_RANGE,
            ),
        )
    }

    override fun onConnected(nowMs: Long): List<Outbound> {
        lastRequestMs = nowMs
        answered = false
        return listOf(Outbound(Message.carType()))
    }

    override fun poll(nowMs: Long): List<Outbound> {
        val elapsed = nowMs - lastRequestMs
        // Request as soon as the previous answer arrived (min 25 ms apart), or retry after 250 ms.
        if (!((answered && elapsed >= 25) || elapsed >= 250)) return emptyList()
        if (stage != Stage.RUN && stageAttempts >= 8) advanceStage()
        val frame = when (stage) {
            Stage.CAR_TYPE -> Message.carType()
            Stage.SERIAL -> Message.serial()
            Stage.VERSIONS -> Message.versions()
            Stage.RUN -> if (nowMs - lastStatsMs > 5_000) {
                lastStatsMs = nowMs
                Message.totalStats()
            } else {
                Message.realtime()
            }
        }
        if (stage != Stage.RUN) stageAttempts++
        lastRequestMs = nowMs
        answered = false
        return listOf(Outbound(frame))
    }

    private fun advanceStage() {
        stage = when (stage) {
            Stage.CAR_TYPE -> Stage.SERIAL
            Stage.SERIAL -> Stage.VERSIONS
            Stage.VERSIONS, Stage.RUN -> Stage.RUN
        }
        stageAttempts = 0
    }

    override fun decode(chunk: ByteArray, nowMs: Long): DecodeResult {
        var updated = false
        for (b in chunk) {
            val message = unpacker.add(b) ?: continue
            answered = true
            updated = handle(message, nowMs) || updated
        }
        return DecodeResult(updated)
    }

    private fun handle(m: Message, nowMs: Long): Boolean {
        val d = m.data
        when {
            m.flags == Message.FLAG_INITIAL && m.command == Message.CMD_MAIN_INFO -> when (u8(d, 0)) {
                0x01 -> if (d.size >= 4) {
                    onModel(u8(d, 2) * 10 + u8(d, 3))
                    if (stage == Stage.CAR_TYPE) advanceStage()
                }
                0x02 -> if (d.size >= 17) {
                    info = info.copy(serial = ascii(d, 1, 16))
                    if (stage == Stage.SERIAL) advanceStage()
                }
                0x06 -> if (d.size >= 24) {
                    val major = u8(d, 14)
                    val minor = u8(d, 13)
                    val patch = u16le(d, 11)
                    info = info.copy(firmware = "$major.$minor.$patch")
                    if (model == Model.V11) {
                        legacyV11 = major < 2 && minor < 4
                    }
                    if (stage == Stage.VERSIONS) advanceStage()
                }
            }
            m.flags == Message.FLAG_DEFAULT && m.command == Message.CMD_REALTIME -> {
                if (stage != Stage.RUN) stage = Stage.RUN
                return parseRealtime(d, nowMs)
            }
            m.flags == Message.FLAG_DEFAULT && m.command == Message.CMD_TOTAL_STATS -> if (d.size >= 20) {
                telemetry = telemetry.copy(totalDistanceM = u32le(d, 0) * 10)
            }
        }
        return false
    }

    private fun onModel(id: Int) {
        val known = Model.byId(id)
        model = known
        layout = known?.layout ?: Layout.MODERN
        info = info.copy(
            model = known?.title ?: "Модель $id",
            supportLevel = when {
                known == null -> SupportLevel.FALLBACK
                known.experimental -> SupportLevel.EXPERIMENTAL
                else -> SupportLevel.KNOWN
            },
        )
        known?.cells?.let { updateCells(it, CellsSource.MODEL_TABLE) }
    }

    private fun parseRealtime(d: ByteArray, nowMs: Long): Boolean {
        // Minimum payload that holds every field read below; the error bitmap after it is optional.
        val needed = when {
            layout == Layout.V11 && legacyV11 -> 40
            layout == Layout.V11 -> 58
            layout == Layout.V12 -> 56
            else -> 77
        }
        if (d.size < needed) return false
        val voltage = u16le(d, 0) / 100.0
        guessCellsFromVoltage(voltage)
        val current = s16le(d, 2) / 100.0
        val sample = when {
            layout == Layout.V11 && legacyV11 -> parseV11Legacy(d)
            layout == Layout.V11 -> parseV11(d)
            layout == Layout.V12 -> parseV12(d)
            layout == Layout.V13 -> parseV13(d)
            else -> parseModern(d)
        }
        publish(
            sample.copy(
                timestampMs = nowMs,
                voltageV = voltage,
                batteryCurrentA = current,
                batteryCurrentEstimated = false,
                batteryPercentFromWheel = true,
                totalDistanceM = telemetry.totalDistanceM,
            ),
        )
        return true
    }

    private fun parseV11Legacy(d: ByteArray): Telemetry {
        val stateAt = if (d.size < 49) 36 else 38
        alerts = decodeErrors(d, stateAt + 5)
        return Telemetry(
            speedKmh = s16le(d, 4) / 100.0,
            reportedPowerW = s16le(d, 8).toDouble(),
            tripDistanceM = u16le(d, 12) * 10L,
            wheelRangeKm = u16le(d, 14) * 10 / 1000.0,
            batteryPercent = (u8(d, 16) and 0x7F).toDouble(),
            temperatureC = temp(d, 17),
            motorTemperatureC = temp(d, 18),
            batteryTemperatureC = temp(d, 19),
            temperature2C = temp(d, 20),
            pitchDeg = s16le(d, 22) / 100.0,
            rollDeg = s16le(d, 26) / 100.0,
            dynamicSpeedLimitKmh = u16le(d, 28) / 100.0,
            dynamicCurrentLimitA = u16le(d, 30) / 100.0,
            pwmPercent = u16le(d, 36) / 100.0,
            charging = (u8(d, stateAt) shr 7) and 1 == 1,
            lightOn = u8(d, stateAt + 1) and 1 == 1,
            lifted = (u8(d, stateAt + 1) shr 2) and 1 == 1,
        )
    }

    private fun parseV11(d: ByteArray): Telemetry {
        alerts = decodeErrors(d, 61)
        return Telemetry(
            speedKmh = s16le(d, 4) / 100.0,
            pwmPercent = s16le(d, 8) / 100.0,
            reportedPowerW = s16le(d, 10).toDouble(),
            pitchDeg = s16le(d, 16) / 100.0,
            rollDeg = s16le(d, 20) / 100.0,
            tripDistanceM = u16le(d, 26) * 10L,
            batteryPercent = u16le(d, 28) / 100.0,
            wheelRangeKm = u16le(d, 30) * 10 / 1000.0,
            dynamicSpeedLimitKmh = u16le(d, 34) / 100.0,
            dynamicCurrentLimitA = u16le(d, 36) / 100.0,
            temperatureC = temp(d, 42),
            motorTemperatureC = temp(d, 43),
            batteryTemperatureC = temp(d, 44),
            temperature2C = temp(d, 45),
            charging = (u8(d, 56) shr 7) and 1 == 1,
            lightOn = u8(d, 57) and 1 == 1,
            lifted = (u8(d, 57) shr 2) and 1 == 1,
        )
    }

    private fun parseV12(d: ByteArray): Telemetry {
        alerts = decodeErrors(d, 59)
        return Telemetry(
            speedKmh = s16le(d, 4) / 100.0,
            pwmPercent = s16le(d, 8) / 100.0,
            reportedPowerW = s16le(d, 10).toDouble(),
            pitchDeg = s16le(d, 16) / 100.0,
            rollDeg = s16le(d, 20) / 100.0,
            tripDistanceM = u16le(d, 22) * 10L,
            batteryPercent = u16le(d, 24) / 100.0,
            wheelRangeKm = u16le(d, 26) * 10 / 1000.0,
            dynamicSpeedLimitKmh = u16le(d, 30) / 100.0,
            dynamicCurrentLimitA = u16le(d, 32) / 100.0,
            temperatureC = temp(d, 40),
            motorTemperatureC = temp(d, 41),
            batteryTemperatureC = temp(d, 42),
            temperature2C = temp(d, 43),
            charging = (u8(d, 54) shr 7) and 1 == 1,
            lightOn = u8(d, 55) and 1 == 1,
            lifted = (u8(d, 55) shr 2) and 1 == 1,
        )
    }

    private fun parseV13(d: ByteArray): Telemetry {
        alerts = decodeErrors(d, 76)
        return Telemetry(
            speedKmh = s16le(d, 8) / 100.0,
            pitchDeg = s16le(d, 6) / 100.0,
            tripDistanceM = revLe32(d, 10),
            pwmPercent = s16le(d, 14) / 100.0,
            reportedPowerW = s16le(d, 16).toDouble(),
            rollDeg = s16le(d, 24) / 100.0,
            batteryPercent = (u16le(d, 34) + u16le(d, 36)) / 200.0,
            dynamicSpeedLimitKmh = u16le(d, 40) / 100.0,
            dynamicCurrentLimitA = u16le(d, 50) / 100.0,
            temperatureC = temp(d, 58),
            motorTemperatureC = temp(d, 59),
            batteryTemperatureC = temp(d, 60),
            temperature2C = temp(d, 61),
            charging = (u8(d, 74) shr 7) and 1 == 1,
            lightOn = (u8(d, 76) shr 1) and 1 == 1,
            lifted = (u8(d, 75) shr 2) and 1 == 1,
        )
    }

    /** V14, V11Y, V9, V12S — and the default for models newer than this table. */
    private fun parseModern(d: ByteArray): Telemetry {
        alerts = decodeErrors(d, 77)
        return Telemetry(
            speedKmh = s16le(d, 8) / 100.0,
            pwmPercent = s16le(d, 14) / 100.0,
            reportedPowerW = s16le(d, 16).toDouble(),
            pitchDeg = s16le(d, 20) / 100.0,
            rollDeg = s16le(d, 22) / 100.0,
            tripDistanceM = u16le(d, 28) * 10L,
            batteryPercent = (u16le(d, 34) + u16le(d, 36)) / 200.0,
            dynamicSpeedLimitKmh = u16le(d, 40) / 100.0,
            dynamicCurrentLimitA = u16le(d, 50) / 100.0,
            temperatureC = temp(d, 58),
            motorTemperatureC = temp(d, 59),
            batteryTemperatureC = temp(d, 60),
            temperature2C = temp(d, 61),
            charging = (u8(d, 74) shr 7) and 1 == 1,
            lightOn = (u8(d, 76) shr 1) and 1 == 1,
            lifted = (u8(d, 76) shr 2) and 1 == 1,
        )
    }

    /** Temperatures are sent as `value + 176` in one byte; 0 means "no sensor". */
    private fun temp(d: ByteArray, i: Int): Double? {
        val raw = u8(d, i)
        if (raw == 0) return null
        return (raw + 80 - 256).toDouble()
    }

    private fun decodeErrors(d: ByteArray, at: Int): Set<WheelAlert> {
        if (at + 6 >= d.size) return emptySet()
        val result = mutableSetOf<WheelAlert>()
        for ((offset, bit, alert) in ERROR_BITS) {
            if ((u8(d, at + offset) shr bit) and 1 == 1) result += alert
        }
        val busCurrent = (u8(d, at + 3) shr 2) and 0x03
        if (busCurrent > 0) result += WheelAlert("inmotion.over_bus_current", "Превышен ток батареи (уровень $busCurrent)")
        val lowBattery = (u8(d, at + 3) shr 4) and 0x03
        if (lowBattery > 0) result += WheelAlert("inmotion.low_battery", "Низкий заряд (уровень $lowBattery)")
        return result
    }

    override fun encode(action: WheelAction): List<Outbound> = listOf(
        Outbound(
            when (action) {
                WheelAction.Beep -> when {
                    model in setOf(Model.V13, Model.V13PRO, Model.V14G, Model.V14S, Model.V11Y) ->
                        Message.control(0x51, 0x02, 0x64)
                    legacyV11 -> Message.control(0x41, 0x18, 0x01)
                    else -> Message.control(0x51, 0x18, 0x01)
                }
                is WheelAction.Light -> when {
                    layout == Layout.V12 -> Message.control(0x50, bit(action.on), bit(action.on))
                    legacyV11 -> Message.control(0x40, bit(action.on))
                    else -> Message.control(0x50, bit(action.on))
                }
                is WheelAction.Lock -> Message.control(0x31, bit(action.locked))
            },
        ),
    )

    private fun bit(on: Boolean) = if (on) 1 else 0

    class Message(val flags: Int, val command: Int, val data: ByteArray) {
        companion object {
            const val FLAG_INITIAL = 0x11
            const val FLAG_DEFAULT = 0x14
            const val CMD_MAIN_INFO = 0x02
            const val CMD_REALTIME = 0x04
            const val CMD_TOTAL_STATS = 0x11
            const val CMD_CONTROL = 0x60

            fun carType() = build(FLAG_INITIAL, CMD_MAIN_INFO, Bytes.of(0x01))
            fun serial() = build(FLAG_INITIAL, CMD_MAIN_INFO, Bytes.of(0x02))
            fun versions() = build(FLAG_INITIAL, CMD_MAIN_INFO, Bytes.of(0x06))
            fun realtime() = build(FLAG_DEFAULT, CMD_REALTIME, ByteArray(0))
            fun totalStats() = build(FLAG_DEFAULT, CMD_TOTAL_STATS, ByteArray(0))
            fun control(vararg data: Int) = build(FLAG_DEFAULT, CMD_CONTROL, Bytes.of(*data))

            fun build(flags: Int, command: Int, data: ByteArray): ByteArray {
                val body = ByteArray(data.size + 3)
                body[0] = flags.toByte()
                body[1] = (data.size + 1).toByte()
                body[2] = command.toByte()
                data.copyInto(body, 3)
                var check = 0
                body.forEach { check = check xor (it.toInt() and 0xFF) }
                val out = java.io.ByteArrayOutputStream()
                out.write(0xAA)
                out.write(0xAA)
                for (b in body) {
                    val v = b.toInt() and 0xFF
                    if (v == 0xAA || v == 0xA5) out.write(0xA5)
                    out.write(v)
                }
                out.write(check)
                return out.toByteArray()
            }
        }
    }

    /** Reassembles escaped frames and verifies the XOR checksum. */
    internal class Unpacker {
        private val body = java.io.ByteArrayOutputStream()
        private var collecting = false
        private var escaped = false
        private var previousRaw = -1
        private var length = -1

        fun add(byte: Byte): Message? {
            val c = byte.toInt() and 0xFF
            if (!escaped && c == 0xA5) {
                escaped = true
                previousRaw = -1
                return null
            }
            val literal = escaped
            escaped = false
            if (!literal && c == 0xAA && previousRaw == 0xAA) {
                body.reset()
                collecting = true
                length = -1
                previousRaw = -1
                return null
            }
            previousRaw = if (literal) -1 else c
            if (!collecting) return null
            body.write(c)
            if (body.size() == 2) length = c
            if (length >= 0 && body.size() == length + 3) {
                collecting = false
                previousRaw = -1
                return verify(body.toByteArray())
            }
            if (body.size() > 300) collecting = false
            return null
        }

        private fun verify(b: ByteArray): Message? {
            var check = 0
            for (i in 0 until b.size - 1) check = check xor (b[i].toInt() and 0xFF)
            if (check != (b.last().toInt() and 0xFF)) return null
            val len = b[1].toInt() and 0xFF
            if (len < 1) return null
            return Message(
                flags = b[0].toInt() and 0xFF,
                command = b[2].toInt() and 0x7F,
                data = b.copyOfRange(3, 3 + len - 1),
            )
        }
    }

    private companion object {
        data class ErrorBit(val offset: Int, val bit: Int, val alert: WheelAlert)

        private fun e(offset: Int, bit: Int, code: String, text: String) =
            ErrorBit(offset, bit, WheelAlert("inmotion.$code", text))

        val ERROR_BITS = listOf(
            e(0, 0, "phase_sensor", "Неисправность датчика фазного тока"),
            e(0, 1, "bus_sensor", "Неисправность датчика тока шины"),
            e(0, 2, "motor_hall", "Ошибка датчиков Холла мотора"),
            e(0, 3, "battery", "Ошибка батареи"),
            e(0, 4, "imu_sensor", "Ошибка гироскопа (IMU)"),
            e(0, 5, "controller_com1", "Нет связи с контроллером (1)"),
            e(0, 6, "controller_com2", "Нет связи с контроллером (2)"),
            e(0, 7, "ble_com1", "Ошибка Bluetooth-модуля (1)"),
            e(1, 0, "ble_com2", "Ошибка Bluetooth-модуля (2)"),
            e(1, 1, "mos_temp_sensor", "Неисправен датчик температуры MOSFET"),
            e(1, 2, "motor_temp_sensor", "Неисправен датчик температуры мотора"),
            e(1, 3, "battery_temp_sensor", "Неисправен датчик температуры батареи"),
            e(1, 4, "board_temp_sensor", "Неисправен датчик температуры платы"),
            e(1, 5, "fan", "Неисправность вентилятора"),
            e(1, 6, "rtc", "Ошибка часов (RTC)"),
            e(1, 7, "external_rom", "Ошибка внешней памяти"),
            e(2, 0, "vbus_sensor", "Неисправен датчик напряжения шины"),
            e(2, 1, "vbattery_sensor", "Неисправен датчик напряжения батареи"),
            e(2, 2, "cannot_power_off", "Колесо не может выключиться"),
            e(3, 0, "under_voltage", "Пониженное напряжение"),
            e(3, 1, "over_voltage", "Перенапряжение"),
            e(3, 6, "mos_temp", "Перегрев MOSFET"),
            e(3, 7, "motor_temp", "Перегрев мотора"),
            e(4, 0, "battery_temp", "Перегрев батареи"),
            e(4, 1, "board_temp", "Перегрев платы"),
            e(4, 2, "over_speed", "Превышение скорости"),
            e(4, 3, "output_saturation", "Мотор на пределе мощности (насыщение выхода)"),
            e(4, 4, "motor_spin", "Мотор крутится без нагрузки"),
            e(4, 5, "motor_block", "Мотор заблокирован"),
            e(4, 6, "posture", "Недопустимый наклон"),
            e(4, 7, "risk_behaviour", "Опасное поведение"),
            e(5, 0, "motor_no_load", "Мотор без нагрузки"),
            e(5, 1, "no_self_test", "Самотест не пройден"),
            e(5, 2, "compatibility", "Несовместимость компонентов"),
            e(5, 3, "power_key_long_press", "Кнопка питания зажата"),
            e(5, 4, "force_dfu", "Режим обновления прошивки"),
            e(5, 5, "device_lock", "Колесо заблокировано"),
            e(5, 6, "cpu_over_temp", "Перегрев процессора"),
            e(5, 7, "imu_over_temp", "Перегрев гироскопа"),
            e(6, 1, "hw_compatibility", "Несовместимость оборудования"),
            e(6, 2, "fan_low_speed", "Вентилятор вращается медленно"),
        )
    }
}
