package app.palimpsest.core

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.NativeLong
import com.sun.jna.Pointer

/**
 * Minimal JNA binding to libsodium. Keys are passed as [Pointer]s into sodium_malloc'd
 * memory, so they never exist as JVM arrays. size_t is [NativeLong] (same width on
 * ILP32 and LP64 Linux/Android); unsigned long long is [Long].
 */
@Suppress("FunctionName")
interface LibSodium : Library {
    fun sodium_init(): Int
    fun sodium_malloc(size: NativeLong): Pointer?
    fun sodium_free(ptr: Pointer)

    fun randombytes_buf(buf: ByteArray, size: NativeLong)
    fun randombytes_buf(buf: Pointer, size: NativeLong)
    fun randombytes_uniform(upperBound: Int): Int

    fun crypto_pwhash_alg_argon2id13(): Int
    fun crypto_pwhash(
        out: Pointer, outlen: Long, passwd: ByteArray, passwdlen: Long, salt: ByteArray,
        opslimit: Long, memlimit: NativeLong, alg: Int,
    ): Int

    fun crypto_kdf_derive_from_key(subkey: Pointer, subkeyLen: NativeLong, subkeyId: Long, ctx: ByteArray, key: Pointer): Int

    fun crypto_generichash(out: ByteArray, outlen: NativeLong, input: ByteArray, inlen: Long, key: Pointer, keylen: NativeLong): Int

    fun crypto_aead_xchacha20poly1305_ietf_encrypt(
        c: ByteArray, clen: LongArray?, m: ByteArray, mlen: Long, ad: ByteArray, adlen: Long,
        nsec: Pointer?, npub: ByteArray, k: Pointer,
    ): Int

    fun crypto_aead_xchacha20poly1305_ietf_decrypt(
        m: ByteArray, mlen: LongArray?, nsec: Pointer?, c: ByteArray, clen: Long, ad: ByteArray, adlen: Long,
        npub: ByteArray, k: Pointer,
    ): Int

    companion object {
        /** [nameOrPath]: "sodium" on Android (libsodium.so in the APK), or an absolute path. */
        fun load(nameOrPath: String): LibSodium = Native.load(nameOrPath, LibSodium::class.java)
    }
}

class SodiumCrypto(private val lib: LibSodium) : Crypto {
    init {
        check(lib.sodium_init() >= 0) { "sodium_init failed" }
    }

    private val argon2id13 = lib.crypto_pwhash_alg_argon2id13()

    private inner class NativeKey : SecretKey {
        private var ptr: Pointer? = checkNotNull(lib.sodium_malloc(KEY)) { "sodium_malloc failed" }

        val pointer: Pointer get() = checkNotNull(ptr) { "key already wiped" }

        /** sodium_free zeroes the memory before releasing it. */
        override fun wipe() {
            ptr?.let(lib::sodium_free)
            ptr = null
        }
    }

    private val SecretKey.p: Pointer get() = (this as NativeKey).pointer

    override fun argon2id(password: ByteArray, salt: ByteArray): SecretKey {
        val key = NativeKey()
        val rc = lib.crypto_pwhash(
            key.pointer, Format.KEY_LEN.toLong(), password, password.size.toLong(), salt,
            Format.ARGON_OPS, NativeLong(Format.ARGON_MEM), argon2id13,
        )
        if (rc != 0) {
            key.wipe()
            error("argon2id failed (out of memory?)")
        }
        return key
    }

    override fun kdf(master: SecretKey, subkeyId: Long, context: ByteArray): SecretKey {
        require(context.size == 8)
        val key = NativeKey()
        check(lib.crypto_kdf_derive_from_key(key.pointer, KEY, subkeyId, context, master.p) == 0) { "kdf failed" }
        return key
    }

    override fun keyedHash16(key: SecretKey, message: ByteArray): ByteArray {
        val out = ByteArray(16)
        check(lib.crypto_generichash(out, NativeLong(16), message, message.size.toLong(), key.p, KEY) == 0) { "hash failed" }
        return out
    }

    override fun aeadSeal(key: SecretKey, nonce: ByteArray, plaintext: ByteArray, ad: ByteArray): ByteArray {
        require(nonce.size == Format.NONCE_LEN)
        val out = ByteArray(plaintext.size + Format.TAG_LEN)
        val rc = lib.crypto_aead_xchacha20poly1305_ietf_encrypt(
            out, null, plaintext, plaintext.size.toLong(), ad, ad.size.toLong(), null, nonce, key.p,
        )
        check(rc == 0) { "seal failed" }
        return out
    }

    override fun aeadOpen(key: SecretKey, nonce: ByteArray, ciphertext: ByteArray, ad: ByteArray): ByteArray? {
        if (nonce.size != Format.NONCE_LEN || ciphertext.size < Format.TAG_LEN) return null
        val out = ByteArray(ciphertext.size - Format.TAG_LEN)
        val rc = lib.crypto_aead_xchacha20poly1305_ietf_decrypt(
            out, null, null, ciphertext, ciphertext.size.toLong(), ad, ad.size.toLong(), nonce, key.p,
        )
        return if (rc == 0) out else null
    }

    override fun randomKey(): SecretKey = NativeKey().also { lib.randombytes_buf(it.pointer, KEY) }

    override fun importKey(bytes: ByteArray): SecretKey {
        require(bytes.size == Format.KEY_LEN)
        return NativeKey().also { it.pointer.write(0, bytes, 0, bytes.size) }
    }

    override fun exportKey(key: SecretKey): ByteArray = key.p.getByteArray(0, Format.KEY_LEN)

    override fun randomBytes(n: Int): ByteArray = ByteArray(n).also { if (n > 0) lib.randombytes_buf(it, NativeLong(n.toLong())) }

    override fun randomBelow(bound: Int): Int {
        require(bound > 0)
        return lib.randombytes_uniform(bound)
    }

    private companion object {
        val KEY = NativeLong(Format.KEY_LEN.toLong())
    }
}
