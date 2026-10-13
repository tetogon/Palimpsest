package app.palimpsest

import android.os.SystemClock
import app.palimpsest.ffi.ArmWipeResult
import app.palimpsest.ffi.ChangePasswordResult
import app.palimpsest.ffi.Container
import app.palimpsest.ffi.ImportResult
import app.palimpsest.ffi.Session
import app.palimpsest.ffi.restoreContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * Process-wide owner of the container (Rust core) and of the single open session.
 * [transfers] holds files on their way in or out; they are ciphertext only.
 */
class VaultRepository(private val file: File, private val transfers: File) {
    @Volatile
    private var opened: Container? = null

    /** Opened (and on first launch created) off the main thread by the first caller. */
    val container: Container
        get() = opened ?: synchronized(this) {
            opened ?: Container.openOrCreate(file.absolutePath).also { opened = it }
        }

    init {
        transfers.deleteRecursively() // left over from an interrupted transfer
    }

    private val _session = MutableStateFlow<Session?>(null)
    val session: StateFlow<Session?> = _session

    /** Called right before locking so unsaved editor text is committed, not lost. */
    @Volatile
    var beforeLock: ((Session) -> Unit)? = null

    /** Called after the vault is locked or deleted (clears our clipboard content). */
    @Volatile
    var onLocked: (() -> Unit)? = null

    /** elapsedRealtime of the last touch or keystroke, for the idle lock. */
    @Volatile
    var lastActivity = SystemClock.elapsedRealtime()
        private set

    fun touch() {
        lastActivity = SystemClock.elapsedRealtime()
    }

    fun unlock(password: String): Boolean {
        val s = container.unlock(password) ?: return false
        touch()
        _session.value = s
        return true
    }

    /** Returns false if the password already belongs to a vault. */
    fun createVault(password: String): Boolean {
        val s = container.createVault(password) ?: return false
        touch()
        _session.value = s
        return true
    }

    fun changePassword(old: String, new: String): ChangePasswordResult =
        container.changePassword(requireSession(), old, new)

    fun armWipe(targetPassword: String): ArmWipeResult = container.armWipe(requireSession(), targetPassword)

    fun disarmWipe() = container.disarmWipe(requireSession())

    /** Own PAD blocks freed; the file shrinks by that many 4 KiB blocks. */
    fun reclaimSpace(): Int = container.reclaimSpace(requireSession()).toInt()

    fun deleteVault() {
        val s = requireSession()
        container.deleteVault(s)
        _session.value = null
        s.close()
        onLocked?.invoke()
    }

    fun lock() {
        val s = _session.value ?: return
        runCatching { beforeLock?.invoke(s) }
        s.lock()
        _session.value = null
        s.close() // frees the native object; keys were already wiped by lock()
        onLocked?.invoke()
    }

    // ---- Backup and transfer (spec §11.7) --------------------------------------------------

    /** A fresh directory for one transfer: the core writes side files next to its files. */
    private fun scratch(): File = File(transfers, System.nanoTime().toString()).apply { mkdirs() }

    /** The whole container file, every vault and the chaff, as it is. */
    fun backupTo(out: OutputStream) {
        val dir = scratch()
        try {
            val copy = File(dir, "backup.bin")
            container.backupTo(copy.absolutePath)
            copy.inputStream().use { it.copyTo(out) }
        } finally {
            dir.deleteRecursively()
        }
    }

    /**
     * Replaces the container with a backup. The open vault is locked first. Throws
     * [app.palimpsest.ffi.CoreException.Invalid] if the file cannot be a container.
     */
    fun restoreFrom(input: InputStream) {
        val dir = scratch()
        try {
            val copy = File(dir, "restore.bin")
            copy.outputStream().use { input.copyTo(it) }
            lock()
            synchronized(this) {
                opened?.close()
                opened = null
                restoreContainer(file.absolutePath, copy.absolutePath)
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    /**
     * Writes this vault, under [password], into a new container file and returns it; the
     * caller copies it out and calls [discard].
     */
    fun exportVault(password: String): File {
        val copy = File(scratch(), "export.bin")
        container.exportVault(requireSession(), copy.absolutePath, password)
        return copy
    }

    fun discard(export: File) {
        export.parentFile?.deleteRecursively()
    }

    /** Copies the vault [password] opens in [input] into this container (see the core). */
    fun importVault(input: InputStream, password: String): ImportResult {
        val dir = scratch()
        try {
            val copy = File(dir, "import.bin")
            copy.outputStream().use { input.copyTo(it) }
            return container.importVault(copy.absolutePath, password, _session.value)
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun requireSession() = checkNotNull(_session.value) { "vault is locked" }
}
