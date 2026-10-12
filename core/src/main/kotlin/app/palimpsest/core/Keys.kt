package app.palimpsest.core

import java.text.Normalizer

/** Keys derived from a password (spec §3.1). */
class PasswordKeys private constructor(val loc: SecretKey, val wrap: SecretKey) {
    fun wipe() {
        loc.wipe()
        wrap.wipe()
    }

    companion object {
        fun derive(crypto: Crypto, password: String, salt: ByteArray): PasswordKeys {
            val pw = Normalizer.normalize(password, Normalizer.Form.NFC).toByteArray(Charsets.UTF_8)
            val p = crypto.argon2id(pw, salt)
            pw.fill(0)
            try {
                return PasswordKeys(crypto.kdf(p, 1, Format.CTX_PW), crypto.kdf(p, 2, Format.CTX_PW))
            } finally {
                p.wipe()
            }
        }
    }
}

/** Subkeys of a vault key V (spec §3.2). */
class VaultKeys private constructor(
    val enc: SecretKey,
    val metaLoc: SecretKey,
    val blobLoc: SecretKey,
    val padLoc: SecretKey,
    val blobNonce: SecretKey,
) {
    fun wipe() = listOf(enc, metaLoc, blobLoc, padLoc, blobNonce).forEach { it.wipe() }

    companion object {
        fun derive(crypto: Crypto, v: SecretKey) = VaultKeys(
            enc = crypto.kdf(v, 1, Format.CTX_VK),
            metaLoc = crypto.kdf(v, 2, Format.CTX_VK),
            blobLoc = crypto.kdf(v, 3, Format.CTX_VK),
            padLoc = crypto.kdf(v, 4, Format.CTX_VK),
            blobNonce = crypto.kdf(v, 5, Format.CTX_VK),
        )
    }
}

/** nonce = r ‖ BLAKE2b(key, r)[0..8] (spec §3.3). */
internal object Locator {
    fun of(crypto: Crypto, key: SecretKey, r: ByteArray): ByteArray =
        crypto.keyedHash16(key, r).copyOf(Format.LOC_LEN)

    fun nonce(crypto: Crypto, key: SecretKey): ByteArray {
        val r = crypto.randomBytes(Format.R_LEN)
        return r + of(crypto, key, r)
    }

    fun matches(crypto: Crypto, key: SecretKey, nonce: ByteArray): Boolean {
        val expected = of(crypto, key, nonce.copyOf(Format.R_LEN))
        var diff = 0
        for (i in 0 until Format.LOC_LEN) diff = diff or (expected[i].toInt() xor nonce[Format.R_LEN + i].toInt())
        return diff == 0
    }
}

/** Block-level sealing with the container salt in AAD (spec §3.4). */
internal class BlockCipher(private val crypto: Crypto, salt: ByteArray) {
    private val ad = Format.AAD_PREFIX + salt

    /** Seals and then zeroes [plaintext]: frames can carry key bytes (KEYWRAP). */
    fun seal(key: SecretKey, nonce: ByteArray, plaintext: ByteArray): ByteArray {
        require(nonce.size == Format.NONCE_LEN && plaintext.size == Format.PT_LEN)
        val ct = crypto.aeadSeal(key, nonce, plaintext, ad)
        plaintext.fill(0)
        check(ct.size == Format.PT_LEN + Format.TAG_LEN)
        return nonce + ct
    }

    fun open(key: SecretKey, block: ByteArray): ByteArray? {
        val nonce = block.copyOf(Format.NONCE_LEN)
        val pt = crypto.aeadOpen(key, nonce, block.copyOfRange(Format.NONCE_LEN, Format.BLOCK_SIZE), ad)
        return pt?.takeIf { it.size == Format.PT_LEN }
    }

    fun chaff(): ByteArray = crypto.randomBytes(Format.BLOCK_SIZE)

    /** Own placeholder left behind by purge (spec §6.5). */
    fun pad(padLoc: SecretKey): ByteArray =
        Locator.nonce(crypto, padLoc) + crypto.randomBytes(Format.BLOCK_SIZE - Format.NONCE_LEN)
}
