package app.palimpsest

import android.os.SystemClock
import app.palimpsest.ffi.ArmWipeResult
import app.palimpsest.ffi.ChangePasswordResult
import app.palimpsest.ffi.Container
import app.palimpsest.ffi.ImportResult
import app.palimpsest.ffi.Session
import app.palimpsest.ffi.CoreException
import app.palimpsest.ffi.MergeResult
import app.palimpsest.ffi.backupOpens
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
/** The sync document is a copy of another container, or not a container at all ([bytes] long). */
class SyncFileProblem(val otherContainer: Boolean, val bytes: Long) : Exception()

class VaultRepository(private val file: File, private val transfers: File) {
    companion object {
        /** Blocks a merge or a sync takes in without asking: 50 MiB. */
        val MERGE_LIMIT = 12_800u
    }

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

    /** Called when the app goes to the background, to save the editor's text (see [flush]). */
    @Volatile
    var onBackground: ((Session?) -> Unit)? = null

    /**
     * The app left the foreground, where the system may kill it: save what is unsaved and
     * sync, with a vault open or not.
     */
    fun flush() {
        runCatching { onBackground?.invoke(_session.value) }
    }

    /** Called after the vault is locked, to sync the blocks the session wrote last. */
    @Volatile
    var afterLock: (() -> Unit)? = null

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
        afterLock?.invoke()
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
     * Replaces the container with a backup, if [password] opens a vault in it; false, with
     * nothing locked or replaced, otherwise. The open vault is locked first. Throws
     * [app.palimpsest.ffi.CoreException.Invalid] if the file cannot be a container.
     */
    fun restoreFrom(input: InputStream, password: String): Boolean {
        val dir = scratch()
        try {
            val copy = File(dir, "restore.bin")
            copy.outputStream().use { input.copyTo(it) }
            if (!backupOpens(copy.absolutePath, password)) return false
            // No sync may touch the file while it is being replaced.
            synchronized(syncLock) {
                lock()
                synchronized(this) {
                    opened?.close()
                    opened = null
                    return restoreContainer(file.absolutePath, copy.absolutePath, password)
                }
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    /**
     * Merges a copy of this container (another phone's, an older backup) into it: the blocks it
     * has and this file lacks are added, for every vault at once, without a password (spec
     * §7.3). The open vault re-reads itself. Nothing is added past [limit] blocks (see the core).
     * Throws [app.palimpsest.ffi.CoreException.Invalid] for a file of another container.
     */
    fun mergeFrom(input: InputStream, limit: UInt = MERGE_LIMIT): MergeResult {
        val dir = scratch()
        try {
            val copy = File(dir, "merge.bin")
            copy.outputStream().use { input.copyTo(it) }
            return mergeFile(copy, limit)
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun mergeFile(copy: File, limit: UInt): MergeResult {
        val result = container.mergeFrom(copy.absolutePath, limit)
        if (result is MergeResult.Merged && result.blocks > 0u) {
            // Attachments erased here but referred to again come in with a second pass.
            val healed = runCatching { _session.value?.reload() }.getOrNull() ?: 0u
            if (healed > 0u) {
                container.mergeFrom(copy.absolutePath, UInt.MAX_VALUE)
                runCatching { _session.value?.reload() }
            }
        }
        return result
    }

    private val syncLock = Any()

    /**
     * Two-way sync with a document in a cloud drive (or a Syncthing folder), reached through the
     * system file picker: its blocks are merged in, then the whole container is written back.
     * An empty document just receives the container. Nothing is written when the document
     * already holds exactly this container: every upload is one more copy for the provider.
     * Needs no open vault. Past [limit] blocks nothing is merged or written.
     */
    fun syncWith(open: () -> InputStream?, write: () -> OutputStream?, limit: UInt = MERGE_LIMIT, fresh: Boolean = false): MergeResult = synchronized(syncLock) {
        val dir = scratch()
        try {
            val remote = File(dir, "remote.bin")
            // A document just created through the picker is empty by definition: not read at all.
            if (!fresh) (open() ?: error("cannot read the sync file")).use { input -> remote.outputStream().use { input.copyTo(it) } }
            val result = if (remote.length() > 0) {
                try {
                    mergeFile(remote, limit)
                } catch (e: CoreException.Invalid) {
                    throw SyncFileProblem(otherContainer = e.message.orEmpty().contains("not a copy"), bytes = remote.length())
                }
            } else {
                MergeResult.Merged(0u)
            }
            if (result is MergeResult.TooLarge) return result
            val local = File(dir, "local.bin")
            container.backupTo(local.absolutePath)
            if (!sameContent(local, remote)) {
                (write() ?: error("cannot write the sync file")).use { out -> local.inputStream().use { it.copyTo(out) } }
            }
            result
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun sameContent(a: File, b: File): Boolean {
        if (a.length() != b.length()) return false
        a.inputStream().buffered().use { x ->
            b.inputStream().buffered().use { y ->
                while (true) {
                    val c = x.read()
                    if (c != y.read()) return false
                    if (c < 0) return true
                }
            }
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
