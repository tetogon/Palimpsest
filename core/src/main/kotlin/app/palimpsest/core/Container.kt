package app.palimpsest.core

import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * A container holds any number of vaults and never knows how many (spec §1).
 * All file access goes through [locked] so a session and the background chaff job
 * never interleave appends.
 */
class Container private constructor(internal val heap: HeapFile, internal val crypto: Crypto) {
    private val lock = ReentrantLock()
    private val salt: ByteArray = heap.readSalt()
    internal val cipher = BlockCipher(crypto, salt)

    internal fun <T> locked(block: () -> T): T = lock.withLock(block)

    /**
     * Returns the session, or null if no vault opens with this password.
     * If the vault has an armed wipe rule (spec §10.3), the target vault is shredded
     * and the rule removed before the session is returned.
     */
    fun unlock(password: String): VaultSession? {
        val pk = PasswordKeys.derive(crypto, password, salt)
        try {
            return locked {
                val nonces = heap.readNonces()
                val kw = findKeywrap(pk, nonces) ?: return@locked null
                val session = VaultSession.load(this, kw, nonces)
                session.wipeTarget()?.let { target ->
                    val locators = target.map(crypto::importKey)
                    target.forEach { it.fill(0) }
                    shred(locators, heap.readNonces())
                    locators.forEach(SecretKey::wipe)
                    session.setWipeTarget(null)
                }
                session
            }
        } finally {
            pk.wipe()
        }
    }

    /** Returns null if the password already opens a vault (spec §11.2). */
    fun createVault(password: String): VaultSession? {
        val pk = PasswordKeys.derive(crypto, password, salt)
        try {
            return locked {
                findKeywrap(pk, heap.readNonces())?.let {
                    it.v.wipe()
                    return@locked null
                }
                val v = crypto.randomKey()
                val block = keywrapBlock(pk, Id.random(crypto), v)
                v.wipe()
                appendShuffled(listOf(block) + List(Chaff.onCommit(crypto, 1)) { cipher.chaff() })

                val nonces = heap.readNonces()
                VaultSession.load(this, checkNotNull(findKeywrap(pk, nonces)), nonces)
            }
        } finally {
            pk.wipe()
        }
    }

    enum class ChangePasswordResult { CHANGED, WRONG_PASSWORD, PASSWORD_TAKEN }

    /** New KEYWRAP with the same V, old one overwritten with chaff (spec §11.4). */
    fun changePassword(session: VaultSession, oldPassword: String, newPassword: String): ChangePasswordResult {
        val old = PasswordKeys.derive(crypto, oldPassword, salt)
        val new = PasswordKeys.derive(crypto, newPassword, salt)
        try {
            return locked {
                val nonces = heap.readNonces()
                val found = findKeywrap(old, nonces)
                val kw = found?.takeIf { it.vaultId == session.vaultId } ?: run {
                    found?.v?.wipe()
                    return@locked ChangePasswordResult.WRONG_PASSWORD
                }
                findKeywrap(new, nonces)?.let {
                    it.v.wipe()
                    kw.v.wipe()
                    return@locked ChangePasswordResult.PASSWORD_TAKEN
                }
                val block = keywrapBlock(new, session.vaultId, kw.v)
                kw.v.wipe()
                val positions = appendShuffled(listOf(block) + List(Chaff.onCommit(crypto, 1)) { cipher.chaff() })
                heap.overwrite(mapOf(kw.blockIndex to cipher.chaff()))
                session.keywrapIndex = positions[0]
                ChangePasswordResult.CHANGED
            }
        } finally {
            old.wipe()
            new.wipe()
        }
    }

    enum class ArmWipeResult { ARMED, NOT_FOUND, SAME_VAULT }

    /**
     * Arms a one-shot rule in [session]'s vault: the next time it is unlocked, the vault
     * opened by [targetPassword] is shredded. Only the target's locators are stored, never
     * its keys: the rule can find the target's blocks but not read them.
     */
    fun armWipe(session: VaultSession, targetPassword: String): ArmWipeResult {
        val pk = PasswordKeys.derive(crypto, targetPassword, salt)
        try {
            return locked {
                val kw = findKeywrap(pk, heap.readNonces()) ?: return@locked ArmWipeResult.NOT_FOUND
                if (kw.vaultId == session.vaultId) {
                    kw.v.wipe()
                    return@locked ArmWipeResult.SAME_VAULT
                }
                val vk = VaultKeys.derive(crypto, kw.v)
                kw.v.wipe()
                val target = listOf(pk.loc, vk.metaLoc, vk.blobLoc, vk.padLoc).map(crypto::exportKey)
                vk.wipe()
                try {
                    session.setWipeTarget(target)
                } finally {
                    target.forEach { it.fill(0) }
                }
                ArmWipeResult.ARMED
            }
        } finally {
            pk.wipe()
        }
    }

    fun disarmWipe(session: VaultSession) = locked { session.setWipeTarget(null) }

    /** Shreds every block of the session's vault, then closes the session. */
    fun deleteVault(session: VaultSession) = locked {
        shred(session.ownLocators(), heap.readNonces(), listOf(session.keywrapIndex))
        session.close()
    }

    fun addBackgroundChaff() = locked {
        val n = Chaff.background(crypto)
        if (n > 0) appendShuffled(List(n) { cipher.chaff() })
    }

    fun blockCount(): Int = locked { heap.blockCount() }

    /** The caller owns [v] and must wipe it. */
    internal class Keywrap(val blockIndex: Int, val vaultId: Id, val v: SecretKey)

    private fun keywrapBlock(pk: PasswordKeys, vaultId: Id, v: SecretKey): ByteArray {
        val raw = crypto.exportKey(v)
        val data = le(16 + Format.KEY_LEN + 8)
            .put(vaultId.bytes).put(raw).putLong(System.currentTimeMillis() shl 16).array()
        raw.fill(0)
        val plaintext = Frame(Kind.KEYWRAP, Id.random(crypto), 0, 1, data).encode()
        data.fill(0)
        return cipher.seal(pk.wrap, Locator.nonce(crypto, pk.loc), plaintext) // zeroes plaintext
    }

    private fun findKeywrap(pk: PasswordKeys, nonces: List<ByteArray>): Keywrap? {
        for (i in nonces.indices) {
            if (!Locator.matches(crypto, pk.loc, nonces[i])) continue
            val pt = cipher.open(pk.wrap, heap.readBlock(i)) ?: continue
            val frame = Frame.decode(pt)
            pt.fill(0)
            if (frame == null || frame.kind != Kind.KEYWRAP || frame.data.size != 16 + Format.KEY_LEN + 8) continue
            val vaultId = Id(frame.data.copyOf(16))
            val raw = frame.data.copyOfRange(16, 16 + Format.KEY_LEN)
            frame.data.fill(0)
            val v = crypto.importKey(raw)
            raw.fill(0)
            return Keywrap(i, vaultId, v)
        }
        return null
    }

    /**
     * Overwrites in place, with fresh random bytes indistinguishable from chaff, every block
     * matching any of [locators] plus [extra]. File size does not change.
     */
    private fun shred(locators: List<SecretKey>, nonces: List<ByteArray>, extra: List<Int> = emptyList()) {
        val targets = nonces.indices.filter { i -> locators.any { Locator.matches(crypto, it, nonces[i]) } } + extra
        if (targets.isNotEmpty()) heap.overwrite(targets.associateWith { cipher.chaff() })
    }

    /** Appends in random order so position never separates chaff from data. Returns each input's index. */
    internal fun appendShuffled(blocks: List<ByteArray>): IntArray {
        val order = blocks.indices.toMutableList()
        for (i in order.size - 1 downTo 1) {
            val j = crypto.randomBelow(i + 1)
            order[i] = order[j].also { order[j] = order[i] }
        }
        val base = heap.append(order.map { blocks[it] })
        val positions = IntArray(blocks.size)
        order.forEachIndexed { slot, source -> positions[source] = base + slot }
        return positions
    }

    companion object {
        fun openOrCreate(file: File, crypto: Crypto): Container {
            val heap = HeapFile(file)
            if (heap.exists()) {
                heap.repairTail()
                return Container(heap, crypto)
            }
            heap.create(crypto.randomBytes(Format.BLOCK_SIZE))
            return Container(heap, crypto).also { c ->
                c.locked { c.appendShuffled(List(Chaff.onCreate(crypto)) { c.cipher.chaff() }) }
            }
        }
    }
}
