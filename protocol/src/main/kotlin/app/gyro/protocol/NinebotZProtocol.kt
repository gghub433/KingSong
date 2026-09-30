package app.gyro.protocol

import app.gyro.protocol.Bytes.ascii
import app.gyro.protocol.Bytes.s16le
import app.gyro.protocol.Bytes.u16le
import app.gyro.protocol.Bytes.u32le
import app.gyro.protocol.Bytes.u8

/**
 * Ninebot Z6/Z8/Z10 on the Nordic UART service.
 *
 * Frames: `5A A5 <len> <src> <dst> <cmd> <param> <data…> <crc16 LE>`; everything after the length
 * byte is XOR-ed with a 16-byte key the wheel hands out on request (all zeros before that).
 * The wheel is polled for its live-data block. Layout follows WheelLog's NinebotZAdapter.
 */
class NinebotZProtocol(bleName: String?) : BaseProtocol(ProtocolFamily.NINEBOT_Z, bleName) {

    private enum class Stage { BLE_VERSION, KEY, SERIAL, FIRMWARE, RUN }

    private var gamma = ByteArray(16)
    private val unpacker = Unpacker()
    private var stage = Stage.BLE_VERSION
    private var stageAttempts = 0
    private var lastRequestMs = 0L
    private var answered = true
    private var lockMode: Int? = null
    private var lastParamsMs = 0L

    init {
        info = info.copy(
            brand = WheelBrand.NINEBOT,
            model = "Ninebot Z",
            supportLevel = SupportLevel.EXPERIMENTAL,
            capabilities = setOf(Capability.LOCK, Capability.BATTERY_CURRENT),
        )
        updateCells(14, CellsSource.MODEL_TABLE)
    }

    override fun onConnected(nowMs: Long): List<Outbound> {
        lastRequestMs = nowMs
        answered = false
        return listOf(Outbound(frame(ADDR_CONTROLLER, CMD_READ, PARAM_BLE_VERSION, Bytes.of(0x02))))
    }

    override fun poll(nowMs: Long): List<Outbound> {
        val elapsed = nowMs - lastRequestMs
        if (!((answered && elapsed >= 25) || elapsed >= 250)) return emptyList()
        if (stage != Stage.RUN && stageAttempts >= 8) advance()
        val request = when (stage) {
            Stage.BLE_VERSION -> frame(ADDR_CONTROLLER, CMD_READ, PARAM_BLE_VERSION, Bytes.of(0x02))
            Stage.KEY -> frame(ADDR_KEY_GENERATOR, CMD_GET_KEY, PARAM_GET_KEY, ByteArray(0))
            Stage.SERIAL -> frame(ADDR_CONTROLLER, CMD_READ, PARAM_SERIAL, Bytes.of(0x0E))
            Stage.FIRMWARE -> frame(ADDR_CONTROLLER, CMD_READ, PARAM_FIRMWARE, Bytes.of(0x06))
            Stage.RUN -> if (nowMs - lastParamsMs > 10_000) {
                lastParamsMs = nowMs
                frame(ADDR_CONTROLLER, CMD_READ, PARAM_LOCK_MODE, Bytes.of(0x20))
            } else {
                frame(ADDR_CONTROLLER, CMD_READ, PARAM_LIVE_DATA, Bytes.of(0x20))
            }
        }
        if (stage != Stage.RUN) stageAttempts++
        lastRequestMs = nowMs
        answered = false
        return listOf(Outbound(request))
    }

    private fun advance() {
        stage = Stage.entries[minOf(stage.ordinal + 1, Stage.RUN.ordinal)]
        stageAttempts = 0
    }

    override fun decode(chunk: ByteArray, nowMs: Long): DecodeResult {
        var updated = false
        for (b in chunk) {
            val raw = unpacker.add(b) ?: continue
            val m = verify(raw) ?: continue
            answered = true
            updated = handle(m, nowMs) || updated
        }
        return DecodeResult(updated)
    }

    private class Message(val source: Int, val command: Int, val param: Int, val data: ByteArray)

    private fun handle(m: Message, nowMs: Long): Boolean {
        when {
            m.source == ADDR_CONTROLLER && m.param == PARAM_BLE_VERSION -> if (stage == Stage.BLE_VERSION) advance()
            m.source == ADDR_KEY_GENERATOR && m.param == PARAM_GET_KEY -> {
                if (m.data.size >= 16) gamma = m.data.copyOf(16)
                if (stage == Stage.KEY) advance()
            }
            m.source == ADDR_CONTROLLER && m.param == PARAM_SERIAL -> {
                info = info.copy(serial = ascii(m.data, 0, m.data.size))
                if (stage == Stage.SERIAL) advance()
            }
            m.source == ADDR_CONTROLLER && m.param == PARAM_FIRMWARE -> {
                val d = m.data
                info = info.copy(firmware = "%X.%X.%X".format(u8(d, 1) and 0x0F, (u8(d, 0) shr 4) and 0x0F, u8(d, 0) and 0x0F))
                if (stage == Stage.FIRMWARE) advance()
            }
            m.source == ADDR_CONTROLLER && m.param == PARAM_LOCK_MODE -> if (m.data.size >= 2) {
                lockMode = u16le(m.data, 0)
            }
            m.source == ADDR_CONTROLLER && m.param == PARAM_LIVE_DATA -> {
                if (stage != Stage.RUN) stage = Stage.RUN
                return parseLive(m.data, nowMs)
            }
            m.source == ADDR_BMS1 || m.source == ADDR_BMS2 -> parseBms(m)
        }
        return false
    }

    private fun parseLive(d: ByteArray, nowMs: Long): Boolean {
        if (d.size < 28) return false
        val voltage = u16le(d, 24) / 100.0
        val errorCode = u16le(d, 0)
        alerts = if (errorCode != 0) setOf(WheelAlert("ninebot.error_$errorCode", errorText(errorCode))) else emptySet()
        publish(
            telemetry.copy(
                timestampMs = nowMs,
                batteryPercent = u16le(d, 8).toDouble(),
                batteryPercentFromWheel = true,
                speedKmh = u16le(d, 10) / 100.0,
                totalDistanceM = u32le(d, 14),
                tripDistanceM = u16le(d, 18) * 10L,
                temperatureC = s16le(d, 22) / 10.0,
                voltageV = voltage,
                batteryCurrentA = s16le(d, 26) / 100.0,
                batteryCurrentEstimated = false,
            ),
        )
        return true
    }

    private fun parseBms(m: Message) {
        val index = if (m.source == ADDR_BMS1) 0 else 1
        val d = m.data
        when (m.param) {
            0x30 -> if (d.size >= 12) setBms(
                index,
                bms(index).copy(
                    remainingCapacityMah = u16le(d, 2),
                    remainingPercent = u16le(d, 4),
                    currentA = s16le(d, 6) / 100.0,
                    voltageV = u16le(d, 8) / 100.0,
                    temperaturesC = listOf((Bytes.s8(d, 10) - 20).toDouble(), (Bytes.s8(d, 11) - 20).toDouble()),
                ),
            )
            0x40 -> if (d.size >= 28) {
                val cells = (0 until 16).map { u16le(d, it * 2) / 1000.0 }.filter { it > 0.5 }
                setBms(index, bms(index).copy(cellVoltages = cells))
                addCapabilities(Capability.BMS_CELLS)
            }
        }
    }

    override fun encode(action: WheelAction): List<Outbound>? = when (action) {
        is WheelAction.Lock -> listOf(
            Outbound(frame(ADDR_CONTROLLER, CMD_WRITE, PARAM_LOCK_MODE, Bytes.of(if (action.locked) 1 else 0, 0))),
        )
        WheelAction.Beep, is WheelAction.Light -> null
    }

    private fun frame(destination: Int, command: Int, param: Int, data: ByteArray): ByteArray =
        encodeFrame(gamma, ADDR_APP, destination, command, param, data)

    private fun verify(raw: ByteArray): Message? {
        val plain = crypt(gamma, raw.copyOfRange(2, raw.size))
        if (plain.size < 7) return null
        val expected = u16le(plain, plain.size - 2)
        if (checksum(plain, plain.size - 2) != expected) return null
        return Message(
            source = u8(plain, 1),
            command = u8(plain, 3),
            param = u8(plain, 4),
            data = plain.copyOfRange(5, plain.size - 2),
        )
    }

    private fun errorText(code: Int): String = when (code) {
        1 -> "Ошибка датчиков Холла мотора"
        8, 9 -> "Ошибка входа батареи"
        10, 11 -> "Нет связи с батареей"
        12 -> "Ошибка инициализации гироскопа"
        24 -> "Напряжение вне допустимого диапазона"
        28, 29 -> "Нестабильное питание от батареи"
        34, 35 -> "Большой разброс напряжений ячеек"
        else -> "Ошибка колеса $code"
    }

    private class Unpacker {
        private val buffer = java.io.ByteArrayOutputStream()
        private var state = 0 // 0 hunting, 1 length, 2 collecting
        private var previous = -1
        private var length = 0

        fun add(byte: Byte): ByteArray? {
            val c = byte.toInt() and 0xFF
            when (state) {
                0 -> {
                    if (c == 0xA5 && previous == 0x5A) {
                        buffer.reset()
                        buffer.write(0x5A)
                        buffer.write(0xA5)
                        state = 1
                    }
                    previous = c
                }
                1 -> {
                    buffer.write(c)
                    length = c
                    state = 2
                }
                else -> {
                    buffer.write(c)
                    if (buffer.size() == length + 9) {
                        state = 0
                        previous = -1
                        return buffer.toByteArray()
                    }
                }
            }
            return null
        }
    }

    companion object {
        internal const val ADDR_BMS1 = 0x11
        internal const val ADDR_BMS2 = 0x12
        internal const val ADDR_CONTROLLER = 0x14
        internal const val ADDR_KEY_GENERATOR = 0x16
        internal const val ADDR_APP = 0x3E
        internal const val CMD_READ = 0x01
        internal const val CMD_WRITE = 0x03
        internal const val CMD_GET_KEY = 0x5B
        internal const val PARAM_GET_KEY = 0x00
        internal const val PARAM_SERIAL = 0x10
        internal const val PARAM_FIRMWARE = 0x1A
        internal const val PARAM_BLE_VERSION = 0x68
        internal const val PARAM_LOCK_MODE = 0x70
        internal const val PARAM_LIVE_DATA = 0xB0

        /** Builds `5A A5 len src dst cmd param data crc` and encrypts it with [gamma]. */
        internal fun encodeFrame(gamma: ByteArray, source: Int, destination: Int, command: Int, param: Int, data: ByteArray): ByteArray {
            val body = ByteArray(data.size + 5)
            body[0] = data.size.toByte()
            body[1] = source.toByte()
            body[2] = destination.toByte()
            body[3] = command.toByte()
            body[4] = param.toByte()
            data.copyInto(body, 5)
            val crc = checksum(body, body.size)
            val plain = body + byteArrayOf((crc and 0xFF).toByte(), ((crc shr 8) and 0xFF).toByte())
            return byteArrayOf(0x5A, 0xA5.toByte()) + crypt(gamma, plain)
        }

        /** Everything after the length byte is XOR-ed with the session key. */
        internal fun crypt(gamma: ByteArray, buffer: ByteArray): ByteArray {
            val out = buffer.copyOf()
            for (j in 1 until out.size) out[j] = (out[j].toInt() xor gamma[(j - 1) % 16].toInt()).toByte()
            return out
        }

        /** Sum of bytes XOR 0xFFFF. */
        fun checksum(buffer: ByteArray, length: Int): Int {
            var sum = 0
            for (i in 0 until length) sum += buffer[i].toInt() and 0xFF
            return (sum xor 0xFFFF) and 0xFFFF
        }
    }
}
