package app.palimpsest.core

import java.io.File
import java.nio.file.Files

/**
 * Real libsodium for tests: the desktop binary bundled in lazysodium-java, loaded through
 * the same [LibSodium] binding and [SodiumCrypto] the app uses on Android.
 */
object TestSodium {
    val crypto: Crypto by lazy { SodiumCrypto(LibSodium.load(extract())) }

    private fun extract(): String {
        val os = System.getProperty("os.name").lowercase()
        val arm = System.getProperty("os.arch") == "aarch64"
        val resource = when {
            "win" in os -> "windows64/libsodium.dll"
            "mac" in os -> if (arm) "mac/aarch64/libsodium.dylib" else "mac/intel/libsodium.dylib"
            else -> if (arm) "arm64/libsodium.so" else "linux64/libsodium.so"
        }
        val input = TestSodium::class.java.classLoader.getResourceAsStream(resource)
            ?: error("no bundled libsodium for $os")
        val dir = Files.createTempDirectory("sodium").toFile().apply { deleteOnExit() }
        val lib = File(dir, resource.substringAfterLast('/')).apply { deleteOnExit() }
        input.use { i -> lib.outputStream().use { i.copyTo(it) } }
        return lib.absolutePath
    }
}
