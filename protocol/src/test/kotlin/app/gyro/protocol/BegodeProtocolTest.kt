package app.gyro.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BegodeProtocolTest {

    // Sample frames documented in WheelLog's GotwayAdapter.
    private val frameA = hex("55AA 19F0 0000 0000 0000 012C FDCA 0001 FFF8 00 18 5A5A5A5A")
    private val frameB = hex("55AA 000A 4A12 4800 1C20 002A 0003 0007 0008 04 18 5A5A5A5A")

    @Test
    fun `decodes documented live frame`() {
        val p = BegodeProtocol("GotWay_1234")
        assertTrue(p.decode(frameA, 500).telemetryUpdated)
        val t = p.state.telemetry
        // 66.40 V is 16S-scaled; the default guess is a 24S pack.
        assertEquals(66.40 * 24 / 16, t.voltageV, 1e-9)
        assertEquals(0.0, t.speedKmh, EPS)
        assertEquals(3.0, t.phaseCurrentA!!, EPS)
        assertEquals(-566 / 340.0 + 36.53, t.temperatureC!!, 1e-9)
        assertEquals((66.40 / 16 - 3.325) / 0.0085, t.batteryPercent!!, 1e-6)
        assertEquals(CellsSource.DEFAULT_GUESS, p.state.info.cellsSource)
        assertNull("stock firmware reports no PWM in frame A", t.pwmPercent)
    }

    @Test
    fun `decodes documented settings frame`() {
        val p = BegodeProtocol(null)
        p.decode(frameB, 0)
        val s = p.state.settings
        assertEquals(674_322L, p.state.telemetry.totalDistanceM)
        assertEquals(0, s[WheelParam.PEDALS_MODE])
        assertEquals(2, s[WheelParam.ALARM_MODE])
        assertEquals(0, s[WheelParam.ROLL_ANGLE])
        assertEquals(7200, s[WheelParam.AUTO_POWER_OFF])
        assertEquals(42, s[WheelParam.TILTBACK_SPEED])
        assertEquals(3, s[WheelParam.LED_MODE])
        assertEquals(3, s[WheelParam.LIGHT_MODE])
        assertTrue(p.state.alerts.isEmpty())
    }

    @Test
    fun `reassembles frames split across notifications with garbage`() {
        val p = BegodeProtocol(null)
        val stream = hex("5A5A 55AA 5A") + frameA + frameB + frameA
        var updates = 0
        stream.toList().chunked(7).forEach { if (p.decode(it.toByteArray(), 0).telemetryUpdated) updates++ }
        assertEquals(2, updates)
        assertEquals(674_322L, p.state.telemetry.totalDistanceM)
    }

    @Test
    fun `rejects frame with broken footer`() {
        val p = BegodeProtocol(null)
        val broken = frameA.copyOf().also { it[21] = 0x00 }
        assertFalse(p.decode(broken, 0).telemetryUpdated)
    }

    @Test
    fun `frame 07 gives battery current and hardware pwm`() {
        val p = BegodeProtocol(null)
        val f07 = hex("55AA 0000 0000 0000 0000 0000 0000 0000 0000 07 18 5A5A5A5A")
            .putBe16(2, -1250)   // raw −12.50 A → 12.50 A out of the battery
            .putBe16(6, 55)      // motor 55 °C
            .putBe16(8, 48)      // 48 % PWM
        p.decode(f07, 0)
        p.decode(frameA, 100)
        val t = p.state.telemetry
        assertEquals(12.5, t.batteryCurrentA!!, EPS)
        assertFalse(t.batteryCurrentEstimated)
        assertEquals(48.0, t.pwmPercent!!, EPS)
        assertEquals(55.0, t.motorTemperatureC!!, EPS)
    }

    @Test
    fun `estimates battery current from phase current and pwm`() {
        // Custom firmware reports PWM in frame A but no battery current, so it is estimated.
        val custom = BegodeProtocol(null)
        custom.decode("CF1.23".toByteArray(), 0)
        val a = frameA.copyOf().putBe16(14, 500).putBe16(10, 2000) // 50.0 % PWM, 20 A phase
        custom.decode(a, 0)
        val t = custom.state.telemetry
        assertEquals(50.0, t.pwmPercent!!, EPS)
        assertEquals(10.0, t.batteryCurrentA!!, EPS)
        assertTrue(t.batteryCurrentEstimated)
    }

    @Test
    fun `name text sets model and series count`() {
        val p = BegodeProtocol(null)
        p.decode("NAME Master Pro".toByteArray(), 0)
        assertEquals("Master Pro", p.state.info.model)
        assertEquals(32, p.state.info.cellsSeries)
        assertEquals(CellsSource.MODEL_TABLE, p.state.info.cellsSource)
    }

    @Test
    fun `extreme bull firmware sets brand`() {
        val p = BegodeProtocol(null)
        p.decode("JN2.10".toByteArray(), 0)
        assertEquals(WheelBrand.EXTREME_BULL, p.state.info.brand)
        assertEquals("2.10", p.state.info.firmware)
    }

    @Test
    fun `true voltage frame measures the series count`() {
        val p = BegodeProtocol(null)
        p.decode(frameA, 0) // raw 66.40 V
        val f01 = hex("55AA 0000 0000 0000 0000 0000 0000 0000 0000 01 00 5A5A5A5A").putBe16(6, 1328) // 132.8 V
        p.decode(f01, 0)
        assertEquals(32, p.state.info.cellsSeries)
        assertEquals(CellsSource.MEASURED, p.state.info.cellsSource)
    }

    @Test
    fun `alert bits become wheel alerts`() {
        val p = BegodeProtocol(null)
        val withAlert = frameB.copyOf().also { it[14] = 0b0010_0001 }
        p.decode(withAlert, 0)
        assertEquals(setOf("begode.high_power", "begode.over_temperature"), p.state.alerts.map { it.code }.toSet())
    }

    @Test
    fun `polls firmware then name until answered`() {
        val p = BegodeProtocol(null)
        assertEquals("V", String(p.poll(1_000).single().bytes))
        assertTrue(p.poll(1_100).isEmpty())
        p.decode("GW1.23".toByteArray(), 1_150)
        assertEquals("N", String(p.poll(1_400).single().bytes))
        p.decode("NAME RS".toByteArray(), 1_450)
        assertTrue(p.poll(2_000).isEmpty())
    }

    @Test
    fun `tiltback write sequence`() {
        val p = BegodeProtocol(null)
        p.decode(frameB, 0)
        val review = SettingsWriteGuard.review(
            wheelId = "w", current = p.state.settings, desired = mapOf(WheelParam.TILTBACK_SPEED to 38),
            writable = p.writableParams, backup = FactoryBackup("w", p.state.settings, 0), batteryPercent = 80.0,
        )
        val permit = SettingsWriteGuard.authorize(review, review.changes.toSet(), review.warnings.toSet())
        val sent = p.encodeSettings(permit)!!.joinToString("") { String(it.bytes) }
        assertEquals("bWY38bb", sent)
    }
}
