package app.gyro.protocol

import app.gyro.protocol.Bytes.ascii
import app.gyro.protocol.Bytes.ks32
import app.gyro.protocol.Bytes.s16le
import app.gyro.protocol.Bytes.s8
import app.gyro.protocol.Bytes.u16le
import app.gyro.protocol.Bytes.u8

/**
 * KingSong (and Rockwheel, which reuses the KingSong board) over the HM-10 UART service.
 *
 * Frames are 20 bytes: `AA 55 <14 data bytes> <type> <arg> 5A 5A`. F-series BMS frames (type
 * F1/F2 with arg D0) are longer and need a raised MTU. Layout follows WheelLog's KingsongAdapter.
 */
class KingSongProtocol(bleName: String?) : BaseProtocol(ProtocolFamily.KINGSONG, bleName) {

    private var bmsSerialRequested = BooleanArray(2)
    private var bmsFirmwareRequested = BooleanArray(2)

    init {
        info = info.copy(
            brand = if (isRockwheelName(bleName)) WheelBrand.ROCKWHEEL else WheelBrand.KINGSONG,
            capabilities = setOf(Capability.BEEP, Capability.LIGHT, Capability.BATTERY_CURRENT, Capability.READ_SETTINGS),
        )
    }

    override val writableParams: Set<WheelParam> = setOf(
        WheelParam.TILTBACK_SPEED, WheelParam.ALARM_1_SPEED, WheelParam.ALARM_2_SPEED,
        WheelParam.ALARM_3_SPEED, WheelParam.PEDALS_MODE, WheelParam.LIGHT_MODE,
    )

    override fun onConnected(nowMs: Long): List<Outbound> = listOf(
        Outbound(request(0x9B)),                            // name → model and firmware
        Outbound(request(0x63), delayBeforeMs = 150),       // serial number
        Outbound(request(0x98), delayBeforeMs = 150),       // alarm speeds and tiltback
    )

    override fun decode(chunk: ByteArray, nowMs: Long): DecodeResult {
        if (chunk.size < 20 || u8(chunk, 0) != 0xAA || u8(chunk, 1) != 0x55) return DecodeResult.NOTHING
        val isExtended = chunk.size > 20 && u8(chunk, 16) in 0xF1..0xF2 && u8(chunk, 17) == 0xD0
        val frames = if (chunk.size > 20 && !isExtended && chunk.size % 20 == 0) {
            (0 until chunk.size / 20).map { chunk.copyOfRange(it * 20, it * 20 + 20) }
        } else {
            listOf(chunk)
        }
        var updated = false
        val outbound = mutableListOf<Outbound>()
        for (frame in frames) {
            if (u8(frame, 0) != 0xAA || u8(frame, 1) != 0x55) continue
            updated = decodeFrame(frame, nowMs, outbound) || updated
        }
        return DecodeResult(updated, outbound)
    }

    private fun decodeFrame(d: ByteArray, nowMs: Long, outbound: MutableList<Outbound>): Boolean {
        when (u8(d, 16)) {
            0xA9 -> {
                decodeLive(d, nowMs)
                return true
            }
            0xB9 -> telemetry = telemetry.copy(
                tripDistanceM = ks32(d, 2),
                topSpeedKmh = s16le(d, 8) / 100.0,
                fanOn = u8(d, 12) != 0,
                charging = u8(d, 13) != 0,
                temperature2C = s16le(d, 14) / 100.0,
            )
            0xBB -> decodeName(d)
            0xB3 -> {
                val serial = ascii(d, 2, 14) + ascii(d, 17, 3)
                if (serial.isNotBlank()) info = info.copy(serial = serial)
            }
            0xF5 -> {
                telemetry = telemetry.copy(cpuLoadPercent = u8(d, 14), pwmPercent = s8(d, 15).toDouble())
                addCapabilities(Capability.HARDWARE_PWM)
            }
            0xF6 -> telemetry = telemetry.copy(dynamicSpeedLimitKmh = s16le(d, 2) / 100.0)
            0xA4, 0xB5 -> {
                settings[WheelParam.ALARM_1_SPEED] = u8(d, 4)
                settings[WheelParam.ALARM_2_SPEED] = u8(d, 6)
                settings[WheelParam.ALARM_3_SPEED] = u8(d, 8)
                settings[WheelParam.TILTBACK_SPEED] = u8(d, 10)
                if (u8(d, 16) == 0xA4) {
                    // The wheel expects the frame echoed back as 0x98 to confirm receipt.
                    val ack = d.copyOf(20)
                    ack[16] = 0x98.toByte()
                    outbound += Outbound(ack)
                }
            }
            0xF1, 0xF2 -> decodeBms(d, u8(d, 16) - 0xF1, outbound)
            0xE1, 0xE2 -> {
                val index = u8(d, 16) - 0xE1
                setBms(index, bms(index).copy(serial = ascii(d, 2, 14) + ascii(d, 17, 3)))
            }
            0xE5, 0xE6 -> {
                val index = u8(d, 16) - 0xE5
                setBms(index, bms(index).copy(firmware = ascii(d, 2, 14) + ascii(d, 17, 3)))
            }
        }
        return false
    }

    private fun decodeLive(d: ByteArray, nowMs: Long) {
        val voltage = u16le(d, 2) / 100.0
        guessCellsFromVoltage(voltage)
        val rideMode = if (u8(d, 15) == 0xE0) s8(d, 14) else null
        if (rideMode != null) settings[WheelParam.PEDALS_MODE] = rideMode
        val current = s16le(d, 10) / 100.0
        // The BMS state of charge beats a voltage curve when the wheel has a smart BMS.
        val bmsPercent = bmsPacks.filterNotNull().mapNotNull { it.remainingPercent }.takeIf { it.isNotEmpty() }?.average()
        publish(
            telemetry.copy(
                timestampMs = nowMs,
                voltageV = voltage,
                speedKmh = s16le(d, 4) / 100.0,
                totalDistanceM = ks32(d, 6),
                batteryCurrentA = current,
                batteryCurrentEstimated = false,
                temperatureC = s16le(d, 12) / 100.0,
                rideModeCode = rideMode ?: telemetry.rideModeCode,
                batteryPercent = bmsPercent ?: estimatedBatteryPercent(voltage),
                batteryPercentFromWheel = bmsPercent != null,
            ),
        )
    }

    private fun decodeName(d: ByteArray) {
        val name = ascii(d, 2, 14)
        if (name.isBlank()) return
        val parts = name.split("-")
        val model = if (parts.size > 1) parts.dropLast(1).joinToString("-") else name
        val firmware = parts.lastOrNull()?.toIntOrNull()?.let { "%.2f".format(java.util.Locale.US, it / 100.0) }
        val brand = if (isRockwheelName(name) || info.brand == WheelBrand.ROCKWHEEL) WheelBrand.ROCKWHEEL else WheelBrand.KINGSONG
        info = info.copy(brand = brand, model = model, name = name, firmware = firmware ?: info.firmware)
        cellsForModel(model)?.let { updateCells(it, CellsSource.MODEL_TABLE) }
        // Now that the model is known the percentage may change from a voltage guess to the table value.
        if (telemetry.voltageV > 0) {
            telemetry = telemetry.copy(batteryPercent = estimatedBatteryPercent(telemetry.voltageV))
        }
    }

    private fun decodeBms(d: ByteArray, index: Int, outbound: MutableList<Outbound>) {
        if (index !in 0..1) return
        addCapabilities(Capability.BMS_CELLS)
        var pack = bms(index)
        val cells = pack.cellVoltages.toMutableList()
        fun setCell(i: Int, value: Double) {
            while (cells.size <= i) cells += 0.0
            cells[i] = value
        }
        when (val pNum = u8(d, 17)) {
            0x00 -> {
                val remaining = u16le(d, 6) * 10
                val factory = u16le(d, 8) * 10
                pack = pack.copy(
                    voltageV = u16le(d, 2) / 100.0,
                    currentA = s16le(d, 4) / 100.0,
                    remainingCapacityMah = remaining,
                    factoryCapacityMah = factory,
                    fullCycles = u16le(d, 10),
                    remainingPercent = if (factory > 0) (remaining * 100 / factory) else null,
                )
                if (pack.serial == null && !bmsSerialRequested[index]) {
                    bmsSerialRequested[index] = true
                    outbound += Outbound(request(0xE1 + index, trailer = false))
                }
            }
            0x01 -> pack = pack.copy(
                temperaturesC = (0 until 6).map { kelvin10(d, 2 + it * 2) },
                mosTemperatureC = kelvin10(d, 14),
            )
            in 0x02..0x05 -> for (i in 0 until 7) setCell((pNum - 2) * 7 + i, u16le(d, 2 + i * 2) / 1000.0)
            0x06 -> {
                setCell(28, u16le(d, 2) / 1000.0)
                setCell(29, u16le(d, 4) / 1000.0)
                if (pack.firmware == null && !bmsFirmwareRequested[index]) {
                    bmsFirmwareRequested[index] = true
                    outbound += Outbound(request(0xE5 + index, trailer = false))
                }
            }
            0xD0 -> pack = decodeExtendedBms(d, pack, cells)
        }
        if (cells.isNotEmpty()) pack = pack.copy(cellVoltages = trimCells(cells))
        setBms(index, pack)
        if (pack.cellCount >= 16 && pack.cellVoltages.size == pack.cellCount) {
            updateCells(pack.cellCount, CellsSource.BMS)
        }
    }

    /** F-series (F18P, F22P) BMS frame: variable cell and temperature count in one long packet. */
    private fun decodeExtendedBms(d: ByteArray, pack: BmsPack, cells: MutableList<Double>): BmsPack {
        val cellCount = u8(d, 21).coerceAtMost(64)
        cells.clear()
        for (i in 0 until cellCount) cells += u16le(d, 22 + i * 2) / 1000.0
        var offset = 23 + cellCount * 2
        val tempCount = u8(d, offset - 1).coerceAtMost(16)
        val temps = (0 until minOf(tempCount, 6)).map { kelvin10(d, offset + it * 2) }
        val mos = if (tempCount > 6) kelvin10(d, offset + 12) else pack.mosTemperatureC
        offset += tempCount * 2
        if (offset + 12 >= d.size) return pack.copy(temperaturesC = temps, mosTemperatureC = mos)
        val factory = u16le(d, offset + 11) * 10
        val percent = u16le(d, offset + 4) / 10
        return pack.copy(
            temperaturesC = temps,
            mosTemperatureC = mos,
            currentA = s16le(d, offset) / 100.0,
            voltageV = u16le(d, offset + 2) / 100.0,
            remainingPercent = percent,
            fullCycles = u16le(d, offset + 9),
            factoryCapacityMah = factory,
            remainingCapacityMah = percent * factory / 100,
        )
    }

    override fun encode(action: WheelAction): List<Outbound>? = when (action) {
        WheelAction.Beep -> listOf(Outbound(request(0x88)))
        // Light modes: 0x12 on, 0x13 off (0x14 auto).
        is WheelAction.Light -> listOf(Outbound(request(0x73, data2 = if (action.on) 0x12 else 0x13, data3 = 0x01)))
        is WheelAction.Lock -> null
    }

    override fun encodeSettings(permit: SettingsWritePermit): List<Outbound>? {
        val target = permit.target
        val changed = permit.changes.map { it.param }.toSet()
        if (!writableParams.containsAll(changed)) return null
        val out = mutableListOf<Outbound>()
        val speedParams = setOf(WheelParam.TILTBACK_SPEED, WheelParam.ALARM_1_SPEED, WheelParam.ALARM_2_SPEED, WheelParam.ALARM_3_SPEED)
        if (changed.any { it in speedParams }) {
            // One frame carries all four speeds, so unchanged ones must be resent with their current value.
            val values = speedParams.associateWith { target[it] ?: settings[it] ?: return null }
            val frame = request(0x85)
            frame[2] = values.getValue(WheelParam.ALARM_1_SPEED).toByte()
            frame[4] = values.getValue(WheelParam.ALARM_2_SPEED).toByte()
            frame[6] = values.getValue(WheelParam.ALARM_3_SPEED).toByte()
            frame[8] = values.getValue(WheelParam.TILTBACK_SPEED).toByte()
            out += Outbound(frame)
            out += Outbound(request(0x98), delayBeforeMs = 300) // read back to confirm
        }
        target[WheelParam.PEDALS_MODE]?.takeIf { WheelParam.PEDALS_MODE in changed }?.let { mode ->
            val frame = request(0x87, data2 = mode, data3 = 0xE0)
            frame[17] = 0x15
            out += Outbound(frame, delayBeforeMs = if (out.isEmpty()) 0 else 200)
        }
        target[WheelParam.LIGHT_MODE]?.takeIf { WheelParam.LIGHT_MODE in changed }?.let { mode ->
            out += Outbound(request(0x73, data2 = 0x12 + mode, data3 = 0x01), delayBeforeMs = if (out.isEmpty()) 0 else 200)
        }
        return out
    }

    private fun request(type: Int, data2: Int = 0, data3: Int = 0, trailer: Boolean = true): ByteArray {
        val frame = Bytes.of(0xAA, 0x55, data2, data3, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, type, 0x14, 0x5A, 0x5A)
        if (!trailer) {
            frame[17] = 0
            frame[18] = 0
            frame[19] = 0
        }
        return frame
    }

    private fun kelvin10(d: ByteArray, i: Int): Double = (u16le(d, i) - 2730) / 10.0

    private fun trimCells(cells: List<Double>): List<Double> {
        val last = cells.indexOfLast { it > 0.5 }
        return if (last < 0) emptyList() else cells.subList(0, last + 1).toList()
    }

    companion object {
        private val MODEL_CELLS: Map<String, Int> = buildMap {
            listOf("KS-18L", "KS-16X", "KS-16XF", "RW", "KS-18LH", "KS-18LY", "KS-S18", "KS-S16", "KS-S16P")
                .forEach { put(it, 20) }
            put("KS-S19", 24)
            put("KS-S20", 30)
            put("KS-S22", 30)
            put("KS-F18P", 36)
            put("KS-F22P", 42)
            listOf("KS-14C", "KS-14D", "KS-14M", "KS-14S", "KS-16", "KS-16B", "KS-16C", "KS-16D", "KS-16S", "KS-18A", "KS-18S")
                .forEach { put(it, 16) }
        }

        /** Null for models released after the table was written; the voltage guess covers those. */
        fun cellsForModel(model: String): Int? = MODEL_CELLS[model.uppercase()]

        fun isRockwheelName(name: String?): Boolean =
            name != null && (name.uppercase().startsWith("ROCKW") || name.equals("RW", ignoreCase = true))
    }
}
