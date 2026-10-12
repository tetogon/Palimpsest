package app.palimpsest.core

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class HeapFileTest {
    private val dir = java.nio.file.Files.createTempDirectory("heap").toFile()
    private val heap = HeapFile(File(dir, "data.bin"))
    private val cache = File(dir, "data.bin.nonces")
    private val c = TestSodium.crypto

    @After
    fun cleanup() {
        dir.deleteRecursively()
    }

    private fun blocks(n: Int) = List(n) { c.randomBytes(Format.BLOCK_SIZE) }

    /** Ground truth: nonces read straight from the heap. */
    private fun scanned() = List(heap.blockCount()) { heap.readBlock(it).copyOf(Format.NONCE_LEN) }

    private fun assertCacheMatchesHeap() {
        val fromCache = heap.readNonces()
        val truth = scanned()
        assertEquals(truth.size, fromCache.size)
        truth.indices.forEach { assertArrayEquals(truth[it], fromCache[it]) }
    }

    @Test
    fun cacheFollowsAppendsAndOverwrites() {
        heap.create(c.randomBytes(Format.BLOCK_SIZE))
        heap.append(blocks(5))
        assertCacheMatchesHeap()
        heap.append(blocks(3))
        heap.overwrite(mapOf(1 to c.randomBytes(Format.BLOCK_SIZE), 6 to c.randomBytes(Format.BLOCK_SIZE)))
        assertCacheMatchesHeap()
        assertEquals(8L * Format.NONCE_LEN, cache.length())
    }

    @Test
    fun missingOrShortCacheIsRebuilt() {
        heap.create(c.randomBytes(Format.BLOCK_SIZE))
        heap.append(blocks(4))
        heap.readNonces()
        cache.delete()
        assertCacheMatchesHeap()

        // Simulated crash between the heap append and the cache append.
        cache.writeBytes(cache.readBytes().copyOf(2 * Format.NONCE_LEN))
        heap.append(blocks(2))
        assertCacheMatchesHeap()
    }

    @Test
    fun tornTailDropsCache() {
        heap.create(c.randomBytes(Format.BLOCK_SIZE))
        heap.append(blocks(2))
        heap.readNonces()
        heap.file.appendBytes(ByteArray(10))
        heap.repairTail()
        assertTrue(!cache.exists())
        assertCacheMatchesHeap()
    }
}
