package app.palimpsest.core

import java.io.ByteArrayOutputStream

class TlvField(val tag: Int, val value: ByteArray)

/** tag u8 ‖ len LEB128 (≤ 4 bytes, ≤ 2²⁰, canonical) ‖ value (spec §6.3). */
internal object Tlv {
    const val MAX_LEN = 1 shl 20

    fun parse(buf: ByteArray, offset: Int): List<TlvField>? {
        val fields = mutableListOf<TlvField>()
        var p = offset
        while (p < buf.size) {
            val tag = buf[p++].toInt() and 0xFF
            var len = 0
            var bytes = 0
            while (true) {
                if (p >= buf.size || bytes == 4) return null
                val b = buf[p++].toInt() and 0xFF
                len = len or ((b and 0x7F) shl (7 * bytes))
                bytes++
                if (b and 0x80 == 0) {
                    if (bytes > 1 && b == 0) return null // overlong encoding
                    break
                }
            }
            if (len > MAX_LEN || len > buf.size - p) return null
            fields += TlvField(tag, buf.copyOfRange(p, p + len))
            p += len
        }
        return fields
    }
}

internal class TlvWriter {
    private val out = ByteArrayOutputStream()

    fun put(tag: Int, value: ByteArray) {
        require(value.size <= Tlv.MAX_LEN)
        out.write(tag)
        var len = value.size
        do {
            val b = len and 0x7F
            len = len ushr 7
            out.write(if (len != 0) b or 0x80 else b)
        } while (len != 0)
        out.write(value)
    }

    fun putString(tag: Int, s: String) = put(tag, s.toByteArray(Charsets.UTF_8))
    fun putU8(tag: Int, v: Int) = put(tag, byteArrayOf(v.toByte()))
    fun putU32(tag: Int, v: Long) = put(tag, le(4).putInt(v.toInt()).array())
    fun putU64(tag: Int, v: Long) = put(tag, le(8).putLong(v).array())

    fun toByteArray(): ByteArray = out.toByteArray()
}
