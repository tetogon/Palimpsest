package app.palimpsest.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** Value wrapper for 16-byte object ids and 24-byte block ids. */
class Id(val bytes: ByteArray) : Comparable<Id> {
    override fun equals(other: Any?) = other is Id && bytes.contentEquals(other.bytes)
    override fun hashCode() = bytes.contentHashCode()

    /** Unsigned lexicographic order: canonical sort for `supersedes` and tie-breaks. */
    override fun compareTo(other: Id): Int {
        for (i in 0 until minOf(bytes.size, other.bytes.size)) {
            val c = (bytes[i].toInt() and 0xFF) - (other.bytes[i].toInt() and 0xFF)
            if (c != 0) return c
        }
        return bytes.size - other.bytes.size
    }

    override fun toString() = bytes.joinToString("") { "%02x".format(it) }

    companion object {
        fun random(crypto: Crypto) = Id(crypto.randomBytes(16))
        fun parse(hex: String) = Id(ByteArray(hex.length / 2) { hex.substring(2 * it, 2 * it + 2).toInt(16).toByte() })
    }
}

internal fun le(size: Int): ByteBuffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)

internal fun leRead(b: ByteArray): ByteBuffer = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)

internal fun le32(b: ByteArray): Long = leRead(b).getInt().toLong() and 0xFFFF_FFFFL

internal fun le64(b: ByteArray): Long = leRead(b).getLong()

/** Strict UTF-8: malformed input is rejected, not replaced. */
internal fun decodeUtf8(b: ByteArray): String? = try {
    Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(b))
        .toString()
} catch (e: CharacterCodingException) {
    null
}
