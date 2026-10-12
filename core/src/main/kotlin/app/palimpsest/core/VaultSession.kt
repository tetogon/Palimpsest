package app.palimpsest.core

import java.time.LocalDate

/** An unlocked vault, from unlock until [close]. */
class VaultSession private constructor(
    private val container: Container,
    private val keys: VaultKeys,
    val vaultId: Id,
    /** Heap index of this vault's KEYWRAP block, for delete and password change. */
    internal var keywrapIndex: Int,
) {
    private val crypto = container.crypto
    private val cipher = container.cipher
    private val sessionId = Id.random(crypto)
    private val ops = HashMap<Id, Op>()
    private val opBlocks = HashMap<Id, List<Int>>()
    private var lastHlc = 0L
    private var closed = false

    var state = VaultState(emptyList())
        private set

    fun entries(): List<EntryView> = container.locked {
        state.entries.values.filter { it.head.entryType != Format.ENTRY_SETTINGS }.sortedWith(
            compareByDescending<EntryView> { it.head.diaryDate ?: 0 }.thenByDescending { it.head.createdMs },
        )
    }

    fun entry(id: Id): EntryView? = container.locked { state.entries[id] }

    /** Writes a new revision; [entryId] null creates an entry. Returns the entry id. */
    fun save(entryId: Id?, title: String, body: String): Id = container.locked {
        checkOpen()
        val id = entryId ?: Id.random(crypto)
        val prev = state.entries[id]?.head
        commit(
            PutOp(
                opId = Id.random(crypto),
                hlc = nextHlc(),
                sessionId = sessionId,
                entryId = id,
                supersedes = state.supersedesFor(id),
                entryType = prev?.entryType ?: Format.ENTRY_DIARY,
                createdMs = prev?.createdMs ?: System.currentTimeMillis(),
                diaryDate = prev?.diaryDate ?: LocalDate.now().toEpochDay().toInt(),
                title = title,
                body = body,
                tags = prev?.tags.orEmpty(),
                unknown = prev?.unknown.orEmpty(),
            ),
        )
        id
    }

    fun delete(entryId: Id) = container.locked {
        checkOpen()
        if (state.entries[entryId] == null) return@locked
        commit(DeleteOp(Id.random(crypto), nextHlc(), sessionId, entryId, state.supersedesFor(entryId)))
    }

    /** True if the next unlock of this vault will shred another one. */
    val wipeArmed: Boolean get() = container.locked { wipeTarget() != null }

    internal fun wipeTarget(): List<ByteArray>? {
        val raw = settings()?.unknown?.firstOrNull { it.tag == Format.T_WIPE_TARGET }?.value ?: return null
        if (raw.size != 4 * Format.KEY_LEN) return null
        return (0 until 4).map { raw.copyOfRange(it * Format.KEY_LEN, (it + 1) * Format.KEY_LEN) }
    }

    /** Writes a new settings revision; the old one is purged like any replaced revision. */
    internal fun setWipeTarget(locators: List<ByteArray>?) = container.locked {
        checkOpen()
        val prev = settings()
        if (prev == null && locators == null) return@locked
        val others = prev?.unknown.orEmpty().filter { it.tag != Format.T_WIPE_TARGET }
        val target = locators?.let { l -> TlvField(Format.T_WIPE_TARGET, l.fold(ByteArray(0)) { acc, b -> acc + b }) }
        commit(
            PutOp(
                opId = Id.random(crypto),
                hlc = nextHlc(),
                sessionId = sessionId,
                entryId = Format.SETTINGS_ID,
                supersedes = state.supersedesFor(Format.SETTINGS_ID),
                entryType = Format.ENTRY_SETTINGS,
                createdMs = prev?.createdMs ?: System.currentTimeMillis(),
                diaryDate = null,
                title = "",
                body = "",
                tags = emptyList(),
                unknown = others + listOfNotNull(target),
            ),
        )
    }

    internal fun ownLocators(): List<SecretKey> = listOf(keys.metaLoc, keys.blobLoc, keys.padLoc)

    private fun settings(): PutOp? = state.entries[Format.SETTINGS_ID]?.head

    /** Session-end chaff, then key wipe. Idempotent. */
    fun close() = container.locked {
        if (closed) return@locked
        val n = Chaff.onSessionEnd(crypto)
        if (n > 0) container.appendShuffled(List(n) { cipher.chaff() })
        keys.wipe()
        ops.clear()
        state = VaultState(emptyList())
        closed = true
    }

    val isClosed: Boolean get() = closed

    private fun commit(op: Op) {
        val frames = Frame.split(Kind.OP, op.opId, OpCodec.encode(op))
        val data = frames.map { cipher.seal(keys.enc, Locator.nonce(crypto, keys.metaLoc), it.encode()) }
        val chaff = List(Chaff.onCommit(crypto, data.size)) { cipher.chaff() }
        val positions = container.appendShuffled(data + chaff)
        ops[op.opId] = op
        opBlocks[op.opId] = positions.take(data.size)
        recompute()
    }

    private fun recompute() {
        state = VaultState(ops.values)
        purgeDead()
    }

    /**
     * Overwrites blocks of replaced revisions with own PAD (spec §8.2).
     * TODO: honour purge_mode = manual for blocks from earlier sessions.
     */
    private fun purgeDead() {
        val writes = HashMap<Int, ByteArray>()
        for (id in state.deadOps) {
            opBlocks.remove(id)?.forEach { writes[it] = cipher.pad(keys.padLoc) }
            ops.remove(id)
        }
        if (writes.isNotEmpty()) container.heap.overwrite(writes)
    }

    private fun nextHlc(): Long {
        lastHlc = maxOf(lastHlc + 1, System.currentTimeMillis() shl 16)
        return lastHlc
    }

    private fun checkOpen() = check(!closed) { "vault is locked" }

    private fun load(nonces: List<ByteArray>) {
        val meta = nonces.indices.filter { Locator.matches(crypto, keys.metaLoc, nonces[it]) }
        val assembler = Assembler()
        container.heap.readBlocks(meta) { index, block ->
            val frame = cipher.open(keys.enc, block)?.let { Frame.decode(it) }
            if (frame?.kind == Kind.OP) assembler.add(frame, index)
        }
        for (obj in assembler.complete()) {
            val op = OpCodec.decode(obj.objectId, obj.data) ?: continue
            ops[op.opId] = op
            opBlocks[op.opId] = obj.blocks
        }
        recompute()
        lastHlc = state.maxHlc
    }

    internal companion object {
        fun load(container: Container, kw: Container.Keywrap, nonces: List<ByteArray>): VaultSession {
            val keys = VaultKeys.derive(container.crypto, kw.v)
            kw.v.wipe()
            return VaultSession(container, keys, kw.vaultId, kw.blockIndex).also { it.load(nonces) }
        }
    }
}
