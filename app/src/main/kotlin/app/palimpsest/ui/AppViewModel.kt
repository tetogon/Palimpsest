package app.palimpsest.ui

import android.app.Application
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.palimpsest.AppSettings
import app.palimpsest.PalimpsestApp
import app.palimpsest.R
import android.graphics.Bitmap
import android.net.Uri
import android.util.LruCache
import app.palimpsest.ImagePrep
import app.palimpsest.VoicePlayer
import app.palimpsest.ffi.ArmWipeResult
import app.palimpsest.ffi.AttachmentInfo
import kotlinx.coroutines.withContext
import app.palimpsest.ffi.ChangePasswordResult
import app.palimpsest.ffi.CoreException
import app.palimpsest.ffi.ImportResult
import app.palimpsest.ffi.EntryKind
import app.palimpsest.ffi.EntrySummary
import app.palimpsest.ffi.Folder
import app.palimpsest.ffi.TagCount
import app.palimpsest.ffi.PurgeMode
import app.palimpsest.ffi.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class Draft(
    val entryId: String?,
    val kind: EntryKind,
    val title: String,
    val body: String,
    val savedTitle: String,
    val savedBody: String,
    val done: Boolean = false,
    val diaryDate: Int? = null,
    val updatedMs: Long? = null,
    val folderId: String? = null,
    val color: Int = 0,
    val pinned: Boolean = false,
    val attachments: List<AttachmentInfo> = emptyList(),
    val tags: List<String> = emptyList(),
    /** Body of a concurrent version, offered for merging (spec §7.1). */
    val conflictBody: String? = null,
) {
    val dirty get() = title != savedTitle || body != savedBody
    val empty get() = title.isBlank() && body.isBlank()
}

/** Where Back goes from an entry opened through a link. */
sealed interface Back {
    data class Entry(val id: String, val then: Back?) : Back
    data object Graph : Back
}

sealed interface Screen {
    data object Unlock : Screen
    data object CreateVault : Screen
    data object EntryList : Screen
    /**
     * [visit] tells apart two openings, also of the same entry (following a link). [back] is
     * where Back leads: the entry or the graph a link was followed from.
     */
    data class Editor(val draft: Draft, val visit: Long = Doc.key(), val back: Back? = null) : Screen
    data object Graph : Screen
    data object Settings : Screen
    data object Trash : Screen

    /** App-wide settings; [returnTo] is the screen it was opened from. */
    data class AppPrefs(val returnTo: Screen) : Screen
}

data class UiState(
    val screen: Screen = Screen.Unlock,
    val busy: Boolean = false,
    @StringRes val error: Int? = null,
    /** Confirmation of a successful settings action. */
    @StringRes val info: Int? = null,
    /** Titles and previews only: full bodies leave the Rust core one entry at a time. */
    val entries: List<EntrySummary> = emptyList(),
    val section: EntryKind = EntryKind.NOTE,
    /** Search runs inside the core; the list shows its matches. */
    val query: String = "",
    /** Entry ids picked by long press; non-empty means selection mode. */
    val selection: Set<String> = emptySet(),
    val folders: List<Folder> = emptyList(),
    val tags: List<TagCount> = emptyList(),
    /** Notes shown from this folder only; null = all notes. */
    val folderFilter: String? = null,
    /** Just moved to the trash; the snackbar offers to undo it. */
    val justTrashed: List<String> = emptyList(),
    /** An attachment is being processed and encrypted. */
    val attaching: Boolean = false,
    /** Voice note being played. */
    val playingId: String? = null,
    val wipeArmed: Boolean = false,
    /** Own PAD blocks that "reclaim space" would free. */
    val reclaimableBlocks: Int = 0,
    val purgeManual: Boolean = false,
    /** Blocks of replaced revisions waiting for "erase now" (manual mode). */
    val pendingPurgeBlocks: Int = 0,
    val appSettings: AppSettings.Values = AppSettings.Values(),
    /** Every live entry, whatever the search: for linking and the graph. */
    val allEntries: List<EntrySummary> = emptyList(),
    /** Links between entries; loaded with an entry and with the graph. */
    val links: List<app.palimpsest.ffi.NoteLink> = emptyList(),
    /** An exported vault is ready to be saved where the user picks. */
    val exportReady: Boolean = false,
    /** A photo being cropped before it is stored; decoded in memory only. */
    val cropping: Bitmap? = null,
)

/** What the home screen shows: current section and folder, not trashed, sorted. */
fun UiState.visible(): List<EntrySummary> {
    val list = entries.filter {
        it.kind == section && it.trashedMs == null &&
            (section != EntryKind.NOTE || folderFilter == null || it.folderId == folderFilter)
    }
    val order = when (appSettings.sort) {
        AppSettings.SortOrder.UPDATED -> compareByDescending<EntrySummary> { it.updatedMs }
        AppSettings.SortOrder.CREATED -> compareByDescending { it.createdMs }
        AppSettings.SortOrder.TITLE -> compareBy { it.title.ifBlank { previewPlain(it.preview) }.lowercase() }
    }
    return list.sortedWith(compareByDescending<EntrySummary> { it.pinned }.then(order))
}

fun UiState.trashed(): List<EntrySummary> =
    entries.filter { it.trashedMs != null }.sortedByDescending { it.trashedMs }

private fun Application.settings() = (this as PalimpsestApp).settings

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = (app as PalimpsestApp).repository
    private val settings = app.settings()
    private val _ui = MutableStateFlow(UiState(appSettings = settings.values.value))
    val ui: StateFlow<UiState> = _ui

    /** Decoded thumbnails by attachment id; cleared on lock. */
    private val thumbs = LruCache<String, Bitmap>(80)
    /** Larger copies for photos placed in the text, bounded by bytes. */
    private val embeds = object : LruCache<String, Bitmap>(32 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val player = VoicePlayer()

    init {
        repo.beforeLock = { session -> draft()?.let { saveDraft(session, it) } }
        viewModelScope.launch(Dispatchers.IO) {
            repo.session.collect { session ->
                if (session == null) {
                    withMain { player.stop() }
                    thumbs.evictAll()
                    embeds.evictAll()
                    _ui.value = UiState(appSettings = settings.values.value)
                } else {
                    showList(session)
                }
            }
        }
    }

    fun unlock(password: String) {
        if (password.isEmpty()) return fail(R.string.password_empty)
        work {
            if (!repo.unlock(password)) slowFail(R.string.wrong_password)
        }
    }

    fun openAppSettings() = _ui.update { it.copy(screen = Screen.AppPrefs(it.screen), error = null, info = null) }

    fun closeAppSettings() = _ui.update { state ->
        val screen = state.screen as? Screen.AppPrefs ?: return@update state
        // The vault may have been locked meanwhile; then go back to the unlock screen.
        val back = if (repo.session.value == null && screen.returnTo != Screen.CreateVault) Screen.Unlock else screen.returnTo
        state.copy(screen = back)
    }

    /** After a snackbar showed the current info or error. */
    fun clearMessages() = _ui.update { it.copy(info = null, error = null) }

    fun updateAppSettings(values: AppSettings.Values) {
        settings.update(values)
        _ui.update { it.copy(appSettings = values) }
    }

    fun createVault(password: String, repeat: String) {
        when {
            password.isEmpty() -> fail(R.string.password_empty)
            password != repeat -> fail(R.string.passwords_differ)
            // "Taken" tells that the password opens a vault: as slow as a wrong password.
            else -> work { if (!repo.createVault(password)) slowFail(R.string.password_taken) }
        }
    }

    fun showCreate() = _ui.update { it.copy(screen = Screen.CreateVault, error = null) }

    fun showUnlock() = _ui.update { it.copy(screen = Screen.Unlock, error = null) }

    fun lock() {
        viewModelScope.launch(Dispatchers.IO) { repo.lock() }
    }

    fun openEntry(id: String) = work {
        val session = repo.session.value ?: return@work
        open(session, id)
    }

    /** From a link in an entry or from the graph; Back returns there. */
    fun openLinked(id: String) = work {
        val session = repo.session.value ?: return@work
        val back = when (val s = _ui.value.screen) {
            is Screen.Editor -> s.draft.entryId?.let { Back.Entry(it, s.back) } ?: s.back
            Screen.Graph -> Back.Graph
            else -> null
        }
        draft()?.let { saveDraft(session, it) }
        refresh(session)
        open(session, id, back)
    }

    fun openGraph() = work {
        val session = repo.session.value ?: return@work
        refresh(session)
        _ui.update { it.copy(screen = Screen.Graph, links = session.noteLinks(), selection = emptySet()) }
    }

    fun closeGraph() = _ui.update { it.copy(screen = Screen.EntryList) }

    /**
     * From the graph: links [fromId] to [toId] (at the end of its text), or, if the two are
     * already linked either way, removes the link.
     */
    fun toggleLink(fromId: String, toId: String) = quiet {
        val session = repo.session.value ?: return@quiet
        val links = _ui.value.links
        val forward = links.any { it.fromId == fromId && it.toId == toId }
        val backward = links.any { it.fromId == toId && it.toId == fromId }
        if (forward || backward) {
            if (forward) unlink(session, fromId, toId)
            if (backward) unlink(session, toId, fromId)
        } else {
            val from = session.entry(fromId) ?: return@quiet
            val to = _ui.value.allEntries.firstOrNull { it.entryId == toId } ?: return@quiet
            val label = to.title.ifBlank { previewPlain(to.preview.lineSequence().firstOrNull().orEmpty()) }
                .replace(Regex("""[\[\]\n]"""), " ").trim().ifEmpty { "…" }
            val body = from.body.trimEnd().let { if (it.isEmpty()) "" else "$it\n\n" } + "[$label](note:$toId)"
            session.save(fromId, from.kind, from.folderId, from.title, body)
        }
        refresh(session)
        _ui.update { it.copy(links = session.noteLinks()) }
    }

    /** A link alone on its line goes with the line; inside text it leaves its words. */
    private fun unlink(session: Session, fromId: String, toId: String) {
        val e = session.entry(fromId) ?: return
        val link = Regex("""\[([^\]\n]*)]\(note:$toId\)""")
        val body = e.body.split('\n')
            .filterNot { link.matches(it.trim()) }
            .joinToString("\n") { link.replace(it) { m -> m.groupValues[1] } }
            .replace(Regex("""\n{3,}"""), "\n\n")
            .trimEnd()
        if (body != e.body) session.save(fromId, e.kind, e.folderId, e.title, body)
    }

    private fun open(session: Session, id: String, back: Back? = null) {
        val e = session.entry(id) ?: return
        val draft = Draft(
            entryId = id, kind = e.kind, title = e.title, body = e.body, savedTitle = e.title, savedBody = e.body,
            done = e.done, diaryDate = e.diaryDate, updatedMs = e.updatedMs, folderId = e.folderId,
            color = e.color, pinned = e.pinned, attachments = e.attachments, tags = e.tags, conflictBody = e.conflictBody,
        )
        _ui.update { it.copy(screen = Screen.Editor(draft, back = back), links = session.noteLinks()) }
    }

    /** A new note lands in the folder being viewed; a diary entry on [day] (default today). */
    fun newEntry(kind: EntryKind, day: java.time.LocalDate? = null) = _ui.update {
        val today = if (kind == EntryKind.DIARY) (day ?: java.time.LocalDate.now()).toEpochDay().toInt() else null
        val folder = if (kind == EntryKind.NOTE) it.folderFilter else null
        it.copy(screen = Screen.Editor(Draft(null, kind, "", "", "", "", diaryDate = today, folderId = folder)))
    }

    fun setSection(kind: EntryKind) = _ui.update { it.copy(section = kind, selection = emptySet()) }

    fun setFolderFilter(folderId: String?) = _ui.update { it.copy(folderFilter = folderId, selection = emptySet()) }

    fun toggleSelect(id: String) = _ui.update {
        it.copy(selection = if (id in it.selection) it.selection - id else it.selection + id)
    }

    /** Everything visible in the current view (section, folder, search, or the trash). */
    fun selectAll() = _ui.update { s ->
        val visible = if (s.screen == Screen.Trash) s.trashed() else s.visible()
        s.copy(selection = visible.mapTo(HashSet()) { it.entryId })
    }

    fun clearSelection() = _ui.update { it.copy(selection = emptySet()) }

    private fun onSelection(action: (Session, List<String>) -> Unit) = quiet {
        val session = repo.session.value ?: return@quiet
        val ids = _ui.value.selection.toList()
        if (ids.isEmpty()) return@quiet
        action(session, ids)
        _ui.update { it.copy(selection = emptySet()) }
        refresh(session)
    }

    fun trashSelected() = onSelection { s, ids ->
        s.trash(ids)
        _ui.update { it.copy(justTrashed = ids) }
    }

    fun undoTrash() = quiet {
        val session = repo.session.value ?: return@quiet
        session.restore(_ui.value.justTrashed)
        _ui.update { it.copy(justTrashed = emptyList()) }
        refresh(session)
    }

    fun trashMessageShown() = _ui.update { it.copy(justTrashed = emptyList()) }

    /** Pins all if any selected note is unpinned, otherwise unpins all. */
    fun togglePinSelected() = onSelection { s, ids ->
        val anyUnpinned = _ui.value.entries.any { it.entryId in ids && !it.pinned }
        s.setPinned(ids, anyUnpinned)
    }

    fun colorSelected(color: Int) = onSelection { s, ids -> s.setColor(ids, color) }

    fun moveSelected(folderId: String?) = onSelection { s, ids -> s.moveToFolder(ids, folderId) }

    fun restoreSelected() = onSelection { s, ids -> s.restore(ids) }

    /** Permanent; only offered in the trash. */
    fun deleteSelected() = onSelection { s, ids -> s.deleteMany(ids) }

    fun emptyTrash() = quiet {
        val session = repo.session.value ?: return@quiet
        session.emptyTrash()
        refresh(session)
    }

    fun openTrash() = _ui.update { it.copy(screen = Screen.Trash, selection = emptySet()) }

    fun closeTrash() = _ui.update { it.copy(screen = Screen.EntryList, selection = emptySet()) }

    fun createFolder(name: String) {
        if (name.isBlank()) return
        quiet {
            val session = repo.session.value ?: return@quiet
            val id = session.createFolder(name.trim())
            _ui.update { it.copy(folderFilter = id) }
            refresh(session)
        }
    }

    fun renameFolder(id: String, name: String) {
        if (name.isBlank()) return
        quiet {
            val session = repo.session.value ?: return@quiet
            session.renameFolder(id, name.trim())
            refresh(session)
        }
    }

    fun reorderFolders(ids: List<String>) {
        _ui.update { s -> s.copy(folders = ids.mapNotNull { id -> s.folders.firstOrNull { it.folderId == id } }) }
        quiet {
            val session = repo.session.value ?: return@quiet
            session.reorderFolders(ids)
            refresh(session)
        }
    }

    fun deleteFolder(id: String) = quiet {
        val session = repo.session.value ?: return@quiet
        session.deleteFolder(id)
        _ui.update { if (it.folderFilter == id) it.copy(folderFilter = null) else it }
        refresh(session)
    }

    fun setSort(sort: AppSettings.SortOrder) = updateAppSettings(_ui.value.appSettings.copy(sort = sort))

    fun setQuery(query: String) {
        repo.touch()
        _ui.update { it.copy(query = query) }
        viewModelScope.launch(Dispatchers.IO) {
            val session = repo.session.value ?: return@launch
            val found = runCatching { session.search(query) }.getOrNull() ?: return@launch
            // Drop stale results if the user kept typing.
            _ui.update { if (it.query == query) it.copy(entries = found) else it }
        }
    }

    /** Tick from the list; `done` is the new state. */
    fun setDone(id: String, done: Boolean) = quiet {
        val session = repo.session.value ?: return@quiet
        session.setDone(id, done)
        refresh(session)
    }

    fun quickAddTodo(text: String) {
        if (text.isBlank()) return
        quiet {
            val session = repo.session.value ?: return@quiet
            session.save(null, EntryKind.TODO, null, text.trim(), "")
            refresh(session)
        }
    }

    fun toggleDraftDone() = editDraft { it.copy(done = !it.done) }

    // ---- Tags -------------------------------------------------------------------------------

    fun addDraftTag(tag: String) = editDraft { d ->
        val t = tag.trim().removePrefix("#").trim()
        if (t.isEmpty() || d.tags.any { it.equals(t, ignoreCase = true) }) d else d.copy(tags = d.tags + t)
    }

    fun removeDraftTag(tag: String) = editDraft { it.copy(tags = it.tags - tag) }

    fun tagSelected(tag: String) = onSelection { s, ids -> s.addTag(ids, tag) }

    /** Everywhere; a search filtering by the old name follows the new one. */
    fun renameTag(old: String, new: String) {
        val clean = new.trim().removePrefix("#").trim()
        if (clean.isEmpty()) return
        quiet {
            val session = repo.session.value ?: return@quiet
            session.renameTag(old, clean)
            replaceTagInQuery(old, clean)
            refresh(session)
        }
    }

    fun deleteTag(tag: String) = quiet {
        val session = repo.session.value ?: return@quiet
        session.deleteTag(tag)
        replaceTagInQuery(tag, null)
        refresh(session)
    }

    private fun replaceTagInQuery(old: String, new: String?) {
        val words = _ui.value.query.split(' ').filter { it.isNotBlank() }
        val next = words.mapNotNull { if (it.equals("#$old", ignoreCase = true)) new?.let { n -> "#$n" } else it }.distinct()
        if (next != words) setQuery(next.joinToString(" "))
    }

    /** Adds "#tag" to the search, or removes it if it is already there. */
    fun toggleTagFilter(tag: String) {
        val token = "#$tag"
        val words = _ui.value.query.split(' ').filter { it.isNotBlank() }
        val next = if (words.any { it.equals(token, ignoreCase = true) }) {
            words.filterNot { it.equals(token, ignoreCase = true) }
        } else {
            words + token
        }
        setQuery(next.joinToString(" "))
    }

    fun reorderAttachments(order: List<String>) {
        editDraft { d -> d.copy(attachments = order.mapNotNull { id -> d.attachments.firstOrNull { it.attachmentId == id } }) }
        quiet {
            val session = repo.session.value ?: return@quiet
            val id = draft()?.entryId ?: return@quiet
            session.reorderAttachments(id, order)
            refresh(session)
        }
    }
    // ---- Attachments ------------------------------------------------------------------------

    suspend fun thumbnail(id: String): Bitmap? = withContext(Dispatchers.IO) {
        thumbs.get(id) ?: runCatching {
            repo.session.value?.thumbnailData(id)?.let(ImagePrep::decode)?.also { thumbs.put(id, it) }
        }.getOrNull()
    }

    /** A photo placed in the text, decoded at about screen width. */
    suspend fun embedImage(id: String): Bitmap? = withContext(Dispatchers.IO) {
        embeds.get(id) ?: runCatching {
            repo.session.value?.attachmentData(id)?.let { bytes ->
                ImagePrep.decode(bytes, EMBED_SIDE).also { bytes.fill(0) }
            }?.also { embeds.put(id, it) }
        }.getOrNull()
    }

    suspend fun fullImage(id: String): Bitmap? = withContext(Dispatchers.IO) {
        runCatching { repo.session.value?.attachmentData(id)?.let(ImagePrep::decode) }.getOrNull()
    }

    /** Attachments need an entry to hang on: a new draft is saved first. */
    private fun ensureSaved(session: Session): String? {
        val d = draft() ?: return null
        d.entryId?.let { return it }
        val id = session.save(null, d.kind, d.folderId, d.title, d.body)
        editDraft { it.copy(entryId = id, savedTitle = it.title, savedBody = it.body) }
        return id
    }

    private fun attach(prepare: () -> Triple<ByteArray, ByteArray?, Triple<String, Long, Pair<Int, Int>>>) {
        _ui.update { it.copy(attaching = true) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val session = repo.session.value ?: return@launch
                val (data, thumb, meta) = prepare()
                val (mime, duration, size) = meta
                val id = ensureSaved(session) ?: return@launch
                session.addAttachment(id, data, mime, thumb, duration, size.first, size.second)
                data.fill(0)
                val list = session.entry(id)?.attachments.orEmpty()
                editDraft { it.copy(attachments = list) }
                refresh(session)
            } catch (e: Exception) {
                fail(R.string.attachment_failed)
            } finally {
                _ui.update { it.copy(attaching = false) }
            }
        }
    }

    /** A photo first goes to the crop screen ([UiState.cropping]), then [finishCrop] stores it. */
    fun attachPhoto(jpeg: ByteArray, rotation: Int) = toCrop {
        ImagePrep.load(jpeg, rotation).also { jpeg.fill(0) }
    }

    fun attachFromGallery(uri: Uri) = toCrop {
        val raw = ImagePrep.read(getApplication(), uri)
        ImagePrep.load(raw).also { raw.fill(0) }
    }

    private fun toCrop(load: () -> Bitmap) {
        _ui.update { it.copy(attaching = true) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val bitmap = load()
                _ui.update { it.copy(cropping = bitmap) }
            } catch (e: Exception) {
                fail(R.string.attachment_failed)
            } finally {
                _ui.update { it.copy(attaching = false) }
            }
        }
    }

    fun finishCrop(bitmap: Bitmap, crop: android.graphics.RectF) {
        _ui.update { it.copy(cropping = null) }
        attach {
            val p = ImagePrep.finish(bitmap, crop)
            Triple(p.jpeg, p.thumbnail, Triple("image/jpeg", 0L, p.width to p.height))
        }
    }

    fun cancelCrop() = _ui.update { it.copy(cropping = null) }

    fun attachVoice(audio: ByteArray, durationMs: Long) = attach {
        Triple(audio, null, Triple("audio/aac", durationMs, 0 to 0))
    }

    fun removeAttachment(a: AttachmentInfo) = quiet {
        val session = repo.session.value ?: return@quiet
        val id = draft()?.entryId ?: return@quiet
        if (player.playingId == a.attachmentId) stopVoice()
        session.removeAttachment(id, a.attachmentId)
        thumbs.remove(a.attachmentId)
        embeds.remove(a.attachmentId)
        val list = session.entry(id)?.attachments.orEmpty()
        editDraft { d ->
            val body = d.body.split('\n').filterNot { Md.parseEmbed(it)?.id == a.attachmentId }.joinToString("\n")
            d.copy(attachments = list, body = body)
        }
        refresh(session)
    }

    fun toggleVoice(a: AttachmentInfo) {
        if (player.playingId == a.attachmentId) return stopVoice()
        quiet {
            val session = repo.session.value ?: return@quiet
            val audio = session.attachmentData(a.attachmentId)
            withMain { player.play(a.attachmentId, audio) { _ui.update { it.copy(playingId = null) } } }
            _ui.update { it.copy(playingId = a.attachmentId) }
        }
    }

    fun stopVoice() {
        player.stop()
        _ui.update { it.copy(playingId = null) }
    }

    private fun withMain(block: () -> Unit) {
        viewModelScope.launch(Dispatchers.Main) { block() }
    }

    override fun onCleared() {
        pendingExport?.let(repo::discard)
        player.stop()
        thumbs.evictAll()
        embeds.evictAll()
    }


    fun toggleDraftPinned() = editDraft { it.copy(pinned = !it.pinned) }

    fun setDraftDiaryDate(day: java.time.LocalDate) = editDraft { it.copy(diaryDate = day.toEpochDay().toInt()) }

    fun setDiaryCalendar(on: Boolean) = updateAppSettings(_ui.value.appSettings.copy(diaryCalendar = on))

    fun setDraftColor(color: Int) = editDraft { it.copy(color = color) }

    fun setDraftFolder(folderId: String?) = editDraft { it.copy(folderId = folderId) }

    fun editTitle(title: String) {
        repo.touch()
        editDraft { it.copy(title = title) }
    }

    fun editBody(body: String) {
        repo.touch()
        editDraft { it.copy(body = body) }
    }

    fun appendConflict() = editDraft { d ->
        d.copy(body = d.body + "\n\n———\n\n" + d.conflictBody.orEmpty(), conflictBody = null)
    }

    /** Back to where the entry was opened from: the linking entry, the graph or the list. */
    fun closeEditor() = work {
        withMain { stopVoice() }
        val session = repo.session.value ?: return@work
        val back = (_ui.value.screen as? Screen.Editor)?.back
        draft()?.let { saveDraft(session, it) }
        when (back) {
            is Back.Entry -> {
                refresh(session)
                if (session.entry(back.id) != null) open(session, back.id, back.then) else showList(session)
            }
            Back.Graph -> {
                refresh(session)
                _ui.update { it.copy(screen = Screen.Graph, links = session.noteLinks()) }
            }
            null -> showList(session)
        }
    }

    /** The editor's delete moves the entry to the trash, with undo on the list. */
    fun deleteEntry() = work {
        val session = repo.session.value ?: return@work
        val id = draft()?.entryId
        if (id != null) {
            session.trash(listOf(id))
            _ui.update { it.copy(justTrashed = listOf(id)) }
        }
        showList(session)
    }

    fun openSettings() = work {
        val session = repo.session.value ?: return@work
        _ui.update {
            it.copy(
                screen = Screen.Settings,
                wipeArmed = session.wipeArmed(),
                reclaimableBlocks = session.reclaimableBlocks().toInt(),
                purgeManual = session.purgeMode() == PurgeMode.MANUAL,
                pendingPurgeBlocks = session.pendingPurgeBlocks().toInt(),
                info = null,
            )
        }
    }

    fun setPurgeManual(manual: Boolean) = work {
        val session = repo.session.value ?: return@work
        session.setPurgeMode(if (manual) PurgeMode.MANUAL else PurgeMode.IMMEDIATE)
        _ui.update { it.copy(purgeManual = manual) }
    }

    fun purgeNow() = work {
        val session = repo.session.value ?: return@work
        session.purgeNow()
        _ui.update {
            it.copy(pendingPurgeBlocks = 0, reclaimableBlocks = session.reclaimableBlocks().toInt())
        }
        done(R.string.purge_done)
    }

    fun reclaimSpace() = work {
        repo.reclaimSpace()
        _ui.update { it.copy(reclaimableBlocks = 0) }
        done(R.string.reclaim_done)
    }

    fun closeSettings() = work {
        repo.session.value?.let(::showList)
    }

    fun changePassword(old: String, new: String, repeat: String) {
        when {
            old.isEmpty() || new.isEmpty() -> fail(R.string.password_empty)
            new != repeat -> fail(R.string.passwords_differ)
            else -> work {
                when (repo.changePassword(old, new)) {
                    ChangePasswordResult.CHANGED -> done(R.string.password_changed)
                    ChangePasswordResult.WRONG_PASSWORD -> slowFail(R.string.wrong_password)
                    ChangePasswordResult.PASSWORD_TAKEN -> slowFail(R.string.password_taken)
                }
            }
        }
    }

    fun armWipe(targetPassword: String) {
        if (targetPassword.isEmpty()) return fail(R.string.password_empty)
        work {
            when (repo.armWipe(targetPassword)) {
                ArmWipeResult.ARMED -> {
                    _ui.update { it.copy(wipeArmed = true) }
                    done(R.string.wipe_armed)
                }
                ArmWipeResult.NOT_FOUND -> slowFail(R.string.wipe_not_found)
                ArmWipeResult.SAME_VAULT -> slowFail(R.string.wipe_same_vault)
            }
        }
    }

    fun disarmWipe() = work {
        repo.disarmWipe()
        _ui.update { it.copy(wipeArmed = false) }
        done(R.string.wipe_disarmed)
    }

    fun deleteVault() = work { repo.deleteVault() }

    // ---- Backup and transfer ----------------------------------------------------------------

    private val resolver get() = getApplication<Application>().contentResolver

    /** Exported vault waiting for the save dialog. Ciphertext, under the export password. */
    @Volatile
    private var pendingExport: java.io.File? = null

    fun backup(uri: Uri) = work {
        resolver.openOutputStream(uri, "wt")?.use(repo::backupTo) ?: error("no output")
        done(R.string.backup_done)
    }

    fun restore(uri: Uri) = work {
        val ok = runCatching { resolver.openInputStream(uri)?.use(repo::restoreFrom) ?: error("no input") }
        if (ok.isFailure) {
            fail(if (ok.exceptionOrNull() is CoreException.Invalid) R.string.not_a_backup else R.string.error_generic)
        } else {
            done(R.string.restore_done)
        }
    }

    fun importVault(uri: Uri, password: String) {
        if (password.isEmpty()) return fail(R.string.password_empty)
        work {
            val result = resolver.openInputStream(uri)?.use { repo.importVault(it, password) } ?: error("no input")
            when (result) {
                is ImportResult.NotFound -> slowFail(R.string.import_not_found)
                is ImportResult.Created -> done(R.string.import_created)
                is ImportResult.Merged -> done(if (result.entries == 0u) R.string.import_nothing else R.string.import_merged)
            }
            repo.session.value?.let(::refresh)
        }
    }

    /** First half of an export: encrypt into a temporary file, then ask where to save it. */
    fun prepareExport(password: String, repeat: String) {
        when {
            password.isEmpty() -> fail(R.string.password_empty)
            password != repeat -> fail(R.string.passwords_differ)
            else -> work {
                pendingExport?.let(repo::discard)
                pendingExport = repo.exportVault(password)
                _ui.update { it.copy(exportReady = true) }
            }
        }
    }

    /** Second half: [uri] is where the user chose to save it, or null if they cancelled. */
    fun finishExport(uri: Uri?) {
        _ui.update { it.copy(exportReady = false) }
        val file = pendingExport ?: return
        pendingExport = null
        work {
            try {
                if (uri != null) {
                    resolver.openOutputStream(uri, "wt")?.use { out -> file.inputStream().use { it.copyTo(out) } } ?: error("no output")
                    done(R.string.export_done)
                }
            } finally {
                repo.discard(file)
            }
        }
    }

    /** Text revision if it changed, then each attribute; unchanged ones are no-ops in the core. */
    private fun saveDraft(session: Session, d: Draft) {
        if (d.entryId == null && d.empty) return
        val id = if (d.dirty || d.entryId == null) {
            session.save(d.entryId, d.kind, d.folderId, d.title, d.body)
        } else {
            d.entryId
        }
        val ids = listOf(id)
        if (d.kind == EntryKind.TODO) session.setDone(id, d.done)
        if (d.kind == EntryKind.DIARY && d.diaryDate != null) session.setDiaryDate(id, d.diaryDate)
        session.setPinned(ids, d.pinned)
        session.setColor(ids, d.color)
        session.moveToFolder(ids, d.folderId)
        session.setTags(id, d.tags)
    }


    private fun refresh(session: Session) = _ui.update {
        val folders = session.folders()
        val all = session.entries()
        it.copy(
            entries = if (it.query.isBlank()) all else session.search(it.query),
            allEntries = all,
            folders = folders,
            tags = session.tags(),
            folderFilter = it.folderFilter?.takeIf { f -> folders.any { x -> x.folderId == f } },
        )
    }

    private fun showList(session: Session) {
        refresh(session)
        _ui.update { it.copy(screen = Screen.EntryList, error = null, info = null) }
    }

    /** Background work without the busy spinner, for instant list actions. */
    private fun quiet(block: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                block()
            } catch (e: Exception) {
                fail(R.string.error_generic)
            }
        }
    }

    private fun draft(): Draft? = (_ui.value.screen as? Screen.Editor)?.draft

    private fun editDraft(change: (Draft) -> Draft) = _ui.update { state ->
        val screen = state.screen as? Screen.Editor ?: return@update state
        state.copy(screen = screen.copy(draft = change(screen.draft)))
    }

    private fun fail(@StringRes message: Int) = _ui.update { it.copy(error = message, info = null) }

    /**
     * For every answer that tells whether a password opens a vault (wrong, taken, not found):
     * a fixed pause on top of Argon2id keeps in-app guessing slow even on fast phones.
     * Call from background work only.
     */
    private fun slowFail(@StringRes message: Int) {
        Thread.sleep(WRONG_PASSWORD_DELAY_MS)
        fail(message)
    }

    private fun done(@StringRes message: Int) = _ui.update { it.copy(info = message, error = null) }

    private companion object {
        const val WRONG_PASSWORD_DELAY_MS = 1_000L
        private const val EMBED_SIDE = 1440
    }

    private fun work(block: () -> Unit) {
        _ui.update { it.copy(busy = true, error = null, info = null) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                block()
            } catch (e: Exception) {
                fail(R.string.error_generic)
            } finally {
                _ui.update { it.copy(busy = false) }
            }
        }
    }
}
