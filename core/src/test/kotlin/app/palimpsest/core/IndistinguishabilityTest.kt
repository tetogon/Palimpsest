package app.palimpsest.core

import org.junit.AfterClass
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.util.zip.Deflater
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Spec §12 T4: from the point of view of someone who holds the decoy password, blocks
 * of a second vault must be statistically indistinguishable from chaff, and the
 * "unknown" blocks of a one-vault container must look like those of a two-vault one.
 *
 * Real libsodium output is uniform by construction, so these tests guard the
 * implementation: an unencrypted header, zero padding outside the ciphertext, a biased
 * locator or PAD would show up here. Thresholds are |z| < 5 (≈6·10⁻⁷ false alarms per check).
 */
class IndistinguishabilityTest {
    private class Region(val name: String, val range: IntRange)

    private val regions = listOf(
        Region("whole block", 0 until Format.BLOCK_SIZE),
        Region("nonce random part", 0 until Format.R_LEN),
        Region("locator", Format.R_LEN until Format.NONCE_LEN),
        Region("ciphertext head (frame header)", Format.NONCE_LEN until Format.NONCE_LEN + 64),
        Region("ciphertext tail (zero padding)", Format.BLOCK_SIZE - 1024 until Format.BLOCK_SIZE),
    )

    @Test
    fun secondVaultLooksLikeChaff() {
        assertTrue("too few blocks to test: ${hidden.size}", hidden.size >= 150)
        assertTrue("too few chaff blocks: ${chaffTwo.size}", chaffTwo.size >= 100)
        for (r in regions) {
            assertUniform("hidden vault, ${r.name}", hidden, r)
            assertUniform("chaff, ${r.name}", chaffTwo, r)
            assertSameDistribution("hidden vs chaff, ${r.name}", hidden, chaffTwo, r)
        }
    }

    @Test
    fun unknownBlocksLookTheSameWithOneOrTwoVaults() {
        for (r in regions) {
            assertUniform("one-vault unknown, ${r.name}", unknownOne, r)
            assertUniform("two-vault unknown, ${r.name}", unknownTwo, r)
            assertSameDistribution("one- vs two-vault unknown, ${r.name}", unknownOne, unknownTwo, r)
        }
    }

    @Test
    fun noBlockClassIsCompressible() {
        for ((name, set) in listOf("hidden" to hidden, "chaff" to chaffTwo, "decoy" to decoyTwo)) {
            val raw = concat(set, 0 until Format.BLOCK_SIZE)
            val ratio = deflatedSize(raw).toDouble() / raw.size
            assertTrue("$name compresses to $ratio", ratio > 0.99)
        }
    }

    @Test
    fun bitBalance() {
        for ((name, set) in listOf("hidden" to hidden, "chaff" to chaffTwo, "decoy" to decoyTwo)) {
            val bytes = concat(set, 0 until Format.BLOCK_SIZE)
            val ones = bytes.sumOf { Integer.bitCount(it.toInt() and 0xFF).toLong() }
            val n = bytes.size * 8.0
            val z = (ones - n / 2) / sqrt(n / 4)
            assertTrue("$name monobit z=$z", abs(z) < 5)
        }
    }

    private fun assertUniform(what: String, set: List<ByteArray>, r: Region) {
        val hist = histogram(concat(set, r.range))
        val expected = hist.sum() / 256.0
        val chi2 = hist.sumOf { (it - expected).pow(2) / expected }
        val z = wilsonHilferty(chi2, 255.0)
        assertTrue("$what: χ²=${"%.1f".format(chi2)}, z=${"%.2f".format(z)}", abs(z) < 5)
    }

    /** χ² test of homogeneity on a 2×256 contingency table. */
    private fun assertSameDistribution(what: String, a: List<ByteArray>, b: List<ByteArray>, r: Region) {
        val ha = histogram(concat(a, r.range))
        val hb = histogram(concat(b, r.range))
        val na = ha.sum()
        val nb = hb.sum()
        var chi2 = 0.0
        for (i in 0 until 256) {
            val col = ha[i] + hb[i]
            if (col == 0.0) continue
            val ea = col * na / (na + nb)
            val eb = col * nb / (na + nb)
            chi2 += (ha[i] - ea).pow(2) / ea + (hb[i] - eb).pow(2) / eb
        }
        val z = wilsonHilferty(chi2, 255.0)
        assertTrue("$what: χ²=${"%.1f".format(chi2)}, z=${"%.2f".format(z)}", abs(z) < 5)
    }

    /** Normal approximation of a χ²(k) statistic. */
    private fun wilsonHilferty(chi2: Double, k: Double): Double {
        val v = 2.0 / (9.0 * k)
        return ((chi2 / k).pow(1.0 / 3.0) - (1 - v)) / sqrt(v)
    }

    private fun histogram(bytes: ByteArray) = DoubleArray(256).also { h -> bytes.forEach { h[it.toInt() and 0xFF]++ } }

    private fun concat(set: List<ByteArray>, range: IntRange): ByteArray {
        val out = ByteArray(set.size * (range.last - range.first + 1))
        var p = 0
        for (b in set) for (i in range) out[p++] = b[i]
        return out
    }

    private fun deflatedSize(data: ByteArray): Int {
        val d = Deflater(Deflater.BEST_COMPRESSION).apply { setInput(data); finish() }
        val buf = ByteArray(data.size + 1024)
        var total = 0
        while (!d.finished()) total += d.deflate(buf)
        d.end()
        return total
    }

    companion object {
        private val c get() = TestSodium.crypto
        private lateinit var dir: File

        /** Container with only the decoy: blocks the decoy holder cannot attribute. */
        lateinit var unknownOne: List<ByteArray>

        /** Container with decoy + hidden vault. */
        lateinit var unknownTwo: List<ByteArray>
        lateinit var hidden: List<ByteArray>
        lateinit var chaffTwo: List<ByteArray>
        lateinit var decoyTwo: List<ByteArray>

        /** Short entries maximise zero padding; edits leave PADs; deletes leave tombstones. */
        private fun fill(container: Container, password: String, entries: Int) {
            val s = container.createVault(password)!!
            repeat(entries) { i ->
                val id = s.save(null, "entry $i", "short text $i")
                if (i % 3 == 0) s.save(id, "entry $i", "edited $i")
                if (i % 10 == 0) s.delete(id)
            }
            s.close()
        }

        /** Every locator a vault owns: its keywrap's password locator, meta, blob and PAD. */
        private fun locatorsOf(container: Container, password: String): List<SecretKey> {
            val pk = PasswordKeys.derive(c, password, container.heap.readSalt())
            val s = container.unlock(password)!!
            val own = s.ownLocators().map { c.importKey(c.exportKey(it)) }
            s.close()
            pk.wrap.wipe()
            return listOf(pk.loc) + own
        }

        private fun blocksOf(container: Container) = (0 until container.heap.blockCount()).map(container.heap::readBlock)

        private fun owned(block: ByteArray, locators: List<SecretKey>) =
            locators.any { Locator.matches(c, it, block.copyOf(Format.NONCE_LEN)) }

        @BeforeClass
        @JvmStatic
        fun build() {
            dir = java.nio.file.Files.createTempDirectory("indist").toFile()

            val one = Container.openOrCreate(File(dir, "one.bin"), c)
            fill(one, "decoy", 120)
            val decoyOne = locatorsOf(one, "decoy")
            unknownOne = blocksOf(one).filterNot { owned(it, decoyOne) }

            val two = Container.openOrCreate(File(dir, "two.bin"), c)
            fill(two, "decoy", 120)
            fill(two, "hidden", 120)
            val decoyLoc = locatorsOf(two, "decoy")
            val hiddenLoc = locatorsOf(two, "hidden")
            val all = blocksOf(two)
            decoyTwo = all.filter { owned(it, decoyLoc) }
            hidden = all.filter { owned(it, hiddenLoc) }
            unknownTwo = all.filterNot { owned(it, decoyLoc) }
            chaffTwo = unknownTwo.filterNot { owned(it, hiddenLoc) }
        }

        @AfterClass
        @JvmStatic
        fun cleanup() {
            dir.deleteRecursively()
        }
    }
}
