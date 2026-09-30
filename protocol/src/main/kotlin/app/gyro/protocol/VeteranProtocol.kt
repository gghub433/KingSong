package app.gyro.protocol

import app.gyro.protocol.Bytes.s16be
import app.gyro.protocol.Bytes.u16be
import app.gyro.protocol.Bytes.u32be
import app.gyro.protocol.Bytes.u8
import app.gyro.protocol.Bytes.veteran32
import java.util.zip.CRC32

/**
 * Leaperkim Veteran (Sherman, Abrams, Patton, Lynx, Oryx…) and Nosfet over the HM-10 UART service.
 *
 * Frames: `DC 5A 5C <len> <payload>`; frames longer than 38 bytes end with a big-endian CRC32 of
 * the first `len` bytes. The model is encoded in the firmware version (version / 1000).
 * Layout follows WheelLog's VeteranAdapter.
 */
class VeteranProtocol(bleName: String?) : BaseProtocol(ProtocolFamily.VETERAN, bleName) {

    private val unpacker = Unpacker()
    private var modelCode = -1
    private var lastByteMs = 0L

    init {
        info = info.copy(
            brand = WheelBrand.VETERAN,
            capabilities = setOf(Capability.BEEP, Capability.LIGHT, Capability.READ_SETTINGS),
        )
    }

    override val writableParams: Set<WheelParam> = setOf(WheelParam.PEDALS_MODE)

    override fun onConnected(nowMs: Long): List<Outbound> = emptyList()

    override fun decode(chunk: ByteArray, nowMs: Long): DecodeResult {
        // A gap means bytes were lost; start hunting for a fresh header.
        if (nowMs - lastByteMs > 100) unpacker.reset()
        lastByteMs = nowMs
        var updated = false
        for (b in chunk) {
            val frame = unpacker.add(b) ?: continue
            decodeFrame(frame, nowMs)
            updated = true
        }
        return DecodeResult(updated)
    }

    private fun decodeFrame(f: ByteArray, nowMs: Long) {
        val version = u16be(f, 28)
        val code = version / 1000
        if (code != modelCode) onModel(code, version)

        val voltage = u16be(f, 4) / 100.0
        if (info.cellsSeries == null) guessCellsFromVoltage(voltage)
        val speed = s16be(f, 6) / 10.0
        val phaseCurrent = s16be(f, 16) / 10.0
        // Hardware PWM exists from the Abrams (model code 2) on.
        val pwm = if (code >= 2) u16be(f, 34) / 100.0 else null
        settings[WheelParam.SPEED_ALERT] = u16be(f, 24) / 10
        settings[WheelParam.TILTBACK_SPEED] = u16be(f, 26) / 10
        settings[WheelParam.PEDALS_MODE] = u16be(f, 30)
        settings[WheelParam.AUTO_POWER_OFF] = u16be(f, 20)

        if (code >= 5 && f.size > 46) decodeBms(f)

        publish(
            telemetry.copy(
                timestampMs = nowMs,
                voltageV = voltage,
                speedKmh = speed,
                tripDistanceM = veteran32(f, 8),
                totalDistanceM = veteran32(f, 12),
                phaseCurrentA = phaseCurrent,
                batteryCurrentA = batteryCurrentFromPhase(phaseCurrent, pwm, speed),
                batteryCurrentEstimated = true,
                pwmPercent = pwm,
                temperatureC = s16be(f, 18) / 100.0,
                charging = u16be(f, 22) != 0,
                pitchDeg = s16be(f, 32) / 100.0,
                batteryPercent = estimatedBatteryPercent(voltage),
                batteryPercentFromWheel = false,
            ),
        )
    }

    private fun onModel(code: Int, version: Int) {
        modelCode = code
        val known = MODELS[code]
        info = info.copy(
            brand = if (code in 42..44) WheelBrand.NOSFET else WheelBrand.VETERAN,
            model = known?.first ?: "Модель $code",
            firmware = "%03d.%01d.%02d".format(version / 1000, (version % 1000) / 100, version % 100),
            supportLevel = if (known == null) SupportLevel.FALLBACK else SupportLevel.KNOWN,
        )
        known?.second?.let { updateCells(it, CellsSource.MODEL_TABLE) }
        if (code >= 2) addCapabilities(Capability.HARDWARE_PWM)
    }

    private fun decodeBms(f: ByteArray) {
        val page = u8(f, 46)
        val index = if (page < 4) 0 else 1
        var pack = bms(index)
        val cells = pack.cellVoltages.toMutableList()
        fun setCell(i: Int, v: Double) {
            while (cells.size <= i) cells += 0.0
            cells[i] = v
        }
        when (page % 4) {
            0 -> if (f.size > 72) {
                setBms(0, bms(0).copy(currentA = s16be(f, 69) / 100.0))
                setBms(1, bms(1).copy(currentA = s16be(f, 71) / 100.0))
                return
            }
            1 -> for (i in 0 until 15) setCell(i, s16be(f, 53 + i * 2) / 1000.0)
            2 -> for (i in 0 until 15) setCell(15 + i, u16be(f, 53 + i * 2) / 1000.0)
            3 -> {
                for (i in 0 until 12) if (59 + i * 2 + 1 < f.size) setCell(30 + i, u16be(f, 59 + i * 2) / 1000.0)
                pack = pack.copy(temperaturesC = (0 until 6).map { s16be(f, 47 + it * 2) / 100.0 })
            }
        }
        addCapabilities(Capability.BMS_CELLS)
        val expected = info.cellsSeries ?: cells.size
        val trimmed = cells.take(expected)
        setBms(index, pack.copy(cellVoltages = trimmed, voltageV = trimmed.sum().takeIf { it > 0 }))
    }

    override fun encode(action: WheelAction): List<Outbound>? = when (action) {
        WheelAction.Beep -> listOf(
            Outbound(
                if (modelCode in 0..2) "b".toByteArray()
                else Bytes.of(0x4C, 0x6B, 0x41, 0x70, 0x0E, 0x00, 0x80, 0x80, 0x80, 0x01, 0xCA, 0x87, 0xE6, 0x6F),
            ),
        )
        is WheelAction.Light -> listOf(Outbound((if (action.on) "SetLightON" else "SetLightOFF").toByteArray()))
        is WheelAction.Lock -> null
    }

    override fun encodeSettings(permit: SettingsWritePermit): List<Outbound>? {
        if (permit.changes.any { it.param != WheelParam.PEDALS_MODE }) return null
        val mode = permit.target[WheelParam.PEDALS_MODE] ?: return null
        val cmd = when (mode) {
            0 -> "SETh"
            1 -> "SETm"
            2 -> "SETs"
            else -> return null
        }
        return listOf(Outbound(cmd.toByteArray()))
    }

    private class Unpacker {
        private val buffer = ByteArray(260) // header 3 + length byte + up to 255 bytes
        private var size = 0
        private var length = 0
        private var state = 0 // 0 hunting, 1 length, 2 collecting
        private var old1 = 0
        private var old2 = 0
        private var usesCrc = false

        fun reset() {
            state = 0
            old1 = 0
            old2 = 0
        }

        fun add(byte: Byte): ByteArray? {
            val c = byte.toInt() and 0xFF
            when (state) {
                0 -> {
                    if (c == 0x5C && old1 == 0x5A && old2 == 0xDC) {
                        buffer[0] = 0xDC.toByte(); buffer[1] = 0x5A; buffer[2] = 0x5C
                        size = 3
                        state = 1
                    }
                    old2 = old1
                    old1 = c
                }
                1 -> {
                    length = c
                    buffer[size++] = byte
                    state = 2
                }
                else -> {
                    // Sanity bytes WheelLog relies on to drop corrupted frames.
                    if ((size == 22 && c != 0x00) || (size == 23 && (c and 0xFE) != 0) || (size == 30 && c != 0x00 && c != 0x07)) {
                        reset()
                        return null
                    }
                    buffer[size++] = byte
                    if (size == length + 4) {
                        reset()
                        val frame = buffer.copyOf(size)
                        if (length > 38 || usesCrc) {
                            val crc = CRC32().apply { update(frame, 0, length) }.value
                            if (crc != u32be(frame, length)) return null
                            usesCrc = true
                        }
                        return frame
                    }
                    if (size >= buffer.size) reset()
                }
            }
            return null
        }
    }

    companion object {
        /** Model code (firmware version / 1000) → name and series cell count. */
        private val MODELS: Map<Int, Pair<String, Int>> = mapOf(
            0 to ("Sherman" to 24), 1 to ("Sherman" to 24), 2 to ("Abrams" to 24), 3 to ("Sherman S" to 24),
            4 to ("Patton" to 30), 5 to ("Lynx" to 36), 6 to ("Sherman L" to 36), 7 to ("Patton S" to 30),
            8 to ("Oryx" to 42), 9 to ("Lynx S" to 36),
            42 to ("Nosfet Apex" to 36), 43 to ("Nosfet Aero" to 30), 44 to ("Nosfet Aeon" to 36),
        )
    }
}
