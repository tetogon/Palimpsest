package app.palimpsest.core

enum class Kind(val code: Int, val maxParts: Int) {
    KEYWRAP(0x01, 1),
    OP(0x02, Format.MAX_OP_PARTS),
    BLOB(0x04, Format.MAX_BLOB_PARTS);

    companion object {
        fun of(code: Int): Kind? = entries.firstOrNull { it.code == code }
    }
}

/** Plaintext layout of one block (spec §5). */
class Frame(
    val kind: Kind,
    val objectId: Id,
    val partIndex: Int,
    val partCount: Int,
    val data: ByteArray,
) {
    fun encode(): ByteArray {
        require(data.size <= Format.FRAME_CAP)
        val out = le(Format.PT_LEN)
        out.put(kind.code.toByte())
        out.put(objectId.bytes)
        out.putInt(partIndex)
        out.putInt(partCount)
        out.putShort(data.size.toShort())
        out.put(data)
        return out.array()
    }

    companion object {
        /** Returns null for any frame that violates spec §5.1. */
        fun decode(pt: ByteArray): Frame? {
            if (pt.size != Format.PT_LEN) return null
            val b = leRead(pt)
            val kind = Kind.of(b.get().toInt() and 0xFF) ?: return null
            val objectId = ByteArray(16).also { b.get(it) }
            val index = b.getInt().toLong() and 0xFFFF_FFFFL
            val count = b.getInt().toLong() and 0xFFFF_FFFFL
            val len = b.getShort().toInt() and 0xFFFF
            if (count == 0L || count > kind.maxParts || index >= count) return null
            if (len > Format.FRAME_CAP) return null
            if (index < count - 1 && len != Format.FRAME_CAP) return null
            for (i in Format.FRAME_HDR + len until Format.PT_LEN) if (pt[i] != 0.toByte()) return null
            return Frame(kind, Id(objectId), index.toInt(), count.toInt(),
                pt.copyOfRange(Format.FRAME_HDR, Format.FRAME_HDR + len))
        }

        fun split(kind: Kind, objectId: Id, data: ByteArray): List<Frame> {
            val count = maxOf(1, (data.size + Format.FRAME_CAP - 1) / Format.FRAME_CAP)
            require(count <= kind.maxParts) { "object too large: $count parts" }
            return (0 until count).map { i ->
                val from = i * Format.FRAME_CAP
                Frame(kind, objectId, i, count, data.copyOfRange(from, minOf(data.size, from + Format.FRAME_CAP)))
            }
        }
    }
}

internal class Assembled(val kind: Kind, val objectId: Id, val data: ByteArray, val blocks: List<Int>)

/** Collects frames into objects (spec §5.3). Incomplete and corrupt objects are never returned. */
internal class Assembler {
    private class Pending(val kind: Kind, val count: Int) {
        val parts = arrayOfNulls<ByteArray>(count)
        val blocks = mutableListOf<Int>()
        var corrupt = false
    }

    private val pending = LinkedHashMap<Id, Pending>()

    fun add(frame: Frame, blockIndex: Int) {
        val p = pending.getOrPut(frame.objectId) { Pending(frame.kind, frame.partCount) }
        p.blocks += blockIndex
        if (p.kind != frame.kind || p.count != frame.partCount) {
            p.corrupt = true
            return
        }
        val existing = p.parts[frame.partIndex]
        if (existing != null && !existing.contentEquals(frame.data)) p.corrupt = true
        p.parts[frame.partIndex] = frame.data
    }

    fun complete(): List<Assembled> = pending.mapNotNull { (id, p) ->
        if (p.corrupt || p.parts.any { it == null }) return@mapNotNull null
        val data = p.parts.fold(ByteArray(0)) { acc, part -> acc + part!! }
        Assembled(p.kind, id, data, p.blocks.toList())
    }
}
