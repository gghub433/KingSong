package app.gyro.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.zip.CRC32

class VeteranProtocolTest {

    /** Old-format (no CRC) frame for a Sherman S, firmware 3.0.35. */
    private fun oldFrame(): ByteArray {
        val f = ByteArray(36)
        f[0] = 0xDC.toByte(); f[1] = 0x5A; f[2] = 0x5C; f[3] = 32
        f.putBe16(4, 10050)          // 100.50 V
        f.putBe16(6, 305)            // 30.5 km/h
        f.putBe16(8, 0x1170); f.putBe16(10, 0x0001)      // trip 70 000 m (low word first)
        f.putBe16(12, 0xD687); f.putBe16(14, 0x0012)     // odometer 1 234 567 m
        f.putBe16(16, 123)           // 12.3 A phase
        f.putBe16(18, 4100)          // 41.00 °C
        f.putBe16(20, 3600)          // auto power off
        f.putBe16(22, 0)             // not charging
        f.putBe16(24, 450)           // speed alert 45.0
        f.putBe16(26, 500)           // tiltback 50.0
        f.putBe16(28, 3035)          // model code 3 → Sherman S
        f.putBe16(30, 2)             // pedals
        f.putBe16(32, -150)          // pitch −1.50°
        f.putBe16(34, 4550)          // PWM 45.50 %
        return f
    }

    @Test
    fun `decodes old format frame`() {
        val p = VeteranProtocol("LK1234")
        assertTrue(p.decode(oldFrame(), 0).telemetryUpdated)
        val s = p.state
        val t = s.telemetry
        assertEquals(100.5, t.voltageV, EPS)
        assertEquals(30.5, t.speedKmh, EPS)
        assertEquals(70_000L, t.tripDistanceM)
        assertEquals(1_234_567L, t.totalDistanceM)
        assertEquals(12.3, t.phaseCurrentA!!, EPS)
        assertEquals(41.0, t.temperatureC!!, EPS)
        assertEquals(-1.5, t.pitchDeg!!, EPS)
        assertEquals(45.5, t.pwmPercent!!, EPS)
        assertEquals(12.3 * 0.455, t.batteryCurrentA!!, 1e-9)
        assertEquals("Sherman S", s.info.model)
        assertEquals("003.0.35", s.info.firmware)
        assertEquals(24, s.info.cellsSeries)
        assertEquals(50, s.settings[WheelParam.TILTBACK_SPEED])
        assertEquals(45, s.settings[WheelParam.SPEED_ALERT])
    }

    @Test
    fun `decodes frame split in small chunks`() {
        val p = VeteranProtocol(null)
        var updated = false
        oldFrame().toList().chunked(5).forEachIndexed { i, c -> updated = p.decode(c.toByteArray(), i * 10L).telemetryUpdated || updated }
        assertTrue(updated)
        assertEquals(100.5, p.state.telemetry.voltageV, EPS)
    }

    @Test
    fun `validates crc on long frames`() {
        val payload = oldFrame().copyOf(46)
        payload[3] = 42
        payload.putBe16(28, 5010) // Lynx
        val crc = CRC32().apply { update(payload, 0, 42) }.value
        payload.putBe32(42, crc)
        val p = VeteranProtocol(null)
        assertTrue(p.decode(payload, 0).telemetryUpdated)
        assertEquals("Lynx", p.state.info.model)
        assertEquals(36, p.state.info.cellsSeries)

        val corrupted = payload.copyOf().also { it[10] = 0x7F }
        assertFalse(VeteranProtocol(null).decode(corrupted, 0).telemetryUpdated)
    }

    @Test
    fun `unknown model code is flagged as fallback`() {
        val f = oldFrame().putBe16(28, 12001)
        val p = VeteranProtocol(null)
        p.decode(f, 0)
        assertEquals(SupportLevel.FALLBACK, p.state.info.supportLevel)
        assertEquals(24, p.state.info.cellsSeries) // guessed from 100.5 V
    }

    @Test
    fun `nosfet models use the veteran protocol`() {
        val p = VeteranProtocol(null)
        p.decode(oldFrame().putBe16(28, 42001), 0)
        assertEquals(WheelBrand.NOSFET, p.state.info.brand)
        assertEquals("Nosfet Apex", p.state.info.model)
    }

    @Test
    fun `light and beep commands`() {
        val p = VeteranProtocol(null)
        p.decode(oldFrame(), 0)
        assertEquals("SetLightON", String(p.encode(WheelAction.Light(true))!!.single().bytes))
        assertEquals(14, p.encode(WheelAction.Beep)!!.single().bytes.size)
    }
}
