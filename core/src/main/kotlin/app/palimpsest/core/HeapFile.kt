package app.palimpsest.core

import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * On-device container: salt block followed by blocks (spec §4.3).
 * Block index i lives at offset (i + 1) · BLOCK_SIZE.
 *
 * `<name>.nonces` caches every block's nonce (24 bytes each), so unlock reads 24·N
 * bytes instead of the whole heap. It is derivable from the heap, reveals nothing new,
 * and is rebuilt whenever its length disagrees with the heap. The heap is always
 * written first: a crash in between leaves the cache short (rebuilt) or stale for an
 * overwritten block, which only costs one failed AEAD attempt.
 */
class HeapFile(val file: File) {
    private val cache = File(file.parentFile, file.name + ".nonces")

    fun exists() = file.length() >= Format.BLOCK_SIZE

    fun create(saltBlock: ByteArray) {
        require(saltBlock.size == Format.BLOCK_SIZE)
        cache.delete()
        val tmp = File(file.parentFile, file.name + ".tmp")
        FileOutputStream(tmp).use {
            it.write(saltBlock)
            it.fd.sync()
        }
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    /** Drops a torn trailing block left by an interrupted append. */
    fun repairTail() {
        val len = file.length()
        val whole = len - len % Format.BLOCK_SIZE
        if (whole != len) {
            RandomAccessFile(file, "rw").use { it.setLength(whole) }
            cache.delete()
        }
    }

    fun blockCount(): Int = (file.length() / Format.BLOCK_SIZE - 1).toInt()

    fun readSalt(): ByteArray = RandomAccessFile(file, "r").use { f ->
        ByteArray(Format.SALT_LEN).also { f.readFully(it) }
    }

    fun readNonces(): List<ByteArray> {
        val n = blockCount()
        if (!cacheValid(n)) rebuildCache(n)
        val bytes = cache.readBytes()
        return List(n) { bytes.copyOfRange(it * Format.NONCE_LEN, (it + 1) * Format.NONCE_LEN) }
    }

    fun readBlock(i: Int): ByteArray = RandomAccessFile(file, "r").use { f -> read(f, i) }

    fun readBlocks(indices: List<Int>, consumer: (Int, ByteArray) -> Unit) {
        RandomAccessFile(file, "r").use { f -> indices.sorted().forEach { consumer(it, read(f, it)) } }
    }

    /** Appends and fsyncs; returns the index of the first appended block. */
    fun append(blocks: List<ByteArray>): Int {
        val base = blockCount()
        val cacheWasValid = cacheValid(base)
        FileOutputStream(file, true).use { out ->
            blocks.forEach {
                require(it.size == Format.BLOCK_SIZE)
                out.write(it)
            }
            out.fd.sync()
        }
        if (cacheWasValid) {
            FileOutputStream(cache, true).use { out -> blocks.forEach { out.write(it, 0, Format.NONCE_LEN) } }
        }
        return base
    }

    /** In-place overwrite, used only for purge and shredding (spec §8.2, §10). */
    fun overwrite(writes: Map<Int, ByteArray>) {
        val n = blockCount()
        RandomAccessFile(file, "rw").use { f ->
            for ((i, block) in writes) {
                require(block.size == Format.BLOCK_SIZE && i in 0 until n)
                f.seek(offset(i))
                f.write(block)
            }
            f.fd.sync()
        }
        if (cacheValid(n)) {
            RandomAccessFile(cache, "rw").use { c ->
                for ((i, block) in writes) {
                    c.seek(i.toLong() * Format.NONCE_LEN)
                    c.write(block, 0, Format.NONCE_LEN)
                }
            }
        }
    }

    private fun cacheValid(n: Int) = cache.exists() && cache.length() == n.toLong() * Format.NONCE_LEN

    private fun rebuildCache(n: Int) {
        val tmp = File(file.parentFile, cache.name + ".tmp")
        DataInputStream(BufferedInputStream(FileInputStream(file), 1 shl 16)).use { s ->
            FileOutputStream(tmp).use { out ->
                skipFully(s, Format.BLOCK_SIZE)
                val nonce = ByteArray(Format.NONCE_LEN)
                repeat(n) {
                    s.readFully(nonce)
                    out.write(nonce)
                    skipFully(s, Format.BLOCK_SIZE - Format.NONCE_LEN)
                }
            }
        }
        Files.move(tmp.toPath(), cache.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    private fun read(f: RandomAccessFile, i: Int): ByteArray {
        f.seek(offset(i))
        return ByteArray(Format.BLOCK_SIZE).also { f.readFully(it) }
    }

    private fun offset(i: Int) = (i.toLong() + 1) * Format.BLOCK_SIZE

    private fun skipFully(s: DataInputStream, n: Int) {
        var left = n
        while (left > 0) {
            val skipped = s.skipBytes(left)
            if (skipped <= 0) throw java.io.EOFException()
            left -= skipped
        }
    }
}
