package app.palimpsest.core

/** One operation of a vault's log (spec §6.2). */
sealed class Op {
    abstract val opId: Id
    abstract val hlc: Long
    abstract val sessionId: Id
    abstract val entryId: Id

    /** Every ancestor revision this operation replaces (spec §7.1). */
    abstract val supersedes: Set<Id>
}

class PutOp(
    override val opId: Id,
    override val hlc: Long,
    override val sessionId: Id,
    override val entryId: Id,
    override val supersedes: Set<Id>,
    val entryType: Int,
    val createdMs: Long,
    val diaryDate: Int?,
    val title: String,
    val body: String,
    val tags: List<String>,
    /** Fields from newer versions, preserved verbatim on rewrite. */
    val unknown: List<TlvField> = emptyList(),
) : Op()

class DeleteOp(
    override val opId: Id,
    override val hlc: Long,
    override val sessionId: Id,
    override val entryId: Id,
    override val supersedes: Set<Id>,
) : Op()

internal object OpCodec {
    private const val VERSION = 1
    private const val TYPE_PUT = 0x01
    private const val TYPE_DELETE = 0x02
    private const val HEADER = 1 + 1 + 8 + 16

    private const val T_ENTRY_ID = 0x01
    private const val T_SUPERSEDES = 0x02
    private const val T_ENTRY_TYPE = 0x03
    private const val T_CREATED = 0x04
    private const val T_DIARY_DATE = 0x05
    private const val T_TITLE = 0x06
    private const val T_BODY = 0x07
    private const val T_TAG = 0x08
    private const val T_ATTACHMENT = 0x09

    private val REPEATABLE = setOf(T_TAG, T_ATTACHMENT)
    private val KNOWN_PUT = setOf(T_ENTRY_ID, T_SUPERSEDES, T_ENTRY_TYPE, T_CREATED, T_DIARY_DATE, T_TITLE, T_BODY, T_TAG)

    fun encode(op: Op): ByteArray {
        val header = le(HEADER)
        header.put(VERSION.toByte())
        header.put((if (op is PutOp) TYPE_PUT else TYPE_DELETE).toByte())
        header.putLong(op.hlc)
        header.put(op.sessionId.bytes)

        val w = TlvWriter()
        w.put(T_ENTRY_ID, op.entryId.bytes)
        w.put(T_SUPERSEDES, op.supersedes.sorted().fold(ByteArray(0)) { acc, id -> acc + id.bytes })
        when (op) {
            is PutOp -> {
                w.putU8(T_ENTRY_TYPE, op.entryType)
                w.putU64(T_CREATED, op.createdMs)
                op.diaryDate?.let { w.putU32(T_DIARY_DATE, it.toLong()) }
                w.putString(T_TITLE, op.title)
                w.putString(T_BODY, op.body)
                op.tags.forEach { w.putString(T_TAG, it) }
                op.unknown.forEach { w.put(it.tag, it.value) }
            }
            is DeleteOp -> Unit
        }
        return header.array() + w.toByteArray()
    }

    /** Returns null for anything malformed; the caller treats the object as foreign. */
    fun decode(opId: Id, data: ByteArray): Op? {
        if (data.size < HEADER) return null
        val b = leRead(data)
        if (b.get().toInt() != VERSION) return null
        val type = b.get().toInt() and 0xFF
        val hlc = b.getLong()
        val sessionId = Id(ByteArray(16).also { b.get(it) })
        val fields = Tlv.parse(data, HEADER) ?: return null

        val single = HashMap<Int, ByteArray>()
        for (f in fields) if (f.tag !in REPEATABLE && single.put(f.tag, f.value) != null) return null

        val entryId = single[T_ENTRY_ID]?.takeIf { it.size == 16 }?.let(::Id) ?: return null
        val supersedes = parseIds(single[T_SUPERSEDES] ?: return null) ?: return null
        if (opId in supersedes) return null

        return when (type) {
            TYPE_PUT -> {
                val entryType = single[T_ENTRY_TYPE]?.takeIf { it.size == 1 }?.let { it[0].toInt() and 0xFF } ?: return null
                val created = single[T_CREATED]?.takeIf { it.size == 8 }?.let(::le64) ?: return null
                val diaryDate = single[T_DIARY_DATE]?.let { if (it.size != 4) return null else le32(it).toInt() }
                val title = single[T_TITLE]?.let { decodeUtf8(it) ?: return null } ?: ""
                val body = single[T_BODY]?.let { decodeUtf8(it) ?: return null } ?: ""
                val tags = fields.filter { it.tag == T_TAG }.map { decodeUtf8(it.value) ?: return null }
                val unknown = fields.filter { it.tag !in KNOWN_PUT }
                PutOp(opId, hlc, sessionId, entryId, supersedes, entryType, created, diaryDate, title, body, tags, unknown)
            }
            TYPE_DELETE -> DeleteOp(opId, hlc, sessionId, entryId, supersedes)
            else -> null
        }
    }

    /** Concatenated 16-byte ids, strictly ascending (canonical, no duplicates). */
    private fun parseIds(b: ByteArray): Set<Id>? {
        if (b.size % 16 != 0) return null
        val ids = (0 until b.size / 16).map { Id(b.copyOfRange(16 * it, 16 * it + 16)) }
        for (i in 1 until ids.size) if (ids[i - 1] >= ids[i]) return null
        return ids.toSet()
    }
}
