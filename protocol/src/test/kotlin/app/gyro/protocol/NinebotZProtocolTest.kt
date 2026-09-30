package app.gyro.protocol

import app.gyro.protocol.NinebotZProtocol.Companion.ADDR_APP
import app.gyro.protocol.NinebotZProtocol.Companion.ADDR_CONTROLLER
import app.gyro.protocol.NinebotZProtocol.Companion.ADDR_KEY_GENERATOR
import app.gyro.protocol.NinebotZProtocol.Companion.CMD_GET_KEY
import app.gyro.protocol.NinebotZProtocol.Companion.CMD_READ
import app.gyro.protocol.NinebotZProtocol.Companion.PARAM_BLE_VERSION
import app.gyro.protocol.NinebotZProtocol.Companion.PARAM_GET_KEY
import app.gyro.protocol.NinebotZProtocol.Companion.PARAM_LIVE_DATA
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NinebotZProtocolTest {

    private val zeroKey = ByteArray(16)
    private val key = ByteArray(16) { (it * 17 + 3).toByte() }

    private fun live(): ByteArray {
        val d = ByteArray(32)
        d.putLe16(8, 76)            // 76 %
        d.putLe16(10, 2450)         // 24.50 km/h
        d.putLe32(14, 1_500_000)    // odometer
        d.putLe16(18, 523)          // trip 5 230 m
        d.putLe16(22, 315)          // 31.5 °C
        d.putLe16(24, 5520)         // 55.20 V
        d.putLe16(26, 830)          // 8.30 A
        return d
    }

    @Test
    fun `checksum and encryption round trip after key exchange`() {
        val p = NinebotZProtocol("NinebotZ10")
        p.onConnected(0)
        p.decode(NinebotZProtocol.encodeFrame(zeroKey, ADDR_CONTROLLER, ADDR_APP, CMD_READ, PARAM_BLE_VERSION, Bytes.of(0x01, 0x02)), 10)
        // The key itself arrives unencrypted (zero key); afterwards everything is XOR-ed with it.
        p.decode(NinebotZProtocol.encodeFrame(zeroKey, ADDR_KEY_GENERATOR, ADDR_APP, CMD_GET_KEY, PARAM_GET_KEY, key), 20)
        val encrypted = NinebotZProtocol.encodeFrame(key, ADDR_CONTROLLER, ADDR_APP, CMD_READ, PARAM_LIVE_DATA, live())
        assertTrue(p.decode(encrypted, 30).telemetryUpdated)
        val t = p.state.telemetry
        assertEquals(76.0, t.batteryPercent!!, EPS)
        assertEquals(24.5, t.speedKmh, EPS)
        assertEquals(1_500_000L, t.totalDistanceM)
        assertEquals(5_230L, t.tripDistanceM)
        assertEquals(31.5, t.temperatureC!!, EPS)
        assertEquals(55.2, t.voltageV, EPS)
        assertEquals(8.3, t.batteryCurrentA!!, EPS)
    }

    @Test
    fun `frame encrypted with the wrong key is rejected`() {
        val p = NinebotZProtocol(null)
        val wrong = NinebotZProtocol.encodeFrame(key, ADDR_CONTROLLER, ADDR_APP, CMD_READ, PARAM_LIVE_DATA, live())
        assertFalse(p.decode(wrong, 0).telemetryUpdated)
    }

    @Test
    fun `requests are polled in order`() {
        val p = NinebotZProtocol(null)
        val first = p.onConnected(0).single().bytes
        assertEquals(0x5A, first[0].toInt() and 0xFF)
        assertEquals(0xA5, first[1].toInt() and 0xFF)
        assertEquals(PARAM_BLE_VERSION, first[6].toInt() and 0xFF)
        p.decode(NinebotZProtocol.encodeFrame(zeroKey, ADDR_CONTROLLER, ADDR_APP, CMD_READ, PARAM_BLE_VERSION, Bytes.of(1)), 10)
        val keyRequest = p.poll(40).single().bytes
        assertEquals(ADDR_KEY_GENERATOR, keyRequest[4].toInt() and 0xFF)
    }

    @Test
    fun `lock is supported, beep is not`() {
        val p = NinebotZProtocol(null)
        assertEquals(1, p.encode(WheelAction.Lock(true))!!.size)
        assertEquals(null, p.encode(WheelAction.Beep))
    }
}
