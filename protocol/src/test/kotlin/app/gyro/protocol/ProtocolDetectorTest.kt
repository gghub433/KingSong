package app.gyro.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProtocolDetectorTest {

    private val ksLive = hex("AA55 D020 F609 BC00 4E61 A2FE C50D 02E0 A9 14 5A5A")
    private val begodeA = hex("55AA 19F0 0000 0000 0000 012C FDCA 0001 FFF8 00 18 5A5A5A5A")

    private fun veteranFrame(): ByteArray = ByteArray(36).also {
        it[0] = 0xDC.toByte(); it[1] = 0x5A; it[2] = 0x5C; it[3] = 32
    }

    @Test
    fun `name hints`() {
        assertEquals(ProtocolFamily.KINGSONG, ProtocolDetector.hintFromName("KS-S22-0243"))
        assertEquals(ProtocolFamily.KINGSONG, ProtocolDetector.hintFromName("RW"))
        assertEquals(ProtocolFamily.BEGODE, ProtocolDetector.hintFromName("GotWay_4501"))
        assertEquals(ProtocolFamily.VETERAN, ProtocolDetector.hintFromName("Sherman-S 102"))
        assertEquals(ProtocolFamily.INMOTION_V2, ProtocolDetector.hintFromName("V13-A1B2"))
        assertEquals(ProtocolFamily.INMOTION_V1, ProtocolDetector.hintFromName("V10F-1234"))
        assertEquals(ProtocolFamily.NINEBOT_Z, ProtocolDetector.hintFromName("NinebotZ10"))
        assertNull(ProtocolDetector.hintFromName("HMSoft"))
    }

    @Test
    fun `candidates put the name hint first but keep the others`() {
        val c = ProtocolDetector.candidates(BleTransport.HM10_UART, "Sherman")
        assertEquals(listOf(ProtocolFamily.VETERAN, ProtocolFamily.KINGSONG, ProtocolFamily.BEGODE), c)
        // A hint for another transport is ignored.
        assertEquals(ProtocolFamily.KINGSONG, ProtocolDetector.candidates(BleTransport.HM10_UART, "V13").first())
    }

    @Test
    fun `sniffs each HM10 protocol from its stream`() {
        fun sniff(bytes: ByteArray) = ProtocolDetector.StreamSniffer().apply { feed(bytes) }.result()
        assertEquals(ProtocolFamily.KINGSONG, sniff(ksLive + ksLive))
        assertEquals(ProtocolFamily.BEGODE, sniff(hex("0102") + begodeA + begodeA))
        assertEquals(ProtocolFamily.VETERAN, sniff(veteranFrame() + veteranFrame()))
        assertNull("one frame is not enough", sniff(ksLive))
        assertNull("noise", sniff(ByteArray(200) { it.toByte() }))
    }

    @Test
    fun `nordic reply`() {
        assertEquals(ProtocolFamily.INMOTION_V2, ProtocolDetector.nordicReply(hex("00AAAA1403")))
        assertEquals(ProtocolFamily.NINEBOT_Z, ProtocolDetector.nordicReply(hex("5AA50314")))
        assertNull(ProtocolDetector.nordicReply(hex("0102")))
    }
}
