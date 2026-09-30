package app.gyro.protocol

/**
 * Byte-order helpers used by the wheel decoders.
 *
 * Wheels are inconsistent: Begode/Veteran send big-endian, InMotion/Ninebot little-endian and KingSong
 * uses little-endian 16-bit words with the two words of a 32-bit value in big-endian order.
 * All readers return 0 instead of throwing when the frame is too short, so a truncated BLE
 * notification can never crash the decoder.
 */
internal object Bytes {

    fun u8(b: ByteArray, i: Int): Int = if (i in b.indices) b[i].toInt() and 0xFF else 0

    fun s8(b: ByteArray, i: Int): Int = if (i in b.indices) b[i].toInt() else 0

    fun u16le(b: ByteArray, i: Int): Int = u8(b, i) or (u8(b, i + 1) shl 8)

    fun s16le(b: ByteArray, i: Int): Int = u16le(b, i).toShort().toInt()

    fun u16be(b: ByteArray, i: Int): Int = (u8(b, i) shl 8) or u8(b, i + 1)

    fun s16be(b: ByteArray, i: Int): Int = u16be(b, i).toShort().toInt()

    fun u32le(b: ByteArray, i: Int): Long =
        (u16le(b, i).toLong()) or (u16le(b, i + 2).toLong() shl 16)

    fun u32be(b: ByteArray, i: Int): Long =
        (u16be(b, i).toLong() shl 16) or u16be(b, i + 2).toLong()

    /** KingSong 32-bit: two little-endian words, high word first (b1 b0 b3 b2). */
    fun ks32(b: ByteArray, i: Int): Long =
        (u16le(b, i).toLong() shl 16) or u16le(b, i + 2).toLong()

    /** Veteran 32-bit: two big-endian words, low word first (b2 b3 b0 b1). */
    fun veteran32(b: ByteArray, i: Int): Long =
        (u16be(b, i + 2).toLong() shl 16) or u16be(b, i).toLong()

    /** InMotion V13 32-bit: two little-endian words, high word first. */
    fun revLe32(b: ByteArray, i: Int): Long = ks32(b, i)

    fun ascii(b: ByteArray, from: Int, maxLen: Int): String {
        val sb = StringBuilder()
        var i = from
        while (i < b.size && i < from + maxLen) {
            val c = b[i].toInt() and 0xFF
            if (c == 0) break
            if (c in 0x20..0x7E) sb.append(c.toChar())
            i++
        }
        return sb.toString().trim()
    }

    fun hex(b: ByteArray): String = b.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }

    fun of(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }
}
