package app.gyro.protocol

import app.gyro.protocol.InMotionV2Protocol.Message
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InMotionV2ProtocolTest {

    private fun carType(series: Int, type: Int) = Message.build(0x11, 0x02, Bytes.of(0x01, 0x02, series, type, 0x01, 0x01, 0x00))

    private fun v12Realtime(): ByteArray {
        val d = ByteArray(66)
        d.putLe16(0, 9800)      // 98.00 V
        d.putLe16(2, 1234)      // 12.34 A
        d.putLe16(4, 3210)      // 32.10 km/h
        d.putLe16(8, 4567)      // PWM 45.67 %
        d.putLe16(10, 1210)     // battery power 1210 W
        d[14] = 0xAA.toByte()   // bytes that must be escaped on the wire
        d[15] = 0xA5.toByte()
        d.putLe16(22, 1234)     // trip 12 340 m
        d.putLe16(24, 8765)     // 87.65 %
        d.putLe16(26, 3000)     // wheel range 30 km
        d[40] = (45 + 176).toByte()
        d[41] = (60 + 176).toByte()
        d[55] = 0x01            // low beam on
        d[59 + 3] = 0x40        // MOSFET over-temperature
        return Message.build(0x14, 0x04, d)
    }

    @Test
    fun `message escaping round trip`() {
        val frame = v12Realtime()
        val body = frame.copyOfRange(2, frame.size)
        assertTrue("escape byte present", body.any { it == 0xA5.toByte() })
        val unpacker = InMotionV2Protocol.Unpacker()
        var message: Message? = null
        frame.forEach { b -> unpacker.add(b)?.let { message = it } }
        assertNotNull(message)
        assertEquals(0x04, message!!.command)
        assertEquals(66, message!!.data.size)
        assertEquals(0xAA.toByte(), message!!.data[14])
        assertEquals(0xA5.toByte(), message!!.data[15])
    }

    @Test
    fun `bad checksum is rejected`() {
        val frame = v12Realtime()
        frame[frame.size - 1] = (frame.last() + 1).toByte()
        val unpacker = InMotionV2Protocol.Unpacker()
        assertNull(frame.firstNotNullOfOrNull { unpacker.add(it) })
    }

    @Test
    fun `identifies model and decodes V12 realtime`() {
        val p = InMotionV2Protocol("V12-123")
        p.onConnected(0)
        p.decode(carType(7, 1), 10)
        assertEquals("V12 HS", p.state.info.model)
        assertEquals(24, p.state.info.cellsSeries)
        assertTrue(p.decode(v12Realtime(), 20).telemetryUpdated)
        val t = p.state.telemetry
        assertEquals(98.0, t.voltageV, EPS)
        assertEquals(12.34, t.batteryCurrentA!!, EPS)
        assertEquals(32.1, t.speedKmh, EPS)
        assertEquals(45.67, t.pwmPercent!!, EPS)
        assertEquals(1210.0, t.powerW!!, EPS)
        assertEquals(12_340L, t.tripDistanceM)
        assertEquals(87.65, t.batteryPercent!!, EPS)
        assertTrue(t.batteryPercentFromWheel)
        assertEquals(30.0, t.wheelRangeKm!!, EPS)
        assertEquals(45.0, t.temperatureC!!, EPS)
        assertEquals(60.0, t.motorTemperatureC!!, EPS)
        assertEquals(true, t.lightOn)
        assertEquals(setOf("inmotion.mos_temp"), p.state.alerts.map { it.code }.toSet())
    }

    @Test
    fun `unknown new model uses newest layout as fallback`() {
        val p = InMotionV2Protocol(null)
        p.decode(carType(15, 1), 0)
        assertEquals(SupportLevel.FALLBACK, p.state.info.supportLevel)
        val d = ByteArray(84).putLe16(0, 12600).putLe16(8, 2500).putLe16(34, 9000).putLe16(36, 8000)
        assertTrue(p.decode(Message.build(0x14, 0x04, d), 0).telemetryUpdated)
        assertEquals(25.0, p.state.telemetry.speedKmh, EPS)
        assertEquals(85.0, p.state.telemetry.batteryPercent!!, EPS)
    }

    @Test
    fun `short realtime payload is ignored`() {
        val p = InMotionV2Protocol(null)
        p.decode(carType(7, 1), 0)
        assertFalse(p.decode(Message.build(0x14, 0x04, ByteArray(10)), 0).telemetryUpdated)
    }

    @Test
    fun `poll walks through identification then realtime`() {
        val p = InMotionV2Protocol(null)
        val first = p.onConnected(0).single().bytes
        assertArrayEquals(Message.carType(), first)
        assertTrue("waits for answer", p.poll(100).isEmpty())
        p.decode(carType(8, 1), 120)
        assertArrayEquals(Message.serial(), p.poll(150).single().bytes)
        p.decode(Message.build(0x11, 0x02, Bytes.of(0x02) + "1234567890ABCDEF".toByteArray()), 160)
        assertEquals("1234567890ABCDEF", p.state.info.serial)
        assertArrayEquals(Message.versions(), p.poll(200).single().bytes)
    }

    @Test
    fun `lock command`() {
        val p = InMotionV2Protocol(null)
        assertArrayEquals(Message.control(0x31, 1), p.encode(WheelAction.Lock(true)).single().bytes)
    }
}

class InMotionV1ProtocolTest {

    private fun fastInfo(): ByteArray {
        val x = ByteArray(80)
        x.putLe32(12, 3812L * 5); x.putLe32(16, 3812L * 5)   // 5 m/s → 18 km/h
        x.putLe32(20, -250L and 0xFFFFFFFFL)                // −2.50 A
        x.putLe32(24, 8000)                                // 80.00 V
        x[32] = 40
        x.putLe32(44, 5000)                                // odometer 5 000 m
        x.putLe32(48, 1234)                                // trip
        val data = ByteArray(8).putLe32(0, x.size.toLong())
        return InMotionV1Protocol.CanMessage(InMotionV1Protocol.ID_FAST_INFO, data, extended = x).encode()
    }

    @Test
    fun `can frame round trip with escaping`() {
        val frame = fastInfo()
        val unpacker = InMotionV1Protocol.Unpacker()
        val msg = frame.firstNotNullOfOrNull { unpacker.add(it) }
        assertNotNull(msg)
        assertEquals(InMotionV1Protocol.ID_FAST_INFO, msg!!.id)
        assertEquals(80, msg.extended!!.size)
    }

    @Test
    fun `decodes fast info`() {
        val p = InMotionV1Protocol("V8-1")
        assertTrue(p.decode(fastInfo(), 0).telemetryUpdated)
        val t = p.state.telemetry
        assertEquals(18.0, t.speedKmh, 1e-9)
        assertEquals(80.0, t.voltageV, EPS)
        assertEquals(-2.5, t.batteryCurrentA!!, EPS)
        assertEquals(40.0, t.temperatureC!!, EPS)
        assertEquals(5000L, t.totalDistanceM)
        assertEquals(1234L, t.tripDistanceM)
    }

    @Test
    fun `sends password first`() {
        val p = InMotionV1Protocol(null)
        val frame = p.onConnected(0).single().bytes
        val msg = frame.firstNotNullOfOrNull(InMotionV1Protocol.Unpacker()::add)
        assertEquals(InMotionV1Protocol.ID_PIN_CODE, msg!!.id)
        assertEquals("000000", String(msg.data, 0, 6))
    }
}
