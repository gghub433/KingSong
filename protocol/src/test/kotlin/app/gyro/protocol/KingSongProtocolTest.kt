package app.gyro.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KingSongProtocolTest {

    private fun frame(type: Int, fill: (ByteArray) -> Unit = {}): ByteArray {
        val f = ByteArray(20)
        f[0] = 0xAA.toByte(); f[1] = 0x55
        f[16] = type.toByte(); f[17] = 0x14; f[18] = 0x5A; f[19] = 0x5A
        fill(f)
        return f
    }

    /** 84.00 V, 25.50 km/h, odometer 12 345 678 m, −3.50 A, 35.25 °C, ride mode 2. */
    private val live = hex("AA55 D020 F609 BC00 4E61 A2FE C50D 02E0 A9 14 5A5A")

    @Test
    fun `decodes live frame`() {
        val p = KingSongProtocol("KS-S18-1234")
        val result = p.decode(live, 1_000)
        assertTrue(result.telemetryUpdated)
        val t = p.state.telemetry
        assertEquals(84.0, t.voltageV, EPS)
        assertEquals(25.5, t.speedKmh, EPS)
        assertEquals(12_345_678L, t.totalDistanceM)
        assertEquals(-3.5, t.batteryCurrentA!!, EPS)
        assertEquals(35.25, t.temperatureC!!, EPS)
        assertEquals(2, t.rideModeCode)
        assertEquals(-294.0, t.powerW!!, EPS)
        assertEquals(1_000L, t.timestampMs)
        assertTrue(p.state.hasTelemetry)
    }

    @Test
    fun `name frame sets model firmware and series count`() {
        val p = KingSongProtocol(null)
        val name = frame(0xBB) { f -> "KS-S22-0243".toByteArray().copyInto(f, 2) }
        p.decode(name, 0)
        val info = p.state.info
        assertEquals("KS-S22", info.model)
        assertEquals("2.43", info.firmware)
        assertEquals(30, info.cellsSeries)
        assertEquals(CellsSource.MODEL_TABLE, info.cellsSource)
        assertEquals(WheelBrand.KINGSONG, info.brand)
    }

    @Test
    fun `battery percent uses model table once name arrives`() {
        val p = KingSongProtocol(null)
        p.decode(frame(0xBB) { f -> "KS-S22-0243".toByteArray().copyInto(f, 2) }, 0)
        // 120.00 V on 30S = 4.0 V per cell.
        p.decode(frame(0xA9) { f -> f.putLe16(2, 12000) }, 10)
        assertEquals((4.0 - 3.325) / 0.0085, p.state.telemetry.batteryPercent!!, 1e-3)
    }

    @Test
    fun `unknown new model falls back to a voltage guess`() {
        val p = KingSongProtocol(null)
        p.decode(frame(0xBB) { f -> "KS-X99-0101".toByteArray().copyInto(f, 2) }, 0)
        p.decode(frame(0xA9) { f -> f.putLe16(2, 17500) }, 10) // 175 V: too high for 40S
        assertEquals(42, p.state.info.cellsSeries)
        assertEquals(CellsSource.VOLTAGE_GUESS, p.state.info.cellsSource)
    }

    @Test
    fun `alarm frame fills settings and acknowledges 0xA4`() {
        val p = KingSongProtocol(null)
        val alarms = frame(0xA4) { f -> f[4] = 30; f[6] = 35; f[8] = 40; f[10] = 45 }
        val result = p.decode(alarms, 0)
        val s = p.state.settings
        assertEquals(30, s[WheelParam.ALARM_1_SPEED])
        assertEquals(35, s[WheelParam.ALARM_2_SPEED])
        assertEquals(40, s[WheelParam.ALARM_3_SPEED])
        assertEquals(45, s[WheelParam.TILTBACK_SPEED])
        assertEquals(1, result.outbound.size)
        assertEquals(0x98, result.outbound[0].bytes[16].toInt() and 0xFF)
        assertEquals(45, result.outbound[0].bytes[10].toInt())
    }

    @Test
    fun `pwm and cpu load frame`() {
        val p = KingSongProtocol(null)
        p.decode(frame(0xF5) { f -> f[14] = 33; f[15] = 67 }, 0)
        assertEquals(67.0, p.state.telemetry.pwmPercent!!, EPS)
        assertEquals(33, p.state.telemetry.cpuLoadPercent)
        assertTrue(Capability.HARDWARE_PWM in p.state.info.capabilities)
    }

    @Test
    fun `trip distance frame`() {
        val p = KingSongProtocol(null)
        // 70 000 m in KingSong word order: high word 0x0001 then low word 0x1170.
        p.decode(frame(0xB9) { f -> f.putLe16(2, 0x0001); f.putLe16(4, 0x1170); f.putLe16(8, 4550); f[13] = 1 }, 0)
        assertEquals(70_000L, p.state.telemetry.tripDistanceM)
        assertEquals(45.5, p.state.telemetry.topSpeedKmh!!, EPS)
        assertEquals(true, p.state.telemetry.charging)
    }

    @Test
    fun `bms frames build cell list and pack stats`() {
        val p = KingSongProtocol(null)
        val general = frame(0xF1) { f -> f[17] = 0; f.putLe16(2, 12000); f.putLe16(4, 150); f.putLe16(6, 1000); f.putLe16(8, 2000); f.putLe16(10, 42) }
        val out = p.decode(general, 0)
        assertEquals(1, out.outbound.size) // asks for the BMS serial once
        for (page in 2..6) {
            p.decode(frame(0xF1) { f ->
                f[17] = page.toByte()
                for (i in 0 until 7) f.putLe16(2 + i * 2, 4000 + page * 10 + i)
            }, 0)
        }
        val pack = p.state.bms.single()
        assertEquals(120.0, pack.voltageV!!, EPS)
        assertEquals(20_000, pack.factoryCapacityMah)
        assertEquals(50, pack.remainingPercent)
        assertEquals(42, pack.fullCycles)
        assertEquals(30, pack.cellCount)
        assertEquals(4.020, pack.cellVoltages[0], EPS)
        assertEquals(4.061, pack.cellVoltages[29], EPS)
        assertEquals(30, p.state.info.cellsSeries)
        assertEquals(CellsSource.BMS, p.state.info.cellsSource)
    }

    @Test
    fun `splits concatenated frames`() {
        val p = KingSongProtocol(null)
        val both = live + frame(0xF5) { f -> f[15] = 12 }
        assertTrue(p.decode(both, 0).telemetryUpdated)
        assertEquals(12.0, p.state.telemetry.pwmPercent!!, EPS)
    }

    @Test
    fun `ignores foreign bytes`() {
        val p = KingSongProtocol(null)
        assertFalse(p.decode(hex("55AA 0102 0304"), 0).telemetryUpdated)
        assertFalse(p.state.hasTelemetry)
    }

    @Test
    fun `encodes actions`() {
        val p = KingSongProtocol(null)
        assertArrayEquals(frame(0x88), p.encode(WheelAction.Beep)!!.single().bytes)
        val light = p.encode(WheelAction.Light(true))!!.single().bytes
        assertEquals(0x12, light[2].toInt())
        assertEquals(0x73, light[16].toInt())
        assertNull(p.encode(WheelAction.Lock(true)))
    }

    @Test
    fun `handshake requests name serial and alarms`() {
        val types = KingSongProtocol(null).onConnected(0).map { it.bytes[16].toInt() and 0xFF }
        assertEquals(listOf(0x9B, 0x63, 0x98), types)
    }
}
