package app.palimpsest.core

class EntryView(
    val entryId: Id,
    /** Head shown by default: max (hlc, sessionId) among PUT heads. */
    val head: PutOp,
    /** Other concurrent PUT heads; non-empty means a conflict (spec §7.1). */
    val otherHeads: List<PutOp>,
)

/**
 * Pure function of the set of operations: order-, duplicate- and purge-independent,
 * so every device that has the same blocks computes the same state (spec §7.2).
 */
class VaultState(ops: Collection<Op>) {
    val entries: Map<Id, EntryView>
    val deadOps: Set<Id>
    val maxHlc: Long = ops.maxOfOrNull { it.hlc } ?: 0L
    private val heads: Map<Id, List<Op>>

    init {
        val entries = HashMap<Id, EntryView>()
        val dead = HashSet<Id>()
        val heads = HashMap<Id, List<Op>>()
        for ((entryId, list) in ops.groupBy { it.entryId }) {
            val superseded = list.flatMapTo(HashSet()) { it.supersedes }
            val h = list.filter { it.opId !in superseded }
            heads[entryId] = h
            // Tombstones never die; only replaced content does (spec §8.1).
            list.filter { it is PutOp && it.opId in superseded }.mapTo(dead) { it.opId }
            val putHeads = h.filterIsInstance<PutOp>()
                .sortedWith(compareByDescending<PutOp> { it.hlc }.thenByDescending { it.sessionId })
            if (putHeads.isNotEmpty()) entries[entryId] = EntryView(entryId, putHeads.first(), putHeads.drop(1))
        }
        this.entries = entries
        this.deadOps = dead
        this.heads = heads
    }

    /** `supersedes` for a new operation that replaces every current head of the entry. */
    fun supersedesFor(entryId: Id): Set<Id> =
        heads[entryId].orEmpty().flatMapTo(HashSet()) { it.supersedes + it.opId }
}
