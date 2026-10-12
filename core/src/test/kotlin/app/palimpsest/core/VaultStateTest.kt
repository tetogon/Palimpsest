package app.palimpsest.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VaultStateTest {
    private val crypto = TestSodium.crypto
    private val entry = Id.random(crypto)

    private fun put(hlc: Long, vararg parents: Op) = PutOp(
        Id.random(crypto), hlc, Id.random(crypto), entry,
        parents.flatMapTo(HashSet()) { it.supersedes + it.opId },
        Format.ENTRY_DIARY, 0, null, "t$hlc", "", emptyList(),
    )

    private fun del(hlc: Long, vararg parents: Op) =
        DeleteOp(Id.random(crypto), hlc, Id.random(crypto), entry, parents.flatMapTo(HashSet()) { it.supersedes + it.opId })

    @Test
    fun editSupersedesAndMarksDead() {
        val a = put(1)
        val b = put(2, a)
        val s = VaultState(listOf(b, a))
        assertEquals(b.opId, s.entries[entry]!!.head.opId)
        assertEquals(setOf(a.opId), s.deadOps)
    }

    @Test
    fun concurrentEditsAreAConflict() {
        val a = put(1)
        val s = VaultState(listOf(a, put(2, a), put(3, a)))
        assertEquals(1, s.entries[entry]!!.otherHeads.size)
    }

    @Test
    fun resurrectedAncestorStaysDeadAfterPurge() {
        val a = put(1)
        val b = put(2, a)
        val c = put(3, b)
        // b was purged; a comes back from an old device copy.
        val s = VaultState(listOf(c, a))
        assertEquals(c.opId, s.entries[entry]!!.head.opId)
        assertTrue(a.opId in s.deadOps)
    }

    @Test
    fun deleteRemovesEntryButConcurrentEditWins() {
        val a = put(1)
        assertNull(VaultState(listOf(a, del(2, a))).entries[entry])
        assertEquals(1, VaultState(listOf(a, del(2, a), put(3, a))).entries.size)
    }

    @Test
    fun orderDoesNotMatter() {
        val a = put(1)
        val ops = listOf(a, put(2, a), put(3, a), del(4, a))
        val ref = VaultState(ops)
        repeat(20) {
            val s = VaultState(ops.shuffled(java.util.Random(it.toLong())))
            assertEquals(ref.entries[entry]!!.head.opId, s.entries[entry]!!.head.opId)
            assertEquals(ref.deadOps, s.deadOps)
        }
    }
}
