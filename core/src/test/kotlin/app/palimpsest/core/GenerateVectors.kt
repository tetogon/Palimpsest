package app.palimpsest.core

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Writes cross-implementation test vectors from the reference (Kotlin + libsodium) core.
 * Run with `gradlew :core:test -PwriteVectors`; the Rust core must reproduce them exactly.
 */
class GenerateVectors {
    private val c = TestSodium.crypto
    private val out = File(System.getProperty("vectorsDir") ?: "")

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun bytes(n: Int, seed: Int) = ByteArray(n) { ((it * 31 + seed * 7) and 0xFF).toByte() }
    private fun json(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

    @Test
    fun write() {
        assumeTrue(System.getProperty("vectorsDir") != null)
        out.mkdirs()
        primitives()
        containers()
    }

    private fun primitives() {
        val salt = bytes(16, 1)
        val master = bytes(32, 2)
        val key = c.importKey(master)
        val nonce = bytes(24, 3)
        val pt = "Palimpsest — тест ✓".toByteArray()
        val ad = Format.AAD_PREFIX + salt
        val r = bytes(16, 4)
        val pw = PasswordKeys.derive(c, "correct horse battery staple", salt)

        val fields = listOf(
            "salt" to hex(salt),
            "password" to json("correct horse battery staple"),
            "argon2id" to hex(c.exportKey(c.argon2id("correct horse battery staple".toByteArray(), salt))),
            "pw_loc" to hex(c.exportKey(pw.loc)),
            "pw_wrap" to hex(c.exportKey(pw.wrap)),
            "master" to hex(master),
            "kdf_vk_1" to hex(c.exportKey(c.kdf(key, 1, Format.CTX_VK))),
            "kdf_vk_5" to hex(c.exportKey(c.kdf(key, 5, Format.CTX_VK))),
            "kdf_pw_2" to hex(c.exportKey(c.kdf(key, 2, Format.CTX_PW))),
            "hash_msg" to hex(r),
            "keyed_hash16" to hex(c.keyedHash16(key, r)),
            "locator_nonce" to hex(r + Locator.of(c, key, r)),
            "aead_nonce" to hex(nonce),
            "aead_ad" to hex(ad),
            "aead_plaintext" to hex(pt),
            "aead_ciphertext" to hex(c.aeadSeal(key, nonce, pt, ad)),
        )
        File(out, "primitives.json").writeText(
            fields.joinToString(",\n", "{\n", "\n}\n") { (k, v) -> "  \"$k\": ${if (v.startsWith("\"")) v else "\"$v\""}" },
        )
    }

    private fun entriesJson(s: VaultSession) = s.entries().joinToString(",\n", "[\n", "\n    ]") { e ->
        "      {\"entry_id\": \"${e.entryId}\", \"title\": ${json(e.head.title)}, \"body\": ${json(e.head.body)}, " +
            "\"diary_date\": ${e.head.diaryDate}, \"created_ms\": ${e.head.createdMs}}"
    }

    private fun containers() {
        // 1. Two vaults: short, edited, deleted, unicode and multi-part entries.
        val f1 = File(out, "two-vaults.bin").apply { delete() }
        File(out, "two-vaults.bin.nonces").delete()
        val one = Container.openOrCreate(f1, c)
        one.createVault("decoy")!!.apply {
            save(null, "groceries", "milk, bread")
            val id = save(null, "plans", "first draft")
            save(id, "plans", "second draft")
        }.close()
        one.createVault("пароль скрытого")!!.apply {
            save(null, "Тайна ✓", "x".repeat(Format.FRAME_CAP * 2 + 17))
            val gone = save(null, "to delete", "bye")
            delete(gone)
            val id = save(null, "edited", "v1")
            save(id, "edited twice", "v2")
            save(id, "edited twice", "v3 final")
        }.close()
        val decoy = Container.openOrCreate(f1, c).unlock("decoy")!!
        val hidden = Container.openOrCreate(f1, c).unlock("пароль скрытого")!!
        File(out, "two-vaults.json").writeText(
            "{\n  \"vaults\": {\n    \"decoy\": ${entriesJson(decoy)},\n    \"пароль скрытого\": ${entriesJson(hidden)}\n  },\n" +
                "  \"wrong_passwords\": [\"Decoy\", \"\", \"пароль\"]\n}\n",
        )
        decoy.close()
        hidden.close()
        File(out, "two-vaults.bin.nonces").delete()

        // 2. "D" is armed to destroy "R" on its next unlock.
        val f2 = File(out, "armed-wipe.bin").apply { delete() }
        val two = Container.openOrCreate(f2, c)
        two.createVault("D")!!.apply { save(null, "decoy entry", "") }.close()
        two.createVault("R")!!.apply { save(null, "real entry", "") }.close()
        two.unlock("D")!!.apply { check(two.armWipe(this, "R") == Container.ArmWipeResult.ARMED) }.close()
        File(f2.path + ".nonces").delete()
        File(out, "armed-wipe.json").writeText(
            "{\n  \"before\": {\"R\": [\"real entry\"]},\n  \"open\": \"D\",\n" +
                "  \"after\": {\"D\": [\"decoy entry\"], \"R\": null}\n}\n",
        )
    }
}
