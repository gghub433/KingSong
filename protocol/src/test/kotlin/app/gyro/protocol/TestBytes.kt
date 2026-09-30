package app.gyro.protocol

fun hex(s: String): ByteArray {
    val clean = s.replace(" ", "").replace("\n", "")
    require(clean.length % 2 == 0) { "odd hex length" }
    return ByteArray(clean.length / 2) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}

fun ByteArray.putLe16(at: Int, value: Int): ByteArray {
    this[at] = (value and 0xFF).toByte()
    this[at + 1] = ((value shr 8) and 0xFF).toByte()
    return this
}

fun ByteArray.putBe16(at: Int, value: Int): ByteArray {
    this[at] = ((value shr 8) and 0xFF).toByte()
    this[at + 1] = (value and 0xFF).toByte()
    return this
}

fun ByteArray.putLe32(at: Int, value: Long): ByteArray {
    for (i in 0 until 4) this[at + i] = ((value shr (8 * i)) and 0xFF).toByte()
    return this
}

fun ByteArray.putBe32(at: Int, value: Long): ByteArray {
    for (i in 0 until 4) this[at + i] = ((value shr (8 * (3 - i))) and 0xFF).toByte()
    return this
}

const val EPS = 1e-6

/** Feeds bytes one by one and returns the first non-null result (a completed frame). */
fun <T : Any> ByteArray.firstNotNullOfOrNull(transform: (Byte) -> T?): T? {
    for (b in this) transform(b)?.let { return it }
    return null
}
