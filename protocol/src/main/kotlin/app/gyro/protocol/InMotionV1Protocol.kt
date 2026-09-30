package app.gyro.protocol

import app.gyro.protocol.Bytes.s8
import app.gyro.protocol.Bytes.u16le
import app.gyro.protocol.Bytes.u32le
import app.gyro.protocol.Bytes.u8
import kotlin.math.abs

/**
 * Older InMotion wheels (V5, V8, V8F/S, V10/F/S/T, Glide 3, R-series) using CAN frames wrapped in
 * `AA AA <escaped frame> <sum> 55 55` on separate notify (FFE4) and write (FFE9) characteristics.
 *
 * The wheel needs the 6-digit Bluetooth password (default 000000) before it answers, and must be
 * polled continuously. Layout follows WheelLog's InMotionAdapter.
 */
class InMotionV1Protocol(bleName: String?, private val password: String = "000000") :
    BaseProtocol(ProtocolFamily.INMOTION_V1, bleName) {

    private enum class Model(val code: String, val title: String, val speedFactor: Double, val cells: Int?, val modernModes: Boolean) {
        R1N("0", "R1N", 3812.0, null, false), R1S("1", "R1S", 1000.0, null, false),
        R1CF("2", "R1CF", 3812.0, null, false), R1AP("3", "R1AP", 3812.0, null, false),
        R1EX("4", "R1EX", 3812.0, null, false), R1_SAMPLE("5", "R1 Sample", 1000.0, null, false),
        R1T("6", "R1T", 3810.0, null, false), R10("7", "R10", 3812.0, null, false),
        V3("10", "V3", 3812.0, null, false), V3C("11", "V3C", 3812.0, null, false),
        V3PRO("12", "V3 Pro", 3812.0, null, false), V3S("13", "V3S", 3812.0, null, false),
        R2("20", "R2", 3812.0, null, false), R2N("21", "R2N", 3812.0, null, false),
        R2S("22", "R2S", 3812.0, null, false), R2_SAMPLE("23", "R2 Sample", 3812.0, null, false),
        R2EX("24", "R2EX", 3812.0, null, false), R0("30", "R0", 1000.0, null, false),
        V5("50", "V5", 3812.0, 16, false), V5PLUS("51", "V5+", 3812.0, 16, false),
        V5F("52", "V5F", 3812.0, 16, false), V5D("53", "V5D", 3812.0, 16, false),
        L6("60", "L6", 3812.0, null, false), LIVELY("61", "Lively", 3812.0, null, false),
        V8("80", "V8", 3812.0, 20, false), GLIDE3("85", "Glide 3", 3812.0, 20, false),
        V8F("86", "V8F", 3812.0, 20, true), V8S("87", "V8S", 3812.0, 20, true),
        V10S("100", "V10S", 3812.0, 20, true), V10SF("101", "V10SF", 3812.0, 20, true),
        V10("140", "V10", 3812.0, 20, true), V10F("141", "V10F", 3812.0, 20, true),
        V10T("142", "V10T", 3812.0, 20, true), V10FT("143", "V10FT", 3812.0, 20, true);

        /** V3, V5, V8 and V10 families store total distance in metres; others use odd scales. */
        val metreOdometer: Boolean
            get() = (code.length == 2 && (code[0] == '1' || code[0] == '5')) ||
                this in setOf(V8, GLIDE3, V8F, V8S, V10, V10F, V10S, V10SF, V10T, V10FT)
    }

    private val unpacker = Unpacker()
    private var model: Model? = null
    private var passwordSent = 0
    private var needSlowInfo = true
    private var lastRequestMs = 0L
    private var answered = true

    init {
        info = info.copy(
            brand = WheelBrand.INMOTION,
            supportLevel = SupportLevel.EXPERIMENTAL,
            capabilities = setOf(Capability.BEEP, Capability.LIGHT, Capability.BATTERY_CURRENT),
        )
    }

    override fun onConnected(nowMs: Long): List<Outbound> {
        lastRequestMs = nowMs
        passwordSent = 1
        return listOf(Outbound(passwordFrame()))
    }

    override fun poll(nowMs: Long): List<Outbound> {
        val elapsed = nowMs - lastRequestMs
        if (!((answered && elapsed >= 25) || elapsed >= 250)) return emptyList()
        lastRequestMs = nowMs
        answered = false
        val frame = when {
            passwordSent < 6 -> {
                passwordSent++
                passwordFrame()
            }
            model == null || needSlowInfo -> CanMessage(ID_SLOW_INFO, FULL, remote = true).encode()
            else -> CanMessage(ID_FAST_INFO, FULL).encode()
        }
        return listOf(Outbound(frame))
    }

    override fun decode(chunk: ByteArray, nowMs: Long): DecodeResult {
        var updated = false
        for (b in chunk) {
            val msg = unpacker.add(b) ?: continue
            answered = true
            when (msg.id) {
                ID_FAST_INFO -> updated = parseFast(msg, nowMs) || updated
                ID_SLOW_INFO -> parseSlow(msg)
                ID_ALERT -> parseAlert(msg)
                ID_PIN_CODE -> passwordSent = Int.MAX_VALUE
            }
        }
        return DecodeResult(updated)
    }

    private fun parseFast(m: CanMessage, nowMs: Long): Boolean {
        val x = m.extended ?: return false
        if (x.size < 76) return false
        val model = model ?: Model.V8
        val speedMs = (u32le(x, 12).toInt() + u32le(x, 16).toInt()) / (model.speedFactor * 2.0)
        val voltage = u32le(x, 24) / 100.0
        guessCellsFromVoltage(voltage)
        val total = when {
            model.metreOdometer -> u32le(x, 44)
            model == Model.R0 -> u32le(x, 44)
            model == Model.L6 -> u32le(x, 44) * 100
            else -> Math.round(u32le(x, 44) / 5.711016379455429E7)
        }
        val workMode = u32le(x, 60).toInt()
        publish(
            telemetry.copy(
                timestampMs = nowMs,
                speedKmh = abs(speedMs * 3.6),
                voltageV = voltage,
                batteryCurrentA = u32le(x, 20).toInt() / 100.0,
                batteryCurrentEstimated = false,
                temperatureC = s8(x, 32).toDouble(),
                temperature2C = s8(x, 34).toDouble(),
                pitchDeg = u32le(x, 0).toInt() / 65536.0,
                rollDeg = if (model.modernModes) null else u32le(x, 72).toInt() / 90.0,
                totalDistanceM = total,
                tripDistanceM = u32le(x, 48),
                charging = if (model.modernModes) (workMode shr 4) == 3 else null,
                batteryPercent = estimatedBatteryPercent(voltage),
                batteryPercentFromWheel = false,
            ),
        )
        return true
    }

    private fun parseSlow(m: CanMessage) {
        val x = m.extended ?: return
        if (x.size < 108) return
        needSlowInfo = false
        val code = buildString {
            if (x[107] > 0) append(x[107].toInt())
            append(x[104].toInt())
        }
        val found = Model.entries.firstOrNull { it.code == code }
        model = found ?: Model.V8
        info = info.copy(
            model = found?.title ?: "Модель $code",
            serial = (7 downTo 0).joinToString("") { "%02X".format(u8(x, it)) },
            firmware = "${u8(x, 27)}.${u8(x, 26)}.${u16le(x, 24)}",
        )
        found?.cells?.let { updateCells(it, CellsSource.MODEL_TABLE) }
        settings[WheelParam.TILTBACK_SPEED] = u16le(x, 60) / 1000
        telemetry = telemetry.copy(lightOn = u8(x, 80) == 1)
    }

    private fun parseAlert(m: CanMessage) {
        val text = when (u8(m.data, 0)) {
            0x05 -> "Старт с большим наклоном"
            0x06 -> "Tiltback: колесо ограничивает скорость"
            0x19 -> "Падение"
            0x20 -> "Низкий заряд батареи"
            0x21 -> "Ограничение скорости"
            0x26 -> "Высокая нагрузка"
            0x1D -> "Требуется ремонт: неисправная ячейка батареи"
            else -> "Предупреждение колеса 0x%02X".format(u8(m.data, 0))
        }
        alerts = setOf(WheelAlert("inmotion_v1.%02x".format(u8(m.data, 0)), text))
    }

    override fun encode(action: WheelAction): List<Outbound>? = when (action) {
        WheelAction.Beep -> listOf(
            Outbound(
                if (model?.modernModes == true) {
                    CanMessage(ID_REMOTE_CONTROL, Bytes.of(0xB2, 0, 0, 0, 0x11, 0, 0, 0)).encode()
                } else {
                    CanMessage(ID_PLAY_SOUND, Bytes.of(0x04, 0, 0, 0, 0, 0, 0, 0)).encode()
                },
            ),
        )
        is WheelAction.Light -> listOf(Outbound(CanMessage(ID_LIGHT, Bytes.of(if (action.on) 1 else 0, 0, 0, 0, 0, 0, 0, 0)).encode()))
        is WheelAction.Lock -> null
    }

    private fun passwordFrame(): ByteArray {
        val p = password.padEnd(6, '0').toByteArray(Charsets.US_ASCII)
        return CanMessage(ID_PIN_CODE, byteArrayOf(p[0], p[1], p[2], p[3], p[4], p[5], 0, 0)).encode()
    }

    /** 16-byte CAN frame: id(4 LE) data(8) len ch format type, plus extended payload when len = 0xFE. */
    internal class CanMessage(
        val id: Long,
        val data: ByteArray,
        val remote: Boolean = false,
        val extended: ByteArray? = null,
    ) {
        fun encode(): ByteArray {
            val raw = java.io.ByteArrayOutputStream()
            raw.write((id and 0xFF).toInt())
            raw.write(((id shr 8) and 0xFF).toInt())
            raw.write(((id shr 16) and 0xFF).toInt())
            raw.write(((id shr 24) and 0xFF).toInt())
            raw.write(data, 0, 8)
            raw.write(if (extended != null) 0xFE else 8)
            raw.write(5)       // channel
            raw.write(0)       // standard format
            raw.write(if (remote) 1 else 0)
            extended?.let { raw.write(it) }
            val body = raw.toByteArray()
            var sum = 0
            body.forEach { sum = (sum + (it.toInt() and 0xFF)) and 0xFF }
            val out = java.io.ByteArrayOutputStream()
            out.write(0xAA)
            out.write(0xAA)
            for (b in body) {
                val v = b.toInt() and 0xFF
                if (v == 0xAA || v == 0x55 || v == 0xA5) out.write(0xA5)
                out.write(v)
            }
            out.write(sum)
            out.write(0x55)
            out.write(0x55)
            return out.toByteArray()
        }

        companion object {
            fun decode(body: ByteArray): CanMessage? {
                if (body.size < 16) return null
                val id = u32le(body, 0)
                val data = body.copyOfRange(4, 12)
                val len = u8(body, 12)
                var extended: ByteArray? = null
                if (len == 0xFE) {
                    val extLen = u32le(data, 0).toInt()
                    if (extLen != body.size - 16) return null
                    extended = body.copyOfRange(16, 16 + extLen)
                }
                return CanMessage(id, data, remote = u8(body, 15) != 0, extended = extended)
            }
        }
    }

    /** Unescapes `AA AA … sum 55 55` frames; the expected length comes from the CAN header. */
    internal class Unpacker {
        private val buf = java.io.ByteArrayOutputStream()
        private var collecting = false
        private var escaped = false
        private var previousRaw = -1

        fun add(byte: Byte): CanMessage? {
            val c = byte.toInt() and 0xFF
            if (!escaped && c == 0xA5) {
                escaped = true
                return null
            }
            val literal = escaped
            escaped = false
            if (!literal && c == 0xAA && previousRaw == 0xAA) {
                buf.reset()
                collecting = true
                previousRaw = -1
                return null
            }
            previousRaw = if (literal) -1 else c
            if (!collecting) return null
            buf.write(c)
            val size = buf.size()
            if (size < 16) return null
            val bytes = buf.toByteArray()
            val len = u8(bytes, 12)
            val expected = if (len == 0xFE) 16 + u32le(bytes, 4).toInt() + 3 else 16 + 3
            if (expected > 4096) {
                collecting = false
                return null
            }
            if (size < expected) return null
            collecting = false
            previousRaw = -1
            if (u8(bytes, size - 1) != 0x55 || u8(bytes, size - 2) != 0x55) return null
            val body = bytes.copyOfRange(0, size - 3)
            var sum = 0
            body.forEach { sum = (sum + (it.toInt() and 0xFF)) and 0xFF }
            if (sum != u8(bytes, size - 3)) return null
            return CanMessage.decode(body)
        }
    }

    companion object {
        const val ID_FAST_INFO = 0x0F550113L
        const val ID_SLOW_INFO = 0x0F550114L
        const val ID_REMOTE_CONTROL = 0x0F550116L
        const val ID_PIN_CODE = 0x0F550307L
        const val ID_LIGHT = 0x0F55010DL
        const val ID_PLAY_SOUND = 0x0F550609L
        const val ID_ALERT = 0x0F780101L
        private val FULL = Bytes.of(0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF)
    }
}
