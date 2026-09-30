package app.gyro.protocol

import app.gyro.protocol.Bytes.s16be
import app.gyro.protocol.Bytes.u16be
import app.gyro.protocol.Bytes.u32be
import app.gyro.protocol.Bytes.u8

/**
 * Begode / Gotway and Extreme Bull over the HM-10 UART service.
 *
 * The controller streams 24-byte frames without checksums (`55 AA … type 18 5A 5A 5A 5A`) and
 * answers single-letter ASCII commands. Voltage is always scaled as if the pack were 16S, so the real
 * series count must come from the model name, the extended 0x01 frame or the rider.
 * Layout follows WheelLog's GotwayAdapter.
 */
class BegodeProtocol(bleName: String?) : BaseProtocol(ProtocolFamily.BEGODE, bleName) {

    private val unpacker = Unpacker()
    private var model: String? = null
    private var firmware: String? = null
    private var customFirmware = false        // Freestyl3r (CF) or SmirnoV (BF): PWM in frame A
    private var smirnovFirmware = false       // BF: MPU6500 temperature formula
    private var framePwmSeen = false          // frame 0x07 carries a real hardware PWM
    private var identifyAttempts = 0
    private var lastIdentifyMs = 0L
    private var voltageRaw16s = 0.0
    private var measuredBatteryCurrent: Double? = null
    private var lastPwm: Double? = null

    init {
        info = info.copy(
            brand = brandForName(bleName),
            capabilities = setOf(Capability.BEEP, Capability.LIGHT, Capability.READ_SETTINGS),
        )
        cellsForModel(bleName)?.let { updateCells(it, CellsSource.MODEL_TABLE) }
            ?: updateCells(DEFAULT_CELLS, CellsSource.DEFAULT_GUESS)
    }

    override val writableParams: Set<WheelParam> = setOf(
        WheelParam.PEDALS_MODE, WheelParam.LIGHT_MODE, WheelParam.TILTBACK_SPEED,
        WheelParam.ALARM_MODE, WheelParam.ROLL_ANGLE,
    )

    override fun onConnected(nowMs: Long): List<Outbound> = listOf(Outbound(ascii("V")))

    override fun poll(nowMs: Long): List<Outbound> {
        // Ask for firmware ("V") and model name ("N") until the wheel answers, like WheelLog does.
        if (identifyAttempts >= MAX_IDENTIFY_ATTEMPTS || nowMs - lastIdentifyMs < 300) return emptyList()
        val request = when {
            firmware == null -> "V"
            model == null -> "N"
            else -> return emptyList()
        }
        identifyAttempts++
        lastIdentifyMs = nowMs
        return listOf(Outbound(ascii(request)))
    }

    override fun decode(chunk: ByteArray, nowMs: Long): DecodeResult {
        decodeText(chunk)
        var updated = false
        for (b in chunk) {
            val frame = unpacker.add(b) ?: continue
            updated = decodeFrame(frame, nowMs) || updated
        }
        return DecodeResult(updated)
    }

    private fun decodeText(chunk: ByteArray) {
        if (model != null && firmware != null) return
        val text = String(chunk, Charsets.ISO_8859_1).trim()
        when {
            text.startsWith("NAME") -> {
                model = text.substring(4).trim().filter { it.code in 0x20..0x7E }
                info = info.copy(model = model, brand = brandForName(model).takeIf { it != WheelBrand.BEGODE } ?: info.brand)
                cellsForModel(model)?.let { updateCells(it, CellsSource.MODEL_TABLE) }
            }
            text.startsWith("GW") -> setFirmware(text.substring(2), custom = false, smirnov = false)
            text.startsWith("JN") -> {
                setFirmware(text.substring(2), custom = false, smirnov = false)
                info = info.copy(brand = WheelBrand.EXTREME_BULL)
            }
            text.startsWith("CF") -> setFirmware(text.substring(2), custom = true, smirnov = false)
            text.startsWith("BF") -> setFirmware(text.substring(2), custom = true, smirnov = true)
        }
    }

    private fun setFirmware(value: String, custom: Boolean, smirnov: Boolean) {
        firmware = value.trim().filter { it.code in 0x20..0x7E }
        customFirmware = custom
        smirnovFirmware = smirnov
        info = info.copy(firmware = firmware)
        if (custom) addCapabilities(Capability.HARDWARE_PWM)
    }

    private fun decodeFrame(f: ByteArray, nowMs: Long): Boolean {
        when (u8(f, 18)) {
            0x00 -> {
                decodeLive(f, nowMs)
                return true
            }
            0x01 -> if (!smirnovFirmware) decodeTrueVoltage(f)
            0x02, 0x03 -> decodeBmsCells(f, u8(f, 18) - 2)
            0x04 -> decodeSettings(f)
            0x07 -> if (!smirnovFirmware) {
                measuredBatteryCurrent = -s16be(f, 2) / 100.0
                val motorTemp = s16be(f, 6).toDouble()
                val pwm = s16be(f, 8).toDouble()
                if (pwm != 0.0) framePwmSeen = true
                if (framePwmSeen) lastPwm = kotlin.math.abs(pwm)
                addCapabilities(Capability.BATTERY_CURRENT)
                if (framePwmSeen) addCapabilities(Capability.HARDWARE_PWM)
                telemetry = telemetry.copy(motorTemperatureC = motorTemp)
            }
        }
        return false
    }

    private fun decodeLive(f: ByteArray, nowMs: Long) {
        voltageRaw16s = u16be(f, 2) / 100.0
        val cells = info.cellsSeries ?: DEFAULT_CELLS
        val voltage = voltageRaw16s * cells / 16.0
        val speed = s16be(f, 4) * 3.6 / 100.0
        val phaseCurrent = s16be(f, 10) / 100.0 * if (smirnovFirmware) 10 else 1
        val tempRaw = s16be(f, 12)
        val temperature = if (smirnovFirmware) tempRaw / 333.87 + 21.0 else tempRaw / 340.0 + 36.53
        if (customFirmware) lastPwm = kotlin.math.abs(s16be(f, 14) / 10.0)
        val pwm = lastPwm
        val measured = measuredBatteryCurrent
        publish(
            telemetry.copy(
                timestampMs = nowMs,
                voltageV = voltage,
                speedKmh = speed,
                tripDistanceM = if (smirnovFirmware) telemetry.tripDistanceM else u16be(f, 8).toLong(),
                phaseCurrentA = phaseCurrent,
                batteryCurrentA = measured ?: batteryCurrentFromPhase(phaseCurrent, pwm, speed),
                batteryCurrentEstimated = measured == null,
                pwmPercent = pwm,
                temperatureC = temperature,
                // Percent uses the 16S-scaled value, so it is right even if the series count is wrong.
                batteryPercent = BatteryEstimator.percentFromPackVoltage(voltageRaw16s, 16),
                batteryPercentFromWheel = false,
            ),
        )
    }

    /** Stock firmware on newer boards reports the true pack voltage; it pins down the series count. */
    private fun decodeTrueVoltage(f: ByteArray) {
        val trueVoltage = u16be(f, 6) / 10.0
        if (trueVoltage > 40 && voltageRaw16s > 40) {
            BatteryEstimator.snapCells(16.0 * trueVoltage / voltageRaw16s)?.let { updateCells(it, CellsSource.MEASURED) }
        }
        val bmsIndex = if (u8(f, 19) < 2) 0 else 1
        setBms(bmsIndex, bms(bmsIndex).copy(currentA = s16be(f, 8) / 10.0))
    }

    private fun decodeBmsCells(f: ByteArray, index: Int) {
        addCapabilities(Capability.BMS_CELLS)
        val page = u8(f, 19)
        val pack = bms(index)
        val cells = pack.cellVoltages.toMutableList()
        for (i in 0 until 8) {
            val cellIndex = page * 8 + i
            if (cellIndex >= 64) break
            while (cells.size <= cellIndex) cells += 0.0
            cells[cellIndex] = u16be(f, (i + 1) * 2) / 1000.0
        }
        val last = cells.indexOfLast { it > 0.5 }
        val trimmed = if (last < 0) emptyList() else cells.subList(0, last + 1).toList()
        setBms(index, pack.copy(cellVoltages = trimmed, voltageV = trimmed.sum().takeIf { it > 0 }))
        if (trimmed.size >= 16 && trimmed.all { it > 0.5 }) updateCells(trimmed.size, CellsSource.BMS)
    }

    private fun decodeSettings(f: ByteArray) {
        telemetry = telemetry.copy(totalDistanceM = u32be(f, 2))
        if (smirnovFirmware) return
        val flags = u16be(f, 6)
        settings[WheelParam.PEDALS_MODE] = 2 - ((flags shr 13) and 0x03)
        settings[WheelParam.ALARM_MODE] = (flags shr 10) and 0x03
        settings[WheelParam.ROLL_ANGLE] = (flags shr 7) and 0x03
        settings[WheelParam.AUTO_POWER_OFF] = u16be(f, 8)
        val tiltback = u16be(f, 10)
        settings[WheelParam.TILTBACK_SPEED] = if (tiltback >= 100) 0 else tiltback
        settings[WheelParam.LED_MODE] = u8(f, 13)
        settings[WheelParam.LIGHT_MODE] = u8(f, 15) and 0x03
        val alertBits = u8(f, 14)
        alerts = ALERT_BITS.filterKeys { bit -> (alertBits shr bit) and 1 == 1 }.values.toSet()
        telemetry = telemetry.copy(lightOn = (u8(f, 15) and 0x03) != 0)
    }

    override fun encode(action: WheelAction): List<Outbound>? = when (action) {
        WheelAction.Beep -> listOf(Outbound(ascii("b")))
        is WheelAction.Light -> listOf(Outbound(ascii(if (action.on) "Q" else "E")), Outbound(ascii("b"), 100))
        is WheelAction.Lock -> null
    }

    override fun encodeSettings(permit: SettingsWritePermit): List<Outbound>? {
        val out = mutableListOf<Outbound>()
        for (change in permit.changes) {
            val gap = if (out.isEmpty()) 0L else 300L
            when (change.param) {
                WheelParam.PEDALS_MODE -> out += command(listOf("h", "f", "s").getOrNull(change.to) ?: return null, gap)
                WheelParam.LIGHT_MODE -> out += command(listOf("E", "Q", "T").getOrNull(change.to) ?: return null, gap)
                WheelParam.ALARM_MODE -> out += command(listOf("o", "u", "i", "I").getOrNull(change.to) ?: return null, gap)
                WheelParam.ROLL_ANGLE -> out += command(listOf(">", "=", "<").getOrNull(change.to) ?: return null, gap)
                WheelParam.TILTBACK_SPEED -> out += tiltbackSequence(change.to, gap) ?: return null
                else -> return null
            }
        }
        return out
    }

    /** "W" "Y" then two ASCII digits; 0 disables tiltback. Timing mirrors the official app. */
    private fun tiltbackSequence(kmh: Int, firstDelay: Long): List<Outbound>? {
        if (kmh == 0) {
            return listOf(Outbound(ascii("b"), firstDelay), Outbound(ascii("\""), 100), Outbound(ascii("b"), 100), Outbound(ascii("b"), 100))
        }
        if (kmh !in 1..99) return null
        return listOf(
            Outbound(ascii("b"), firstDelay),
            Outbound(ascii("W"), 100),
            Outbound(ascii("Y"), 100),
            Outbound(byteArrayOf((0x30 + kmh / 10).toByte()), 100),
            Outbound(byteArrayOf((0x30 + kmh % 10).toByte()), 100),
            Outbound(ascii("b"), 100),
            Outbound(ascii("b"), 100),
        )
    }

    private fun command(letter: String, delay: Long) = listOf(Outbound(ascii(letter), delay), Outbound(ascii("b"), 100))

    private fun ascii(s: String) = s.toByteArray(Charsets.US_ASCII)

    /** Collects 24-byte frames from an unframed byte stream that may lose bytes. */
    private class Unpacker {
        private val buffer = ByteArray(24)
        private var size = 0
        private var collecting = false
        private var previous = -1

        fun add(byte: Byte): ByteArray? {
            val c = byte.toInt() and 0xFF
            if (!collecting) {
                if (c == 0xAA && previous == 0x55) {
                    buffer[0] = 0x55
                    buffer[1] = 0xAA.toByte()
                    size = 2
                    collecting = true
                }
                previous = c
                return null
            }
            buffer[size++] = byte
            previous = c
            // Garbage "55 AA 5A 55 AA" / "55 AA 5A 5A 55 AA" sequences appear between frames: restart.
            if (size >= 5 && u8(buffer, size - 2) == 0x55 && c == 0xAA && size <= 6) {
                buffer[0] = 0x55
                buffer[1] = 0xAA.toByte()
                size = 2
                return null
            }
            if (size in 21..24 && c != 0x5A) {
                collecting = false
                return null
            }
            if (size == 24) {
                collecting = false
                return buffer.copyOf()
            }
            return null
        }
    }

    companion object {
        const val DEFAULT_CELLS = 24
        private const val MAX_IDENTIFY_ATTEMPTS = 20

        private val ALERT_BITS = mapOf(
            0 to WheelAlert("begode.high_power", "Высокая нагрузка (PWM-сигнал колеса)"),
            1 to WheelAlert("begode.speed2", "Сигнал скорости 2"),
            2 to WheelAlert("begode.speed1", "Сигнал скорости 1"),
            3 to WheelAlert("begode.low_voltage", "Низкое напряжение батареи"),
            4 to WheelAlert("begode.over_voltage", "Перенапряжение батареи"),
            5 to WheelAlert("begode.over_temperature", "Перегрев"),
            6 to WheelAlert("begode.hall_error", "Ошибка датчиков Холла"),
            7 to WheelAlert("begode.transport_mode", "Транспортный режим"),
        )

        /** Series count by model name; only models whose battery voltage is well known. */
        private val MODEL_CELLS = listOf(
            "MASTER PRO" to 32, "MASTER" to 32, "ET MAX" to 40, "BLITZ" to 32,
            "EX.N" to 24, "EXN" to 24, "HERO" to 24, "T4" to 24, "MONSTER PRO" to 24, "MSP" to 24,
            "NIKOLA" to 24, "RS" to 24, "MTEN4" to 20, "A2" to 20,
        )

        fun cellsForModel(name: String?): Int? {
            val upper = name?.uppercase() ?: return null
            return MODEL_CELLS.firstOrNull { (key, _) -> upper.contains(key) }?.second
        }

        fun brandForName(name: String?): WheelBrand {
            val upper = name?.uppercase() ?: return WheelBrand.BEGODE
            return if (upper.contains("EXTREME") || upper.startsWith("EB")) WheelBrand.EXTREME_BULL else WheelBrand.BEGODE
        }
    }
}
