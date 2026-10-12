package app.palimpsest.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/** Our libsodium binding, exercised on the real library. */
class CryptoTest {
    private val c = TestSodium.crypto
    private val nonce = ByteArray(Format.NONCE_LEN) { it.toByte() }

    @Test
    fun sealOpenRoundTripAndTamperRejection() {
        val k = c.randomKey()
        val ct = c.aeadSeal(k, nonce, "hello".toByteArray(), "ad".toByteArray())
        assertEquals(5 + Format.TAG_LEN, ct.size)
        assertArrayEquals("hello".toByteArray(), c.aeadOpen(k, nonce, ct, "ad".toByteArray()))

        assertNull(c.aeadOpen(k, nonce, ct, "AD".toByteArray()))
        assertNull(c.aeadOpen(k, nonce, ct.copyOf().also { it[0] = (it[0] + 1).toByte() }, "ad".toByteArray()))
        assertNull(c.aeadOpen(c.randomKey(), nonce, ct, "ad".toByteArray()))
    }

    @Test
    fun argon2IsDeterministicPerPasswordAndSalt() {
        val salt = ByteArray(Format.SALT_LEN) { 7 }
        val a = c.exportKey(c.argon2id("pw".toByteArray(), salt))
        val b = c.exportKey(c.argon2id("pw".toByteArray(), salt))
        val other = c.exportKey(c.argon2id("pw2".toByteArray(), salt))
        assertArrayEquals(a, b)
        assertFalse(a.contentEquals(other))
    }

    @Test
    fun kdfSubkeysDifferAndHashIsKeyed() {
        val master = c.randomKey()
        val k1 = c.exportKey(c.kdf(master, 1, Format.CTX_VK))
        val k2 = c.exportKey(c.kdf(master, 2, Format.CTX_VK))
        assertFalse(k1.contentEquals(k2))
        val msg = ByteArray(16)
        assertFalse(c.keyedHash16(c.importKey(k1), msg).contentEquals(c.keyedHash16(c.importKey(k2), msg)))
    }

    @Test
    fun importExportRoundTripAndWipeDisablesKey() {
        val raw = c.randomBytes(32)
        val k = c.importKey(raw)
        assertArrayEquals(raw, c.exportKey(k))
        k.wipe()
        k.wipe() // idempotent
        assertThrows(IllegalStateException::class.java) { c.exportKey(k) }
    }

    @Test
    fun randomness() {
        assertNotNull(c.randomBytes(0))
        repeat(1000) { assert(c.randomBelow(3) in 0..2) }
    }
}
