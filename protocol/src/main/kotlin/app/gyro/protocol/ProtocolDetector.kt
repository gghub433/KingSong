package app.gyro.protocol

/**
 * Picks the wire protocol for a freshly connected wheel.
 *
 * GATT services only narrow it down to a transport: KingSong, Begode and Veteran all use the same
 * HM-10 service, and InMotion V2 shares the Nordic UART service with Ninebot Z. The advertised
 * name is a hint, but new models get new names, so the decisive signal is the byte stream: each
 * protocol has a frame shape that the others never produce.
 */
object ProtocolDetector {

    /** Protocols possible for the discovered GATT layout, most likely first. */
    fun candidates(transport: BleTransport, name: String?, hasNinebotService: Boolean = false, hasInMotionMarker: Boolean = false): List<ProtocolFamily> {
        val hinted = hintFromName(name)
        val base = when (transport) {
            BleTransport.HM10_UART -> listOf(ProtocolFamily.KINGSONG, ProtocolFamily.BEGODE, ProtocolFamily.VETERAN)
            BleTransport.INMOTION_V1 -> listOf(ProtocolFamily.INMOTION_V1)
            BleTransport.NORDIC_UART -> when {
                hasNinebotService -> listOf(ProtocolFamily.NINEBOT_Z, ProtocolFamily.INMOTION_V2)
                hasInMotionMarker -> listOf(ProtocolFamily.INMOTION_V2, ProtocolFamily.NINEBOT_Z)
                else -> listOf(ProtocolFamily.INMOTION_V2, ProtocolFamily.NINEBOT_Z)
            }
        }
        return if (hinted != null && hinted in base) listOf(hinted) + (base - hinted) else base
    }

    /** Brand hint from the advertised name; null when the name says nothing useful. */
    fun hintFromName(name: String?): ProtocolFamily? {
        val n = name?.trim()?.uppercase() ?: return null
        return when {
            n.startsWith("KS-") || n.startsWith("KSN") || n.startsWith("KS_") || n.contains("KINGSONG") ||
                n.startsWith("ROCKW") || n == "RW" -> ProtocolFamily.KINGSONG
            n.startsWith("GOTWAY") || n.contains("BEGODE") || n.startsWith("GW_") || n.contains("EXTREMEBULL") ||
                n.startsWith("EB_") -> ProtocolFamily.BEGODE
            n.startsWith("LK") || n.contains("VETERAN") || n.contains("LEAPERKIM") || n.contains("SHERMAN") ||
                n.contains("PATTON") || n.contains("LYNX") || n.contains("ORYX") || n.contains("ABRAMS") ||
                n.contains("NOSFET") -> ProtocolFamily.VETERAN
            n.startsWith("NINEBOT") || n.startsWith("NBZ") || n.matches(Regex("^Z(6|8|10)\\b.*")) -> ProtocolFamily.NINEBOT_Z
            n.contains("INMOTION") || n.matches(Regex("^(V9|V11|V12|V13|V14|V15|V16|V18|P6)\\b.*")) -> ProtocolFamily.INMOTION_V2
            n.matches(Regex("^(V5|V8|V10|R1|R2|L6|GLIDE).*")) || n.contains("SOLOWHEEL") -> ProtocolFamily.INMOTION_V1
            else -> null
        }
    }

    /**
     * Scores raw bytes received on the HM-10 service. Wheels of these brands stream on their own,
     * so a second of traffic is enough. Returns null until one protocol has clearly won.
     */
    class StreamSniffer {
        private val data = java.io.ByteArrayOutputStream()

        fun feed(chunk: ByteArray) {
            if (data.size() < 4096) data.write(chunk)
        }

        fun scores(): Map<ProtocolFamily, Int> {
            val b = data.toByteArray()
            return mapOf(
                ProtocolFamily.KINGSONG to countKingSong(b),
                ProtocolFamily.BEGODE to countBegode(b),
                ProtocolFamily.VETERAN to countVeteran(b),
            )
        }

        /** Two complete frames of one family and none of the others is a confident answer. */
        fun result(minFrames: Int = 2): ProtocolFamily? {
            val s = scores().filterValues { it > 0 }
            if (s.size != 1) return null
            val (family, count) = s.entries.first()
            return family.takeIf { count >= minFrames }
        }

        private fun countKingSong(b: ByteArray): Int {
            var n = 0
            var i = 0
            while (i + 20 <= b.size) {
                if (Bytes.u8(b, i) == 0xAA && Bytes.u8(b, i + 1) == 0x55 &&
                    Bytes.u8(b, i + 18) == 0x5A && Bytes.u8(b, i + 19) == 0x5A &&
                    Bytes.u8(b, i + 16) in KS_TYPES
                ) {
                    n++
                    i += 20
                } else {
                    i++
                }
            }
            return n
        }

        private fun countBegode(b: ByteArray): Int {
            var n = 0
            var i = 0
            while (i + 24 <= b.size) {
                if (Bytes.u8(b, i) == 0x55 && Bytes.u8(b, i + 1) == 0xAA &&
                    (20..23).all { Bytes.u8(b, i + it) == 0x5A }
                ) {
                    n++
                    i += 24
                } else {
                    i++
                }
            }
            return n
        }

        private fun countVeteran(b: ByteArray): Int {
            var n = 0
            var i = 0
            while (i + 4 <= b.size) {
                if (Bytes.u8(b, i) == 0xDC && Bytes.u8(b, i + 1) == 0x5A && Bytes.u8(b, i + 2) == 0x5C) {
                    val len = Bytes.u8(b, i + 3)
                    if (len >= 32 && i + len + 4 <= b.size) {
                        n++
                        i += len + 4
                        continue
                    }
                }
                i++
            }
            return n
        }

        private companion object {
            val KS_TYPES = setOf(0xA9, 0xB9, 0xBB, 0xB3, 0xF5, 0xF6, 0xA4, 0xB5, 0xF1, 0xF2, 0xE1, 0xE2, 0xE5, 0xE6)
        }
    }

    /** InMotion V2 answers polls with `AA AA` frames, Ninebot Z with `5A A5`. */
    fun nordicReply(bytes: ByteArray): ProtocolFamily? {
        for (i in 0 until bytes.size - 1) {
            val a = Bytes.u8(bytes, i)
            val b = Bytes.u8(bytes, i + 1)
            if (a == 0xAA && b == 0xAA) return ProtocolFamily.INMOTION_V2
            if (a == 0x5A && b == 0xA5) return ProtocolFamily.NINEBOT_Z
        }
        return null
    }
}
