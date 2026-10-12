package app.palimpsest.core

/**
 * Opaque handle to key material. The production implementation keeps it in libsodium's
 * guarded memory (sodium_malloc) outside the JVM heap; core code never sees the bytes
 * except where the format needs them as plaintext (KEYWRAP, wipe_target).
 */
interface SecretKey {
    /** Zeroes and releases the key. Idempotent; the key is unusable afterwards. */
    fun wipe()
}

/**
 * The only primitives the format uses (spec §3). Production implementation wraps
 * libsodium; tests use the same implementation on desktop libsodium.
 */
interface Crypto {
    /** Argon2id with [Format.ARGON_OPS] / [Format.ARGON_MEM]; 32-byte key. */
    fun argon2id(password: ByteArray, salt: ByteArray): SecretKey

    /** crypto_kdf_derive_from_key, 32-byte subkey. */
    fun kdf(master: SecretKey, subkeyId: Long, context: ByteArray): SecretKey

    /** Keyed BLAKE2b, 16-byte output. */
    fun keyedHash16(key: SecretKey, message: ByteArray): ByteArray

    /** XChaCha20-Poly1305-IETF; returns ciphertext ‖ tag. */
    fun aeadSeal(key: SecretKey, nonce: ByteArray, plaintext: ByteArray, ad: ByteArray): ByteArray

    /** Returns null when authentication fails. */
    fun aeadOpen(key: SecretKey, nonce: ByteArray, ciphertext: ByteArray, ad: ByteArray): ByteArray?

    fun randomKey(): SecretKey

    /** Copies [bytes] (32) into a new key. The caller still owns and wipes [bytes]. */
    fun importKey(bytes: ByteArray): SecretKey

    /** Copies the key out of guarded memory. The caller must wipe the result. */
    fun exportKey(key: SecretKey): ByteArray

    fun randomBytes(n: Int): ByteArray

    /** Uniform in [0, bound). */
    fun randomBelow(bound: Int): Int
}
